package com.naiwa.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.naiwa.core.ai.harmonize.PctNetHarmonizer
import com.naiwa.core.ai.light.CvLightEstimator
import com.naiwa.core.ai.light.TfliteLightEstimator
import com.naiwa.core.common.device.DeviceCapabilityProbe
import com.naiwa.core.common.image.AlphaUtils
import com.naiwa.core.common.model.BacklitShade
import com.naiwa.core.common.model.LocalLightSample
import com.naiwa.core.common.model.DeviceCapabilityTier
import com.naiwa.core.common.model.FrogPrepareResult
import com.naiwa.core.common.model.FusionBackend
import com.naiwa.core.common.model.FusionTier
import com.naiwa.core.common.model.HarmonizeRequest
import com.naiwa.core.common.model.LightFxRules
import com.naiwa.core.common.model.LightParams
import com.naiwa.core.common.model.LutChoice
import com.naiwa.core.common.model.MatteQuality
import com.naiwa.core.common.model.RenderMode
import com.naiwa.core.common.model.RenderRequest
import com.naiwa.core.common.model.RenderResult
import com.naiwa.core.common.pipeline.ImagePipeline
import com.naiwa.core.common.util.BitmapGuard
import com.naiwa.core.common.util.PlacementTransforms
import com.naiwa.core.cv.blend.AlphaFeather
import com.naiwa.core.cv.blend.EdgeHaloFix
import com.naiwa.core.cv.blend.LaplacianBlender
import com.naiwa.core.ai.light.CvLightParams
import com.naiwa.core.cv.blend.PoissonBlender
import com.naiwa.core.cv.debug.PersonHistogram
import com.naiwa.core.cv.blend.TiledBlender
import com.naiwa.core.cv.color.ColorTransfer
import com.naiwa.core.cv.color.LabColor
import com.naiwa.core.cv.color.LutApply
import com.naiwa.core.cv.color.LutRepository
import com.naiwa.core.cv.color.MklHarmonizer
import com.naiwa.core.cv.edge.FrogEdgePreparer
import com.naiwa.core.cv.glow.Glow
import com.naiwa.core.cv.fusion.RepDetailRefiner
import com.naiwa.core.cv.gles.GlesFusionRenderer
import com.naiwa.core.cv.gles.GlesLayerPipeline
import com.naiwa.core.cv.matte.ColorKeyMatte
import com.naiwa.core.cv.matte.MaskUtils
import com.naiwa.core.cv.rim.RimLight
import com.naiwa.core.cv.scene.ScenePalette
import com.naiwa.core.cv.scene.ScenePaletteExtractor
import com.naiwa.core.cv.scene.CvLightingAnalyzer
import com.naiwa.core.cv.shadow.DirectionalShadow
import com.naiwa.core.cv.shadow.ShadowTint
import com.naiwa.core.cv.wrap.LightWrap
import com.naiwa.core.cv.wrap.LightWrapGradient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图像管线实现（P1 + P2 + P3）。
 *
 * 一次完整渲染的阶段顺序（与架构文档 §6/§7 严格对应）：
 * ```
 * FROG_PREP      场景/奶蛙缩到本模式工作分辨率
 * COLOR_TRANSFER 颜色与亮度协调（Reinhard + lerp 力度、分位数亮度、色温增益）+ 轮廓光
 * SHADOW         方向性阴影（1/4 分辨率扫掠生成后上采样，正片叠底）
 * FUSION         羽化直贴 + 泊松（档 1/档 2）或拉普拉斯多频段（档 3）
 * EDGE           α 加权回贴三段式（中心 = 融合结果，过渡带 = 两者加权）
 * ```
 * 拖动模式（[RenderMode.DRAG]）只走一次 Canvas 直贴并跳过全部效果，保证拖动 30fps。
 *
 * 契约（[ImagePipeline] 的 KDoc 已声明，这里落实）：
 *  1. **不抛异常** —— 光照失败返回中性参数；融合失败自动降档；OOM 自动降分辨率重试一次。
 *  2. **不修改输入、不回收输入** —— 只回收自己创建的副本。
 *  3. **`stageTimings` / `actualTier` / `lightSource` 如实反映本次实际做了什么**。
 */
@Singleton
class DefaultImagePipeline @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImagePipeline {

    private val cvEstimator = CvLightEstimator()

    /** 模型估计器：模型文件缺失时它自己退化为"始终返回中性参数"，不抛异常。 */
    private val modelEstimator: TfliteLightEstimator by lazy { TfliteLightEstimator(context) }

    /** 内置 3D LUT 仓库（规格 8.1）。加载失败时 [LutRepository.load] 返回 null，走无 LUT 兜底。 */
    private val lutRepository: LutRepository by lazy { LutRepository(context) }

    /** PCT-Net 和谐化（规格 8.2 备选，高端机可选）。权重缺失时返回 null，回退 MKL。 */
    private val pctNet: PctNetHarmonizer by lazy { PctNetHarmonizer(context) }

    @Volatile
    private var lastLightSource: RenderResult.LightSource = RenderResult.LightSource.NEUTRAL_FALLBACK

    /**
     * P3 的运行时自适应结果：首次按设备能力选档，之后按**实测融合耗时**动态升降档，
     * 避免"默认档在低端机上翻车"或"高性能机一直用低质量档"。
     */
    @Volatile
    private var adaptiveTier: FusionTier? = null

    /**
     * 已被实测证明"在这台设备上超时"的档位。**只降不升**：
     * 拉黑后本进程内不会再自动升回该档，避免"升上去超时 → 降下来很快 → 又升上去"的震荡。
     * 用同步集合是因为渲染可能在多线程下并发触发。
     */
    private val degradedTiers = java.util.Collections.synchronizedSet(mutableSetOf<FusionTier>())

    @Volatile
    private var lastFusionMs: Long = 0L

    private val matteConfig = ColorKeyMatte.ColorKeyConfig()

    /**
     * 场景缓存：预览场景（scenePreview）在编辑页是**同一个 Bitmap 实例**贯穿所有 SETTLE 渲染，
     * 但每次 SETTLE 都重新做「缩放 + getPixels 大数组拷贝 + 场景取色（整图采样排序 + 4 区统计）」。
     * 这些都是"只依赖场景内容、与 Placement/Overrides 无关"的纯函数结果，缓存起来即可
     * 让松手预览从"每次全量重算"变成"首次算一次、之后复用"。
     *
     * 键用 Bitmap 的 identity（`===`）：场景 Bitmap 变实例（如切素材/换图）时自然失效。
     */
    private var cachedScene: Bitmap? = null

    /** 缩放后的工作场景缓存（键 = 原场景 identity + 目标短边）。 */
    private var cachedScaledScene: Bitmap? = null
    private var cachedScaledSource: Bitmap? = null
    private var cachedScaledMaxShortSide = 0

    /** 缓存位图是否由管线 createScaledBitmap 创建（是才在失效时回收，否则是调用方持有）。 */
    private var cachedScaledIsOwned = false

    /** 场景像素缓存（避免每次 `getPixels` 拷贝 1600×N 大数组）。 */
    private var cachedScenePixels: IntArray? = null

    /** 场景取色缓存（[ScenePaletteExtractor.extract] 的结果）。 */
    private var cachedPalette: ScenePalette? = null

    private var cachedSceneW = 0
    private var cachedSceneH = 0

    // ------------------------------------------------------------------ 光照估计

    override suspend fun estimateLight(scene: Bitmap): LightParams = withContext(Dispatchers.Default) {
        if (!BitmapGuard.isUsable(scene)) return@withContext LightParams.NEUTRAL

        val (pixels, w, h) = downscalePixels(scene, LIGHT_ESTIMATION_SHORT_SIDE)

        // 1) 模型优先（模型缺失或推理失败时返回 confidence = 0）
        val fromModel = try {
            modelEstimator.estimate(pixels, w, h)
        } catch (t: Throwable) {
            LightParams.NEUTRAL
        }
        if (fromModel.isTrustworthy) {
            lastLightSource = RenderResult.LightSource.MODEL
            return@withContext fromModel
        }

        // 2) 传统 CV 兜底（纯 Kotlin，永不抛异常）
        val fromCv = cvEstimator.estimate(pixels, w, h)
        lastLightSource = if (fromCv.isTrustworthy) {
            RenderResult.LightSource.CV_FALLBACK
        } else {
            RenderResult.LightSource.NEUTRAL_FALLBACK
        }
        fromCv
    }

    // ------------------------------------------------------------------ GPU 融合（次元融合）

