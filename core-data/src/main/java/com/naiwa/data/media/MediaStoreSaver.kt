package com.naiwa.data.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把成品写入系统相册 + 分享。
 *
 * 两套实现，按系统版本分流（这一点写错会直接导致 Android 8/9 上保存失败）：
 *
 * - **Android 10（API 29）+**：走 MediaStore + `RELATIVE_PATH`，**不需要任何存储权限**。
 *   写入期间用 `IS_PENDING=1` 占位，写完置 0 —— 否则相册可能在图片还没写完时就索引到半张图。
 * - **Android 9（API 28）及以下**：MediaStore 不支持 RELATIVE_PATH，只能先落盘到公共 Pictures 目录，
 *   再用 MediaScanner 通知相册。这条路径**需要 WRITE_EXTERNAL_STORAGE**（Manifest 已声明 maxSdkVersion=28，
 *   运行时权限由编辑页在保存前申请）。
 */
@Singleton
class MediaStoreSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 保存结果。 */
    data class SaveResult(val uri: Uri, val displayName: String, val bytes: Long)

    /**
     * 保存到相册。
     *
     * @param bitmap 成品位图；保存前会统一按 sRGB 编码（架构文档 §6.7）
     * @param displayName 文件名（不含扩展名）；不传则按时间戳生成
     * @return 成功返回 uri 与文件名；失败返回 null（不抛异常）
     */
    suspend fun saveToGallery(
        bitmap: Bitmap,
        displayName: String? = null,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    ): SaveResult? = withContext(Dispatchers.IO) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: defaultName()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, name, jpegQuality)
            } else {
                saveViaPublicDirectory(bitmap, name, jpegQuality)
            }
        } catch (e: Exception) {
            // 包含 SecurityException（API<=28 未授予写权限）与 IOException
            Log.w(TAG, "保存到相册失败: ${e.message}")
            null
        }
    }

    /** 构造分享 Intent（分享到其他 App）。 */
    fun buildShareIntent(uri: Uri, mimeType: String = "image/jpeg", text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            // 相册 Uri 是内容 Uri，必须显式授权，否则接收方拿到的是"无权限"的链接
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** 是否需要在保存前申请旧版写外部存储权限（API <= 28）。 */
    val requiresLegacyWritePermission: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    val legacyWritePermission: String
        get() = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

    // ------------------------------------------------------------ 实现

    private fun saveViaMediaStore(bitmap: Bitmap, name: String, quality: Int): SaveResult? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_JPEG)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            // 占位写入：写完之前不给相册看到半张图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: return null

        var written = 0L
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.flush()
            written = 0L
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }

        // 清理占位标记
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)

        val fileName = "$name.jpg"
        return SaveResult(uri = uri, displayName = fileName, bytes = written)
    }

    private fun saveViaPublicDirectory(bitmap: Bitmap, name: String, quality: Int): SaveResult? {
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val folder = File(pictures, ALBUM_NAME).apply { if (!exists()) mkdirs() }
        val file = File(folder, "$name.jpg")

        try {
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    Log.w(TAG, "JPEG 编码失败")
                    return null
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "写入公共目录失败: ${e.message}")
            return null
        }

        // 通知媒体库扫描；异步回调，这里不阻塞
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(MIME_JPEG),
            null,
        )
        val uri = Uri.fromFile(file)
        return SaveResult(uri = uri, displayName = file.name, bytes = file.length())
    }

    private fun defaultName(): String =
        "NaiwaCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG = "NaiwaCamera"
        private const val MIME_JPEG = "image/jpeg"

        /** 相册里的子目录。 */
        const val ALBUM_NAME = "奶蛙相机"

        /**
         * MediaStore 的相对路径。
         * 注意不能用 const：Environment.DIRECTORY_PICTURES 是 Java 静态字段，
         * Kotlin 不把它当作编译期常量，写成 const val 会编译失败。
         */
        private val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"

        /**
         * JPEG 质量。95 是"肉眼看不出压缩痕迹"的常用上限，
         * 再往上文件体积涨得快但没有可见收益（融合后的软过渡区最经不起低质量压缩）。
         */
        const val DEFAULT_JPEG_QUALITY = 95
    }
}
