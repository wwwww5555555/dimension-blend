package com.naiwa.core.cv.blend

import android.graphics.Bitmap
import android.util.Log
import com.naiwa.core.common.model.FusionTier
import com.naiwa.core.cv.OpenCvRuntime
import kotlin.math.cos

/**
 * 分块融合（P3 的可选优化）—— 仅在**大图**时使用。
 *
 * 【为什么 P0–P2 不用它】分块会在 tile 边界引入两个额外风险：梯度域不连续导致的接缝、
 * 以及每个 tile 都要重复的常数开销。在还没有性能基准之前引入，等于同时排查"算法错"和"分块错"。
 *
 * 【必须满足的条件】架构文档 §7.3 要求 **25% 重叠 + 重叠区加权融合**，且用**汉宁窗**而不是线性窗：
 * 线性窗在 tile 边界权重为 0，会让边界像素完全依赖单侧结果，反而把接缝暴露出来。
 *
 * 【内存】合并阶段**逐行累加**而不是全图累加。
 * 4096×4096 的图如果开全图浮点累加器要 300 MB 以上 —— 而分块本来就是为了省内存，
 * 那样等于把问题挪了个位置。逐行累加只需要 5 个长度为 width 的累加器。
 *
 * 【产品位置】本类对应 [FusionTier.TILED]，由 `DefaultImagePipeline` 在用户**显式选择**
 * 该档位时调用（低端/中端机由 [com.naiwa.core.common.model.FusionTierChoice] 提前降级）。
 * 它不参与自动升降档：耗时随 tile 数线性增长，一旦被"上次跑得快"自动升上来就会失控。
 */
object TiledBlender {

    private const val TAG = "NaiwaCamera"

    /** tile 边长（像素）。2048 兼顾"装得进 540p 泊松域"与"分块收益明显"。 */
    const val TILE_SIZE = 2048

    /** 重叠比例：25%（架构文档 §7.3 的硬性要求）。 */
    const val OVERLAP_RATIO = 0.25f

    /** 一维汉宁窗：两端为 0、中间为 1，过渡平缓（线性窗会在边界留下可见痕迹）。 */
    fun hannWindow(size: Int): FloatArray {
        if (size <= 1) return FloatArray(size) { 1f }
        return FloatArray(size) { i ->
            (0.5 * (1.0 - cos(2.0 * Math.PI * i / (size - 1)))).toFloat()
        }
    }

    /**
     * 规划分块方案，返回每个 tile 的 `[left, top, right, bottom]`。
     * 最后一个 tile 与图像边界对齐（其实际重叠可能大于 25%，这是刻意的：宁可多重叠，也不要留窄缝）。
     */
    fun planTiles(width: Int, height: Int, tileSize: Int = TILE_SIZE): List<IntArray> {
        if (width <= tileSize && height <= tileSize) {
            return listOf(intArrayOf(0, 0, width, height))
        }
        val overlap = (tileSize * OVERLAP_RATIO).toInt()
        val step = (tileSize - overlap).coerceAtLeast(1)
        val xs = buildAxis(width, tileSize, step)
        val ys = buildAxis(height, tileSize, step)

        val tiles = ArrayList<IntArray>(xs.size * ys.size)
        for (y in ys) for (x in xs) tiles += intArrayOf(x[0], y[0], x[1], y[1])
        return tiles
    }

    private fun buildAxis(length: Int, tileSize: Int, step: Int): List<IntArray> {
        val result = ArrayList<IntArray>()
        var start = 0
        while (true) {
            val end = (start + tileSize).coerceAtMost(length)
            result += intArrayOf(start, end)
            if (end >= length) break
            start += step
            if (start + tileSize > length) {
                // 收尾：与边界对齐，避免最后留下比 step 还窄的一条
                result += intArrayOf((length - tileSize).coerceAtLeast(0), length)
                break
            }
        }
        return result
    }

