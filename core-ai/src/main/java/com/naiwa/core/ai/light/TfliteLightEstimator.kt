package com.naiwa.core.ai.light

import android.content.Context
import android.util.Log
import com.naiwa.core.ai.ModelAssetInstaller
import com.naiwa.core.ai.RuntimeProvider
import com.naiwa.core.common.model.LightParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.tensorflow.lite.Interpreter

/**
 * 基于 LiteRT（TFLite）回归模型的光照估计器。
 *
 * **模型契约**（训练时按此对齐，见 `core-ai/src/main/assets/models/README.md`）：
 *  - 输入：`1 × 224 × 224 × 3` float32，像素值归一化到 [0, 1]，RGB 顺序；
 *  - 输出：`1 × 9` float32，按顺序对应
 *    `[colorTempK(0~1→2000~12000) , tint(-1~1) , exposureEv(-3~3→0~1) , contrast ,
 *      saturation , azimuth(0~1→0~360) , elevation(0~1→0~90) , shadowStrength , confidence(0~1)]`；
 *  - 若模型只有 8 个输出（无 confidence），按"模型跑通即可用"取固定置信度 [DEFAULT_CONFIDENCE]。
 *
 * **当前状态说明（重要）**：仓库里**没有**训练好的 `light_estimator_int8.tflite`。
 * 因此本类在运行时的实际行为是：探测到模型缺失 → 记录一次日志 → 返回 [LightParams.NEUTRAL]（confidence = 0），
 * 由调用方切到 [CvLightEstimator]。这是**刻意的设计**而不是未完成品：
 * 传统 CV 路径必须永远可用，模型只是"更好时用更好的"，不是"没有就崩"。
 */
