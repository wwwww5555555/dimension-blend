package com.naiwa.core.common.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.naiwa.core.common.model.Placement

/**
 * 摆放参数 → 位图变换矩阵。
 *
 * 这是「手势/滑杆」与「像素」之间唯一的换算入口，预览与导出共用，
 * 保证所见即所得（预览用低清位图、导出用全分辨率位图，同一个 Placement 得到相同构图）。
 *
 * 变换顺序固定为 **平移到目标中心 → 旋转 → 缩放 → 居中到自身**：
 * 位图默认以左上角为原点，所以要先把原点搬到青蛙中心（-w/2, -h/2），
 * 否则旋转会绕左上角转，用户会看到青蛙"甩出去"。
 *
 * @param sceneWidth  场景位图宽度（决定 scale 的参考基准）
 * @param sceneHeight 场景位图高度
 * @param frogWidth   奶蛙位图原始宽度
 * @param frogHeight  奶蛙位图原始高度
 */
object PlacementTransforms {

    fun matrixFor(
        placement: Placement,
        sceneWidth: Int,
        sceneHeight: Int,
        frogWidth: Int,
        frogHeight: Int,
    ): Matrix {
        val p = placement.clamped()
        val m = Matrix()

        // 目标宽度按「场景短边 × scale」计算；高度按原图长宽比等比缩放，避免奶蛙被拉变形
        val targetW = p.targetWidthPx(sceneWidth, sceneHeight).toFloat()
        val aspect = if (frogWidth > 0) frogHeight.toFloat() / frogWidth.toFloat() else 1f

        m.postTranslate(-frogWidth / 2f, -frogHeight / 2f)          // 1. 原点搬到图像中心
        m.postRotate(p.rotationDeg)                                  // 2. 绕中心旋转
        m.postScale(targetW / frogWidth.coerceAtLeast(1), targetW * aspect / frogHeight.coerceAtLeast(1)) // 3. 等比缩放
        if (p.flipHorizontal) m.postScale(-1f, 1f)                   // 4. 可选镜像（在缩放之后，避免负缩放翻转长宽比）
        m.postTranslate(p.centerX * sceneWidth, p.centerY * sceneHeight) // 5. 平移到目标位置
        return m
    }

    /**
     * 奶蛙在场景中的外接矩形 `[left, top, right, bottom]`（像素，可能超出画面边界）。
     *
     * 用途：颜色协调只应参考"奶蛙所处位置的局部背景"，
     * 场景统计若用整图会被大片天空/墙面主导，得到的不是奶蛙真正受到的光照。
     */
    fun boundsFor(
        placement: Placement,
        sceneWidth: Int,
        sceneHeight: Int,
        frogWidth: Int,
        frogHeight: Int,
    ): IntArray {
        val matrix = matrixFor(placement, sceneWidth, sceneHeight, frogWidth, frogHeight)
        val rect = RectF(0f, 0f, frogWidth.toFloat(), frogHeight.toFloat())
        matrix.mapRect(rect)
        return intArrayOf(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        )
    }

    /**
     * 把屏幕手势（像素位移/缩放/旋转）换算成归一化 [Placement] 增量。
     *
     * @param panX        屏幕横向位移（px）
     * @param panY        屏幕纵向位移（px）
     * @param zoom        双指缩放倍率（1.0 = 不变）
     * @param rotationDeg 旋转角度增量（度，顺时针为正）
     * @param viewWidth   预览控件宽度（px），用于把位移归一化
     * @param viewHeight  预览控件高度（px）
     */
    fun applyGesture(
        current: Placement,
        panX: Float,
        panY: Float,
        zoom: Float,
        rotationDeg: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): Placement {
        val w = viewWidth.coerceAtLeast(1).toFloat()
        val h = viewHeight.coerceAtLeast(1).toFloat()
        return current.copy(
            centerX = current.centerX + panX / w,
            centerY = current.centerY + panY / h,
            scale = current.scale * zoom,
            rotationDeg = current.rotationDeg + rotationDeg,
        ).clamped()
    }
}

/**
 * 位图安全检查与释放工具。
 * 大图处理最容易踩的两个坑：① 对已回收位图取像素导致崩溃；② 忘记回收导致 OOM。
 */
object BitmapGuard {

    /** 位图是否可用（非空、未回收、尺寸有效）。 */
    fun isUsable(bitmap: Bitmap?): Boolean =
        bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0

    /** 安全回收：已回收或为 null 时静默返回。 */
    fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    /**
     * 按「长边不超过 maxShortSide」计算采样率（inSampleSize）。
     * 采样率只取 2 的幂，这是 BitmapFactory 的最优取值（它内部也是这么取整的）。
     *
     * @param srcWidth    原图宽
     * @param srcHeight   原图高
     * @param maxShortSide 允许的最大短边（例如预览 720、导出 Int.MAX_VALUE 表示不缩）
     */
    fun sampleSizeFor(srcWidth: Int, srcHeight: Int, maxShortSide: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxShortSide <= 0) return 1
        val shortSide = minOf(srcWidth, srcHeight)
        if (shortSide <= maxShortSide) return 1
        var sample = 1
        while (shortSide / (sample * 2) >= maxShortSide) {
            sample *= 2
        }
        return sample
    }
}
