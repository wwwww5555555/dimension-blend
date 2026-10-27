package com.naiwa.data.source

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.naiwa.core.common.image.BitmapDecoder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 场景图加载器：把「相机拍的文件 / 相册选的 Uri」统一变成**已校正方向、已是 sRGB** 的位图。
 *
 * 这一层存在的意义是把「不同来源的差异」挡在数据层内部：
 * 相机给的是 File（带 EXIF 方向），相册给的是 content:// Uri（可能来自云盘、可能是 HEIC），
 * 上层编辑页只看到「一张正确的位图」。
 */
@Singleton
class SceneSourceLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 从相册 Uri 加载。
     * @param maxShortSide 短边上限。编辑预览用 1600 足够，导出时会按需重新解码原图
     */
    suspend fun loadFromUri(uri: Uri, maxShortSide: Int = DEFAULT_MAX_SHORT_SIDE): Bitmap? =
        withContext(Dispatchers.IO) {
            BitmapDecoder.decodeUri(context, uri, maxShortSide)
        }

    /** 从相机输出文件加载。 */
    suspend fun loadFromFile(file: File, maxShortSide: Int = DEFAULT_MAX_SHORT_SIDE): Bitmap? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            BitmapDecoder.decodeFile(file, maxShortSide)
        }

    companion object {
        /**
         * 编辑预览的默认短边上限。
         * 1600 是权衡结果：再大对预览观感提升有限，但内存与合成耗时线性上升。
         */
        const val DEFAULT_MAX_SHORT_SIDE = 1600
    }
}
