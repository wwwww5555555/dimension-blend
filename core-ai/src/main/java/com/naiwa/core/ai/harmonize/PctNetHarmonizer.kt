package com.naiwa.core.ai.harmonize

import android.content.Context
import android.util.Log
import com.naiwa.core.common.model.HarmonizeRequest
import com.naiwa.core.common.model.HarmonizeResult
import com.naiwa.core.common.model.HarmonizerBackend
import com.naiwa.core.ai.ModelAssetInstaller
import com.naiwa.core.ai.RuntimeProvider
import com.naiwa.core.common.color.PctNetTransform
import java.io.File

/**
 * PCT-Net 端侧和谐化（规格 8.2 修订后的备选方案）。
 *
 * PCT-Net（CVPR 2023，GitHub: rakutentech/PCT-Net-Image-Harmonization）：
 * 图像和谐化模型，**低分辨率预测逐像素颜色变换参数，再应用到全分辨率前景**，计算量小，
 * 适合端侧部署。权重直接下载 `pretrained_models/PCTNet_ViT.pth`，**无需邮件申请**。
 *
 * 【为什么替换 DreamLite】上一版的 DreamLite（0.39B 扩散）权重是 gated（需申请 token），
 * 且是 PyTorch/diffusers 格式、依赖 Qwen3-VL 文本编码器，转换链长、流程繁琐；
 * PCT-Net 权重免申请直接下载，且是**纯视觉**模型（输入前景+背景+mask），
 * 无需文本编码器，转换与部署都更简单，更适合快速迭代。
 *
 * 【关键限制（必须如实说明）】PCT-Net 的公开权重是 **PyTorch `.pth` 格式**，
 * **不是 LiteRT `.tflite`**。端侧部署需要先做 PyTorch → ONNX → LiteRT 的转换。
 * 本仓库**不提供转换后的权重**——转换流程见 `docs/DEVICE-VERIFICATION.md`，
 * 转换结果需真机验证。
 *
 * 【权重文件如何接入】双来源：外部 `filesDir/models/`（adb push，推荐）或 assets 内置。
 * 权重（转换后的 `pct_net_vit.tflite`）到位后零代码改动即启用。
 *
 * 【启用条件】仅高端机（RAM ≥ 8GB 且核数 ≥ 8），8s 超时回退 MKL —— 由调用方判断。
 */
