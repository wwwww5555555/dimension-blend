package com.naiwa.core.common.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.naiwa.core.common.util.BitmapGuard
import java.io.File
import java.io.InputStream

/**
 * 统一解码入口 —— 位图从哪来都必须过这里。
 *
 * 它解决四个「不做就出 bug」的问题：
 *  1. **内存**：先只读图片头拿到原始尺寸，再算 inSampleSize，否则 4000 万像素的照片直接 OOM；
 *  2. **方向**：相机和相册图都带 EXIF 方向，不校正会出现竖拍照片横着显示；
 *  3. **色域（请求侧）**：inPreferredColorSpace 请解码器直接输出 sRGB，
 *     避免 Display P3 输入造成整体色偏（见架构文档 §6.7 —— 必须在光照估计之前完成）；
 *  4. **色域（兜底侧）**：`inPreferredColorSpace` 只是**建议**，部分解码器（尤其 HEIC /
 *     厂商私有路径）会忽略它。所以解码结果会再过一道 [ensureSrgb] 实测校验，
 *     真的不是 sRGB 就重画一遍做转换 —— 光靠第 3 点不足以保证"进管线的图一定是 sRGB"。
 *
 * 所有方法都不抛异常，失败返回 null，由调用方给出用户可读的提示。
 */
object BitmapDecoder {

    /** 目标解码配置：ARGB_8888 才有 alpha 通道，奶蛙素材必须用它。 */
    private val CONFIG: Bitmap.Config = Bitmap.Config.ARGB_8888

    /**
     * 从任意 Uri 解码（content:// 与 file:// 都支持）。
     * @param maxShortSide 期望的短边上限；传大值表示按原尺寸解码
     */
    fun decodeUri(context: Context, uri: Uri, maxShortSide: Int): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = readBounds { options ->
                resolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } ?: return null

            val options = buildOptions(bounds, maxShortSide)
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null
            ensureSrgb(applyExifFromUri(context, uri, decoded))
        } catch (t: Throwable) {
            null
        }
    }

    /** 从文件解码（相机输出走这条）。 */
    fun decodeFile(file: File, maxShortSide: Int): Bitmap? {
        return try {
            if (!file.exists() || !file.canRead()) return null
            val bounds = readBounds { options ->
                BitmapFactory.decodeFile(file.absolutePath, options)
            } ?: return null

            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                buildOptions(bounds, maxShortSide),
            ) ?: return null
            ensureSrgb(applyExifFromFile(file, decoded))
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 assets 解码（内置奶蛙素材走这条；assets 里只有已登记 license 的 PNG）。 */
    fun decodeAsset(context: Context, assetPath: String, maxShortSide: Int): Bitmap? {
        return try {
            val bounds = readBounds { options ->
                context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } ?: return null

            val decoded = context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, buildOptions(bounds, maxShortSide))
            } ?: return null
            // assets 里的 PNG 无 EXIF，且不做旋转；色域仍要兜一道
            ensureSrgb(decoded)
        } catch (t: Throwable) {
            null
        }
    }

    /** 供测试与工具使用：把 InputStream 读成位图（不做 EXIF 处理）。 */
    fun decodeStream(stream: InputStream, maxShortSide: Int): Bitmap? {
        return try {
            val bytes = stream.readBytes()
            val bounds = readBounds { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } ?: return null
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, buildOptions(bounds, maxShortSide))
            decoded?.let { ensureSrgb(it) }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 把非 sRGB 的位图转成 sRGB（实测校验，而不是相信 inPreferredColorSpace）。
     *
     * 实现方式：新建一张 sRGB 位图，用 Canvas 把原图画上去 —— Canvas 的绘制会做
     * 色彩空间转换（Skia 在 target 的色域里重采样）。转换成功后**回收原图**，
     * 避免广色域大图在内存里留两份。
     *
     * @return 已是 sRGB（或拿不到色域信息）时**原样返回**，不产生多余拷贝
     */
    private fun ensureSrgb(src: Bitmap): Bitmap {
        val space = src.colorSpace
        if (space == null || space.isSrgb) return src
        return try {
            // API 26+：ARGB_8888 的新建位图默认色域即为 sRGB
            val out = Bitmap.createBitmap(src.width, src.height, CONFIG).apply {
                density = src.density
            }
            Canvas(out).drawBitmap(src, 0f, 0f, null)
            BitmapGuard.recycle(src)
            out
        } catch (t: Throwable) {
            // 转换失败时保住原图：色偏总比丢图好
            src
        }
    }

    /**
     * 只读图片头拿尺寸，不解码像素 —— 这是避免 OOM 的关键一步。
     *
     * @param decode 用 inJustDecodeBounds = true 的 Options 调一次 BitmapFactory；返回值忽略
     * @return 图片头有效时返回填好 outWidth/outHeight 的 Options，否则 null
     */
    private fun readBounds(decode: (BitmapFactory.Options) -> Unit): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(options)
        return if (options.outWidth > 0 && options.outHeight > 0) options else null
    }

    private fun buildOptions(bounds: BitmapFactory.Options, maxShortSide: Int): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inSampleSize = BitmapGuard.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxShortSide)
            inPreferredConfig = CONFIG
            inScaled = false
            // API 26+：让解码器直接把广色域输入转成 sRGB，从源头规避色偏
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }

    private fun applyExifFromUri(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return rotateIfNeeded(bitmap, orientation)
    }

    private fun applyExifFromFile(file: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (t: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return rotateIfNeeded(bitmap, orientation)
    }

    /** 按 EXIF 方向校正位图；不需要校正时原样返回（不产生多余拷贝）。 */
    private fun rotateIfNeeded(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        return try {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (rotated !== src) BitmapGuard.recycle(src)
            rotated
        } catch (t: Throwable) {
            src
        }
    }
}
