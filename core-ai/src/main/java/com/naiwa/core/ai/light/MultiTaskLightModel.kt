package com.naiwa.core.ai.light

import android.content.Context
import android.util.Log
import com.naiwa.core.ai.ModelAssetInstaller
import com.naiwa.core.ai.RuntimeProvider
import com.naiwa.core.common.model.LightingEstimate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.tensorflow.lite.Interpreter

/**
 * 多任务光影模型的**端侧契约**（规格二：MobileNetV3 骨干 + 5 个输出头）。
 *
 * 【模型 I/O 契约（微调时必须严格对齐）】
 *  输入：`[1, 224, 224, 3]` float32，RGB，[0,1]；**Letterbox** 等比缩放 + 均值填充（禁止拉伸/最近邻）。
 *  输出（拼接为 `[1, 16]`，缺省头用默认值）：
 *  ```
 *  [0..8]  ColorMatrix 3×3（行优先，原始 logits → tanh 约束到 [-0.5, 0.5] 增量后 +1）
 *  [9..11] LightDir 3D 向量（先 sigmoid→[0,1]，再映射到 [-1,1] 后归一化）
 *  [12]    LightIntensity   sigmoid → [0,1]
 *  [13]    GlowStrength     sigmoid → [0,1]
 *  [14]    WrapStrength     sigmoid → [0,1]
 *  [15]    保留（confidence）
 *  ```
 *
 * 【当前状态（如实）】仓库**不提供**训练好的权重。模型缺失时 [estimate] 返回 null，
 * 由 `CvLightingAnalyzer`（Scharr 光向 + 拉普拉斯方差 + 亮部 5%）确定性兜底 ——
 * 这条降级路径是设计的一部分，不是未完成品：任何设备都能出完整光影参数。
 *
 * 【并发】Interpreter 非线程安全，run 全程持 [INFERENCE_LOCK]。
 */
