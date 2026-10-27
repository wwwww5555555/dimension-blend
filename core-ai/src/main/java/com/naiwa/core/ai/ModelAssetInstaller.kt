package com.naiwa.core.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 assets 里的模型复制到应用私有目录（仅首次），后续直接用文件路径 mmap 加载。
 *
 * 为什么不全用 `AssetManager` 流式读：
 *  - TFLite 的 `Interpreter(File)` 与 `FileChannel.map` 都要求真实文件路径，才能做到零拷贝 mmap；
 *  - assets 里的文件虽然可用 `AssetFileDescriptor` 映射，但对压缩条目不可靠 —— 而我们在
 *    `androidResources.noCompress` 里已经排除压缩，仍然统一走文件更稳，只有一次几 MB 的复制成本。
 *
 * 全程不抛异常：模型缺失/复制失败都只是让上层走兜底估计器。
 */
object ModelAssetInstaller {

    private const val TAG = "NaiwaCamera"
    private const val ASSET_DIR = "models"
    private const val TARGET_DIR = "models"

    private val checked = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    /** 光照估计模型的文件名（放在 `core-ai/src/main/assets/models/` 下）。 */
    const val LIGHT_ESTIMATOR_MODEL = "light_estimator_int8.tflite"

    /** 交互式分割模型（MediaPipe magic touch）。 */
    const val INTERACTIVE_SEGMENTER_MODEL = "magic_touch.tflite"

    /**
     * 确保模型已就绪，返回可读的文件；失败返回 null。
     * @param assetFileName assets/models 下的文件名
     */
    fun ensureModel(context: Context, assetFileName: String): File? {
        val target = File(File(context.filesDir, TARGET_DIR).apply { mkdirs() }, assetFileName)
        if (target.exists() && target.length() > 0L) return target

        return try {
            context.assets.open("$ASSET_DIR/$assetFileName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0L) {
                Log.i(TAG, "模型已从 assets 复制到 ${target.absolutePath}（${target.length() / 1024} KB）")
                target
            } else {
                target.delete()
                Log.w(TAG, "模型复制后为空: $assetFileName")
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "模型不可用（assets 中不存在 $assetFileName？）: ${e.message}")
            null
        }
    }

    /** 光照估计模型是否可用（结果缓存，避免每帧探测）。 */
    fun isLightEstimatorAvailable(context: Context): Boolean {
        if (checked.compareAndSet(false, true)) {
            ready.set(ensureModel(context, LIGHT_ESTIMATOR_MODEL) != null)
        }
        return ready.get()
    }
}
