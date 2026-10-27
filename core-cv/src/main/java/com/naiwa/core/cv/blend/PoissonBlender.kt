package com.naiwa.core.cv.blend

import android.graphics.Bitmap
import android.util.Log
import com.naiwa.core.common.model.FusionTier
import com.naiwa.core.cv.OpenCvRuntime
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * 泊松融合（`seamlessClone`）—— **档 1 / 档 2**。
 *
 * 【规格 7 的定位调整】泊松**不再是全图融合方案，而是"局部边缘缝合工具"**：
 * 求解仍用完整前景掩膜（保证梯度场连续），但输出里"距掩膜边界 ≥4px"的核心区
 * 会被 [RingSeam] 写回奶蛙原像素 —— **核心区绝不经泊松**，高频细节与线稿得以保留；
 * 只有核心区外的环形缝合带使用泊松解把奶蛙边缘"缝"进背景。
 * （求解 mask 不用环形的原因：环形 mask 的内边界 Dirichlet 条件会取 dst 背景色，
 * 使环形靠近核心一侧被拉向背景色，与核心区拼接产生色差带 —— 见 RingSeam 的 KDoc。）
 *
 * 【坐标契约（很容易搞错的地方）】
 *  - `scene` 与 `sceneMask` 必须是**同一坐标系、同一分辨率**（都是场景空间）；
 *  - `placedFrog` 是"已经按摆放参数旋转缩放好、贴在场景空间里"的奶蛙（背景透明），
 *    不是原始素材位图 —— 因为 `seamlessClone` **不会帮你旋转**，
 *    如果把原始素材直接传进去，旋转过的奶蛙融合位置会完全错位；
 *  - 内部会把 `placedFrog` 按掩膜包围盒裁出来当 `src`（这样 src 的尺寸与掩膜包围盒严格一致，
 *    是 `seamlessClone` 的标准用法；尺寸不匹配会产生缩放伪影）。
 *
 * 【为什么分档】泊松求解在中端 ARM CPU 上远比直觉慢：
 *  - 档 1（720p 域）：质量最好，1.5~3.0 s，会把总耗时推过 3 s 验收线 → 可选高质量档，非默认；
 *  - 档 2（**540p 域 + 上采样回贴**）：0.6~1.3 s → **验收线档（默认）**。
 * 失败或超时的兜底是 [LaplacianBlender]（档 3）；**低端机（RAM<4GB）在管线层直接跳过泊松**
 * 走拉普拉斯多频段（规格 7 的性能兜底，由 `defaultTierFor()` 决定）。
 */
object PoissonBlender {

    private const val TAG = "NaiwaCamera"

    /**
     * 执行无缝克隆。
     *
     * @param scene 全分辨率场景（不被修改）
     * @param placedFrog 场景空间中的奶蛙（已按摆放参数旋转缩放，背景透明）
     * @param sceneMask 场景空间的掩膜（0~255），尺寸必须与 scene 一致
     * @param tier 融合档位；[FusionTier.NONE] / [FusionTier.LAPLACIAN] 时直接返回 null
     * @return 全分辨率的融合结果位图；**失败返回 null**（调用方必须回退到多频段融合）
     */
    fun fuse(
        scene: Bitmap,
        placedFrog: Bitmap,
        sceneMask: IntArray,
        tier: FusionTier,
    ): Bitmap? {
        if (tier != FusionTier.FUSION_540 && tier != FusionTier.FUSION_720) return null
        if (!OpenCvRuntime.isAvailable()) {
            Log.i(TAG, "OpenCV 不可用，泊松融合跳过（回退多频段融合）")
            return null
        }
        if (sceneMask.size < scene.width * scene.height) return null
        if (placedFrog.width != scene.width || placedFrog.height != scene.height) {
            Log.w(TAG, "placedFrog 与 scene 尺寸不一致，泊松融合跳过")
            return null
        }

        val bbox = foregroundBounds(sceneMask, scene.width, scene.height) ?: return null

        return try {
            fuseInternal(scene, placedFrog, sceneMask, bbox, tier.shortSide)
        } catch (t: Throwable) {
            // OOM / 原生异常都只让本次降级，绝不让主流程崩掉
            Log.w(TAG, "泊松融合失败（回退多频段融合）：${t.message}")
            null
        }
    }