class MultiTaskLightModel(
    context: Context,
    private val inputSize: Int = INPUT_SIZE,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var acceleratorName: String = "none"

    @Volatile
    private var unavailableLogged = false

    /** 模型是否可用（首次调用尝试加载）。 */
    val isReady: Boolean
        get() = ensureInterpreter() != null

    /**
     * 推理一次。
     *
     * @return 光影参数；模型缺失/推理失败/输出形状不符一律返回 **null**（调用方走 CV 兜底）
     */
    fun estimate(pixels: IntArray, width: Int, height: Int): LightingEstimate? {
        val engine = ensureInterpreter() ?: return null
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        return try {
            val input = buildInput(pixels, width, height)
            val output = Array(1) { FloatArray(OUTPUT_SIZE) }
            synchronized(INFERENCE_LOCK) {
                engine.run(input, output)
            }
            parseOutput(output[0], width, height)
        } catch (t: Throwable) {
            Log.w(TAG, "多任务光影推理失败，回退 CV 分析：${t.message}")
            null
        }
    }

    private fun ensureInterpreter(): Interpreter? {
        interpreter?.let { return it }
        synchronized(this) {
            interpreter?.let { return it }
            val model = ModelAssetInstaller.ensureModel(appContext, MODEL_FILE)
            if (model == null) {
                if (!unavailableLogged) {
                    unavailableLogged = true
                    Log.i(TAG, "未提供 $MODEL_FILE，光影参数由 CV 分析兜底（功能完整）")
                }
                return null
            }
            return try {
                val (engine, accel) = RuntimeProvider.createInterpreter(
                    model,
                    RuntimeProvider.Accelerator.QNN,
                )
                acceleratorName = accel.name
                interpreter = engine
                Log.i(TAG, "多任务光影模型已加载（$acceleratorName，输入 ${inputSize}x$inputSize）")
                engine
            } catch (t: Throwable) {
                Log.w(TAG, "多任务模型加载失败，回退 CV：${t.message}")
                null
            }
        }
    }

    /** Letterbox + 双线性采样（与 TfliteLightEstimator 同一策略）。 */
    private fun buildInput(pixels: IntArray, width: Int, height: Int): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        val step = (pixels.size / 2048).coerceAtLeast(1)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            cnt++; i += step
        }
        val mr = (sr / cnt.coerceAtLeast(1) / 255.0).toFloat()
        val mg = (sg / cnt.coerceAtLeast(1) / 255.0).toFloat()
        val mb = (sb / cnt.coerceAtLeast(1) / 255.0).toFloat()

        val scale = inputSize.toFloat() / maxOf(width, height)
        val drawW = (width * scale).roundToInt().coerceAtMost(inputSize)
        val drawH = (height * scale).roundToInt().coerceAtMost(inputSize)
        val padX = (inputSize - drawW) / 2f
        val padY = (inputSize - drawH) / 2f

        for (y in 0 until inputSize) {
            val syF = (y - padY) / scale
            val inY = syF >= 0f && syF <= height - 1
            val sy = syF.coerceIn(0f, (height - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val wy = sy - y0
            for (x in 0 until inputSize) {
                val sxF = (x - padX) / scale
                val inX = sxF >= 0f && sxF <= width - 1
                if (!inX || !inY) {
                    buffer.putFloat(mr); buffer.putFloat(mg); buffer.putFloat(mb)
                    continue
                }
                val sx = sxF.coerceIn(0f, (width - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val wx = sx - x0
                val c00 = pixels[y0 * width + x0]
                val c01 = pixels[y0 * width + x1]
                val c10 = pixels[y1 * width + x0]
                val c11 = pixels[y1 * width + x1]
                buffer.putFloat(bilinear(c00, c01, c10, c11, 16, wx, wy))
                buffer.putFloat(bilinear(c00, c01, c10, c11, 8, wx, wy))
                buffer.putFloat(bilinear(c00, c01, c10, c11, 0, wx, wy))
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun bilinear(c00: Int, c01: Int, c10: Int, c11: Int, shift: Int, wx: Float, wy: Float): Float {
        val v00 = ((c00 shr shift) and 0xFF) / 255f
        val v01 = ((c01 shr shift) and 0xFF) / 255f
        val v10 = ((c10 shr shift) and 0xFF) / 255f
        val v11 = ((c11 shr shift) and 0xFF) / 255f
        val top = v00 + (v01 - v00) * wx
        val bottom = v10 + (v11 - v10) * wx
        return top + (bottom - top) * wy
    }

    /** 解析 16 维输出（Sigmoid/tanh 约束到合法区间，绝不信任裸 logits）。 */
    private fun parseOutput(out: FloatArray, width: Int, height: Int): LightingEstimate? {
        if (out.size < 15) return null

        // ColorMatrix：9 个 logits → tanh 增量（±0.5）后加 1（单位矩阵附近）
        val matrix = FloatArray(9) { i -> 1f + 0.5f * kotlin.math.tanh(out[i]) }

        // LightDir：sigmoid→[0,1]→[-1,1]，再归一化
        var dx = (sigmoid(out[9]) * 2f - 1f)
        var dy = (sigmoid(out[10]) * 2f - 1f)
        var dz = (sigmoid(out[11]) * 2f - 1f)
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        dx /= len; dy /= len; dz /= len

        val intensity = sigmoid(out[12])
        val glow = sigmoid(out[13])
        val wrap = sigmoid(out[14])

        // 背景亮部色与模糊 σ 不在模型输出里（属于 CV 统计量），由调用方合并
        return LightingEstimate(
            lightDirX = dx,
            lightDirY = dy,
            lightDirZ = dz,
            lightIntensity = intensity.coerceIn(0.1f, 1f),
            glowStrength = glow.coerceIn(0f, 1f),
            wrapStrength = wrap.coerceIn(0f, 1f),
            bgHighlightColor = 0xFFF2F2F2.toInt(),
            bgBlurSigma = 22f,
            colorMatrix = matrix,
        )
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + Math.exp(-x.toDouble()))).toFloat()

    private companion object {
        const val TAG = "NaiwaCamera"
        const val MODEL_FILE = "multitask_light_mobilenetv3.tflite"
        const val INPUT_SIZE = 224
        const val OUTPUT_SIZE = 16

        /** 推理互斥锁：Interpreter 非线程安全。 */
        private val INFERENCE_LOCK = Any()
    }
}