    /**
     * GPU 主路径（GLES 3.0 全 shader）：绿幕色键 → Gamma 线性 → 接触阴影(Multiply) →
     * Alpha 叠加 → Rim(Screen) → Light Wrap(Screen) → 反弹光(Screen) → 高频重注入 → sRGB。
     *
     * 任何 EGL/GL 失败返回 null，由调用方（编辑页）降级 [render]（CPU 管线）。
     * 本实现不在此做 AI（PCT-Net/LiteRT 仍在 CPU 路径），GPU 管线只做确定性像素计算。
     */
    override suspend fun renderGpu(request: RenderRequest): Bitmap? =
        withContext(Dispatchers.Default) {
            if (!BitmapGuard.isUsable(request.scene) || !BitmapGuard.isUsable(request.frog)) {
                return@withContext null
            }
            // 局部回收表（不能做成字段：管线是 Singleton，并发渲染会互相回收）
            val created = ArrayList<Bitmap>(4)
            try {
                // ① 先把场景压到 GPU 纹理上限内，**同一尺寸基准**下做后续摆放，
                //    否则 background 被 renderer 内部压缩、placedFrog 仍是原尺寸，UV 会错位。
                val gpuScene = scaleToMaxShortSide(request.scene, GPU_TEXTURE_LIMIT)
                if (gpuScene !== request.scene) created += gpuScene

                // ② 摆放变换（GPU 路径的关键缺口）：
                //    shader 里的 uFrog 必须是**已经摆进场景坐标系**的人物图层（全画布尺寸、
                //    背景透明、旋转缩放已应用）。直接把素材原始位图传进去，UV 0..1 采样会把它
                //    拉伸铺满整张画布 —— 这就是"素材位置异常移动"的根因。
                //    与 CPU 路径共用同一个变换入口（PlacementTransforms），保证所见即所得。
                val placed = try {
                    SimpleBlender.renderFrogOnly(
                        frog = request.frog,
                        placement = request.placement,
                        sceneWidth = gpuScene.width,
                        sceneHeight = gpuScene.height,
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU 路径摆放变换失败：${t.message}", t)
                    null
                } ?: return@withContext null
                created += placed

                // ③ 多任务光影参数：纯 CV 物理骨架（NMS 光源 + Scharr 光向 + 背光判定）
                //   AI 只允许贡献 3×3 颜色矩阵（estimate.colorMatrix），严禁几何参数。
                val personMean = sampledMeanColor(placed)   // [r,g,b,luma]
                val estimate = try {
                    CvLightingAnalyzer.analyze(gpuScene, personMean?.get(3))
                } catch (t: Throwable) {
                    Log.w(TAG, "CV 光影分析失败，用中性参数：${t.message}")
                    com.naiwa.core.common.model.LightingEstimate.NEUTRAL
                }
                // ③-b 纯 CV 几何骨架（:core-ai.LightEstimator，规格阶段二）：
                //   光源坐标 NMS、Scharr 方位角、逆光判定 —— 这是**几何参数的唯一权威**。
                //   上面 CvLightingAnalyzer 只负责光度（强度/辉光/σ/亮部取色），几何一律以骨架为准，
                //   避免两套 CV 对同一张图给出不同光向（一边算 30°、一边算 210° 是典型症状）。
                // 一次降采样同时拿到：四区色板（上/下/左/右）、阴影环境色、背景均值色
                val stats = sceneStats(gpuScene)
                val skeleton: CvLightParams? = try {
                    estimateSkeleton(stats.pixels, stats.w, stats.h, personMean?.get(3))
                } catch (t: Throwable) {
                    Log.w(TAG, "CV 骨架测光失败，退回光照分析结果：${t.message}")
                    null
                }
                val geo = skeleton?.let { LightSkeletonBridge.toGeometry(it) }

                // ---- 光源邻域取色（v0.28）----
                // 光包裹的颜色必须跟着**光源位置**走：光在左边就取左边那团夕阳/云彩的颜色，
                // 而不是全图高光均值（多光源场景下均值会把冷暖互相抵消成灰）。
                // 取不到（样本太少）时回退到全局高光色。
                val lightPosX = geo?.posX ?: estimate.lightX
                val lightPosY = geo?.posY ?: estimate.lightY
                val localKey = LocalLightSample.extract(
                    stats.pixels, stats.w, stats.h, lightPosX, lightPosY,
                )
                val keyLightColor = if (localKey != 0) localKey else stats.keyRgb

                val fx = LightFxRules.plan(request.effectiveLight, request.overrides, allowExpensiveFx = true)
                val tint = tintGain(request.effectiveLight.colorTempK)
                // 3×3 颜色和谐化矩阵：AI 若给出矩阵优先用（模型只允许出矩阵）；
                // 否则用 CV 对角染色矩阵（人物均值 → 背景高光色），shader 内再乘 0.6 强度。
                val personMatrix = estimate.colorMatrix ?: colorHarmonyMatrix(
                    personMean = personMean,
                    targetRgb = estimate.bgHighlightColor,
                )
                // 阴影环境色：背景暗部中位色（严禁纯黑 —— 纯黑 Multiply 会把底图细节整块抹掉）
                val shadowTint = stats.shadowTint
                // 环境色罩：背景的**代表色**（均值，不是暗部也不是高光）—— 草地绿/夕阳橙就来自这里
                val ambientColor = stats.meanRgb
                // 四色渐变环境光：上/下/左/右四区中位色（已排除过曝与死黑）
                val quad = stats.palette

                val params = GlesFusionRenderer.Params(
                    hasGreenScreen = false, // 素材已抠图；绿幕直传由导入流程接入后置 true
                    lightAzimuthDeg = estimate.azimuthDeg,
                    lightIntensity = estimate.lightIntensity,
                    wrapOpacity = (fx.lightWrapStrength / LightFxRules.WRAP_DEFAULT).coerceIn(0f, 2f),
                    // v0.29：`shadowStrength` 是光照模型里的**相对权重**（默认 0.3），
                    // 直接当暗度用会让根部只有 0.3 → 亮背景上几乎看不见（"阴影消失"）。
                    // 重映射到 [0.55, 1.0]；用户显式拖到 0 才真正关闭。
                    shadowStrength = run {
                        val s0 = request.effectiveLight.shadowStrength.coerceIn(0f, 1f)
                        if (s0 < 0.01f) 0f else (0.55f + 0.45f * s0).coerceAtMost(1f)
                    },
                    shadowOffsetPx = 60f,             // 24 → 60：投影要有长度才看得出
                    bounceStrength = 0.25f,
                    // 高频重注入权重随"画风保护度"走（保护度越高，线稿回注越强）
                    hfWeight = (0.4f * request.harmonizeStrength.coerceIn(0f, 1f) / 0.8f)
                        .coerceIn(0.2f, 0.5f),
                    rimStrength = fx.rimStrength.coerceIn(0f, 0.5f),
                    lightDirX = geo?.dirX ?: estimate.lightDirX,
                    lightDirY = geo?.dirY ?: estimate.lightDirY,
                    lightDirZ = geo?.dirZ ?: estimate.lightDirZ,
                    wrapStrength = estimate.wrapStrength,
                    glowStrength = if (fx.glowStrength > 0f) estimate.glowStrength else 0f,
                    bgHighlightColor = estimate.bgHighlightColor,
                    bgBlurSigma = estimate.bgBlurSigma,
                    tintR = tint[0],
                    tintG = tint[1],
                    tintB = tint[2],
                    // ---- 多图层管线参数（P1 光晕定位 / P2-c 逆光判定） ----
                    lightX = lightPosX,               // 与下面的方向计算共用同一个来源
                    lightY = lightPosY,
                    backlit = geo?.backlit ?: estimate.backlit,
                    // ---- v0.27 逆光剪影压暗 + 阴影漏光熔断 ----
                    // 常量统一来自 BacklitShade（纯函数层，有单测），此处只做透传，
                    // 避免"shader 默认值 / 管线传参 / 单测"三处各写一套数字。
                    backlitDarken = BacklitShade.DARKEN_BACK,
                    backlitGlobalDarken = BacklitShade.DARKEN_GLOBAL,
                    backlitChroma = BacklitShade.CHROMA_WEIGHT,
                    wrapShadowCut = BacklitShade.CUT_STRENGTH,
                    wrapAmbientFloor = BacklitShade.AMBIENT_FLOOR,
                    // ---- 三个光影节点（规格第三步）----
                    // 光包裹强度直接取滑杆值：0 = 关闭（管线用 1×1 黑纹理占位，省一个全分辨率 pass）
                    lightWrapStrength = fx.lightWrapStrength.coerceIn(0f, 1f),
                    wrapRadiusPx = 5f,                   // 光包裹 dilate 半径 5px（shader 内按画布等比缩放）
                    wrapMaxLuma = 0.35f,                 // 规格：背景光限亮 0.2~0.4
                    // ---- 光包裹的冷暖双色 + 三维衰减 ----
                    // v0.28：光源**邻域**加权均值（半径 5% 长边），不是全图均值
                    keyLightColorRgb = keyLightColor,
                    wrapAmbientColorRgb = stats.ambientRgb,   // 背景暗部（≤中位）均值
                    wrapColorMix = 0.75f,
                    wrapHeightStart = 0.70f,                  // 底部 30% 开始变弱
                    wrapHeightEnd = 1.0f,                     // 画面最底部归零
                    // 1.0：投影遮罩直接参与 max(背光权重, 遮罩) 的熔断，不再打折
                    wrapShadowOcclusion = 1.0f,
                    wrapAmbientLift = 0.25f,                  // 环境色最低亮度（防灰泥）
                    debugWrapMode = com.naiwa.core.common.debug.DebugReport.wrapMode,
                    // 镜头光晕：只有真检测到明确光源才给满强度，否则整屏发白
                    flareStrength = if (estimate.lightIntensity > 0.35f) 0.55f else 0.2f,
                    flareRadius = 1f,
                    shadowSoftness = 1f,
                    shadowFalloff = 0.80f,           // 近深远浅：根部最重、末端几乎消失
                    shadowBlurBasePx = 2.5f,         // 根部基本实边
                    shadowBlurGrowPx = 26f,          // 越远越糊（配合两层阴影做出层次）
                    // ---- 全局后处理（合成之后、FXAA 之前，顺序固定）----
                    // 景深：低端机跳过（16 tap 全图采样对 4 核机是明显负担，宁可保住帧率）
                    // 只糊"人物轮廓外 20px 的环"，半径上限 5px：整张背景糊死是上一版的致命问题
                    dofRadiusPx = if (isLowEndDevice()) 0f else 4f,
                    dofRingPx = 20f,
                    // 全局调色：轻微胶片化，把 P1/P2 压进同一个色空间；改回 1f 即整段跳过
                    gradeContrast = 1.05f,
                    gradeSaturation = 1.06f,
                    // 全局 Multiply：暗部 ×0.85（高光不压，保对比度）；四周的压暗交给暗角节点
                    globalMultiply = 0.85f,
                    // ---- 大气透视 + 环境色罩（消除"贴纸感"）----
                    bleedPxMin = 10f,                 // 迎光侧渗透 10px（锐利）
                    bleedPxMax = 20f,                 // 背光侧渗透 20px（消散进环境）
                    bleedStrength = 0.35f,
                    ambientColorRgb = ambientColor,   // 背景代表色
                    ambientTint = 0.05f,              // 极低透明度罩一层，色彩同频
                    // ---- 四色渐变环境光（上/下/左/右四区中位色）+ 伪立体打光 ----
                    quadTopRgb = quad.topColor,
                    quadBottomRgb = quad.bottomColor,
                    quadLeftRgb = quad.leftColor,
                    quadRightRgb = quad.rightColor,
                    quadStrength = 0.25f,             // SoftLight（非加性）；0 = 关闭
                    // 加性项一律"点缀级"：峰值增量 ≤0.15，绝不作为主光源
                    fakeLightStrength = 0.15f,
                    // 径向渐变：0.2~0.8 的 S 型带，近光亮 1.1 倍、远处暗 0.6 倍
                    gradientMinDist = 0.20f,
                    gradientMaxDist = 0.80f,
                    gradientStrength = 1.0f,
                    feetFadeStart = 0.78f,            // 脚底开始压暗的高度
                    feetFadeStrength = 0.20f,         // 只轻微压暗，避免腿被吃掉
                    rimWidthPx = 3f,                  // 规格 2~4px
                    rimGain = 1.2f,                   // 点缀级：峰值增量约 0.13
                    specThreshold = 0.85f,
                    specStrength = 0.12f,             // 眼睛/鼻尖只提一点
                    envSplitStrength = 0.45f,         // 暗部 Multiply 环境暗色（亮部固定 0.15）
                    // ---- 白边熔断 / 底部托底 ----
                    edgeShrinkPx = 1.5f,              // Shrink Mask：向内腐蚀 1.5px
                    defringeStrength = 0.6f,          // 边缘 RGB 向内部颜色收缩
                    bounceLightStrength = 0.12f,      // 背景底部色 Screen 到下半身（0.10~0.15）
                    // HDR 保护：最终输出前的 Reinhard（硬 clamp 会把高光压成纯白 → 泛白）
                    toneMapStrength = 1f,
                    toneMapExposure = 1f,             // 整体偏暗时抬到 1.2~1.3，别动 P2
                    toneMapKnee = 0.6f,               // 膝点以下恒等，只压高光
                    // Looks 漏光：风格化效果，默认关（不替用户做风格决定）
                    looksStrength = 0f,
                    // 暗角：轻度，把视觉中心收到人物
                    vignetteStrength = 0.22f,
                    vignetteRadius = 0.72f,
                    vignetteSoftness = 0.45f,
                    // ---- P2 质感（规格：色调对比 / 泛光 / 光包裹 / 方向性羽化）----
                    // S 曲线：smoothstep(0.2, 0.8, luma)，强度 0.55 —— 先把明暗拉开，
                    // 后面 ③方向光 / ④环境光 / ⑥矩阵 才有的可拉（顺序错了就是"死平"）
                    toneContrast = 0.55f,
                    toneCurveLow = 0.2f,
                    toneCurveHigh = 0.8f,
                    unsharp = 0.25f,                 // 线稿/头饰高频回注，防二次元被糊平
                    // ---- v0.26 边缘发光（EMIT → 高斯金字塔 → Screen）----
                    // 「边缘光」滑杆（0~0.5）现在驱动**发光源增益**，而不是"描边颜色叠加"。
                    // 语义保持：0 = 完全不出光；滑杆拉满 = 强发光。低端机不关闭（它才是防白边的主功能），
                    // 只把扩散半径收小 —— 级数少一级，省掉最粗那档的填充。
                    edgeGlowStrength = (fx.rimStrength / LightFxRules.RIM_MAX * 0.9f)
                        .coerceIn(0f, 1.2f),
                    glowSpreadPx = if (isLowEndDevice()) 16f else 28f,
                    glowIntensity = 1.35f,   // 补偿金字塔稀释（峰值只剩 1/5~1/3）
                    glowAirRatio = 0.35f,            // 背景空气只收 35%：微微渗透，不糊灰
                    // 旧版"人物高光泛光"已并入上面这条链，四项保留但不再消费（见 Params 注释）
                    bloomStrength = 0f,
                    // AI 只出 3×3 矩阵（缺失时 shader 内矩阵强度乘 0.6 且为单位阵 → 等价不调色）
                    personColorMatrix = personMatrix,
                    // 阴影一律用"背景暗部中位色"做 Multiply 的颜色，**严禁纯黑**
                    shadowColorRgb = shadowTint,
                    contactShadowStrength = 1.0f,        // 1.0：接触带要"死黑"，这是踩在地上的关键
                    contactShadowPx = 4.0f,              // 规格 3~5px，shader 内按 h/1080 缩放
                    contactShadowColorRgb = deepenShadow(shadowTint),
                )
                // 新图层管线（P1/P2/P2b/P2c + 发光链 + FXAA）优先。
                // 失败才降到 legacy 3-pass —— 它**没有** v0.26+ 的光效链，
                // 所以"降级"必须留一条明确日志：否则现场只会看到"光影突然变少了"。
                val layerResult = GlesLayerPipeline.render(gpuScene, placed, params)
                val rendered = layerResult ?: run {
                    Log.w(TAG, "图层管线失败 → 降级 legacy 3-pass（光效链会缺失，仅作兜底）")
                    GlesFusionRenderer.render(gpuScene, placed, params)?.also {
                        com.naiwa.core.common.debug.DebugReport.lastPipeline = "legacy-gpu"
                    }
                }
                // 调试度量：统计人物区域直方图（只在全量融合后跑一次，拖动/松手预览不跑）。
                // 失败静默，绝不影响渲染结果。
                if (rendered != null) PersonHistogram.publish(rendered, placed)
                rendered
            } finally {
                created.forEach { BitmapGuard.recycle(it) }
            }
        }

    /** 色温 → RGB 乘法增益（与 CPU 路径同一套 KelvinRgb 映射，避免两条路径色调不一致）。 */
    private fun tintGain(colorTempK: Float): FloatArray =
        com.naiwa.core.common.color.KelvinRgb.kelvinToRgbGain(colorTempK.toInt())

    /**
     * 人物内部颜色均值（alpha > 0.5 步长采样），返回 `[r, g, b, luma]`（0~255）。
     * 用途：① 背光判定（luma 与背景高光对比）；② 3×3 颜色和谐化矩阵。
     */
    /**
     * 在长边 ≤480 的缩略图上跑 CV 骨架测光（`:core-ai.CvLightEstimator`）。
     *
     * 为什么降采样：骨架算法本身是 boxBlur + Scharr + NMS，全部 O(像素)；
     * 光照是低频信息，480px 与 4096px 得到的方位角差异 < 2°，但耗时差 70 倍。
     * 返回到原图坐标系时不需要任何换算 —— 输出全部是**归一化坐标/角度**，与分辨率无关。
     *
     * @param personMeanLuma 人物内部平均亮度（背光判定基准；null 时跳过逆光判定）
     */
    private fun estimateSkeleton(
        px: IntArray,
        w: Int,
        h: Int,
        personMeanLuma: Float?,
    ): CvLightParams? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        return CvLightEstimator().estimateSkeleton(px, w, h, personMeanLuma)
    }