class PctNetHarmonizer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 执行 PCT-Net 和谐化。
     *
     * 契约：**不抛异常**。模型缺失 / 加载失败 / 超时都返回 null，由调用方回退 MKL。
     *
     * @return 和谐化结果（backend = PCT_NET）；失败返回 null
     */
    fun harmonize(request: HarmonizeRequest): HarmonizeResult? {
        val modelFile = locateModel() ?: run {
            Log.i(TAG, "PCT-Net 权重未找到（assets 与 filesDir/models 均无），回退 MKL")
            return null
        }

        return try {
            // 串行推理：Interpreter 非线程安全，并发 run 会崩（闪退根因之一）。
            // 用 synchronized 保证同一时刻只有一个线程调用 Interpreter。
            val (interpreter, accelerator) = synchronized(INFERENCE_LOCK) {
                RuntimeProvider.createInterpreter(
                    modelFile,
                    maxAccelerator = RuntimeProvider.Accelerator.QNN,
                )
            }
            Log.i(TAG, "PCT-Net 和谐化（$accelerator，低分辨率参数预测）")

            // 模型 I/O 契约（以转换后权重为准）：
            //  输入：前景 RGB + 背景 RGB + 前景 mask（纯视觉，无文本）
            //  输出：和谐化后的前景 RGB（低分辨率预测参数 → 上采样应用）
            // 具体张量形状以转换后的权重为准；权重到位后按签名填充。
            val out = synchronized(INFERENCE_LOCK) { runInference(interpreter, request) }

            // interpreter 已由 RuntimeProvider 缓存，不应在此 close
            HarmonizeResult(out, HarmonizerBackend.PCT_NET, params = null)
        } catch (t: Throwable) {
            Log.w(TAG, "PCT-Net 推理失败，回退 MKL：${t.message}")
            null
        }
    }

    /**
     * 定位权重文件。按优先级：外部 filesDir/models → assets。
     * 真机测试用 `adb push` 到 filesDir/models/pct_net_vit.tflite 即可，免重新打包。
     */
    private fun locateModel(): File? {
        val external = File(appContext.filesDir, "models/$MODEL_FILE")
        if (external.exists() && external.length() > 0) {
            Log.i(TAG, "PCT-Net 权重来自外部 filesDir：${external.absolutePath}")
            return external
        }
        return ModelAssetInstaller.ensureModel(appContext, MODEL_FILE)
    }

    /**
     * 真实推理：低分辨率 4 通道输入 → 参数图 → 上采样 + 仿射应用。
     *
     * TFLite 模型（转换脚本见 tools/convert_pctnet.py）的 I/O 契约：
     *  - 输入：`[1, 256, 256, 4]` float32 —— RGB（前景）+ mask（归一化 0/1）按通道拼接，
     *    由 [HarmonizeRequest] 的 foregroundPixels + mask 缩放而来。
     *  - 输出：`[1, 256, 256, 12]` float32 —— 逐像素 12 参数仿射（3×3 + 3 平移）。
     *  后半段（bicubic 上采样 + 仿射 + `out*mask + image*(1-mask)`）由 [PctNetTransform] 在端侧完成。
     *
     * 若转换后的权重签名与上述不符（例如 ViT 骨干导出时把上采样也并进图），
     * 这里会在张量尺寸对不上时抛异常，由上层回退 MKL，**不崩溃**。
     */
    private fun runInference(
        interpreter: org.tensorflow.lite.Interpreter,
        request: HarmonizeRequest,
    ): IntArray {
        val w = request.width
        val h = request.height

        // 1) 构造低分辨率输入 [1,256,256,4]：RGB + mask
        //    ⚠️ 必须是**双线性**重采样。早期实现用 (x * invW / 255).toInt() 直接取整，
        //    等于最近邻下采样 —— 高频线稿被抽点丢弃，模型预测出块状参数，
        //    再上采样回原图就是满屏方形色块（这正是"大图色块"的根因之一）。
        //
        //    这里**不做 letterbox**：PctNet 输出的是逐像素参数图（256×256 → 原图 w×h），
        //    letterbox 会让有效参数区缩到中心，而 PctNetTransform 仍按全画布映射取值，
        //    结果是参数错位（比宽高比失真严重得多）。参数图不需要"保持主体比例"，
        //    只需要"输入输出坐标一一对应"。
        val lrInput = Array(1) { Array(256) { Array(256) { FloatArray(4) } } }
        val scaleX = (w - 1).toFloat() / 255f
        val scaleY = (h - 1).toFloat() / 255f
        for (y in 0 until 256) {
            val syF = y * scaleY
            val y0 = syF.toInt().coerceIn(0, h - 1)
            val y1 = (y0 + 1).coerceIn(0, h - 1)
            val wy = syF - y0
            for (x in 0 until 256) {
                val sxF = x * scaleX
                val x0 = sxF.toInt().coerceIn(0, w - 1)
                val x1 = (x0 + 1).coerceIn(0, w - 1)
                val wx = sxF - x0

                val i00 = y0 * w + x0
                val i01 = y0 * w + x1
                val i10 = y1 * w + x0
                val i11 = y1 * w + x1

                lrInput[0][y][x][0] = bilinearChannel(request.foregroundPixels, i00, i01, i10, i11, 16, wx, wy)
                lrInput[0][y][x][1] = bilinearChannel(request.foregroundPixels, i00, i01, i10, i11, 8, wx, wy)
                lrInput[0][y][x][2] = bilinearChannel(request.foregroundPixels, i00, i01, i10, i11, 0, wx, wy)
                lrInput[0][y][x][3] = (request.mask[i00] * (1 - wx) * (1 - wy) +
                    request.mask[i01] * wx * (1 - wy) +
                    request.mask[i10] * (1 - wx) * wy +
                    request.mask[i11] * wx * wy) / 255f
            }
        }

        // 2) 推理得到参数图 [1,256,256,12]
        val lrOutput = Array(1) { Array(256) { Array(256) { FloatArray(PctNetTransform.PARAMS_PER_PIXEL) } } }
        interpreter.run(lrInput, lrOutput)

        // 3) 展平成 [256][256][12] 一维，交给纯函数做上采样 + 仿射
        val flatParams = FloatArray(256 * 256 * PctNetTransform.PARAMS_PER_PIXEL)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val dst = (y * 256 + x) * PctNetTransform.PARAMS_PER_PIXEL
                lrOutput[0][y][x].copyInto(flatParams, dst)
            }
        }

        return PctNetTransform.apply(
            image = request.foregroundPixels,
            mask = request.mask,
            lowResParams = flatParams,
            width = w,
            height = h,
        )
    }

    /** 双线性采样单通道（shift = 16/8/0 取 R/G/B），输出 [0,1]。 */
    private fun bilinearChannel(
        pixels: IntArray,
        i00: Int, i01: Int, i10: Int, i11: Int,
        shift: Int, wx: Float, wy: Float,
    ): Float {
        val c00 = ((pixels[i00] shr shift) and 0xFF) / 255f
        val c01 = ((pixels[i01] shr shift) and 0xFF) / 255f
        val c10 = ((pixels[i10] shr shift) and 0xFF) / 255f
        val c11 = ((pixels[i11] shr shift) and 0xFF) / 255f
        val top = c00 + (c01 - c00) * wx
        val bottom = c10 + (c11 - c10) * wx
        return top + (bottom - top) * wy
    }

    private companion object {
        const val TAG = "NaiwaCamera"
        const val MODEL_FILE = "pct_net_vit.tflite"
        /** 推理超时（规格 8.2 修订：8s，超时回退 MKL）。 */
        const val TIMEOUT_MS = 8000L

        /** 推理互斥锁：Interpreter 非线程安全，串行化所有 run 调用。 */
        private val INFERENCE_LOCK = Any()
    }
}