    private fun fuseInternal(
        scene: Bitmap,
        placedFrog: Bitmap,
        sceneMask: IntArray,
        bbox: IntArray,
        workShortSide: Int,
    ): Bitmap? {
        val sceneW = scene.width
        val sceneH = scene.height
        val shortSide = minOf(sceneW, sceneH)
        val scale = if (shortSide > workShortSide) workShortSide.toFloat() / shortSide else 1f

        val workW = (sceneW * scale).toInt().coerceAtLeast(1)
        val workH = (sceneH * scale).toInt().coerceAtLeast(1)

        // ---- 场景与掩膜缩到工作域 ----
        val sceneMat = Mat()
        Utils.bitmapToMat(scene, sceneMat)
        val sceneRgb = Mat()
        Imgproc.cvtColor(sceneMat, sceneRgb, Imgproc.COLOR_RGBA2RGB)

        val sceneWork = Mat()
        if (scale < 1f) {
            Imgproc.resize(sceneRgb, sceneWork, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            sceneRgb.copyTo(sceneWork)
        }

        val maskFull = maskToMat(sceneMask, sceneW, sceneH)
        val maskWork = Mat()
        if (scale < 1f) {
            Imgproc.resize(maskFull, maskWork, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
        } else {
            maskFull.copyTo(maskWork)
        }

        // ---- src：掩膜包围盒内的 placedFrog（尺寸与包围盒严格一致） ----
        val boxW = (bbox[2] - bbox[0]).coerceAtLeast(1)
        val boxH = (bbox[3] - bbox[1]).coerceAtLeast(1)
        val srcFull = Bitmap.createBitmap(placedFrog, bbox[0], bbox[1], boxW, boxH)
        val srcWork = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                srcFull,
                (boxW * scale).toInt().coerceAtLeast(1),
                (boxH * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            srcFull
        }

        val srcMat = Mat()
        Utils.bitmapToMat(srcWork, srcMat)
        val srcRgb = Mat()
        Imgproc.cvtColor(srcMat, srcRgb, Imgproc.COLOR_RGBA2RGB)

        // 融合中心 = 包围盒中心（工作域坐标）
        val center = Point(
            (bbox[0] + boxW / 2.0) * scale,
            (bbox[1] + boxH / 2.0) * scale,
        )

        val cloneResult = Mat()
        Photo.seamlessClone(srcRgb, sceneWork, maskWork, center, cloneResult, Photo.MIXED_CLONE)

        // ---- 上采样回原分辨率 ----
        val full = Mat()
        if (scale < 1f) {
            Imgproc.resize(cloneResult, full, Size(sceneW.toDouble(), sceneH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        } else {
            cloneResult.copyTo(full)
        }

        val rgba = Mat()
        Imgproc.cvtColor(full, rgba, Imgproc.COLOR_RGB2RGBA)
        val result = Bitmap.createBitmap(sceneW, sceneH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, result)

        // ---- 规格 7：局部泊松 —— 核心区写回奶蛙原像素 ----
        // seamlessClone 的求解 mask 仍是完整前景（梯度场连续、颜色不失真），但输出里
        // "距掩膜边界 ≥4px"的核心区被替换回奶蛙原像素 —— 核心区绝不经泊松，
        // 高频细节与线稿得以保留；core↔ring 用 2px alpha 过渡带拼接。
        val poissonPx = IntArray(sceneW * sceneH)
        result.getPixels(poissonPx, 0, sceneW, 0, 0, sceneW, sceneH)
        val frogPx = IntArray(sceneW * sceneH)
        placedFrog.getPixels(frogPx, 0, sceneW, 0, 0, sceneW, sceneH)
        val seamPx = RingSeam.apply(
            sceneMask = sceneMask,
            poissonPixels = poissonPx,
            frogPixels = frogPx,
            width = sceneW,
            height = sceneH,
        )
        result.setPixels(seamPx, 0, sceneW, 0, 0, sceneW, sceneH)

        // ---- 释放所有原生缓冲（Mat 不受 GC 管理，漏掉就是内存泄漏） ----
        listOf(
            sceneMat, sceneRgb, sceneWork, maskFull, maskWork,
            srcMat, srcRgb, cloneResult, full, rgba,
        ).forEach { runCatching { it.release() } }
        if (srcWork !== srcFull) srcWork.recycle()
        srcFull.recycle()

        return result
    }

    /** 掩膜包围盒 `[left, top, right, bottom]`；前景为空时返回 null。 */
    private fun foregroundBounds(mask: IntArray, width: Int, height: Int): IntArray? {
        var left = width
        var top = -1
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            var rowMin = -1
            var rowMax = -1
            for (x in 0 until width) {
                if (mask[row + x] >= 16) {
                    if (rowMin < 0) rowMin = x
                    rowMax = x
                }
            }
            if (rowMin >= 0) {
                if (top < 0) top = y
                bottom = y
                if (rowMin < left) left = rowMin
                if (rowMax > right) right = rowMax
            }
        }
        if (right < 0) return null
        // 外扩 2px：让掩膜边缘不贴到裁剪框上，seamlessClone 对贴边掩膜容易出问题
        return intArrayOf(
            (left - 2).coerceAtLeast(0),
            (top - 2).coerceAtLeast(0),
            (right + 3).coerceAtMost(width),
            (bottom + 3).coerceAtMost(height),
        )
    }

    /** 把 0~255 的掩膜数组转成 CV_8UC1 的 Mat。 */
    private fun maskToMat(mask: IntArray, width: Int, height: Int): Mat {
        val mat = Mat(height, width, CvType.CV_8UC1)
        val bytes = ByteArray(width * height)
        for (i in bytes.indices) {
            bytes[i] = mask[i].coerceIn(0, 255).toByte()
        }
        mat.put(0, 0, bytes)
        return mat
    }
}
