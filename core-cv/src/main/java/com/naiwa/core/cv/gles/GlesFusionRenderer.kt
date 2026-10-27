package com.naiwa.core.cv.gles

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.naiwa.core.common.render.GlReadback
import com.naiwa.core.common.render.GlState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「次元融合」GPU 离屏渲染器（规格：专业级光影融合，GLES 3.0）。
 *
 * 【管线（3 pass，全部在 Fragment Shader 内）】
 *  passKey       : 绿幕立绘 → 人物 RGBA（YCbCr 色键 + smoothstep 抗锯齿 + 溢出抑绿）
 *  passBlur      : 13-tap 边缘感知高斯（背景模糊光 / 人物低频，用于高频重注入）
 *  passComposite : 主合成 —— Gamma 转线性 → 接触阴影(Multiply) → Alpha 叠加 →
 *                  Rim Light(Screen) → Light Wrap(Screen，限亮+平滑衰减) → 反弹光(Screen) →
 *                  高频重注入 → Clamp → Gamma 转回 sRGB
 *
 * 【降级契约】任何 EGL/GL 错误都返回 null，由调用方回退 CPU 管线 —— 本类**从未在真机验证**，
 * 首跑必须与 CPU 路径做 A/B 对拍（见 docs/GPU-SHADERS.md 启用检查单）。
 *
 * 【CPU 侧仅保留】2048 点取色统计（暗部/高光色，O(1) 采样非渲染循环）与 LiteRT 推理。
 */
object GlesFusionRenderer {

    private const val TAG = "NaiwaCamera"

