package com.naiwa.core.cv.edge

import android.graphics.Bitmap

/**
 * **奶蛙边缘预处理（规格 6.1 → 6.4，顺序不可颠倒）**
 *
 * ```
 * 6.1 预乘检查 → 反预乘        还原 RGB 本真值（否则后面全是白做，还会把污染扩散）
 * 6.2 边缘去白（只改 RGB）      把被背景色污染的半透明边缘换成前景本体色
 * 6.3 Alpha 边缘柔化（只改 alpha）  小半径各向同性，磨平过渡的阶梯
 * 6.4 方向性边缘柔化（只改 alpha）  沿切线方向再柔一次，保边且更自然
 * 6.5 重新预乘                 交还给 Android 之前必须回到预乘格式
 * ```
 *
 * ## 为什么顺序不能变（规格 6.1 的原话："不做这一步后续模糊只会把污染扩散"）
 * - 反预乘必须在最前：把"被 alpha 压暗的 RGB"当成真实颜色去统计，得到的目标色本身就是错的。
 * - 去白必须在柔化之前：alpha 一旦被摊开，被污染的颜色会**跟着扩散到更多像素**，
 *   此时再去白就只能向更远处找参考，效果更差。
 * - 重新预乘必须在最后：内部全程用非预乘（本真色）运算，
 *   而 `Bitmap.createBitmap(ARGB_8888)` + `setPixels` 与 `Canvas.drawBitmap` 都按预乘解释。
 *   这条边界守不住，边缘就会因为"多乘了一次 alpha"而发暗发灰。
 *
 * ## 幂等性
 * 3 步里 alpha 柔化**不幂等**（反复柔化会把边缘越磨越虚）。
 * 因此 [VERSION] 会写进导入素材索引，同一素材在同一次安装里只处理一次；
 * 选择素材时先查版本号，已处理过就直接加载。
 *
 * ## 失败契约
 * 与项目其它增强步骤一致：尺寸不合法时原样返回、只把 [Report] 标成 `skipped = true`，
 * **不抛异常** —— 边缘预处理失败不该让"选一张奶蛙"这件事失败。
 */
object FrogEdgePreparer {

    /** 预处理版本。写进导入素材索引；将来改算法时递增即可让老素材重新处理一次。 */
    const val VERSION = 1

    /** 默认柔和度（拿不到光照参数时用）。 */
    const val DEFAULT_SOFTNESS = 0.5f

    data class Report(
        val skipped: Boolean = false,
        /** 6.1：是否判定为预乘并做了反预乘。 */
        val unPremultiplied: Boolean = false,
        /** 6.1：判定时测到的违规比例（0 = 明显是预乘；越高越像非预乘）。 */
        val violatingRatio: Float = 0f,
        /** 6.2：被替换 RGB 的像素数。 */
        val defringedPixels: Int = 0,
        /** 6.2：连最大半径都找不到实心参考、未能去白的像素数。 */
        val unresolvedPixels: Int = 0,
        /** 6.3：alpha 被柔化的像素数。 */
        val softenedPixels: Int = 0,
        /** 6.4：alpha 被方向性柔化的像素数。 */
        val directionalPixels: Int = 0,
    ) {
        /** 单行摘要，直接进日志与诊断行。 */
        val summary: String
            get() = if (skipped) {
                "边缘预处理已跳过（尺寸不合法）"
            } else {
                "反预乘=${if (unPremultiplied) "是" else "否"}（违规率 %.3f）/ 去白 %d px（未解决 %d）/ 柔化 %d px / 方向性 %d px"
                    .format(violatingRatio, defringedPixels, unresolvedPixels, softenedPixels, directionalPixels)
            }
    }

    data class Prepared(val pixels: IntArray, val report: Report)

    /**
     * 对像素数组执行 6.1~6.5。
     *
     * @param softness 阴影柔和度（0~1），只影响 6.3 的半径
     */
    fun prepare(
        pixels: IntArray,
        width: Int,
        height: Int,
        softness: Float = DEFAULT_SOFTNESS,
    ): Prepared {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return Prepared(pixels.copyOf(), Report(skipped = true))
        }

        // ---------- 6.1 预乘检查 → 反预乘 ----------
        val assessment = PremultipliedAlpha.assess(pixels)
        var working = if (assessment.premultiplied) {
            PremultipliedAlpha.unpremultiply(pixels)
        } else {
            pixels.copyOf()
        }

        // ---------- 6.2 边缘去白（只改 RGB） ----------
        val defringe = EdgeDefringe.defringe(working, width, height)
        working = defringe.pixels

        // ---------- 6.3 + 6.4 Alpha 边缘柔化（只改 alpha） ----------
        val soften = AlphaEdgeSoften.soften(working, width, height, softness)
        working = soften.pixels

        // ---------- 6.5 重新预乘 ----------
        val finalPixels = PremultipliedAlpha.premultiply(working)

        return Prepared(
            pixels = finalPixels,
            report = Report(
                skipped = false,
                unPremultiplied = assessment.premultiplied,
                violatingRatio = assessment.violatingRatio,
                defringedPixels = defringe.changedPixels,
                unresolvedPixels = defringe.unresolvedPixels,
                softenedPixels = soften.softenedPixels,
                directionalPixels = soften.directionalPixels,
            ),
        )
    }

    /**
     * 位图版（新建位图，输入不被修改也不被回收）。
     *
     * @return 处理后的位图；失败返回 null（调用方应退回原始位图）
     */
    fun prepareBitmap(bitmap: Bitmap, softness: Float = DEFAULT_SOFTNESS): Pair<Bitmap, Report>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        return try {
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val prepared = prepare(pixels, w, h, softness)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(prepared.pixels, 0, w, 0, 0, w, h)
            out to prepared.report
        } catch (t: Throwable) {
            // 增强步骤：宁可返回 null 让调用方退回原图，也不要中断"选奶蛙"这件事
            null
        }
    }
}