    /**
     * 用汉宁窗把多个分块结果加权合并成全图。
     *
     * @param width 全图宽
     * @param height 全图高
     * @param plan [planTiles] 的结果
     * @param tiles 与 plan 顺序一致的分块像素（每块 tileWidth × tileHeight）
     * @return 合并后的全图像素（权重已归一化，不会变暗或变亮）
     */
    fun mergeTiles(
        width: Int,
        height: Int,
        plan: List<IntArray>,
        tiles: List<IntArray>,
    ): IntArray {
        require(plan.size == tiles.size) { "tile 计划与数据数量不一致" }

        // 预计算每个 tile 的汉宁窗（复用，避免每行重算）
        val windowsX = ArrayList<FloatArray>(plan.size)
        val windowsY = ArrayList<FloatArray>(plan.size)
        for (rect in plan) {
            windowsX += hannWindow(rect[2] - rect[0])
            windowsY += hannWindow(rect[3] - rect[1])
        }

        val out = IntArray(width * height)
        // 逐行累加：5 个长度为 width 的累加器，内存与图高无关
        val accR = FloatArray(width)
        val accG = FloatArray(width)
        val accB = FloatArray(width)
        val accA = FloatArray(width)
        val accW = FloatArray(width)

        for (y in 0 until height) {
            java.util.Arrays.fill(accR, 0f)
            java.util.Arrays.fill(accG, 0f)
            java.util.Arrays.fill(accB, 0f)
            java.util.Arrays.fill(accA, 0f)
            java.util.Arrays.fill(accW, 0f)

            var covered = false
            for (index in plan.indices) {
                val rect = plan[index]
                val top = rect[1]
                val bottom = rect[3]
                if (y < top || y >= bottom) continue
                covered = true

                val left = rect[0]
                val right = rect[2]
                val tileW = right - left
                val tileH = bottom - top
                val localY = y - top
                val wy = windowsY[index][localY]
                val windowX = windowsX[index]
                val tile = tiles[index]
                val rowTile = localY * tileW

                val maxX = minOf(tileW, width - left)
                for (x in 0 until maxX) {
                    val w = (windowX[x] * wy).coerceAtLeast(MIN_WEIGHT)
                    val gx = left + x
                    val c = tile[rowTile + x]
                    accR[gx] += ((c shr 16) and 0xFF) * w
                    accG[gx] += ((c shr 8) and 0xFF) * w
                    accB[gx] += (c and 0xFF) * w
                    accA[gx] += ((c ushr 24) and 0xFF) * w
                    accW[gx] += w
                }
            }

            if (!covered) continue
            val rowOut = y * width
            for (x in 0 until width) {
                val sum = accW[x]
                if (sum <= 1e-5f) continue
                val r = (accR[x] / sum).toInt().coerceIn(0, 255)
                val g = (accG[x] / sum).toInt().coerceIn(0, 255)
                val b = (accB[x] / sum).toInt().coerceIn(0, 255)
                val a = (accA[x] / sum).toInt().coerceIn(0, 255)
                out[rowOut + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /**
     * 全分辨率分块泊松融合。
     *
     * 流程：规划 tile → 逐块裁剪 → 块内泊松求解 → 汉宁窗加权合并。
     *
     * 【为什么每块用 540p 工作域而不是全分辨率求解】泊松求解的代价由工作域短边决定。
     * 若每块都在 2048 原生分辨率上求解，单块就等价于一次 2048p 求解（远超交互预算），
     * 分块的意义（把大图拆成能承受的小问题）就消失了。用 540p 工作域 + 25% 重叠合并，
     * 换来的是"没有全图降采样 + 上采样"的高保真结果 —— 过渡带不会因为上采样而发糊。
     *
     * 【边界为什么不露缝】`seamlessClone` 以边界像素作为 Dirichlet 条件，所以每块结果
     * 在自身边界处与场景**逐像素一致**；重叠区再按汉宁窗加权（两端权重不为 0），
     * 相邻块在缝上都是"接近场景"的值，不会出现梯度断层。
     *
     * @param scene 全分辨率场景
     * @param placedFrog 场景空间中的奶蛙（背景透明），尺寸必须与 scene 一致
     * @param sceneMask 场景空间掩膜（0~255）
     * @param tileSize tile 边长，默认 [TILE_SIZE]
     * @param tileTier 每个 tile 内部使用的泊松档位（默认验收线档 540p）
     * @return 全分辨率融合结果；**失败返回 null**（调用方必须回退到多频段融合）
     */
    fun fuse(
        scene: Bitmap,
        placedFrog: Bitmap,
        sceneMask: IntArray,
        tileSize: Int = TILE_SIZE,
        tileTier: FusionTier = FusionTier.FUSION_540,
    ): Bitmap? {
        if (!OpenCvRuntime.isAvailable()) {
            Log.i(TAG, "OpenCV 不可用，分块融合跳过（回退多频段融合）")
            return null
        }
        val width = scene.width
        val height = scene.height
        if (width <= 0 || height <= 0 || tileSize <= 0) return null
        if (placedFrog.width != width || placedFrog.height != height) {
            Log.w(TAG, "placedFrog 与 scene 尺寸不一致，分块融合跳过")
            return null
        }
        if (sceneMask.size < width * height) return null

        return try {
            val plan = planTiles(width, height, tileSize)
            val results = ArrayList<IntArray>(plan.size)
            var solvedTiles = 0

            for (rect in plan) {
                val left = rect[0]
                val top = rect[1]
                val tileW = rect[2] - left
                val tileH = rect[3] - top

                val sceneCrop = cropToBitmap(scene, left, top, tileW, tileH)
                val tileMask = cropMask(sceneMask, width, left, top, tileW, tileH)

                // 这块完全没有前景：泊松求解在掩膜全零时是恒等变换（白算一次），直接取场景像素
                val tilePixels = if (!hasForeground(tileMask)) {
                    readPixels(sceneCrop, tileW, tileH)
                } else {
                    val frogCrop = cropToBitmap(placedFrog, left, top, tileW, tileH)
                    val fused = PoissonBlender.fuse(
                        scene = sceneCrop,
                        placedFrog = frogCrop,
                        sceneMask = tileMask,
                        tier = tileTier,
                    )
                    // 单块失败不让整档失败：该块退回"未融合的场景"，其余块照常融合后交给加权合并
                    if (fused != null) solvedTiles++
                    fused?.let { readPixels(it, tileW, tileH) } ?: readPixels(sceneCrop, tileW, tileH)
                }
                results += tilePixels
            }

            if (solvedTiles == 0) {
                Log.w(TAG, "分块融合没有任何一块完成求解，回退多频段融合")
                return null
            }
            Log.i(TAG, "分块融合完成：${plan.size} 块，其中 $solvedTiles 块做了泊松求解")

            val merged = mergeTiles(width, height, plan, results)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(merged, 0, width, 0, 0, width, height)
            }
        } catch (t: Throwable) {
            // OOM / 原生异常只让本次降级，绝不让主流程崩掉
            Log.w(TAG, "分块融合失败（回退多频段融合）：${t.message}")
            null
        }
    }

    /** 裁剪子区域。`Bitmap.createBitmap` 对未修改的来源共享像素，读路径安全且不额外占内存。 */
    private fun cropToBitmap(source: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(source, left, top, width, height)

    private fun readPixels(bitmap: Bitmap, width: Int, height: Int): IntArray =
        IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }

    /**
     * 从整图掩膜里取出一个 tile 的子掩膜。
     *
     * `internal` 而非 `private`：这块代码是"分块"这类 bug 的高发区（行列算错会表现为
     * 某个 tile 的合成位置整体偏移），必须能在 JVM 单测里直接对拍 —— 而整条 [fuse] 路径
     * 需要 Android Bitmap 与 OpenCV，无法在 JVM 覆盖。
     */
    internal fun cropMask(
        mask: IntArray,
        maskWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(mask, (top + y) * maskWidth + left, out, y * width, width)
        }
        return out
    }

    /** 该区域是否含前景（全零掩膜下泊松求解是恒等变换，可以整块跳过以省时间）。 */
    internal fun hasForeground(mask: IntArray): Boolean {
        for (v in mask) if (v > 0) return true
        return false
    }

    /** 汉宁窗两端为 0，给极小权重避免除零，同时不让边界像素权重完全消失。 */
    private const val MIN_WEIGHT = 1e-3f
}