    /** GPU 融合参数（CPU 统计/分析一次得到，全部走 uniform）。 */
    data class Params(
        val hasGreenScreen: Boolean,
        val lightAzimuthDeg: Float,
        val lightIntensity: Float,
        val wrapOpacity: Float = 0.3f,
        val shadowStrength: Float = 0.5f,
        val shadowOffsetPx: Float = 24f,
        val bounceStrength: Float = 0.25f,
        val hfWeight: Float = 0.4f,
        val rimStrength: Float = 0.35f,
        /** 主光方向 3D 向量（法向点积用；缺省由方位角换算）。 */
        val lightDirX: Float = 0f,
        val lightDirY: Float = -1f,
        val lightDirZ: Float = 0.6f,
        /** 光包裹强度（0~1，来自多任务模型/CV 分析）。 */
        val wrapStrength: Float = 0.3f,
        /** 辉光强度（0~1）。 */
        val glowStrength: Float = 0.2f,
        /** 背景亮部前 5% 中位色（光包裹颜色来源）。 */
        val bgHighlightColor: Int = 0xFFF2F2F2.toInt(),
        /** 背景模糊 σ（拉普拉斯方差动态计算，15~30）。 */
        val bgBlurSigma: Float = 22f,
        /** 色温乘法增益（R/G/B，1.0 = 中性）—— 让素材真正参与调色而非原始贴图。 */
        val tintR: Float = 1f,
        val tintG: Float = 1f,
        val tintB: Float = 1f,
        /** 主光源中心（归一化 0~1，NMS 结果）—— 镜头光晕定位、射线模糊中心。 */
        val lightX: Float = 0.5f,
        val lightY: Float = 0.3f,
        /** 是否强逆光（背景高光显著亮于人物）。 */
        val backlit: Boolean = false,

        // ------------------------------------------------ v0.27 逆光压暗 + 阴影熔断
        /**
         * 逆光时**背光侧**的压暗系数（默认 0.32，来自
         * [com.naiwa.core.common.model.BacklitShade.DARKEN_BACK]）。
         *
         * 只在 `backlit = true` 时生效；非逆光整段 shader 不执行。
         */
        val backlitDarken: Float = com.naiwa.core.common.model.BacklitShade.DARKEN_BACK,
        /**
         * 逆光时的**全局**压暗（默认 0.82）。
         *
         * 为什么还需要全局：二次元平涂人物没有表面法向变化，只压背光侧会把人物切成
         * "一半正常一半死黑"。逆光的本质是"主体整体在阴影里"，所以先整体压一次。
         */
        val backlitGlobalDarken: Float = com.naiwa.core.common.model.BacklitShade.DARKEN_GLOBAL,
        /** 暗部染色强度（0 = 只压暗不染色；默认 0.65）。染的是背景暗部环境色的**色相**。 */
        val backlitChroma: Float = com.naiwa.core.common.model.BacklitShade.CHROMA_WEIGHT,
        /**
         * 阴影漏光熔断强度（1 = 处于背光/投影中的像素，主光类光效完全归零）。
         *
         * 作用对象：`FRAG_EMIT`（发光源）、`FRAG_LIGHTWRAP`（向内包裹）、`FRAG_P2B`（立体反光）。
         */
        val wrapShadowCut: Float = com.naiwa.core.common.model.BacklitShade.CUT_STRENGTH,
        /**
         * 熔断后保留的环境散射地板（默认 0.15）。
         *
         * 给 0 会让背光侧变成死黑，与"人物融进背景"没有区别；0.15 相当于环境漫反射。
         * 想要"绝对熔断"把它设成 0 —— 这是唯一需要动它的场景。
         */
        val wrapAmbientFloor: Float = com.naiwa.core.common.model.BacklitShade.AMBIENT_FLOOR,
        /**
         * 明暗交界过渡带的**半宽**（1080p 基准像素，默认 7）。
         *
         * 它同时决定两件事：
         * 1. 低频 alpha 的模糊半径（轮廓被抹成多宽的斜坡）；
         * 2. P2 求法向时的差分基线（±N 像素）。
         *
         * 太小（<4）= 硬边；太大（>14）= 明暗糊成一片、失去立体感。
         */
        val penumbraPx: Float = 7f,
        /** P1 全局调色矩阵（3×3 行优先，默认单位）。 */
        val globalColorMatrix: FloatArray = UNIT_3X3,
        /** P2 人像颜色矩阵（3×3，默认单位；AI 只允许出这个，且乘 0.6）。 */
        val personColorMatrix: FloatArray = UNIT_3X3,
        /** P2-c 阴影颜色（sRGB）。 */
        val shadowColorRgb: Int = 0xFF26221C.toInt(),
        /**
         * P2-w 光包裹强度（0 = 关闭该 pass）。与 [wrapStrength] 的区别：
         *  - [wrapStrength] 是 P2-b 立体反光（人物自身高光外溢）；
         *  - 本字段是 P2-w 环境光包裹（**背景模糊光**糊到轮廓/发丝上），防大白边的正主。
         */
        val lightWrapStrength: Float = 0.35f,
        /** 光包裹环绕带外扩像素（规格 5px；P2-w 的 dilate 半径）。 */
        val wrapRadiusPx: Float = 5f,
        /**
         * 光包裹·主光色（sRGB）：背景亮度前 5% 的**加权均值** —— 夕阳暖黄/正午暖白。
         *
         * 与 [bgHighlightColor] 的区别：那个是"中位色"（用于边缘光），这个是"加权均值"，
         * 更接近光源本身的光谱（高光区里最亮的那部分权重大）。
         */
        val keyLightColorRgb: Int = 0xFFFFD8A8.toInt(),
        /**
         * 光包裹·环境色（sRGB）：背景**暗部（≤中位亮度）的均值** —— 天空冷蓝/阴影青。
         *
         * 它描述的是"背光面反射到的环境漫反射"，与主光色构成 Teal & Orange 互补。
         */
        val wrapAmbientColorRgb: Int = 0xFF8FA8C8.toInt(),
        /** 双色替换背景光色的比例（0.75 = 以双色为主，保留 25% 原始背景光）。 */
        val wrapColorMix: Float = 0.75f,
        /** 高度衰减起点（uv.y，本工程 1 = 画面底部；此值以下开始变弱）。 */
        val wrapHeightStart: Float = 0.70f,
        /** 高度衰减终点（1.0 = 画面最底部，此处包裹光归零）。 */
        val wrapHeightEnd: Float = 1.0f,
        /**
         * 环境色最低亮度（默认 0.25，线性）。
         *
         * 背景暗部均值常常接近纯黑，直接乘到人物侧面会糊成灰泥。按亮度归一后
         * 抬到这个下限，保留冷色调的"光感"而不改变色相。
         */
        val wrapAmbientLift: Float = 0.25f,
        /** 阴影遮挡衰减量（0.8 → 被遮挡处强度只剩 20%）。 */
        val wrapShadowOcclusion: Float = 0.8f,
        /**
         * 调试通道（0 = 正常出图）。
         *
         * | 值 | 输出 | 判读 |
         * | --- | --- | --- |
         * | 1 | 外法向 `n*0.5+0.5` | 平涂区应为中性灰 (0.5,0.5,0.5)；整屏蓝/紫 → 梯度恒 0 |
         * | 2 | `(keyW, 1-keyW, 0)` | 迎光红、背光绿；整屏单色 → N·L 有问题 |
         * | 3 | 左环境色 / 右主光色 | 两半必须明显不同；都是白/灰 → 取色错 |
         * | 4 | `heightWeight` 灰度 | 顶部白、底部黑；全黑 → uv.y 约定错 |
         * | 5 | **向内渗透带** `wrapMask` | 只在人物**内侧** 5~10px 非零；若出现在轮廓外 → 又变成描边了 |
         * | 6 | `occlusion` | 投影覆盖处 < 1 |
         * | 7 | **发光源图** `emit`（×2） | 应只有"迎光侧"的轮廓在亮；全周均匀 → 迎光门控失效 |
         * | 8 | **泛光金字塔结果** | 应为从轮廓向外衰减的柔和光晕；出现方块台阶 → tent 上采样有问题 |
         */
        val debugWrapMode: Int = 0,
        /** 光包裹限亮系数：背景光按亮度上限压暗，0.2~0.4 防止糊成一片白。 */
        val wrapMaxLuma: Float = 0.35f,
        /** P2 色调映射 S 曲线强度（0 = 关闭；0.55 约等于中度胶片对比）。 */
        val toneContrast: Float = 0.55f,
        /** S 曲线暗部端点：低于它的亮度被压向 0（规格 0.2）。 */
        val toneCurveLow: Float = 0.2f,
        /** S 曲线亮部端点：高于它的亮度被推向 1（规格 0.8）。 */
        val toneCurveHigh: Float = 0.8f,
        /** P2 局部对比度（Unsharp Mask 权重，0 = 关闭）。 */
        val unsharp: Float = 0.25f,
        // ---------------- 旧版"人物高光泛光"（v0.26 起**不再被图层管线消费**）----------------
        // 保留字段只为不破坏既有调用方；实际生效的是下面的 edgeGlowStrength / glowSpreadPx 那条链。
        // 判定方式：在 GlesLayerPipeline 里 grep `bloomStrength` —— 零命中即说明确实没在用。
        val bloomStrength: Float = 0.3f,
        val bloomThreshold: Float = 0.8f,
        val bloomKnee: Float = 0.15f,
        val bloomRadiusPx: Float = 18f,

        // ------------------------------------------------ v0.26 边缘发光（EMIT → 金字塔 → Screen）
        /**
         * 边缘发光源强度（0 = 关闭整条发光链）。
         *
         * 这是**发光源图**的增益，不是最终亮度：它先被高斯金字塔扩散，再在
         * composite 之后 Screen。所以 0.5 的实际观感远弱于 0.5 的"直接叠加"——
         * 这是刻意的，真实发光的能量分布在很大的面积上，单点必须暗。
         */
        val edgeGlowStrength: Float = 0.55f,
        /**
         * 泛光扩散半径（1080p 基准像素，4~96）。
         *
         * 决定高斯金字塔的**级数**（见 [com.naiwa.core.common.model.GlowPyramid]）：
         * 24px ≈ 5 级，等效半径 32px。它不是"模糊核半径"，而是"光能溢出多远"。
         */
        val glowSpreadPx: Float = 28f,
        /** 泛光 Screen 到画面上的强度（0~3，默认 1.35）。
         *
         * 为什么可以 >1：光晕在金字塔里被逐级稀释，峰值通常只剩源强度的 1/5~1/3；
         * 这里补回来，再用 [glowAirRatio] 与 shader 内的 `uClamp=0.85` 兜住上限。
         */
        val glowIntensity: Float = 1.35f,
        /**
         * 背景空气接收泛光的比例（0~1，默认 0.35）。
         *
         * 人物上给 100%，背景只给 35% —— 规格要求"微微渗透到背景空气中"。
         * 给满会让整张背景发灰（泛光覆盖面积大，背景又本来就亮）。
         */
        val glowAirRatio: Float = 0.35f,
        /** P1 镜头光晕强度（0 = 关闭）。 */
        val flareStrength: Float = 0.55f,
        /** 镜头光晕半径倍率（1 = 默认；越大光斑越散）。 */
        val flareRadius: Float = 1f,
        /** P2-c 阴影柔化倍率（非逆光地面投影；1 = 默认）。 */
        val shadowSoftness: Float = 1f,
        /** P2-c 距离衰减：0 = 不衰减（影子均匀），1 = 末端几乎消失（近深远浅）。 */
        val shadowFalloff: Float = 0.80f,
        /** P2-c 根部一维模糊步长（像素，1080p 基准）。 */
        val shadowBlurBasePx: Float = 2.5f,
        /** P2-c 模糊随距离增长的幅度（像素）；越大越"近实远虚"。 */
        val shadowBlurGrowPx: Float = 26f,
        /** 接触阴影强度（0 = 关闭；0.9~1.0 才有"站住"的观感）。 */
        val contactShadowStrength: Float = 0.9f,
        /** 接触阴影垂直偏移（像素，2~5px；按 1080p 基准等比缩放）。 */
        val contactShadowPx: Float = 3.5f,
        /** 接触阴影颜色（sRGB，比环境阴影更深但**非纯黑**，默认深冷灰）。 */
        val contactShadowColorRgb: Int = 0xFF090A0C.toInt(),

        // ------------------------------------------------ 全局后处理（合成后、FXAA 前）
        /** 景深最大弥散半径（像素，1080p 基准；规格 3~5px，0 = 关闭该 pass）。 */
        val dofRadiusPx: Float = 4f,
        /** 景深交界环形带宽度（像素）：只模糊人物轮廓外这一圈，远处背景不动。 */
        val dofRingPx: Float = 20f,
        /** 大气透视：迎光侧渗透宽度（像素）。 */
        val bleedPxMin: Float = 10f,
        /** 大气透视：背光侧渗透宽度（像素）。 */
        val bleedPxMax: Float = 20f,
        /** 大气透视强度（0 = 关闭）。 */
        val bleedStrength: Float = 0.35f,
        /** 环境色罩颜色（sRGB，取背景代表色）。 */
        val ambientColorRgb: Int = 0xFF808080.toInt(),
        /** 环境色罩不透明度（0.05 左右）。 */
        val ambientTint: Float = 0.05f,

        // ------------------------------------------------ P2 四色渐变环境光 + 伪立体打光
        /** 背景四区中位色（sRGB）：上、下、左、右。 */
        val quadTopRgb: Int = 0xFFBFBFBF.toInt(),
        val quadBottomRgb: Int = 0xFFBFBFBF.toInt(),
        val quadLeftRgb: Int = 0xFFBFBFBF.toInt(),
        val quadRightRgb: Int = 0xFFBFBFBF.toInt(),
        /** 四色渐变强度（SoftLight 混合比例，默认 0.25；0 = 关闭）。 */
        val quadStrength: Float = 0.25f,
        /** 伪立体打光强度（迎光提亮 + 背光压暗，0 = 关闭）。 */
        val fakeLightStrength: Float = 0.15f,
        /** 逆光渐变带内径（归一化距离，越近越亮）。 */
        val gradientMinDist: Float = 0.20f,
        /** 逆光渐变带外径（越远越暗）。 */
        val gradientMaxDist: Float = 0.80f,
        /** 逆光渐变混合比例（0 = 关闭）。 */
        val gradientStrength: Float = 1.0f,
        /** 脚底垂直渐变起始 Y（0.78 ≈ 画面 78% 高度以下开始压暗）。 */
        val feetFadeStart: Float = 0.78f,
        /** 脚底压暗强度（0 = 关闭）。 */
        val feetFadeStrength: Float = 0.20f,
        /**
         * Shrink Mask：向内腐蚀像素（1~2px）。
         *
         * 抠图最外圈 1~2px 的 RGB 一定是被抠掉的背景色（白/绿）—— 直接不画这一圈，
         * 是去白边最直接的手段。副作用：极细发丝会一起被削掉，所以默认 1.5 而不是 3。
         */
        val edgeShrinkPx: Float = 1.5f,
        /**
         * Defringe 强度（0 = 关闭，默认 0.6）。
         *
         * 取边缘 4 邻域中 alpha 最大者的颜色作为"内部色"，只在 a<0.95 处混入，
         * 把被背景色污染的边缘 RGB 换成人物真实颜色。
         */
        val defringeStrength: Float = 0.6f,
        /**
         * 底部反弹光强度（默认 0.12，规格 0.10~0.15）。
         *
         * 背景底部主色（四区色板的 bottom 中位色）Screen 到人物下半身，
         * 模拟雪地/亮马路的反射光托底。
         */
        val bounceLightStrength: Float = 0.12f,
        /** 边缘光宽度（像素，规格 2~4px，决定环带与法向采样半径）。 */
        val rimWidthPx: Float = 3f,
        /** 边缘光增益（逆光金边需要比以前更亮）。 */
        val rimGain: Float = 1.2f,
        /** 高光阈值（原图 sRGB 亮度，规格 0.85）。 */
        val specThreshold: Float = 0.85f,
        /** 高光提亮强度。 */
        val specStrength: Float = 0.12f,
        /** 环境色分离强度（暗部冷 / 亮部暖，0 = 关闭）。 */
        val envSplitStrength: Float = 0.45f,
        /** 全局调色 3×3（行优先，线性空间）。 */
        val gradeMatrix: FloatArray = UNIT_3X3,
        val gradeContrast: Float = 1f,
        val gradeSaturation: Float = 1f,
        /**
         * Reinhard 色调映射强度（1 = 完全启用；0 = 关闭，仅调试用）。
         *
         * 位置在最终输出（FXAA pass，线性空间）。`c/(1+c)` 把高光平滑压回 [0,1)，
         * 替代硬 clamp —— 后者会把 >1 的像素全部变成纯白，这正是"泛白"的来源。
         */
        val toneMapStrength: Float = 1f,
        /**
         * Reinhard 前的线性曝光补偿（1 = 不补偿）。
         *
         * 线性空间的 Reinhard 对暗部近似恒等（0.05→0.048），主要压高光；
         * 若整张图偏暗，抬到 1.2~1.3 即可，**不要**去改 P2 的亮度参数。
         */
        val toneMapExposure: Float = 1f,
        /**
         * Reinhard 膝点（默认 0.6，线性 ≈ sRGB 0.81）。
         *
         * - `0.6`：膝点以下**零改动**，只压缩过曝部分 —— 保住原图色彩分布；
         * - `0.0`：整幅 `c/(1+c)`（规格原式），但中间调会一起变暗发灰，仅建议调试对比。
         */
        val toneMapKnee: Float = 0.6f,
        /** 全局 Multiply 压暗系数（1 = 关闭；0.85 = 暗部压暗，高光不受影响）。 */
        val globalMultiply: Float = 1f,
        /** Looks 漏光强度（0 = 关闭）。 */
        val looksStrength: Float = 0f,
        /** 漏光中心（归一化）。 */
        val looksPosX: Float = 0.12f,
        val looksPosY: Float = 0.08f,
        /** 漏光颜色（sRGB，shader 内转线性）。 */
        val looksColor: Int = 0xFFFF9A4D.toInt(),
        /** 漏光 UV 微调相位。 */
        val looksPhase: Float = 0f,
        /** 暗角强度（0 = 关闭）。 */
        val vignetteStrength: Float = 0f,
        /** 暗角开始压暗的归一化半径。 */
        val vignetteRadius: Float = 0.72f,
        /** 暗角过渡宽度。 */
        val vignetteSoftness: Float = 0.45f,
    ) {
        /** 单位 3×3（FloatArray 放在伴生对象，避免每次构造新数组）。 */
        companion object {
            val UNIT_3X3 = floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            )
        }
    }

    // ---------------------------------------------------------------- shaders

    private const val VERT = """
#version 300 es
out vec2 vTexCoord;
void main() {
    // 全屏三角形条带：gl_VertexID 0..3 → (0,0)(1,0)(0,1)(1,1)，无需 VBO
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vTexCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

    /** passKey：绿幕抠像（YCbCr 绿色距离 + 1px smoothstep 抗锯齿 + 溢出抑绿）。 */
    private const val FRAG_KEY = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform float uHasGreenScreen;   // 0 = 输入已是带 alpha 人物，直接透传
uniform float uKeyCb;            // 绿幕参考 Cb（由 CPU 统计一次）
uniform float uKeyCr;
uniform float uKeyT1;            // 全透明阈值（色距）
uniform float uKeyT2;            // 全不透明阈值

vec3 rgb2ycbcr(vec3 c) {
    float y  =  0.299*c.r + 0.587*c.g + 0.114*c.b;
    float cb = -0.169*c.r - 0.331*c.g + 0.5*c.b + 0.5;
    float cr =  0.5*c.r - 0.419*c.g - 0.081*c.b + 0.5;
    return vec3(y, cb, cr);
}

void main() {
    // 规格一.2：UV 一律 clamp，防采样越界产生色块
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec4 c = texture(uSrc, uv);
    if (uHasGreenScreen < 0.5) { fragColor = vec4(c.rgb, c.a); return; }

    vec3 ycc = rgb2ycbcr(c.rgb);
    float d = length(vec2(ycc.y - uKeyCb, ycc.z - uKeyCr));
    // 1px 平滑过渡带：色距域窄区间 smoothstep（等价规格的 smoothstep(0.0,0.05,mask)）
    float alpha = smoothstep(uKeyT1, uKeyT2, d);

    // 溢出抑制（spill suppression）：越接近边缘越把 G 压到 max(R,B)
    vec3 rgb = c.rgb;
    float spill = 1.0 - alpha;
    float maxRB = max(rgb.r, rgb.b);
    rgb.g = mix(rgb.g, min(rgb.g, maxRB), spill * 0.8);

    fragColor = vec4(rgb, alpha);   // 非预乘输出（管线统一约定）
}"""

    /** passBlur：13-tap 边缘感知高斯（人物 RGB 用 alpha 加权，避免透明区残留色污染低频）。 */
    private const val FRAG_BLUR = """
#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexel;       // 1/输入纹理尺寸
uniform float uScale;      // 步长倍率（1/4 域默认 1.7 ≈ 原域 σ22）
uniform float uAlphaWeighted; // 1 = 人物（alpha 加权），0 = 不透明背景

void main() {
    vec3 sum = vec3(0.0); float wsum = 0.0; float awsum = 0.0;
    #define TAP(dx, dy, w) { \
        vec4 t = texture(uTex, clamp(vTexCoord + uTexel * vec2(dx, dy) * uScale, 0.0, 1.0)); \
        float wgt = w; sum += t.rgb * wgt; wsum += wgt; \
        if (uAlphaWeighted > 0.5) { awsum += t.a * wgt; } else { awsum += wgt; } }
    TAP(0.0, 0.0, 0.15)
    TAP( 2.5, 0.0, 0.10) TAP(-2.5, 0.0, 0.10) TAP(0.0,  2.5, 0.10) TAP(0.0, -2.5, 0.10)
    TAP( 5.0, 0.0, 0.05) TAP(-5.0, 0.0, 0.05) TAP(0.0,  5.0, 0.05) TAP(0.0, -5.0, 0.05)
    TAP( 2.5,  2.5, 0.0625) TAP(-2.5,  2.5, 0.0625) TAP(2.5, -2.5, 0.0625) TAP(-2.5, -2.5, 0.0625)
    #undef TAP
    fragColor = vec4(sum / max(awsum, 1e-4), 1.0);
}"""

    /** passComposite：主合成（含**光效熔断**，规格三）。 */
    private const val FRAG_COMPOSITE = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uBackground;
