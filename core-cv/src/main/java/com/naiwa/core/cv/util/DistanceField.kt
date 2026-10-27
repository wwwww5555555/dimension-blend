package com.naiwa.core.cv.util

import kotlin.math.sqrt

/**
 * 近似欧氏距离变换（Chamfer 3-4 / 两遍扫描）。
 *
 * 用途：从二值前景掩膜求「每个像素到最近背景的距离场」，其**梯度场**就是轮廓法向
 * —— 距离场在边缘处从 0 向物体内部单调增大，梯度指向物体内部（内法向），取负即外法向。
 * 比"模糊 alpha 的中心差分"更稳健：对锯齿轮廓不敏感，也不会因为羽化半径不同而漂移。
 *
 * 实现说明（两遍扫描，纯 Kotlin，可 JVM 单测）：
 *  1. 第一遍（左上→右下）：每个前景像素取「左上邻域距离 + 邻域步长」的最小值；
 *  2. 第二遍（右下→左上）：取「右下邻域距离 + 邻域步长」的最小值；
 *  3. Chamfer 步长 3/4 是欧氏距离的整数近似（`1, 1 → 3`、`1, 0 → 4`，即 √2≈1.41、1≈1 放大 3 倍取整）。
 *     最后除以 3 还原为近似欧氏像素距离。
 *
 * 这不是 OpenCV 的 `distanceTransform`（精确欧氏 + 双精度），但对「求法向方向」这个用途，
 * Chamfer 的梯度方向误差在 1~2 个像素内，完全够用；且避免引入 OpenCV 依赖，保持 :core-cv 的纯 Kotlin 兜底能力。
 *
 * @return 距离场（浮点，单位像素）；背景像素 = 0，前景像素 = 到最近边界的近似欧氏距离
 */
object DistanceField {

    /** Chamfer 步长（放大 3 倍的整数近似）：对角线 √2≈1.414 → 3，轴对齐 1 → 4/3？ */
    private const val DIAG_COST = 3
    private const val AXIS_COST = 4

    /** 还原系数：步长放大 3 倍，距离要除以 3。 */
    private const val SCALE = 3f

    /**
     * 对二值掩膜求距离场。
     *
     * @param mask 0/1 掩膜（1 = 前景）；长度必须等于 width × height
     * @return 距离场；前景像素 = 到最近背景的近似欧氏距离（像素），背景 = 0
     */
    fun compute(mask: IntArray, width: Int, height: Int): FloatArray {
        if (width <= 0 || height <= 0 || mask.size < width * height) {
            return FloatArray(mask.size)
        }

        // 距离场初始化为"无穷"（用大整数表示），背景为 0
        val INF = width + height + 1
        val dist = IntArray(width * height) { i -> if (mask[i] > 0) INF else 0 }

        // 第一遍：左上 → 右下，只看上方与左方的邻域
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (mask[i] == 0) continue
                var best = dist[i]
                // 上（y-1, x）与左（y, x-1）是轴对齐（cost AXIS_COST）
                if (y > 0) best = minOf(best, dist[i - width] + AXIS_COST)
                if (x > 0) best = minOf(best, dist[i - 1] + AXIS_COST)
                // 左上（y-1, x-1）与右上（y-1, x+1）是对角（cost DIAG_COST）
                if (y > 0 && x > 0) best = minOf(best, dist[i - width - 1] + DIAG_COST)
                if (y > 0 && x + 1 < width) best = minOf(best, dist[i - width + 1] + DIAG_COST)
                dist[i] = best
            }
        }

        // 第二遍：右下 → 左上，只看下方与右方的邻域
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val i = row + x
                if (mask[i] == 0) continue
                var best = dist[i]
                if (y + 1 < height) best = minOf(best, dist[i + width] + AXIS_COST)
                if (x + 1 < width) best = minOf(best, dist[i + 1] + AXIS_COST)
                if (y + 1 < height && x > 0) best = minOf(best, dist[i + width - 1] + DIAG_COST)
                if (y + 1 < height && x + 1 < width) best = minOf(best, dist[i + width + 1] + DIAG_COST)
                dist[i] = best
            }
        }

        // 还原为浮点像素距离
        return FloatArray(width * height) { i -> dist[i] / SCALE }
    }

    /**
     * 用距离场求**外法向**（指向物体外侧）。
     *
     * 距离场在物体内部随"离边缘越远"而增大，其梯度（∂d/∂x, ∂d/∂y）指向物体内部；
     * 外法向 = -梯度。边缘像素处梯度幅值最大（距离场变化最陡）。
     *
     * @return 单位外法向 `(nx, ny)`；梯度为零处返回 `(0, 0)`
     */
    fun outwardNormal(field: FloatArray, width: Int, height: Int, x: Int, y: Int): FloatArray {
        if (x <= 0 || y <= 0 || x + 1 >= width || y + 1 >= height) return floatArrayOf(0f, 0f)
        val i = y * width + x
        // 距离场梯度（中心差分）
        val gx = field[i + 1] - field[i - 1]
        val gy = field[i + width] - field[i - width]
        val len = sqrt(gx * gx + gy * gy)
        if (len < 1e-4f) return floatArrayOf(0f, 0f)
        return floatArrayOf(-gx / len, -gy / len)
    }
}