class TfliteLightEstimator(
    context: Context,
    /** 输入分辨率，必须与训练时一致。 */
    private val inputSize: Int = INPUT_SIZE,
    /** 允许探测到的最高加速器（QNN → NPU → GPU → CPU 全链）。低端机传 [RuntimeProvider.Accelerator.CPU]。 */
    private val maxAccelerator: RuntimeProvider.Accelerator = RuntimeProvider.Accelerator.QNN,
) : LightEstimator {

    private val appContext = context.applicationContext

    override val name: String = "tflite-regression"

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var acceleratorName: String = "none"

    @Volatile
    private var unavailableLogged = false

    /** 模型是否已就绪（首次调用会尝试加载）。 */
    val isReady: Boolean
        get() = ensureInterpreter() != null

    override fun estimate(pixels: IntArray, width: Int, height: Int): LightParams {
        val engine = ensureInterpreter() ?: return LightParams.NEUTRAL
        if (width <= 0 || height <= 0 || pixels.size < width * height) return LightParams.NEUTRAL

        return try {
            val input = buildInput(pixels, width, height)
            val output = Array(1) { FloatArray(OUTPUT_SIZE) }
            // 串行推理：Interpreter 非线程安全，并发 run 会崩。与 PCT-Net 共用同一把锁，
            // 因为它们可能被同一条渲染链路先后触发（闪退根因之一）。
            synchronized(INFERENCE_LOCK) {
                engine.run(input, output)
            }
            parseOutput(output[0])
        } catch (t: Throwable) {
            // 推理异常（形状不符、内存不足、delegate 崩）：不抛给上层，退化为中性参数
            Log.w(TAG, "模型推理失败，本次退化为中性参数：${t.message}")
            LightParams.NEUTRAL
        }
    }

    private fun ensureInterpreter(): Interpreter? {
        interpreter?.let { return it }
        synchronized(this) {
            interpreter?.let { return it }

            val modelFile = ModelAssetInstaller.ensureModel(
                appContext,
                ModelAssetInstaller.LIGHT_ESTIMATOR_MODEL,
            )
            if (modelFile == null) {
                if (!unavailableLogged) {
                    unavailableLogged = true
                    Log.i(
                        TAG,
                        "未提供 ${ModelAssetInstaller.LIGHT_ESTIMATOR_MODEL}，" +
                            "光照估计使用传统 CV 兜底（功能完整，精度略降）",
                    )
                }
                return null
            }

            return try {
                val (engine, accelerator) = RuntimeProvider.createInterpreter(modelFile, maxAccelerator)
                acceleratorName = accelerator.name
                interpreter = engine
                Log.i(TAG, "光照模型已加载（$acceleratorName，输入 ${inputSize}x$inputSize）")
                engine
            } catch (t: Throwable) {
                Log.w(TAG, "模型加载失败，改用传统 CV 兜底：${t.message}")
                null
            }
        }
    }

    /**
     * 把任意尺寸的 ARGB 像素重采样到模型输入张量。
     *
     * **Letterbox**（规格一.1）：等比缩放到 inputSize 内 + 居中 padding，不拉伸变形 ——
     * 直接把 16:9 压成 1:1 会让模型看到"被压扁的场景"，方位角/高度角估计随之偏移。
     * Padding 用**图像均值**填充（不是黑色）：黑边会被模型当成强阴影，污染 shadowStrength。
     * 采样一律**双线性**（禁止最近邻）。
     */
    private fun buildInput(pixels: IntArray, width: Int, height: Int): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())

        // 均值（步长采样，O(2048)）
        val step = (pixels.size / 2048).coerceAtLeast(1)
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0; var cnt = 0
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            sumR += (c shr 16) and 0xFF; sumG += (c shr 8) and 0xFF; sumB += c and 0xFF
            cnt++; i += step
        }
        val meanR = (sumR / cnt.coerceAtLeast(1) / 255.0).toFloat()
        val meanG = (sumG / cnt.coerceAtLeast(1) / 255.0).toFloat()
        val meanB = (sumB / cnt.coerceAtLeast(1) / 255.0).toFloat()

        // Letterbox 几何：等比缩放 + 居中
        val scale = inputSize.toFloat() / maxOf(width, height)
        val drawW = (width * scale).roundToInt().coerceAtMost(inputSize)
        val drawH = (height * scale).roundToInt().coerceAtMost(inputSize)
        val padX = (inputSize - drawW) / 2f
        val padY = (inputSize - drawH) / 2f

        for (y in 0 until inputSize) {
            val srcYF = (y - padY) / scale
            val inY = srcYF >= 0f && srcYF <= height - 1
            val sy = srcYF.coerceIn(0f, (height - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val wy = sy - y0
            for (x in 0 until inputSize) {
                val srcXF = (x - padX) / scale
                val inX = srcXF >= 0f && srcXF <= width - 1
                if (!inX || !inY) {
                    // Padding 区：均值填充
                    buffer.putFloat(meanR); buffer.putFloat(meanG); buffer.putFloat(meanB)
                    continue
                }
                val sx = srcXF.coerceIn(0f, (width - 1).toFloat())
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

    /** 双线性插值单个通道（shift = 16/8/0 分别取 R/G/B），输出 [0,1]。 */
    private fun bilinear(c00: Int, c01: Int, c10: Int, c11: Int, shift: Int, wx: Float, wy: Float): Float {
        val v00 = channel(c00, shift)
        val v01 = channel(c01, shift)
        val v10 = channel(c10, shift)
        val v11 = channel(c11, shift)
        val top = v00 + (v01 - v00) * wx
        val bottom = v10 + (v11 - v10) * wx
        return (top + (bottom - top) * wy) / 255f
    }

    private fun channel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF).toFloat()

    /** 解析模型输出到 [LightParams]。越界值交给 [LightParams.clamped] 统一收口。 */
    private fun parseOutput(out: FloatArray): LightParams {
        val confidence = if (out.size > CONFIDENCE_INDEX) out[CONFIDENCE_INDEX] else DEFAULT_CONFIDENCE
        return LightParams(
            colorTempK = denormalize(out[0], LightParams.MIN_TEMP_K, LightParams.MAX_TEMP_K),
            tint = out[1].coerceIn(-1f, 1f),
            exposureEv = denormalize(out[2], -3f, 3f),
            brightness = 1f + denormalize(out[2], -3f, 3f) * 0.15f,
            contrast = denormalize(out[3], 0.5f, 1.5f),
            saturation = denormalize(out[4], 0.5f, 1.5f),
            keyAzimuthDeg = out[5] * 360f,
            keyElevationDeg = out[6] * 90f,
            // 模型不直接输出这两个字段时用合理默认值，避免下游拿到 0 导致阴影/环境光完全消失
            keyIntensity = DEFAULT_KEY_INTENSITY,
            ambientIntensity = DEFAULT_AMBIENT_INTENSITY,
            shadowStrength = out[7].coerceIn(0f, 1f),
            shadowSoftness = DEFAULT_SHADOW_SOFTNESS,
            confidence = confidence,
        ).clamped()
    }

    private fun denormalize(v: Float, min: Float, max: Float): Float =
        (min + v.coerceIn(0f, 1f) * (max - min))

    private companion object {
        const val TAG = "NaiwaCamera"
        const val INPUT_SIZE = 224
        const val OUTPUT_SIZE = 9
        const val CONFIDENCE_INDEX = 8

        /** 推理互斥锁：与 PctNetHarmonizer 语义一致，串行化 Interpreter.run 调用。 */
        private val INFERENCE_LOCK = Any()

        /** 模型只有 8 个输出时的默认置信度：够高以被采信，但不等于"绝对正确"。 */
        const val DEFAULT_CONFIDENCE = 0.7f
        const val DEFAULT_KEY_INTENSITY = 0.6f
        const val DEFAULT_AMBIENT_INTENSITY = 0.4f
        const val DEFAULT_SHADOW_SOFTNESS = 0.5f
    }
}

/** 把归一化输出换算回物理量（供测试与调试查看）。 */
internal fun Float.roundToDegree(): Int = (this * 360f).roundToInt()
