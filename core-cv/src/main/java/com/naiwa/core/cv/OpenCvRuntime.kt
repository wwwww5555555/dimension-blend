package com.naiwa.core.cv

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * OpenCV 原生库的懒加载与可用性探测。
 *
 * 为什么不做"启动时初始化 + 失败就崩"：
 *  本项目所有 OpenCV 用法都有纯 Kotlin 降级路径（色键抠图、拉普拉斯多频段融合、
 *  直接贴图）。OpenCV 加载失败只应该让质量略降，**不应该让 App 打不开或功能消失**。
 *
 * 为什么用 `OpenCVLoader.initLocal()`：它加载 APK 内置的 `.so`，不需要 `OpenCV Manager` 这种外部依赖，
 * 符合"完全本地、离线优先、不依赖私有服务"的约束。
 */
object OpenCvRuntime {

    private const val TAG = "NaiwaCamera"

    @Volatile
    private var state: Boolean? = null

    /** 是否可用。首次调用会尝试加载（几十毫秒），之后走缓存。 */
    fun isAvailable(): Boolean {
        state?.let { return it }
        return synchronized(this) {
            state?.let { return it }
            val ok = try {
                val loaded = OpenCVLoader.initLocal()
                Log.i(TAG, "OpenCV 加载${if (loaded) "成功" else "失败"}：${OpenCVLoader.OPENCV_VERSION}")
                loaded
            } catch (t: Throwable) {
                Log.w(TAG, "OpenCV 加载异常，将全局降级到纯 Kotlin 实现：${t.message}")
                false
            }
            state = ok
            ok
        }
    }

    /** 版本号字符串，写进 RenderResult 的诊断信息里便于定位"这台设备走的哪条路径"。 */
    val version: String
        get() = if (isAvailable()) OpenCVLoader.OPENCV_VERSION else "unavailable"

    /** 单元测试用：重置探测状态。 */
    internal fun resetForTest() {
        state = null
    }
}
