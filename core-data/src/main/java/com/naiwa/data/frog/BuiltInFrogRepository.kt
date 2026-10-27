package com.naiwa.data.frog

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.naiwa.core.common.image.BitmapDecoder
import com.naiwa.core.common.model.FrogAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置奶蛙素材仓库。
 *
 * ⚠️ **本应用默认不内置任何素材**：`assets/frogs/manifest.json` 的 `assets` 是空数组，
 * 因此 [listAssets] 正常返回空列表，用户改为从相册导入奶蛙图。
 * "清单为空"是**预期状态而不是故障**，所以走 `Log.i` 而不是 `Log.w` ——
 * 否则每台设备每次冷启动都会打一条看起来像错误的日志。
 *
 * 合规红线（重新加入内置素材时生效）：**只加载 `manifest.json` 里登记过、且 license 合法的素材**。
 * 具体来说，本类做四道过滤，任何一道不过就跳过并打 warning：
 *  1. license 必须能解析成 [FrogAsset.License]（`CC0` / `self-drawn`）—— 由 [FrogManifest.parse] 保证；
 *  2. 文件必须真实存在于 assets（清单先写好、图后放的情况很常见，不能因此崩溃）；
 *  3. 文件必须能被解码（PNG 损坏时不要让整个素材列表挂掉）；
 *  4. 解码失败/为空的一律不进列表。
 *
 * 缓存策略：素材列表在进程内缓存一次（读 assets 是 IO，列表页会频繁重建）；
 * 位图不缓存（一张 1MB 级别，交给 Composable 自己的生命周期管理，避免全局持有大图）。
 */
@Singleton
class BuiltInFrogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cachedAssets: List<FrogAsset>? = null

    /**
     * 列出全部可用的内置素材。
     * 首次调用会读 assets 并解析清单，之后走缓存。
     */
    suspend fun listAssets(): List<FrogAsset> = withContext(Dispatchers.IO) {
        cachedAssets?.let { return@withContext it }

        val manifestText = readAssetText(FrogManifest.ASSET_PATH)
        if (manifestText == null) {
            // 清单文件本身缺失属于打包异常（空清单应当是"文件在、assets 为空数组"）
            Log.w(TAG, "未找到 ${FrogManifest.ASSET_PATH}，内置素材列表为空")
            cachedAssets = emptyList()
            return@withContext emptyList()
        }

        val outcome = FrogManifest.parse(manifestText)
        outcome.warnings.forEach { Log.w(TAG, "manifest 警告: $it") }

        if (outcome.entries.isEmpty()) {
            // 预期路径：本应用不内置素材，素材全部来自用户相册导入
            Log.i(TAG, "内置素材清单为空（预期）：素材由用户从相册导入")
            cachedAssets = emptyList()
            return@withContext emptyList()
        }

        val availableFiles = listAssetFiles()
        val result = ArrayList<FrogAsset>(outcome.entries.size)
        for (entry in outcome.entries) {
            if (entry.file !in availableFiles) {
                Log.w(TAG, "${entry.file} 已在 manifest 登记但 assets 中不存在，已跳过")
                continue
            }
            val bitmap = BitmapDecoder.decodeAsset(context, entry.assetPath, THUMBNAIL_SHORT_SIDE)
            if (bitmap == null) {
                Log.w(TAG, "${entry.file} 解码失败（文件可能损坏），已跳过")
                continue
            }
            val hasAlpha = hasAlphaChannel(bitmap)
            bitmap.recycle()

            result += FrogAsset(
                id = entry.assetId,
                displayName = entry.displayName,
                uriString = "file:///android_asset/${entry.assetPath}",
                license = entry.license,
                hasAlpha = hasAlpha,
                builtIn = true,
                attribution = listOfNotNull(entry.author, entry.source).joinToString(" · ")
                    .ifBlank { null },
            )
        }

        Log.i(TAG, "内置素材加载完成：${result.size} 个可用 / 清单登记 ${outcome.entries.size} 条")
        cachedAssets = result
        result
    }

    /**
     * 加载素材位图。
     * @param assetId [FrogAsset.id]，形如 `builtin:frog_classic_01.png`
     * @param maxShortSide 短边上限，默认 1024（素材规范上限，超过无收益）
     */
    suspend fun loadFrogBitmap(assetId: String, maxShortSide: Int = FROG_SHORT_SIDE): Bitmap? =
        withContext(Dispatchers.IO) {
            val fileName = assetId.removePrefix(FrogAsset.BUILTIN_ID_PREFIX)
            if (fileName == assetId) {
                Log.w(TAG, "不是内置素材 id: $assetId")
                return@withContext null
            }
            BitmapDecoder.decodeAsset(context, "${FrogManifest.ASSET_DIR}/$fileName", maxShortSide)
        }

    /** 让下次调用重新扫一遍素材（用户在新版本里加了素材时用）。 */
    fun invalidate() {
        cachedAssets = null
    }

    // ------------------------------------------------------------ 内部

    private fun readAssetText(path: String): String? = try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: IOException) {
        null
    }

    /** 列出 assets/frogs 下真实存在的文件（清单与文件可能不同步）。 */
    private fun listAssetFiles(): Set<String> = try {
        context.assets.list(FrogManifest.ASSET_DIR)?.toSet() ?: emptySet()
    } catch (e: IOException) {
        emptySet()
    }

    /** 判断素材是否真的带透明通道（用 alpha 直方图，而不是"配置里有没有 alpha"）。 */
    private fun hasAlphaChannel(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val row = IntArray(width)
        var y = 0
        val step = maxOf(1, height / 64)
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((row[x] ushr 24) < 250) return true
            }
            y += step
        }
        return false
    }

    private companion object {
        const val TAG = "NaiwaCamera"
        /** 内置素材规范：长边 512~1024，这里按短边 1024 上限解码。 */
        const val FROG_SHORT_SIDE = 1024
        const val THUMBNAIL_SHORT_SIDE = 256
    }
}