uniform sampler2D uFrog;
uniform sampler2D uBgBlur;
uniform sampler2D uFrogBlur;
uniform vec2  uTexel;
uniform vec3  uLightDir;        // 3D 主光方向（单位向量）
uniform float uLightIntensity;
uniform float uWrapOpacity;
uniform float uWrapStrength;
uniform float uGlowStrength;
uniform vec3  uBgHighlight;     // 背景亮部中位色（sRGB）
uniform float uShadowStrength;
uniform float uShadowOffsetPx;
uniform vec3  uShadowColor;
uniform float uBounceStrength;
uniform vec3  uBounceColor;
uniform float uHfWeight;
uniform float uRimStrength;
uniform vec3  uTint;            // 色温乘法增益

vec3 screen(vec3 a, vec3 b) { return 1.0 - (1.0 - a) * (1.0 - b); }

// 采样一律 clamp UV（规格一.2：防采样越界产生色块）
#define SAMPLE(tex, uv) texture(tex, clamp(uv, 0.0, 1.0))

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);

    // ===== Gamma 转线性 =====
    vec3 bg = pow(SAMPLE(uBackground, uv).rgb, vec3(2.2));
    vec4 f  = SAMPLE(uFrog, uv);
    float a = f.a;
    vec3 frog = pow(max(f.rgb, 0.0), vec3(2.2));
    // 色温协调（线性空间乘法增益）：素材进入调色流程，不再是原始贴图
    frog *= clamp(uTint, vec3(0.6), vec3(1.6));
    vec3 bgBlur = pow(SAMPLE(uBgBlur, uv).rgb, vec3(2.2));
    vec3 frogLow = pow(max(SAMPLE(uFrogBlur, uv).rgb, 0.0), vec3(2.2));

    // ===== 边缘法向（从 alpha 梯度提取，指向外部） =====
    float aL = SAMPLE(uFrog, uv - vec2(uTexel.x, 0.0)).a;
    float aR = SAMPLE(uFrog, uv + vec2(uTexel.x, 0.0)).a;
    float aU = SAMPLE(uFrog, uv - vec2(0.0, uTexel.y)).a;
    float aD = SAMPLE(uFrog, uv + vec2(0.0, uTexel.y)).a;
    vec2 n2 = normalize(vec2(aR - aL, aD - aU) + vec2(1e-5));
    vec3 normal = vec3(n2, 0.0);                    // 2D 抠图 z≈0，不臆造深度
    float facing = max(0.0, dot(normal, uLightDir)); // 迎光面才发光（规格三.2）

    // ===== 光效熔断（规格三.1）：人物实心内部强制归零 =====
    // smoothstep(0.9, 0.95, a)：a≥0.95 完全熔断，0.9 以下正常 —— 脸和衣服绝不被白光糊满
    float solidFuse = 1.0 - smoothstep(0.9, 0.95, a);
    // 轮廓带：a 在 0.15~0.85 的过渡区才是"发丝边缘"，纯 0 与纯 1 都不该有光效
    float edgeBand = smoothstep(0.05, 0.3, a) * (1.0 - smoothstep(0.7, 0.9, a));

    // ===== 环绕遮罩（8 tap，仅用于光包裹/反弹光的外扩范围） =====
    float wrapSum = 0.0;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.785398;
        wrapSum += SAMPLE(uFrog, uv + vec2(cos(ang), sin(ang)) * 4.0 * uTexel).a;
    }
    float wrapMask = smoothstep(0.0, 1.0, wrapSum * 0.125) * 0.5;
    wrapMask *= (1.0 - smoothstep(0.9, 0.95, a));   // 内部熔断
    wrapMask *= mix(0.35, 1.0, facing);              // 背光面大幅衰减（不归零但仍熔断大半）

    // ===== 接触阴影（Multiply） =====
    float sh = 0.0;
    for (int i = 1; i <= 8; i++) {
        sh += SAMPLE(uFrog, uv + vec2(0.0, uShadowOffsetPx * float(i) / 8.0 * uTexel.y)).a;
    }
    float shadowField = smoothstep(0.0, 1.0, sh * 0.125) * (1.0 - a);
    vec3 shadowLin = pow(uShadowColor, vec3(2.2));
    bg *= mix(vec3(1.0), shadowLin, shadowField * uShadowStrength);

    // ===== 主合成：平滑 Alpha 叠加 =====
    vec3 comp = mix(bg, frog, smoothstep(0.0, 1.0, a));

    // ===== 光包裹（规格二.4 + 三）：背景光压暗 0.3，仅迎光边缘 =====
    vec3 highlightLin = pow(uBgHighlight, vec3(2.2));
    vec3 wrapColor = highlightLin * 0.3 * facing * uWrapStrength * wrapMask * solidFuse;
    comp = screen(comp, wrapColor * uWrapOpacity * 2.0);

    // ===== 边缘光（Rim）：迎光面轮廓高光，同样熔断内部 =====
    float rim = pow(facing, 1.5) * edgeBand * solidFuse * uRimStrength * uLightIntensity;
    comp = screen(comp, highlightLin * 1.2 * rim);

    // ===== 辉光（Screen，熔断内部 + 迎光加权） =====
    float glowBand = edgeBand * solidFuse * mix(0.2, 1.0, facing);
    vec3 glowColor = bgBlur * uGlowStrength * glowBand;
    comp = screen(comp, glowColor);

    // ===== 反弹光（Screen 到人物周围背景） =====
    vec3 bounceLin = pow(uBounceColor, vec3(2.2));
    comp = screen(comp, bounceLin * wrapMask * uBounceStrength);

    // ===== 高频重注入（线稿保护） =====
    vec3 hf = frog - frogLow;
    comp += hf * uHfWeight * a;

    // ===== Clamp → sRGB =====
    comp = clamp(comp, 0.0, 1.0);
    fragColor = vec4(pow(comp, vec3(1.0 / 2.2)), 1.0);
}"""

    // ---------------------------------------------------------------- 渲染入口

    /**
     * 执行 GPU 融合。任何 EGL/GL 失败返回 null（调用方降级 CPU）。
     *
     * @param background 写实背景（不修改）
     * @param foreground 绿幕立绘（[Params.hasGreenScreen]=true）或已抠人物 RGBA（false）
     */
    fun render(background: Bitmap, foreground: Bitmap, params: Params): Bitmap? {
        if (background.isRecycled || foreground.isRecycled) return null
        val w = background.width
        val h = background.height
        if (w <= 0 || h <= 0) return null

        val egl = EglEnv.create(w, h) ?: run {
            Log.i(TAG, "GLES 环境创建失败（EGL），GPU 融合不可用")
            return null
        }
        return try {
            egl.makeCurrent()
            // 规格一.2：纹理尺寸必须受硬件与 2048 双重限制，否则采样错乱 → 方形色块
            val hwMax = queryMaxTextureSize().coerceAtLeast(512)
            val limit = minOf(MAX_TEXTURE_SIDE, hwMax)
            val bgSafe = sanitizeSize(background, limit) ?: return null
            val fgSafe = sanitizeSize(foreground, limit) ?: return null
            if (bgSafe !== background || fgSafe !== foreground) {
                Log.i(
                    TAG,
                    "纹理超限已压缩：${background.width}x${background.height} → " +
                        "${bgSafe.width}x${bgSafe.height}（上限 $limit，硬件 $hwMax）",
                )
            }
            renderInternal(egl, bgSafe, fgSafe, params, bgSafe.width, bgSafe.height)
                .also {
                    // sanitize 产生的临时位图用完即回收（未缩放的返回原图，不能碰）
                    if (bgSafe !== background) bgSafe.recycle()
                    if (fgSafe !== foreground) fgSafe.recycle()
                }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU 融合失败，降级 CPU：${t.message}")
            null
        } finally {
            egl.release()
        }
    }

    /** 单边纹理上限（规格：长边超过 2048 强制压缩）。 */
    private const val MAX_TEXTURE_SIDE = 2048

    /** 查询 GL_MAX_TEXTURE_SIZE；失败返回 0（调用方按 512 兜底）。 */
    private fun queryMaxTextureSize(): Int = try {
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        v[0]
    } catch (t: Throwable) {
        0
    }

    /**
     * 长边超过 [limit] 时等比压缩（双线性）。
     * 返回**原实例**表示无需缩放（调用方不得回收），返回新实例表示需要调用方回收。
     */
    private fun sanitizeSize(src: Bitmap, limit: Int): Bitmap? {
        if (src.isRecycled) return null
        val longSide = maxOf(src.width, src.height)
        if (longSide <= limit) return src
        val scale = limit.toFloat() / longSide
        val tw = (src.width * scale).toInt().coerceAtLeast(1)
        val th = (src.height * scale).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, tw, th, true)
        } catch (t: Throwable) {
            Log.w(TAG, "纹理压缩失败：${t.message}")
            null
        }
    }

    private fun renderInternal(
        egl: EglEnv,
        background: Bitmap,
        foreground: Bitmap,
        params: Params,
        w: Int,
        h: Int,
    ): Bitmap? {
        val progKey = buildProgram(VERT, FRAG_KEY) ?: return null
        val progBlur = buildProgram(VERT, FRAG_BLUR) ?: return null
        val progComp = buildProgram(VERT, FRAG_COMPOSITE) ?: return null
        GLES30.glUseProgram(progKey)
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        // ---- 纹理上传（统一非预乘约定） ----
        val bg = uploadBitmap(background) ?: return null
        val fg = uploadBitmap(foreground) ?: return null

        // ---- passKey：绿幕 → 人物 RGBA ----
        val texFrog = createTexture(w, h) ?: return null
        val fboFrog = createFbo(texFrog) ?: return null
        drawPass(progKey, fboFrog, w, h) {
            bindTexture("uSrc", fg, 0)
            GLES30.glUniform1f(u("uHasGreenScreen"), if (params.hasGreenScreen) 1f else 0f)
            if (params.hasGreenScreen) {
                val (cb, cr) = sampleKeyChroma(foreground)
                GLES30.glUniform1f(u("uKeyCb"), cb)
                GLES30.glUniform1f(u("uKeyCr"), cr)
                GLES30.glUniform1f(u("uKeyT1"), 0.08f)
                GLES30.glUniform1f(u("uKeyT2"), 0.20f)
            }
        } ?: return null

        // ---- 背景降采样 + 模糊（1/4 域） ----
        val qw = (w / 4).coerceAtLeast(1)
        val qh = (h / 4).coerceAtLeast(1)
        val texBgQ = createTexture(qw, qh) ?: return null
        val fboBgQ = createFbo(texBgQ) ?: return null
        drawPass(progBlur, fboBgQ, qw, qh) {
            bindTexture("uTex", bg, 0)
            GLES30.glUniform2f(u("uTexel"), 1f / w, 1f / h)
            GLES30.glUniform1f(u("uScale"), 0.45f)   // 全分辨率域 3-tap×0.45 ≈ quarter 域正常步长
            GLES30.glUniform1f(u("uAlphaWeighted"), 0f)
        } ?: return null

        val texBgBlur = createTexture(qw, qh) ?: return null
        val fboBgBlur = createFbo(texBgBlur) ?: return null
        // σ 由背景复杂度动态决定（规格二.1）：uScale = 等效 1/4 域半径 / 13-tap 基础步长 2.5
        val blurScale = (params.bgBlurSigma / 4f * 1.732f / 2.5f).coerceIn(0.8f, 6f)
        drawPass(progBlur, fboBgBlur, qw, qh) {
            bindTexture("uTex", texBgQ, 0)
            GLES30.glUniform2f(u("uTexel"), 1f / qw, 1f / qh)
            GLES30.glUniform1f(u("uScale"), blurScale)
            GLES30.glUniform1f(u("uAlphaWeighted"), 0f)
        } ?: return null

        // ---- 人物低频（高频重注入用） ----
        val texFrogBlur = createTexture(qw, qh) ?: return null
        val fboFrogBlur = createFbo(texFrogBlur) ?: return null
        drawPass(progBlur, fboFrogBlur, qw, qh) {
            bindTexture("uTex", texFrog, 0)
            GLES30.glUniform2f(u("uTexel"), 1f / w, 1f / h)
            GLES30.glUniform1f(u("uScale"), 0.45f)
            GLES30.glUniform1f(u("uAlphaWeighted"), 1f)
        } ?: return null

        // ---- 轻量取色统计（2048 点采样，非渲染循环） ----
        val scenePx = sampleColors(background)
        val frogPx = sampleColors(foreground)
        val shadowColor = darkMedian(scenePx)
        val bounceColor = brightAverage(frogPx)

        // ---- passComposite：主合成 ----
        val texOut = createTexture(w, h) ?: return null
        val fboOut = createFbo(texOut) ?: return null
        drawPass(progComp, fboOut, w, h) {
            bindTexture("uBackground", bg, 0)
            bindTexture("uFrog", texFrog, 1)
            bindTexture("uBgBlur", texBgBlur, 2)
            bindTexture("uFrogBlur", texFrogBlur, 3)
            GLES30.glUniform2f(u("uTexel"), 1f / w, 1f / h)
            // 3D 主光方向（规格三.2：法向点积需要完整三维向量）
            GLES30.glUniform3f(
                u("uLightDir"),
                params.lightDirX,
                params.lightDirY,
                params.lightDirZ,
            )
            GLES30.glUniform1f(u("uLightIntensity"), params.lightIntensity)
            GLES30.glUniform1f(u("uWrapOpacity"), params.wrapOpacity)
            GLES30.glUniform1f(u("uWrapStrength"), params.wrapStrength)
            GLES30.glUniform1f(u("uGlowStrength"), params.glowStrength)
            GLES30.glUniform3f(
                u("uBgHighlight"),
                red(params.bgHighlightColor) / 255f,
                green(params.bgHighlightColor) / 255f,
                blue(params.bgHighlightColor) / 255f,
            )
            GLES30.glUniform1f(u("uShadowStrength"), params.shadowStrength)
            GLES30.glUniform1f(u("uShadowOffsetPx"), params.shadowOffsetPx)
            GLES30.glUniform3f(u("uShadowColor"), red(shadowColor) / 255f, green(shadowColor) / 255f, blue(shadowColor) / 255f)
            GLES30.glUniform1f(u("uBounceStrength"), params.bounceStrength)
            GLES30.glUniform3f(u("uBounceColor"), red(bounceColor) / 255f, green(bounceColor) / 255f, blue(bounceColor) / 255f)
            GLES30.glUniform1f(u("uHfWeight"), params.hfWeight)
            GLES30.glUniform1f(u("uRimStrength"), params.rimStrength)
            GLES30.glUniform3f(u("uTint"), params.tintR, params.tintG, params.tintB)
        } ?: return null

        // ---- 回读：方向由 GlReadback 实测决定（这里**必须**用 fboOut，不能靠"当前绑定"） ----
        val result = GlReadback.read(fboOut, w, h) ?: return null

        // ---- 清理 ----
        listOf(texFrog, texBgQ, texBgBlur, texFrogBlur, texOut, bg, fg).forEach {
            GLES30.glDeleteTextures(1, intArrayOf(it), 0)
        }
        listOf(fboFrog, fboBgQ, fboBgBlur, fboFrogBlur, fboOut).forEach {
            GLES30.glDeleteFramebuffers(1, intArrayOf(it), 0)
        }
        GLES30.glDeleteProgram(progKey)
        GLES30.glDeleteProgram(progBlur)
        GLES30.glDeleteProgram(progComp)
        GLES30.glDeleteVertexArrays(1, vao, 0)
        checkGlError("cleanup")
        return result
    }

    // ---------------------------------------------------------------- EGL

    private class EglEnv private constructor(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface,
    ) {
        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }
        }

        fun release() {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        companion object {
            fun create(w: Int, h: Int): EglEnv? {
                return try {
                    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    if (display == EGL14.EGL_NO_DISPLAY) return null
                    val version = IntArray(2)
                    if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

                    // GLES 3.0 + pbuffer + RGBA8（RENDERABLE_TYPE 0x0040 = EGL_OPENGL_ES3_BIT）
                    val attribs = intArrayOf(
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                        EGL14.EGL_NONE,
                    )
                    val configs = arrayOfNulls<EGLConfig>(1)
                    val num = IntArray(1)
                    if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
                        return null
                    }
                    val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
                    val surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttribs, 0)
                    if (surface == EGL14.EGL_NO_SURFACE) return null
                    val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                    val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                    if (context == EGL14.EGL_NO_CONTEXT) return null
                    EglEnv(display, context, surface)
                } catch (t: Throwable) {
                    Log.w(TAG, "EGL 初始化异常：${t.message}")
                    null
                }
            }
        }
    }

    // ---------------------------------------------------------------- GL helpers

    private fun buildProgram(vertSrc: String, fragSrc: String): Int? = try {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.w(TAG, "program link 失败：${GLES30.glGetProgramInfoLog(prog)}")
            null
        } else {
            prog
        }
    } catch (t: Throwable) {
        Log.w(TAG, "shader 编译异常：${t.message}")
        null
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader 编译失败：${GLES30.glGetShaderInfoLog(shader)}")
        return shader
    }

    private var currentProgram = 0

    /** 执行一个 pass；返回 null 表示 GL 错误（调用方降级）。 */
    private fun drawPass(
        program: Int,
        fbo: Int,
        w: Int,
        h: Int,
        setup: () -> Unit,
    ): Unit? {
        currentProgram = program
        GLES30.glUseProgram(program)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GlState.ensure2dPass()
        setup()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES30.glGetError()
        return if (err == GLES30.GL_NO_ERROR) Unit else {
            Log.w(TAG, "GL 错误 0x${Integer.toHexString(err)}，GPU 融合降级")
            null
        }
    }

    /** 在当前 program 上：按名字取 location 并立即设置 sampler。 */
    private fun bindTexture(name: String, tex: Int, unit: Int) {
        val loc = GLES30.glGetUniformLocation(currentProgram, name)
        if (loc < 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc, unit)
    }

    private fun u(name: String): Int = GLES30.glGetUniformLocation(currentProgram, name)

    /** 上传位图为非预乘 RGBA 纹理（管线统一约定：shader 内按非预乘处理）。 */
    private fun uploadBitmap(bitmap: Bitmap): Int? {
        val tmp = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        tmp.setPremultiplied(false)
        val tex = createTexture(bitmap.width, bitmap.height) ?: run { tmp.recycle(); return null }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, tmp, 0)
        val err = GLES30.glGetError()
        tmp.recycle()
        if (err != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "纹理上传失败 0x${Integer.toHexString(err)}")
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            return null
        }
        return tex
    }

    private fun createTexture(w: Int, h: Int): Int? {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        if (tex[0] == 0) return null
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        // 规格：强制 GL_LINEAR 双线性过滤（抗锯齿第二道保险）
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(tex[0]), 0)
            return null
        }
        return tex[0]
    }

    private fun createFbo(tex: Int): Int? {
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        if (fbo[0] == 0) return null
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "FBO 不完整 0x${Integer.toHexString(status)}")
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo[0]), 0)
            return null
        }
        return fbo[0]
    }

    // 回读**不再**在本文件实现。这里是本项目历史上唯一真正出过问题的地方：
    // 旧实现无条件把行序倒过来（注释写着"GL 底部起源 → Bitmap 顶部起源"），
    // 但上传(GLUtils) 与全屏 quad 的 uv 已经把方向抵掉了，再翻一次就是上下颠倒。
    // 结果是：正常走图层管线时画面正，一旦降级到本渲染器就翻转 —— 统一到 GlReadback。

    // ---------------------------------------------------------------- CPU 统计（轻量，非渲染循环）

    /** 步长采样（约 2048 点）—— O(1) 统计，非像素循环。 */
    private fun sampleColors(bitmap: Bitmap): IntArray {
        val total = bitmap.width * bitmap.height
        val step = (total / 2048).coerceAtLeast(1)
        val px = IntArray((total / step).coerceAtLeast(1))
        val all = IntArray(total)
        bitmap.getPixels(all, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in px.indices) px[i] = all[i * step]
        return px
    }

    /** 背景暗部（下 20% 亮度）中位色 → 阴影色（带环境色倾向，不死黑）。 */
    private fun darkMedian(px: IntArray): Int {
        if (px.isEmpty()) return 0xFF26221C.toInt()
        val sorted = px.sortedBy { luma(it) }
        val dark = sorted.take((sorted.size * 0.2f).toInt().coerceAtLeast(1))
        return medianColor(dark)
    }

    /** 人物亮部（上 20%）平均色 → 反弹光色。 */
    private fun brightAverage(px: IntArray): Int {
        if (px.isEmpty()) return 0xFFF2B12B.toInt()
        val sorted = px.sortedByDescending { luma(it) }
        val bright = sorted.take((sorted.size * 0.2f).toInt().coerceAtLeast(1))
        var r = 0L; var g = 0L; var b = 0L
        for (c in bright) {
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        val n = bright.size
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** 绿幕参考色度（前景边缘带的 Cb/Cr 中位）。 */
    private fun sampleKeyChroma(bitmap: Bitmap): Pair<Float, Float> {
        val px = sampleColors(bitmap)
        val greens = px.map { c ->
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val cb = -0.169f * r - 0.331f * g + 0.5f * b + 0.5f
            val cr = 0.5f * r - 0.419f * g - 0.081f * b + 0.5f
            luma(c) to (cb to cr)
        }.filter { it.first > 0.15f }                       // 排除死黑
        if (greens.isEmpty()) return 0.5f to 0.5f
        val cb = greens.map { it.second.first }.sorted()[greens.size / 2]
        val cr = greens.map { it.second.second }.sorted()[greens.size / 2]
        return cb to cr
    }

    private fun luma(c: Int): Float =
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

    private fun medianColor(colors: List<Int>): Int {
        val rs = IntArray(colors.size) { (colors[it] shr 16) and 0xFF }.also { it.sort() }
        val gs = IntArray(colors.size) { (colors[it] shr 8) and 0xFF }.also { it.sort() }
        val bs = IntArray(colors.size) { colors[it] and 0xFF }.also { it.sort() }
        val m = colors.size / 2
        return (0xFF shl 24) or (rs[m] shl 16) or (gs[m] shl 8) or bs[m]
    }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    private fun checkGlError(where: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "GL 残留错误（$where）：0x${Integer.toHexString(err)}")
    }
}
