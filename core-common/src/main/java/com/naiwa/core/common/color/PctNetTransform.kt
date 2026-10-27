package com.naiwa.core.common.color

/**
 * PCT-Net 的像素级颜色变换（Pixel-wise Color Transformation）参数应用 + 参数图上采样。
 *
 * 这是规格 8.2 里 PCT-Net 端侧推理的**后半段**（纯 Kotlin，可 JVM 单测）：
 * TFLite 模型只负责"低分辨率 256×256 输入 → 256×256×12 仿射参数图"，
 * 参数图到全分辨率的 bicubic 上采样、以及逐像素仿射变换，都在这里完成。
 *
 * 这样做的理由（与论文一致）：参数空间比图像空间低频得多，在参数空间做上采样
 * 引入的插值误差远小于"先上采样图像再变换"。而 bicubic 插值 + 3×4 仿射都是
 * 确定性小计算，端侧做比在 ONNX 里处理动态 H/W 更稳（转换脚本见 tools/convert_pctnet.py）。
 *
 * PCT-Net 论文（CVPR 2023）与 libcom 实现确认的契约：
 *  - `transform_type='linear', affine=true, dim=3` → 每像素 **12 参数**（3×3 线性 + 3 平移）。
 *  - 仿射变换：`out = A @ p + b`，A 为 3×3（参数前 9 个），b 为 3 维（后 3 个）。
 *  - 输出只作用于前景：`out * mask + image * (1 - mask)`，背景像素原样保留。
 *
 * 放在 `core-common` 而非 `core-cv` 的原因：`:core-ai` 的 [com.naiwa.core.ai.harmonize.PctNetHarmonizer]
 * 也要调用它，而 `core-ai` 与 `core-cv` 禁止互相依赖，两者都能引用的最小公共层是 `core-common`。
 *
 * 所有方法都是纯函数、不抛异常（非法尺寸返回原图），符合管线"增强步骤不阻断主流程"的契约。
 */
object PctNetTransform {

    /** 每像素仿射参数个数：3×3 线性 + 3 平移。 */
    const val PARAMS_PER_PIXEL = 12

    /** 低分辨率分支的固定输入边长（论文与 libcom 均为 256）。 */
    const val LOW_RES = 256

    /**
     * 把低分辨率参数图 bicubic 上采样到目标尺寸，并对前景应用逐像素仿射。
     *
     * @param image 全分辨率合成图 ARGB（ARGB 打包，与 Android Bitmap 一致），只改前景 RGB、保留 alpha
     * @param mask 前景掩膜（0/255），与 image 同尺寸
     * @param lowResParams 低分辨率参数图，长度 = 256×256×12，布局 [256][256][12]
     * @param width / height 全分辨率尺寸
     * @return 变换后的 ARGB 像素；输入非法时返回 image 的副本（恒等，不抛异常）
     */
    fun apply(
        image: IntArray,
        mask: IntArray,
        lowResParams: FloatArray,
        width: Int,
        height: Int,
    ): IntArray {
        val expected = LOW_RES * LOW_RES * PARAMS_PER_PIXEL
        if (width <= 0 || height <= 0 ||
            image.size != width * height || mask.size != width * height ||
            lowResParams.size != expected
        ) {
            return image.copyOf()
        }

        val out = image.copyOf()
        for (y in 0 until height) {
            // 参数图上采样：目标坐标 → 源坐标（align_corners=true 语义）
            val sy = y.toFloat() * (LOW_RES - 1).toFloat() / (height - 1).coerceAtLeast(1).toFloat()
            for (x in 0 until width) {
                val idx = y * width + x
                val a = (image[idx] ushr 24) and 0xFF
                if (a == 0) continue // 纯透明，无需处理
                if (mask[idx] == 0) continue // 非前景，保持不变

                val sx = x.toFloat() * (LOW_RES - 1).toFloat() / (width - 1).coerceAtLeast(1).toFloat()
                val p = sampleParamsBicubic(lowResParams, sx, sy)

                val r = (image[idx] ushr 16) and 0xFF
                val g = (image[idx] ushr 8) and 0xFF
                val b = image[idx] and 0xFF

                // out = A @ [r,g,b]^T + b，A 为 3×3 行优先（p[0..8]），偏移 p[9..11]
                val nr = p[0] * r + p[1] * g + p[2] * b + p[9]
                val ng = p[3] * r + p[4] * g + p[5] * b + p[10]
                val nb = p[6] * r + p[7] * g + p[8] * b + p[11]

                val cr = nr.coerceIn(0f, 255f).toInt()
                val cg = ng.coerceIn(0f, 255f).toInt()
                val cb = nb.coerceIn(0f, 255f).toInt()

                out[idx] = (a shl 24) or (cr shl 16) or (cg shl 8) or cb
            }
        }
        return out
    }

    /**
     * 在低分辨率参数图上做双三次（bicubic）采样，返回 12 维参数。
     *
     * 用 Catmull-Rom 核（与 PyTorch `F.interpolate(mode='bicubic', align_corners=True)` 一致）。
     * 采样点坐标 sx/sy 已是 `align_corners=true` 语义下的归一化坐标。
     */
    private fun sampleParamsBicubic(params: FloatArray, sx: Float, sy: Float): FloatArray {
        val result = FloatArray(PARAMS_PER_PIXEL)
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        val fx = sx - x0
        val fy = sy - y0

        for (c in 0 until PARAMS_PER_PIXEL) {
            var acc = 0f
            for (dy in -1..2) {
                val yy = (y0 + dy).coerceIn(0, LOW_RES - 1)
                for (dx in -1..2) {
                    val xx = (x0 + dx).coerceIn(0, LOW_RES - 1)
                    val w = cubicWeight(dx - fx) * cubicWeight(dy - fy)
                    acc += w * params[((yy * LOW_RES) + xx) * PARAMS_PER_PIXEL + c]
                }
            }
            result[c] = acc
        }
        return result
    }

    /** Catmull-Rom 三次卷积核（PyTorch bicubic 的权重函数）。 */
    private fun cubicWeight(t: Float): Float {
        val x = kotlin.math.abs(t)
        return when {
            x <= 1f -> 1.5f * x * x * x - 2.5f * x * x + 1f
            x <= 2f -> -0.5f * x * x * x + 2.5f * x * x - 4f * x + 2f
            else -> 0f
        }
    }
}