    /**
     * 背景暗部中位色（投射阴影的环境色）。
     *
     * 阴影颜色必须是"环境光的缺席色"而不是纯黑：`base * vec3(0)` 会把底图细节整块抹掉，
     * 观感是死黑、发脏。这里取背景最暗 12% 的中位色（[ShadowTint]），并在 480px 缩略图上算
     * （亮度分布是低频信息，缩略图与全图的中位色差异 < 3/255）。
     */
    /**
     * 场景统计（**一次降采样**拿到三样东西，避免为每个色值各降采样一遍）：
     *  - [palette]：上/下/左/右四区中位色 —— 四色渐变环境光的插值源
     *  - [shadowTint]：最暗 12% 中位色 —— 阴影/背光着色（严禁纯黑）
     *  - [meanRgb]：全图均值 —— 环境色罩的代表色
     */
    private class SceneStats(
        val pixels: IntArray,
        val w: Int,
        val h: Int,
        val palette: ScenePalette,
        val shadowTint: Int,
        val meanRgb: Int,
        /** 光包裹主光色：亮度前 5% 的加权均值。 */
        val keyRgb: Int,
        /** 光包裹环境色：暗部（≤ 中位亮度）均值。 */
        val ambientRgb: Int,
    )

    private fun sceneStats(scene: Bitmap): SceneStats {
        val (px, w, h) = try {
            downscalePixels(scene, SKELETON_LONG_SIDE)
        } catch (t: Throwable) {
            Log.w(TAG, "场景降采样失败：${t.message}")
            return SceneStats(
                IntArray(0), 0, 0, ScenePalette.NEUTRAL, ShadowTint.NEUTRAL, 0xFF808080.toInt(),
                SceneLightColors.KEY_NEUTRAL, SceneLightColors.AMBIENT_NEUTRAL,
            )
        }
        val palette = runCatching { ScenePaletteExtractor.extract(px, w, h) }
            .getOrDefault(ScenePalette.NEUTRAL)
        val shadow = runCatching { ShadowTint.fromBackground(px, w, h) }
            .getOrDefault(ShadowTint.NEUTRAL)
        // 均值：步长采样，与分辨率无关
        val step = (px.size / 4096).coerceAtLeast(1)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        var i = 0
        while (i < px.size) {
            val c = px[i]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
            i += step
        }
        val mean = if (n > 0) {
            (0xFF shl 24) or
                ((r / n).toInt().coerceIn(0, 255) shl 16) or
                ((g / n).toInt().coerceIn(0, 255) shl 8) or
                (b / n).toInt().coerceIn(0, 255)
        } else {
            0xFF808080.toInt()
        }
        val colors = SceneLightColors.extract(px)
        return SceneStats(px, w, h, palette, shadow, mean, colors.key, colors.ambient)
    }

