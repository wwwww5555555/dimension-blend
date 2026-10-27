package com.naiwa.core.render

import android.graphics.Bitmap
import android.util.Log
import org.wysaid.nativePort.CGENativeLibrary

/**
 * `android-gpuimage-plus`（wysaid）的 DAG 节点封装。
 *
 * 【为什么必须走 Bitmap 往返（重要）】
 * 该库的公开 API 只有 `filterImage_MultipleEffects(Bitmap, String, float) -> Bitmap`：
 * C++ 内部自己创建 EGL + FBO、渲染、再 **readPixels 回 Bitmap** 返回 ——
 * **它不暴露 FBO 纹理句柄**（`cgeFilterImageWithCustomFilter` 也是 Bitmap→Bitmap）。
 * 所以接进 DAG 的路径必然是：
 * ```
 * 上游 FBO纹理 --glReadPixels--> Bitmap --C++滤镜--> Bitmap --texImage2D--> 本节点FBO
 * ```
 * 每个节点多两次全图拷贝（1080p 约 +15~30ms）。这是**库的架构限制**，不是实现问题：
 * 它的价值在于成熟滤镜库 + 一行 EffectString，而不是"零拷贝 FBO 链"。
 *
 * 【线程/上下文】`filterImage_MultipleEffects` 内部会自己绑定/解绑 EGL（native 侧），
 * 调用前**必须**让本线程没有 current context（否则上下文互相抢）。因此本类在
 * [RenderGraphExecutor] 里用 `makeCurrent() → readPixels → 解绑 → 调滤镜 → 重绑` 的顺序，
 * 详见 executor 的 `cge:` 分支。
 */
object GpuImagePlusNode {

    /** DAG spec 前缀：`cge:@adjust(...)` 中 `cge:` 之后的部分原样交给 C++ 引擎。 */
    const val SPEC_PREFIX = "cge:"

    private const val TAG = "NaiwaCamera"

    @Volatile
    private var nativeAvailable: Boolean? = null

    /** 原生库是否可用（首次调用探测，缓存结果；失败不影响其他节点）。 */
    fun isAvailable(): Boolean {
        nativeAvailable?.let { return it }
        val ok = try {
            CGENativeLibrary.filterImage_MultipleEffects(
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                "",
                1f,
            )
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError（缺 so/ABI 不匹配）也在这里被吞掉：滤镜不可用 = 直通
            Log.w(TAG, "gpuimage-plus 原生库不可用：${t.message}")
            false
        }
        nativeAvailable = ok
        return ok
    }

    /** 从 spec 里取出 EffectString（`cge:` 之后），无前缀返回 null。 */
    fun effectOf(spec: String): String? =
        if (spec.startsWith(SPEC_PREFIX)) spec.removePrefix(SPEC_PREFIX) else null

    /**
     * 应用效果串。
     *
     * @param effect gpuimage-plus 的 EffectString，例如 `@adjust(brightness, 0.15)`
     *   （语法由 C++ 引擎解析，Kotlin 侧不猜、不拆解——透传才能跟上库的语法演进）
     * @param src 输入位图（不修改、不回收）
     * @param intensity 0~1，多效果的整体强度
     * @return 新位图（调用方负责 recycle）；失败返回 null（调用方直通）
     */
    fun apply(effect: String, src: Bitmap, intensity: Float = 1f): Bitmap? {
        if (src.isRecycled || effect.isBlank()) return null
        if (!isAvailable()) return null
        return try {
            val out = CGENativeLibrary.filterImage_MultipleEffects(
                src,
                effect,
                intensity.coerceIn(0f, 1f),
            )
            if (out == null || out.isRecycled) {
                Log.w(TAG, "滤镜返回空位图，直通")
                null
            } else {
                out
            }
        } catch (t: Throwable) {
            Log.w(TAG, "滤镜执行失败（直通）：${t.message}")
            null
        }
    }

    /**
     * 原地写回版（省一次 Bitmap 分配）：等价于 `filterImage_MultipleEffects`，
     * 但结果写回入参位图。**要求入参是可变位图**且调用方不再需要原图。
     */
    fun applyInPlace(effect: String, mutable: Bitmap, intensity: Float = 1f): Boolean {
        if (mutable.isRecycled || mutable.isMutable.not() || effect.isBlank()) return false
        if (!isAvailable()) return false
        return try {
            CGENativeLibrary.filterImage_MultipleEffectsWriteBack(
                mutable,
                effect,
                intensity.coerceIn(0f, 1f),
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "滤镜写回失败：${t.message}")
            false
        }
    }
}
