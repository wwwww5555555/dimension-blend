package com.naiwa.core.cv.util

/**
 * 可分离盒式模糊（两趟 = 近似高斯），复杂度与半径无关（O(n)）。
 *
 * 全项目统一用这一份实现，避免每处各写一遍 —— 阴影、边缘光、羽化、金字塔都要用它，
 * 而"多处各写一份模糊"是这类算法代码最容易出现细微不一致的地方。
 */
object BoxBlur {

    /**
     * 对单通道浮点缓冲做可分离盒式模糊。
     *
     * @param src 输入缓冲（不被修改）
     * @param width 宽
     * @param height 高
     * @param radius 半径（像素）。0 或负数时返回输入的拷贝
     * @param passes 趟数。1 趟 = 盒式；**2 趟 ≈ 三角核；3 趟 ≈ 高斯核**（业界常用的近似）
     */
    fun blur(src: FloatArray, width: Int, height: Int, radius: Int, passes: Int = 2): FloatArray {
        if (radius <= 0) return src.copyOf()
        var current = src.copyOf()
        repeat(passes.coerceAtLeast(1)) {
            current = blurOnce(current, width, height, radius)
        }
        return current
    }

    /** 半径由期望 σ 反推：盒式模糊的等效 σ ≈ radius / sqrt(3)。 */
    fun radiusForSigma(sigma: Float): Int =
        (sigma * 1.732f).toInt().coerceAtLeast(0)

    /**
     * 方向性模糊：横向与纵向用**不同半径**，等效于沿某个方向的椭圆高斯核（拉伸模糊）。
     *
     * 用途：辉光/镜头光晕的方向性 —— 逆光时高光沿光源方向拉长，而非各向同性的圆斑。
     * 实现：先做 radiusX 的横向盒式模糊，再做 radiusY 的纵向盒式模糊（可分离，O(n)）。
     *
     * @param radiusX 横向半径（沿 X 轴拉伸程度）
     * @param radiusY 纵向半径（沿 Y 轴拉伸程度）
     * @param passes 每方向重复趟数（3 趟 ≈ 高斯）
     */
    fun blurDirectional(
        src: FloatArray,
        width: Int,
        height: Int,
        radiusX: Int,
        radiusY: Int,
        passes: Int = 3,
    ): FloatArray {
        var current = src.copyOf()
        repeat(passes.coerceAtLeast(1)) {
            current = blurHorizontal(current, width, height, radiusX)
            current = blurVertical(current, width, height, radiusY)
        }
        return current
    }

    /** 仅横向盒式模糊（供方向模糊复用）。 */
    fun blurHorizontal(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val out = FloatArray(src.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (dx in -radius..radius) {
                sum += src[row + dx.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                out[row + x] = sum / window
                val outIdx = (x - radius).coerceIn(0, width - 1)
                val inIdx = (x + radius + 1).coerceIn(0, width - 1)
                sum += src[row + inIdx] - src[row + outIdx]
            }
        }
        return out
    }

    /** 仅纵向盒式模糊（供方向模糊复用）。 */
    fun blurVertical(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val out = FloatArray(src.size)
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sum = 0f
            for (dy in -radius..radius) {
                sum += src[dy.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val outIdx = (y - radius).coerceIn(0, height - 1)
                val inIdx = (y + radius + 1).coerceIn(0, height - 1)
                sum += src[inIdx * width + x] - src[outIdx * width + x]
            }
        }
        return out
    }

    private fun blurOnce(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val horizontal = FloatArray(src.size)
        val window = radius * 2 + 1

        // 横向：滑动窗口求和
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (dx in -radius..radius) {
                sum += src[row + dx.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / window
                val outIdx = (x - radius).coerceIn(0, width - 1)
                val inIdx = (x + radius + 1).coerceIn(0, width - 1)
                sum += src[row + inIdx] - src[row + outIdx]
            }
        }

        // 纵向
        val out = FloatArray(src.size)
        for (x in 0 until width) {
            var sum = 0f
            for (dy in -radius..radius) {
                sum += horizontal[dy.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                out[y * width + x] = sum / window
                val outIdx = (y - radius).coerceIn(0, height - 1)
                val inIdx = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[inIdx * width + x] - horizontal[outIdx * width + x]
            }
        }
        return out
    }

    /**
     * 2× 盒式下采样（宽高各减半），用于构建图像金字塔。
     * 取 2×2 均值而不是抽点采样：抽点会让高频噪声在下采样后变成"拍频"伪影。
     */
    fun downsample(src: FloatArray, width: Int, height: Int): Pair<FloatArray, IntArray> {
        val w = (width / 2).coerceAtLeast(1)
        val h = (height / 2).coerceAtLeast(1)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = (y * 2).coerceAtMost(height - 1)
            val y1 = (y * 2 + 1).coerceAtMost(height - 1)
            for (x in 0 until w) {
                val x0 = (x * 2).coerceAtMost(width - 1)
                val x1 = (x * 2 + 1).coerceAtMost(width - 1)
                out[y * w + x] = (src[y0 * width + x0] + src[y0 * width + x1] +
                    src[y1 * width + x0] + src[y1 * width + x1]) * 0.25f
            }
        }
        return out to intArrayOf(w, h)
    }

    /** 双线性上采样到目标尺寸（用于金字塔重建）。 */
    fun upsample(src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): FloatArray {
        val out = FloatArray(dstW * dstH)
        val scaleX = (srcW - 1).toFloat() / (dstW - 1).coerceAtLeast(1)
        val scaleY = (srcH - 1).toFloat() / (dstH - 1).coerceAtLeast(1)
        for (y in 0 until dstH) {
            val sy = y * scaleY
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = (y0 + 1).coerceIn(0, srcH - 1)
            val wy = sy - y0
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            for (x in 0 until dstW) {
                val sx = x * scaleX
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val wx = sx - x0
                val top = src[row0 + x0] + (src[row0 + x1] - src[row0 + x0]) * wx
                val bottom = src[row1 + x0] + (src[row1 + x1] - src[row1 + x0]) * wx
                out[y * dstW + x] = top + (bottom - top) * wy
            }
        }
        return out
    }
}