    /** 由环境暗色推出"更深一档"的接触阴影色：乘 0.55，但每通道保底 6（仍非纯黑）。 */
    private fun deepenShadow(rgb: Int): Int {
        fun ch(shift: Int): Int = (((rgb shr shift) and 0xFF) * 0.55f).toInt().coerceIn(6, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun sampledMeanColor(person: Bitmap): FloatArray? {
        val w = person.width
        val h = person.height
        val total = w * h
        if (total <= 0) return null
        val px = IntArray(total)
        person.getPixels(px, 0, w, 0, 0, w, h)
        val step = (total / 2048).coerceAtLeast(1)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var sl = 0.0; var n = 0
        var i = 0
        while (i < total) {
            val c = px[i]
            if (((c ushr 24) and 0xFF) > 128) {
                val r = ((c shr 16) and 0xFF)
                val g = ((c shr 8) and 0xFF)
                val b = (c and 0xFF)
                sr += r; sg += g; sb += b
                sl += 0.2126f * r + 0.7152f * g + 0.0722f * b
                n++
            }
            i += step
        }
        if (n == 0) return null
        return floatArrayOf(
            (sr / n).toFloat(),
            (sg / n).toFloat(),
            (sb / n).toFloat(),
            (sl / n).toFloat(),
        )
    }

    /**
     * CV 颜色和谐化矩阵（3×3 对角，规格：AI 只允许出这个形状）。
     *
     * 对角项 = 目标色 / 源色（逐通道增益），钳制在 0.6~1.6 防止把人物拉爆；
     * shader 内还会再乘 0.6 强度，所以这里给的是"完整力度"，不是半力度。
     */
    private fun colorHarmonyMatrix(personMean: FloatArray?, targetRgb: Int): FloatArray {
        val unit = GlesFusionRenderer.Params.UNIT_3X3.copyOf()
        if (personMean == null) return unit
        val tr = ((targetRgb shr 16) and 0xFF).coerceAtLeast(1).toFloat()
        val tg = ((targetRgb shr 8) and 0xFF).coerceAtLeast(1).toFloat()
        val tb = (targetRgb and 0xFF).coerceAtLeast(1).toFloat()
        val gr = (tr / personMean[0].coerceAtLeast(1f)).coerceIn(0.6f, 1.6f)
        val gg = (tg / personMean[1].coerceAtLeast(1f)).coerceIn(0.6f, 1.6f)
        val gb = (tb / personMean[2].coerceAtLeast(1f)).coerceIn(0.6f, 1.6f)
        return floatArrayOf(
            gr, 0f, 0f,
            0f, gg, 0f,
            0f, 0f, gb,
        )
    }

    /** GPU 纹理上限（与 GlesFusionRenderer 的 MAX_TEXTURE_SIDE 一致）。 */
    private val GPU_TEXTURE_LIMIT = 2048

    // ------------------------------------------------------------------ 奶蛙准备（抠图）

    /**
     * 奶蛙准备：抠图（按需）+ **边缘预处理（规格 6.1~6.4，永远执行）**。
     *
     * 关键修正：早期实现在"原图已带 alpha"时直接 return，
     * 于是**自带透明通道的素材完全没有机会做反预乘与去白** ——
     * 而那正是"透明玻璃白边"的高发场景（半透明边缘的 RGB 仍保留着原背景色）。
     * 现在无论走不走抠图，边缘预处理都会执行。
     */
    override suspend fun prepareFrog(
        source: Bitmap,
        runMattingIfNeeded: Boolean,
        softness: Float,
    ): FrogPrepareResult? = withContext(Dispatchers.Default) {
        if (!BitmapGuard.isUsable(source)) return@withContext null

        if (runMattingIfNeeded && AlphaUtils.hasUsableAlpha(source)) {
            // 已带可用透明通道：跳过抠图，但边缘预处理照做（白边就出在这类素材上）
            val (prepared, report) = runEdgePrep(source, softness)
            return@withContext FrogPrepareResult(
                bitmap = prepared,
                didRunMatting = false,
                foregroundRatio = 0f,
                quality = MatteQuality.ALREADY_HAS_ALPHA,
                edgePrepApplied = report != null && !report.skipped,
                edgePrepSummary = report?.summary,
            )
        }

        val matted = try {
            ColorKeyMatte.matte(source, matteConfig)
        } catch (t: Throwable) {
            null
        } ?: return@withContext FrogPrepareResult(
            bitmap = source.copy(Bitmap.Config.ARGB_8888, false) ?: source,
            didRunMatting = true,
            foregroundRatio = 0f,
            quality = MatteQuality.MATTED_SUSPICIOUS,
        )

        val ratio = MaskUtils.foregroundRatio(MaskUtils.extractAlphaMask(matted))
        val (prepared, report) = runEdgePrep(matted, softness)
        // matted 是我们自己创建的中间产物；边缘预处理产出了新位图后它就没用了
        if (prepared !== matted) BitmapGuard.recycle(matted)

        FrogPrepareResult(
            bitmap = prepared,
            didRunMatting = true,
            foregroundRatio = ratio,
            quality = MatteQuality.of(ratio),
            edgePrepApplied = report != null && !report.skipped,
            edgePrepSummary = report?.summary,
        )
    }

    /**
     * 执行边缘预处理（规格 6.1→6.4）。
     *
     * 契约：**输入位图不会被回收**（`prepareFrog` 的入参可能属于调用方），
     * 失败时原样返回输入并给出 null 报告 —— 增强步骤不阻断主流程。
     */
    private fun runEdgePrep(
        bitmap: Bitmap,
        softness: Float,
    ): Pair<Bitmap, FrogEdgePreparer.Report?> =
        FrogEdgePreparer.prepareBitmap(bitmap, softness) ?: (bitmap to null)

    // ------------------------------------------------------------------ 渲染

    override suspend fun render(
        request: RenderRequest,
        onStage: ((RenderResult.Stage) -> Unit)?,
    ): RenderResult = withContext(Dispatchers.Default) {
        try {
            renderInternal(request, onStage, degrade = false)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "渲染 OOM，降分辨率重试（720p）")
            System.gc()
            renderInternal(request, onStage, degrade = true)
        }
    }

    private fun renderInternal(
        request: RenderRequest,
        onStage: ((RenderResult.Stage) -> Unit)?,
        degrade: Boolean,
    ): RenderResult {
        val timings = LinkedHashMap<RenderResult.Stage, Long>()
        val created = ArrayList<Bitmap>(8)

        // 双轨制选路：请求值优先，缺省走经典路径。设备能力否决由调用方（编辑页）完成，
        // 管线只负责**如实执行并如实上报**，不做二次判断 —— 否则"UI 显示的"与"实际跑的"会不一致。
        val backend = request.requestedBackend ?: FusionBackend.DEFAULT
        val detailStrength = if (backend == FusionBackend.REP_DETAIL) {
            RepDetailRefiner.DEFAULT_STRENGTH
        } else {
            0f
        }
        // 细节保真是增强步骤，可能因内存/参数原因未执行；用回报对象把真实结果带回显。
        val detailReport = if (detailStrength > 0f) EdgeHaloFix.DetailReport() else null
        // 实际生效的后端：默认等于请求值，EDGE 阶段结束时按回报校正
        var actualBackend = backend

        try {
            // ---------- FROG_PREP ----------
            onStage?.invoke(RenderResult.Stage.FROG_PREP)
            val t0 = SystemClock.elapsedRealtime()

            // 色彩空间查找表在这里预热：cbrt/pow 的一维表在后台线程建好并归属到本阶段计时，
            // 不让它悄悄算进 COLOR_TRANSFER 的账上（否则分阶段耗时就是骗人的）
            LabColor.warmUp()

            val maxShortSide = if (degrade) {
                DEGRADED_SHORT_SIDE
            } else {
                when (request.mode) {
                    RenderMode.EXPORT -> MAX_EXPORT_SHORT_SIDE
                    else -> request.mode.maxShortSide
                }
            }

            // 大图保护（§7.3，P0–P2 不分块）：
            //  · 工作副本长边 > 4096 → 整图缩到 4096（一次缩放，不产生拼接痕迹）
            //  · 长边 > 2048 → 融合阶段不在这张全图上跑，而是由 PoissonBlender 把「场景+已摆放奶蛙」
            //    一起降采样到档位工作域（720 / 540）求解，再把结果上采样回贴 —— 这就是
            //    "整图降采样融合 + 上采样回贴"，天然避免了大图在泊松求解里的 OOM 与耗时爆炸。
            //  · 分块融合（[FusionTier.TILED]，25% 重叠 + 汉宁窗）**只在用户显式选择该档位时**走，
            //    且只有高端机原样执行 —— 中/低端机由 FusionTierChoice 提前降级并如实回报。
            //    它永不进入自动升降档：耗时随 tile 数线性增长，被"上次跑得快"自动升上来就会失控。
            // 工作场景缩放 + 缓存：预览场景（scenePreview）是固定实例，但短边 1600 > SETTLE 的 1080，
            // 每次 SETTLE 都重新 1600→1080 缩放是预览的最大隐藏开销之一。缓存缩放结果，键用
            // 原场景 identity + 目标短边，松手预览不再重复整图缩放。
            val workScene = cachedScaledScene(request.scene, maxShortSide)
            // 缓存位图绝不进 created：它会被后续 SETTLE 复用，finally 的 recycle 会把它回收掉，
            // 下次访问已回收的 Bitmap 直接崩溃。缓存位图的生命周期由 cachedScaledScene 内部管理。
            // workScene 恒等于缓存值，因此这里无需 created += workScene。

            val targetWidth = request.placement
                .targetWidthPx(workScene.width, workScene.height)
                .coerceAtLeast(1)
            val workFrog = downscaleIfOversized(request.frog, targetWidth * FROG_OVERSAMPLE)
            if (workFrog !== request.frog) created += workFrog

            timings[RenderResult.Stage.FROG_PREP] = SystemClock.elapsedRealtime() - t0

            // ---------- 拖动模式：只直贴，跳过一切效果 ----------
            if (request.mode == RenderMode.DRAG) {
                onStage?.invoke(RenderResult.Stage.FUSION)
                val tDrag = SystemClock.elapsedRealtime()
                val output = SimpleBlender.blend(workScene, workFrog, request.placement)
                timings[RenderResult.Stage.FUSION] = SystemClock.elapsedRealtime() - tDrag
                return RenderResult(
                    bitmap = output,
                    actualTier = FusionTier.NONE,
                    lightSource = lastLightSource,
                    stageTimings = timings,
                    backend = backend,
                )
            }

            val light = request.effectiveLight
            val sceneW = workScene.width
            val sceneH = workScene.height
            val featherPx = EdgeHaloFix.featherPx(
                workShortSide = minOf(sceneW, sceneH),
                featherScale = request.overrides.effectiveFeatherScale,
            )

            // ---------- COLOR_TRANSFER + 光效（规格 7.5 的顺序） ----------
            // 奶蛙侧的完整链路：
            //   颜色/亮度协调 → 四色渐变环境光 → 边缘打光 → 辉光 → （逐块）进入泊松融合
            // 光效的开关与强度由 LightFxRules 统一决定，管线只负责按计划执行。
            onStage?.invoke(RenderResult.Stage.COLOR_TRANSFER)
            val t1 = SystemClock.elapsedRealtime()

            val bounds = PlacementTransforms.boundsFor(
                placement = request.placement,
                sceneWidth = sceneW,
                sceneHeight = sceneH,
                frogWidth = workFrog.width,
                frogHeight = workFrog.height,
            )

            // 规格 7.6：辉光与四色渐变**只在导出且非低端机**时执行；预览只做边缘打光
            val allowExpensiveFx = request.mode == RenderMode.EXPORT && !isLowEndDevice()
            val fxPlan = LightFxRules.plan(light, request.overrides, allowExpensiveFx)

            // 场景取色是三项光效的共同数据源（高光色 / 暗部色 / 四区主色）。
            // 只有真的要用到才统计，避免"滑杆全 0 还白跑一次采样"。
            // 且用缓存：预览场景是同一实例，取色结果只依赖场景内容，缓存后松手预览不再重复整图采样。
            val needPalette = request.mode.includesColorTransfer &&
                (fxPlan.rimStrength > 0f || fxPlan.glowStrength > 0f || fxPlan.lightWrapStrength > 0f)
            val palette = if (needPalette) cachedScenePalette(workScene) else null

            var harmonized = workFrog
            if (request.mode.includesColorTransfer) {
                var current = ColorTransfer.harmonize(
                    frog = workFrog,
                    scene = workScene,
                    placementRect = bounds,
                    light = light,
                    strength = request.fusionStrength,
                ).also { created += it }

                // 3D LUT 颜色映射（规格 8.1）：按色温/主光选 LUT，作用于奶蛙前景 RGB。
                // 放在颜色协调之后、四色渐变之前（规格 8.4 顺序）。
                // 加载失败（LUT 缺失/损坏）静默跳过，不破坏传统颜色迁移。
                val lutSel = LutChoice.select(light.colorTempK, light.keyIntensity, fxPlan.backlit)
                val primaryLut = lutRepository.load(lutSel.primary)
                if (primaryLut != null) {
                    val secondaryLut = lutSel.secondary?.let { lutRepository.load(it) }
                    current = LutApply.apply(current, primaryLut, secondaryLut, lutSel.blend)
                        .also { created += it }
                }

                // 可选 AI 和谐化（规格 8.2）：MKL 闭式解为默认增强，PCT-Net 仅高端机。
                // 顺序在 3D LUT 之后、四色渐变之前（规格 8.4）。失败/权重缺失静默回退 MKL。
                // debugSkipAI 为 true 时跳过 AI 和谐化（用于排查失真来源，见需求 §三.4）。
                val fgPx = IntArray(current.width * current.height)
                current.getPixels(fgPx, 0, current.width, 0, 0, current.width, current.height)
                // 场景像素复用缓存（预览场景是同一实例，避免反复 getPixels 拷贝大数组）
                val scenePxForHarm = cachedScenePixelArray(workScene)
                // 前景 mask：alpha > 0 即前景
                val fgMask = IntArray(current.width * current.height) { i ->
                    if ((fgPx[i] ushr 24) and 0xFF > 0) 255 else 0
                }
                if (!request.debugSkipAI) {
                    val harmonizeReq = HarmonizeRequest(
                        foregroundPixels = fgPx,
                        backgroundPixels = scenePxForHarm,
                        width = current.width,
                        height = current.height,
                        mask = fgMask,
                    )
                    // 高端机（RAM≥8GB 且核数≥8）先试 PCT-Net，失败回退 MKL；其余直接 MKL。
                    // DebugConfig.forcePctNet 供真机调试：中端机也能强制走 PCT-Net 验证 NPU/超时回退。
                    val harmonizeResult =
                        if (isHighEndDevice() || com.naiwa.core.common.debug.DebugConfig.forcePctNet) {
                            pctNet.harmonize(harmonizeReq) ?: MklHarmonizer.harmonize(harmonizeReq)
                        } else {
                            MklHarmonizer.harmonize(harmonizeReq)
                        }
                    if (com.naiwa.core.common.debug.DebugConfig.diagnosticLogging) {
                        Log.i(
                            TAG,
                            "规格8 生效路径：LUT=${lutSel.primary.label} 和谐化=${harmonizeResult.backend}",
                        )
                    }
                    // 色彩失真保护（需求 §三.1）：
                    //  1) Clamp：AI 预测的颜色矩阵可能出现极端数值，逐像素钳制到 [0,255]；
                    //  2) 全局混合：最终 = (1-strength)*原像素 + strength*AI像素，strength 默认 0.8，
                    //     避免 AI 一步到位把颜色拉过头导致过饱和。
                    val strength = request.harmonizeStrength.coerceIn(0f, 1f)
                    val blended = blendWithOriginal(
                        original = fgPx,
                        aiPixels = harmonizeResult.pixels,
                        mask = fgMask,
                        strength = strength,
                    )
                    val harmonizedBmp = android.graphics.Bitmap.createBitmap(
                        current.width, current.height, android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    harmonizedBmp.setPixels(
                        blended, 0, current.width, 0, 0, current.width, current.height,
                    )
                    current = harmonizedBmp.also { created += it }
                }

                // 细节保护（需求 §三.2）：高频重注入 —— 提取奶蛙原图的高频细节
                // （原图 - 高斯模糊图），按 0.3~0.5 权重叠加回融合结果，找回被融合糊掉的线稿。
                current = reinjectHighFrequency(current, workFrog, fgMask)
                    .also { created += it }

                // ① 四色渐变环境光（规格 7.4）：让"上蓝下绿"这类复杂环境色进得来
                if (palette != null && fxPlan.lightWrapStrength > 0f) {
                    current = LightWrapGradient.apply(current, palette, fxPlan.lightWrapStrength)
                        .also { created += it }
                }

                // ② 边缘打光（规格 7.1）：颜色取场景高光区的中位色
                if (palette != null && fxPlan.rimStrength > 0f) {
                    // 亮边宽度 2~4px（规格）。按奶蛙自身的分辨率缩放：素材分辨率越高，
                    // 同样的像素宽度在成品里越细，所以要跟着放大后仍留在 2~4px 区间内。
                    val rimWidth = (3f * minOf(current.width, current.height) / 512f)
                        .coerceIn(2f, 4f)
                    current = RimLight.apply(
                        frog = current,
                        light = light,
                        strength = fxPlan.rimStrength,
                        rimColor = palette.highlightColor,
                        widthPx = rimWidth,
                    ).also { created += it }
                }

                // ③ 辉光（规格 7.2）：只吃奶蛙自身的高光，强度上限 0.3
                // 方向模糊（规格 9 增强）：沿主光方向拉伸，模拟逆光镜头光晕的方向性
                if (fxPlan.glowStrength > 0f) {
                    current = Glow.apply(
                        frog = current,
                        strength = fxPlan.glowStrength,
                        directionAngleDeg = light.keyAzimuthDeg,
                    ).also { created += it }
                }

                harmonized = current
            }
            timings[RenderResult.Stage.COLOR_TRANSFER] = SystemClock.elapsedRealtime() - t1

            // ---------- SHADOW ----------
            onStage?.invoke(RenderResult.Stage.SHADOW)
            val t2 = SystemClock.elapsedRealtime()
            val needShadow = request.mode.includesShadow && light.shadowStrength > SHADOW_MIN_STRENGTH
            val sceneForFusion: Bitmap
            if (needShadow) {
                // 掩膜取自"场景坐标系下的奶蛙图层"，天然与服务端坐标一致（旋转已被应用）
                val placedForMask = SimpleBlender.renderFrogOnly(harmonized, request.placement, sceneW, sceneH)
                created += placedForMask
                val shadowMask = MaskUtils.extractAlphaMask(placedForMask)
                val shadowed = DirectionalShadow.composite(
                    scene = workScene,
                    frogAlpha = shadowMask,
                    alphaWidth = sceneW,
                    alphaHeight = sceneH,
                    // 掩膜已经是场景坐标系，因此"奶蛙外接矩形"就是整幅画面
                    placementRect = intArrayOf(0, 0, sceneW, sceneH),
                    light = light,
                    palette = palette,
                )
                created += shadowed
                sceneForFusion = shadowed
            } else {
                sceneForFusion = workScene
            }
            timings[RenderResult.Stage.SHADOW] = SystemClock.elapsedRealtime() - t2

            // ---------- FUSION ----------
            onStage?.invoke(RenderResult.Stage.FUSION)
            val t3 = SystemClock.elapsedRealtime()

            val placedFrog = SimpleBlender.renderFrogOnly(harmonized, request.placement, sceneW, sceneH)
            created += placedFrog
            val sceneMask = MaskUtils.extractAlphaMask(placedFrog)

            // 羽化直贴：过渡带的一半权重来源
            val featheredFrog = AlphaFeather.feather(harmonized, featherPx)
            created += featheredFrog
            val placedFeathered = SimpleBlender.renderFrogOnly(featheredFrog, request.placement, sceneW, sceneH)
            created += placedFeathered
            val featheredDirect = SimpleBlender.composite(sceneForFusion, placedFeathered)
            created += featheredDirect

            var actualTier = FusionTier.NONE
            var fusedBitmap: Bitmap? = null

            // debugSkipAI 时跳过泊松融合（只做 α 直贴），用于排查失真来源
            if (request.mode.includesFusion && !request.debugSkipAI) {
                val tier = request.requestedTier ?: adaptiveTier ?: defaultTierFor()
                actualTier = tier

                if (tier == FusionTier.FUSION_540 || tier == FusionTier.FUSION_720) {
                    val tPoisson = SystemClock.elapsedRealtime()
                    fusedBitmap = PoissonBlender.fuse(
                        scene = sceneForFusion,
                        placedFrog = placedFrog,
                        sceneMask = sceneMask,
                        tier = tier,
                    )
                    val elapsed = SystemClock.elapsedRealtime() - tPoisson
                    updateAdaptiveTier(tier, elapsed)
                    if (fusedBitmap == null) {
                        // 泊松失败（OOM / 原生异常 / OpenCV 不可用）→ 保底档
                        actualTier = FusionTier.LAPLACIAN
                    } else {
                        created += fusedBitmap
                    }
                }

                if (actualTier == FusionTier.TILED) {
                    // 分块档是**用户显式选择**：不调用 updateAdaptiveTier ——
                    // 它的耗时随 tile 数线性增长，若参与自适应，一次"跑得快"会把默认档永久推到分块上。
                    // 代价高、收益是"无上采样"的高保真，所以只由用户为具体某张图决定。
                    val tTile = SystemClock.elapsedRealtime()
                    fusedBitmap = TiledBlender.fuse(
                        scene = sceneForFusion,
                        placedFrog = placedFrog,
                        sceneMask = sceneMask,
                    )
                    Log.i(
                        TAG,
                        "融合档位：分块融合用时 ${SystemClock.elapsedRealtime() - tTile}ms" +
                            if (fusedBitmap == null) "（失败，回退保底档）" else "",
                    )
                    if (fusedBitmap == null) {
                        actualTier = FusionTier.LAPLACIAN
                    } else {
                        created += fusedBitmap
                    }
                }

                if (actualTier == FusionTier.LAPLACIAN) {
                    val tLap = SystemClock.elapsedRealtime()
                    fusedBitmap = laplacianFuse(sceneForFusion, placedFrog, sceneMask, sceneW, sceneH)
                        .also { created += it }
                    updateAdaptiveTier(FusionTier.LAPLACIAN, SystemClock.elapsedRealtime() - tLap)
                }
            }
            timings[RenderResult.Stage.FUSION] = SystemClock.elapsedRealtime() - t3

            // ---------- EDGE：α 加权回贴三段式 ----------
            onStage?.invoke(RenderResult.Stage.EDGE)
            val t4 = SystemClock.elapsedRealtime()

            var finalBitmap: Bitmap = if (request.mode.includesFusion && fusedBitmap != null) {
                val scenePx = IntArray(sceneW * sceneH)
                sceneForFusion.getPixels(scenePx, 0, sceneW, 0, 0, sceneW, sceneH)
                val featheredPx = IntArray(sceneW * sceneH)
                featheredDirect.getPixels(featheredPx, 0, sceneW, 0, 0, sceneW, sceneH)
                val fusedPx = IntArray(sceneW * sceneH)
                fusedBitmap.getPixels(fusedPx, 0, sceneW, 0, 0, sceneW, sceneH)

                val out = EdgeHaloFix.blend(
                    scenePixels = scenePx,
                    featheredPixels = featheredPx,
                    fusedPixels = fusedPx,
                    mask = sceneMask,
                    width = sceneW,
                    height = sceneH,
                    featherPx = featherPx,
                    // 双轨制：高配设备上额外做一步细节保真（把过渡带丢掉的高频补回来）
                    detailStrength = detailStrength,
                    detailReport = detailReport,
                    // 规格 8.3：方向性渐变蒙版（迎光面窄硬 / 背光面宽软）
                    lightAzimuthDeg = light.keyAzimuthDeg,
                )
                Bitmap.createBitmap(sceneW, sceneH, Bitmap.Config.ARGB_8888).apply {
                    setPixels(out, 0, sceneW, 0, 0, sceneW, sceneH)
                }.also { created += it }
            } else {
                // 未走融合（SETTLE 模式）但仍可应用细节保真：此时 featheredDirect 就是"混合结果"
                if (detailStrength > 0f) {
                    val scenePx = IntArray(sceneW * sceneH)
                    sceneForFusion.getPixels(scenePx, 0, sceneW, 0, 0, sceneW, sceneH)
                    val blendedPx = IntArray(sceneW * sceneH)
                    featheredDirect.getPixels(blendedPx, 0, sceneW, 0, 0, sceneW, sceneH)
                    val out = EdgeHaloFix.blend(
                        scenePixels = scenePx,
                        featheredPixels = blendedPx,
                        fusedPixels = blendedPx,
                        mask = sceneMask,
                        width = sceneW,
                        height = sceneH,
                        featherPx = featherPx,
                        detailStrength = detailStrength,
                        detailReport = detailReport,
                        lightAzimuthDeg = light.keyAzimuthDeg,
                    )
                    Bitmap.createBitmap(sceneW, sceneH, Bitmap.Config.ARGB_8888).apply {
                        setPixels(out, 0, sceneW, 0, 0, sceneW, sceneH)
                    }.also { created += it }
                } else {
                    featheredDirect
                }
            }
            timings[RenderResult.Stage.EDGE] = SystemClock.elapsedRealtime() - t4

            // ---------- LIGHT WRAP（规格 8.2）：背景溢出光 Screen 叠加 ----------
            // 环绕奶蛙轮廓的"背景光色带"：内侧给边缘染环境色、外侧是自然光晕。
            // 与四色渐变（Soft Light 全场着色）互补；强度由 lightWrapStrength 滑杆等比驱动
            // （默认 WRAP_DEFAULT=0.25 时 opacity 恰为规格值 0.4）。
            if (fxPlan.lightWrapStrength > 0f && request.mode.includesFusion) {
                val tWrap = SystemClock.elapsedRealtime()
                val compositePx = IntArray(sceneW * sceneH)
                finalBitmap.getPixels(compositePx, 0, sceneW, 0, 0, sceneW, sceneH)
                val wrapOpacity = (LightWrap.DEFAULT_OPACITY *
                    fxPlan.lightWrapStrength / LightFxRules.WRAP_DEFAULT).coerceIn(0f, 1f)
                val wrapped = LightWrap.apply(
                    compositePixels = compositePx,
                    scenePixels = cachedScenePixelArray(sceneForFusion),
                    frogMask = sceneMask,
                    width = sceneW,
                    height = sceneH,
                    opacity = wrapOpacity,
                )
                val wrappedBitmap = Bitmap.createBitmap(sceneW, sceneH, Bitmap.Config.ARGB_8888).apply {
                    setPixels(wrapped, 0, sceneW, 0, 0, sceneW, sceneH)
                }
                created += finalBitmap // 旧结果由 created 统一回收
                finalBitmap = wrappedBitmap
                timings[RenderResult.Stage.EDGE] =
                    (timings[RenderResult.Stage.EDGE] ?: 0L) + (SystemClock.elapsedRealtime() - tWrap)
            }

            // 如实校正后端：细节保真没真正执行时，不能继续上报"已启用"
            if (detailStrength > 0f && detailReport?.applied != true) {
                actualBackend = FusionBackend.CLASSICAL
                Log.w(TAG, "细节保真未生效，已退回经典路径：${detailReport?.fallbackReason ?: "未知原因"}")
            }

            // 输出位图不能随 created 一起回收
            created.remove(finalBitmap)

            return RenderResult(
                bitmap = finalBitmap,
                actualTier = actualTier,
                lightSource = lastLightSource,
                stageTimings = timings,
                backend = actualBackend,
                // 光效计划如实上报：逆光与否 + 三项强度。全 0 时也带上（用户据此知道"这台低端机关了辉光"）
                fxPlan = fxPlan,
            )
        } finally {
            created.forEach { BitmapGuard.recycle(it) }
        }
    }

    /**
     * 工作场景缩放缓存：原场景（scenePreview）是固定实例，目标短边也固定，
     * 缓存缩放结果，避免每次 SETTLE 重复整图缩放（1600→1080）。
     *
     * 内存安全：只有当缓存位图是**管线自己 createScaledBitmap 出来的新对象**时才在
     * 失效时回收；若缓存的就是 source 本身（未缩放，短边已达标），绝不回收——
     * 那是调用方持有的 Bitmap。
     */
    private fun cachedScaledScene(source: Bitmap, maxShortSide: Int): Bitmap {
        if (source === cachedScaledSource && maxShortSide == cachedScaledMaxShortSide) {
            cachedScaledScene?.let { return it }
        }
        val scaled = scaleToMaxShortSide(source, maxShortSide)
        val isOwned = scaled !== source // createScaledBitmap 返回新对象才是管线创建的
        val old = cachedScaledScene
        val oldOwned = cachedScaledIsOwned
        cachedScaledSource = source
        cachedScaledMaxShortSide = maxShortSide
        cachedScaledScene = scaled
        cachedScaledIsOwned = isOwned
        // 缩放结果变了，下游像素/palette 缓存一并失效
        cachedScene = null
        cachedScenePixels = null
        cachedPalette = null
        // 只有"管线自己创建"的旧缓存才回收（调用方持有的 Bitmap 绝不碰）
        if (old != null && old !== scaled && oldOwned) old.recycle()
        return scaled
    }

    /**
     * 场景取色缓存：预览场景是同一 Bitmap 实例，取色结果只依赖场景内容，
     * 缓存后松手预览不再重复「整图采样 + 排序 + 4 区统计」。
     */
    private fun cachedScenePalette(scene: Bitmap): ScenePalette {
        if (scene === cachedScene) {
            cachedPalette?.let { return it }
        }
        val px = cachedScenePixelArray(scene)
        val p = ScenePaletteExtractor.extract(px, scene.width, scene.height)
        cachedPalette = p
        return p
    }

    /**
     * 场景像素缓存：避免每次 `getPixels` 拷贝 1600×N 大数组。
     * 场景 Bitmap 换实例（切素材/换图）时自动失效重建。
     */
    private fun cachedScenePixelArray(scene: Bitmap): IntArray {
        if (scene === cachedScene) {
            cachedScenePixels?.let { return it }
        }
        val w = scene.width
        val h = scene.height
        val px = IntArray(w * h)
        scene.getPixels(px, 0, w, 0, 0, w, h)
        cachedScene = scene
        cachedScenePixels = px
        cachedSceneW = w
        cachedSceneH = h
        cachedPalette = null // 场景变了，palette 一并失效
        return px
    }

    /** 档 3：拉普拉斯多频段融合（纯 Kotlin，不依赖 OpenCV）。 */
    private fun laplacianFuse(
        scene: Bitmap,
        placedFrog: Bitmap,
        sceneMask: IntArray,
        width: Int,
        height: Int,
    ): Bitmap {
        val scenePx = IntArray(width * height)
        scene.getPixels(scenePx, 0, width, 0, 0, width, height)
        val frogPx = IntArray(width * height)
        placedFrog.getPixels(frogPx, 0, width, 0, 0, width, height)
        val merged = LaplacianBlender.blend(scenePx, frogPx, sceneMask, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(merged, 0, width, 0, 0, width, height)
        }
    }

    /**
     * 色彩失真保护（需求 §三.1）：
     *  - Clamp：AI 预测的颜色矩阵可能产生越界值，逐像素钳制到 [0,255]；
     *  - 全局混合：`final = (1-strength)*original + strength*ai`，strength 默认 0.8。
     *  只作用于前景（mask > 0），背景原样保留；alpha 通道透传原始值。
     */
    private fun blendWithOriginal(
        original: IntArray,
        aiPixels: IntArray,
        mask: IntArray,
        strength: Float,
    ): IntArray {
        if (original.size != aiPixels.size || original.size != mask.size) return original
        val out = original.copyOf()
        val s = strength.coerceIn(0f, 1f)
        for (i in out.indices) {
            if (mask[i] == 0) continue
            val a = (original[i] ushr 24) and 0xFF
            if (a == 0) continue

            val oR = (original[i] ushr 16) and 0xFF
            val oG = (original[i] ushr 8) and 0xFF
            val oB = original[i] and 0xFF

            // AI 输出先 clamp 再混合
            val aiR = (((aiPixels[i] ushr 16) and 0xFF)).coerceIn(0, 255)
            val aiG = (((aiPixels[i] ushr 8) and 0xFF)).coerceIn(0, 255)
            val aiB = (aiPixels[i] and 0xFF).coerceIn(0, 255)

            val r = (oR * (1f - s) + aiR * s).coerceIn(0f, 255f).toInt()
            val g = (oG * (1f - s) + aiG * s).coerceIn(0f, 255f).toInt()
            val b = (oB * (1f - s) + aiB * s).coerceIn(0f, 255f).toInt()

            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * 细节保护（需求 §三.2）：高频重注入。
     *
     * 提取奶蛙**原图**的高频细节（原图 - 高斯模糊图，即高通滤波结果），
     * 按 [HIGH_FREQ_REINJECT_WEIGHT] 权重叠加回融合结果 —— 把被 AI 和谐化/泊松融合抹糊的
     * 二次元线稿找回来。只作用于前景，且只在融合结果偏离原图时补高频，不改变整体色调。
     *
     * 高斯模糊用两次 box blur 近似（核半径 3），够快且足够作为高通底图。
     */
    private fun reinjectHighFrequency(
        fused: Bitmap,
        original: Bitmap,
        mask: IntArray,
    ): Bitmap {
        if (fused.width != original.width || fused.height != original.height) return fused
        val w = fused.width
        val h = fused.height

        val fusedPx = IntArray(w * h)
        fused.getPixels(fusedPx, 0, w, 0, 0, w, h)
        val origPx = IntArray(w * h)
        original.getPixels(origPx, 0, w, 0, 0, w, h)

        // 原图的低通（高斯近似）→ 高通 = 原图 - 低通
        val lowPass = boxBlur(origPx, w, h, radius = 3)
        val highPass = IntArray(w * h)
        for (i in origPx.indices) {
            if (mask[i] == 0) continue
            val a = (origPx[i] ushr 24) and 0xFF
            if (a == 0) continue
            val lr = (lowPass[i] ushr 16) and 0xFF
            val lg = (lowPass[i] ushr 8) and 0xFF
            val lb = lowPass[i] and 0xFF
            val or = (origPx[i] ushr 16) and 0xFF
            val og = (origPx[i] ushr 8) and 0xFF
            val ob = origPx[i] and 0xFF
            // 高频（可正可负），居中到 128 便于叠加
            highPass[i] = ((or - lr + 128).coerceIn(0, 255) shl 16) or
                ((og - lg + 128).coerceIn(0, 255) shl 8) or
                ((ob - lb + 128).coerceIn(0, 255))
        }

        // 叠加：fused + weight * (highPass - 128)，只在 mask 内
        val out = fusedPx.copyOf()
        val wt = HIGH_FREQ_REINJECT_WEIGHT
        for (i in out.indices) {
            if (mask[i] == 0) continue
            val a = (fusedPx[i] ushr 24) and 0xFF
            if (a == 0) continue
            val hr = (highPass[i] ushr 16) and 0xFF
            val hg = (highPass[i] ushr 8) and 0xFF
            val hb = highPass[i] and 0xFF
            val r = (fusedPx[i] ushr 16) and 0xFF
            val g = (fusedPx[i] ushr 8) and 0xFF
            val b = fusedPx[i] and 0xFF
            val nr = (r + wt * (hr - 128)).coerceIn(0f, 255f).toInt()
            val ng = (g + wt * (hg - 128)).coerceIn(0f, 255f).toInt()
            val nb = (b + wt * (hb - 128)).coerceIn(0f, 255f).toInt()
            out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    /** 两次 box blur 近似高斯模糊（核半径 radius），返回 ARGB 低通图。 */
    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels.copyOf()
        var src = pixels
        var dst = IntArray(w * h)
        // 水平方向
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val p = src[y * w + xx]
                    r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF; n++
                }
                dst[y * w + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        src = dst
        dst = IntArray(w * h)
        // 垂直方向
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val p = src[yy * w + x]
                    r += (p ushr 16) and 0xFF; g += (p ushr 8) and 0xFF; b += p and 0xFF; n++
                }
                dst[y * w + x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return dst
    }

    // ------------------------------------------------------------------ P3：档位自适应

    /** 首次渲染按设备能力初选档位。 */
    private fun defaultTierFor(): FusionTier =
        if (isLowEndDevice()) {
            // 低端机：RAM < 4GB 或核心数 ≤ 4。泊松求解在 4 核上会明显拖慢，
            // 直接走备选档，避免用户第一次导出就等 10 秒。
            FusionTier.LAPLACIAN
        } else {
            // 2026-10 起性能标准放宽（1080p < 5s / 内存 < 600MB），
            // 档 1 从"要实测够快才升"改为**默认档**：720p 域克隆质量最好，总耗时约 3.5s，仍在验收线内。
            // 实测超过 5s 才降到档 2；再慢降到档 3。
            FusionTier.FUSION_720
        }

    /**
     * 按实测耗时自适应升降档。
     *
     * 两个必须说清楚的设计点：
     *  1. **只改"下一次"的默认档，不改变本次结果** —— 保证同一张图重复导出得到同样的输出。
     *  2. **降过的档不会自动升回去**（[fuseTooSlow] 黑名单）。否则会震荡：
     *     档 1 跑 6s → 降到档 2 → 档 2 只跑 1s → 又升回档 1 → 又跑 6s，无限来回。
     *     而"这台设备跑档 1 会超时"是**设备的固有属性**，记住它就够了。
     */
    private fun updateAdaptiveTier(usedTier: FusionTier, elapsedMs: Long) {
        lastFusionMs = elapsedMs

        // 超时的档位拉黑：本进程内不再自动升回该档
        if (elapsedMs >= TIER_TIMEOUT_MS) {
            degradedTiers += usedTier
            Log.w(TAG, "$usedTier 实测 ${elapsedMs}ms 超过 ${TIER_TIMEOUT_MS}ms 验收线，本进程内不再使用该档")
        }

        adaptiveTier = when {
            // 档 3 是终点，不需要再变
            usedTier == FusionTier.LAPLACIAN -> FusionTier.LAPLACIAN

            // 档 2 跑得够快，且档 1 没被拉黑 → 下次试档 1
            usedTier == FusionTier.FUSION_540 &&
                elapsedMs <= TIER_UPGRADE_MS &&
                FusionTier.FUSION_720 !in degradedTiers -> FusionTier.FUSION_720

            // 档 2 在可接受范围内 → 保持
            usedTier == FusionTier.FUSION_540 &&
                elapsedMs < TIER_TIMEOUT_MS -> FusionTier.FUSION_540

            // 档 2 也超时了 → 退到备选档
            usedTier == FusionTier.FUSION_540 -> FusionTier.LAPLACIAN

            // 档 1：超时则降到档 2，否则保持
            elapsedMs >= TIER_TIMEOUT_MS -> FusionTier.FUSION_540
            else -> FusionTier.FUSION_720
        }
        Log.i(TAG, "融合档位：本次 $usedTier 用时 ${elapsedMs}ms → 下次默认 $adaptiveTier")
    }

    /**
     * 低端机判定。
     *
     * 规则本身已收敛到 [com.naiwa.core.common.model.FusionTrack.tierFor]（纯函数、有单测），
     * 这里只负责取数 —— **全项目只有一处**读 ActivityManager，
     * 避免"双轨制选路"与"融合档位初值"各处判断口径不同而自相矛盾。
     */
    private fun isLowEndDevice(): Boolean = try {
        DeviceCapabilityProbe.detect(context).tier == DeviceCapabilityTier.LOW_END
    } catch (t: Throwable) {
        // 拿不到系统信息时按"不是低端机"处理：宁可多花点时间，也不要无故降质
        false
    }

    /** 高端机（RAM ≥ 8GB 且核心数 ≥ 8）：规格 8.2 里 PCT-Net 的启用门槛。 */
    private fun isHighEndDevice(): Boolean = try {
        DeviceCapabilityProbe.detect(context).tier == DeviceCapabilityTier.HIGH_END
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ 工具

    /** 缩到指定短边并取出像素（估计器只吃像素数组，便于纯 JVM 单测）。 */
    private fun downscalePixels(source: Bitmap, maxShortSide: Int): Triple<IntArray, Int, Int> {
        val shortSide = minOf(source.width, source.height)
        val scaled = if (shortSide > maxShortSide) {
            val ratio = maxShortSide.toFloat() / shortSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            null
        }
        val bmp = scaled ?: source
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val result = Triple(pixels, bmp.width, bmp.height)
        if (scaled != null && scaled !== source) scaled.recycle()
        return result
    }

    private fun scaleToMaxShortSide(source: Bitmap, maxShortSide: Int): Bitmap {
        if (maxShortSide <= 0) return source
        val shortSide = minOf(source.width, source.height)
        if (shortSide <= maxShortSide) return source
        val ratio = maxShortSide.toFloat() / shortSide
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun downscaleIfOversized(source: Bitmap, maxWidth: Int): Bitmap {
        if (maxWidth <= 0 || source.width <= maxWidth) return source
        val ratio = maxWidth.toFloat() / source.width
        return Bitmap.createScaledBitmap(
            source,
            maxWidth,
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 供 UI / 调试查看：当前自适应档位与最近一次实测融合耗时。 */
    fun fusionDiagnostics(): Pair<FusionTier?, Long> = adaptiveTier to lastFusionMs

    private companion object {
        const val TAG = "NaiwaCamera"


        /**
         * CV 骨架测光的工作分辨率（长边）。
         * 光照是低频信息：480px 与 4096px 的方位角差异 < 2°，但耗时差两个数量级。
         */
        const val SKELETON_LONG_SIDE = 480

        /**
         * 大图保护上限（§7.3）：工作副本长边超过 4096 先缩到 4096，不做分块。
         * 4096 是"4K 长边"的验收口径（4K 导出 < 12s）。
         */
        const val MAX_EXPORT_SHORT_SIDE = 4096

        /**
         * OOM 降级后的工作分辨率。
         *
         * 2026-10 起从 1280 提高到 **1600**：内存预算放宽到 600MB 后，
         * 1280 的下采样痕迹（尤其在高频背景上的糊块）反而成了主要问题。
         */
        const val DEGRADED_SHORT_SIDE = 1600

        /** 奶蛙预备分辨率相对目标宽度的过采样倍数。 */
        const val FROG_OVERSAMPLE = 2

        /** 光照估计的输入短边：估计只看低频统计量，256 够用且快。 */
        const val LIGHT_ESTIMATION_SHORT_SIDE = 256

        /** 阴影浓度低于此值时不生成阴影（省一次 1/4 分辨率扫掠）。 */
        const val SHADOW_MIN_STRENGTH = 0.05f

        /**
         * 档位升级阈值：档 2 融合跑进 3.0s 说明还有余量，下次试档 1。
         * 阈值给得比超时线紧，是为了避免"刚好卡在线上"来回切档。
         *
         * 2026-10-16 起预算放宽（"600MB / 5s 不卡太死，能上就上"），
         * 因此这两个阈值从 2500/4000ms 松到 3000/6000ms：宁可多等一会儿换更好的质量，
         * 而不是在还没超时就急着降档。
         */
        const val TIER_UPGRADE_MS = 3000L

        /**
         * 档位超时线。原口径是"验收线 5s 减去其余阶段约 1.5s"= 3.5s（取 4000ms）；
         * 预算放宽后取 **6000ms** —— 只有在"这一档确实太慢"时才降，不再按紧预算提前降级。
         */
        const val TIER_TIMEOUT_MS = 6000L

        /** 判定低端机的核数阈值（与 FusionTrack.LOW_END_CORES 保持一致，仅用于日志）。 */
        const val LOW_END_CORES = 4

        /**
         * 高频重注入权重（需求 §三.2）：0.3~0.5 区间取中值 0.4。
         * 太高会把噪声也带回来，太低找不回线稿。
         */
        const val HIGH_FREQ_REINJECT_WEIGHT = 0.4f
    }
}
