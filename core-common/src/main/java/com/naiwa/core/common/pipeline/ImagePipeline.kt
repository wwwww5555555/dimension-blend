package com.naiwa.core.common.pipeline

import android.graphics.Bitmap
import com.naiwa.core.common.model.FrogPrepareResult
import com.naiwa.core.common.model.LightParams
import com.naiwa.core.common.model.RenderRequest
import com.naiwa.core.common.model.RenderResult

/**
 * 图像管线接口 —— feature 模块与实现之间的**唯一契约**。
 *
 * 实现位于 `:core-pipeline`（`DefaultImagePipeline`），通过 Hilt 注入到 ViewModel。
 * feature 模块在编译期只依赖本接口与 `:core-common` 的模型，**不依赖任何具体算法类**
 * （依赖纪律第 4 条，见 docs/ARCHITECTURE.md §3）。
 *
 * 线程约定：两个方法都是挂起函数，**必须在主线程之外调用**；
 * 实现在内部按阶段切换到专用调度器（CV → Default、推理 → 单线程、IO → IO）。
 *
 * @param onStage 阶段开始回调，用于驱动进度 UI。在主线程之外的线程调用，实现方需自行切回主线程
 */
interface ImagePipeline {

    /**
     * 只做场景光照估计，不做融合。
     *
     * 编辑页进入时需要先拿到光照参数以便显示"当前光源"提示并驱动预览，
     * 与 [render] 拆开可以避免「每改一次摆放就重算一次光照」的浪费。
     *
     * 契约：**本方法不抛异常**。模型失败 → 传统 CV；传统 CV 失败 → 返回 [LightParams.NEUTRAL]。
     *
     * @param scene 场景位图（需已校正方向）
     * @return 已钳制到合法区间的光照参数；同时给出可信度
     */
    suspend fun estimateLight(scene: Bitmap): LightParams

    /**
     * 执行渲染。
     *
     * 契约：
     *  - 不抛异常；内存不足时自动降档重试一次，仍失败才返回异常结果（由调用方提示用户）；
     *  - 返回的位图是**新建的**，调用方负责回收；输入的 scene/frog 不会被修改也**不会被回收**；
     *  - [RenderResult.stageTimings] 必须包含实际执行过的每个阶段。
     *
     * @param request 渲染请求（含模式与档位）
     * @param onStage 阶段开始回调；不需要进度时传 null
     */
    suspend fun render(
        request: RenderRequest,
        onStage: ((RenderResult.Stage) -> Unit)? = null,
    ): RenderResult

    /**
     * 把用户给的任意一张图准备成「可融合的奶蛙」—— 即抠图 **+ 边缘预处理**。
     *
     * 放在管线里而不是放在 feature 里，是因为抠图与边缘预处理都属于图像算法（`:core-cv`），
     * 而 feature 模块被禁止引用算法实现类（依赖纪律第 4 条）。
     * 由管线对外提供一个方法，feature 就能在不接触 OpenCV/算法细节的前提下完成导入。
     *
     * 处理顺序（规格 6.1→6.4，与抠图的关系见实现）：
     * ```
     * 抠图（若需要）→ 6.1 反预乘 → 6.2 去白 → 6.3/6.4 alpha 柔化 → 6.5 重新预乘
     * ```
     * **注意**：即使原图自带透明通道（不走抠图），边缘预处理**仍然会执行** ——
     * "透明玻璃白边"正是发生在自带 alpha 的素材上（半透明边缘的 RGB 仍保留着原背景色）。
     * 早期版本在"已带 alpha"时直接 return，等于把这类素材的白边原封不动放进场景。
     *
     * 契约：不抛异常。失败时返回 null，由调用方提示"这张图没能识别出奶蛙"。
     *
     * @param source 用户导入的原图（可以是白底/绿幕照片）
     * @param runMattingIfNeeded 若原图已带可用透明通道，是否跳过**抠图**（默认跳过，避免破坏已抠好的素材）
     * @param softness 阴影柔和度参数（0~1），只影响 6.3 的柔化半径
     * @return 准备结果；位图无效时返回 null
     */
    suspend fun prepareFrog(
        source: Bitmap,
        runMattingIfNeeded: Boolean = true,
        softness: Float = 0.5f,
    ): FrogPrepareResult?

    /**
     * GPU 融合（规格「次元融合」：GLES 3.0 全 shader 管线）。
     *
     * 流程：绿幕色键抠像（YCbCr + smoothstep 抗锯齿 + 溢出抑绿）→ Gamma 转线性 →
     * 接触阴影(Multiply) → Alpha 叠加 → Rim Light(Screen) → Light Wrap(Screen) →
     * 反弹光(Screen) → 高频重注入 → 转回 sRGB。全部在 Fragment Shader 内执行。
     *
     * 契约：
     *  - **不抛异常**；任何 EGL/GL 错误返回 null，调用方降级 [render]（CPU 管线）；
     *  - [request.scene] 为写实背景，[request.frog] 为绿幕立绘**或**已抠人物（实现按
     *    素材是否有不透明背景自动判定键控开关）；
     *  - 返回新位图；输入不被修改也不被回收。
     *
     * 默认实现返回 null（GPU 路径不可用/未接线时自动走 CPU）。
     */
    suspend fun renderGpu(request: RenderRequest): Bitmap? = null
}
