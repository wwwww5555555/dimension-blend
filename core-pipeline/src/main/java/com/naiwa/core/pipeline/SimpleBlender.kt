package com.naiwa.core.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import com.naiwa.core.common.model.Placement
import com.naiwa.core.common.util.PlacementTransforms

/**
 * P0 的融合实现：**直接贴图**（Canvas + Matrix），不引入 OpenCV。
 *
 * 它与 P2 的 `PoissonBlender` 实现同一个契约（输入输出都是位图），
 * 因此在 P2 接入 OpenCV 时只需替换 [com.naiwa.core.common.pipeline.ImagePipeline] 的实现，
 * 编辑页一行代码都不用改。
 *
 * 已知不足（P0 明确接受）：奶蛙是"贴上去"而不是"融进去"——没有颜色协调、没有方向性阴影、
 * 边缘只有 alpha 羽化没有泊松融合。这是 P1/P2 要解决的问题。
 */
object SimpleBlender {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 把 [frog] 按 [placement] 画到 [scene] 的**副本**上。
     *
     * @param scene 场景位图（不会被修改）
     * @param frog  奶蛙位图，必须带 alpha
     * @return 新的合成位图（ARGB_8888）。调用方负责回收。
     */
    fun blend(
        scene: Bitmap,
        frog: Bitmap,
        placement: Placement,
        backgroundArgb: Int = 0xFF000000.toInt(),
    ): Bitmap {
        val output = createOutputLike(scene, backgroundArgb)
        val canvas = Canvas(output)

        // 清成不透明底色：如果场景位图本身带 alpha（少数相册图），直接画会让四角透出黑边
        canvas.drawColor(backgroundArgb, PorterDuff.Mode.SRC)

        canvas.drawBitmap(scene, 0f, 0f, paint)

        val matrix = PlacementTransforms.matrixFor(
            placement = placement,
            sceneWidth = scene.width,
            sceneHeight = scene.height,
            frogWidth = frog.width,
            frogHeight = frog.height,
        )
        // FILTER_BITMAP_FLAG + ANTI_ALIAS 保证缩放/旋转后的奶蛙边缘平滑
        canvas.drawBitmap(frog, matrix, paint)
        return output
    }

    /**
     * 只做变换、不合成场景：把奶蛙按摆放参数画到**场景尺寸**的透明位图上。
     *
     * 这是"把奶蛙搬进场景坐标系"的标准做法，P1/P2 的融合全都依赖它：
     *  - 掩膜直接从它的 alpha 取，天然与场景同坐标系（旋转/缩放都已经被应用）；
     *  - 泊松融合、拉普拉斯融合都以它为 src，避免"旋转后位置错位"这个经典坑。
     */
    fun renderFrogOnly(
        frog: Bitmap,
        placement: Placement,
        sceneWidth: Int,
        sceneHeight: Int,
    ): Bitmap {
        val output = Bitmap.createBitmap(
            sceneWidth.coerceAtLeast(1),
            sceneHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        val matrix = PlacementTransforms.matrixFor(
            placement = placement,
            sceneWidth = sceneWidth,
            sceneHeight = sceneHeight,
            frogWidth = frog.width,
            frogHeight = frog.height,
        )
        canvas.drawBitmap(frog, matrix, paint)
        return output
    }

    /**
     * 把已经处于场景坐标系的图层叠到基础图上（不再应用任何变换）。
     * 用于"羽化直贴"：overlay 是已经旋转缩放好的奶蛙图层。
     */
    fun composite(base: Bitmap, overlay: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(
            base.width.coerceAtLeast(1),
            base.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        canvas.drawColor(0, PorterDuff.Mode.SRC)
        canvas.drawBitmap(base, 0f, 0f, paint)
        canvas.drawBitmap(overlay, 0f, 0f, paint)
        return output
    }

    private fun createOutputLike(source: Bitmap, backgroundArgb: Int): Bitmap =
        if (backgroundArgb == 0) {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
                setHasAlpha(true)
            }
        } else {
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        }
}
