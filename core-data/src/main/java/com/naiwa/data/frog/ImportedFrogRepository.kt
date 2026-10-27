package com.naiwa.data.frog

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.naiwa.core.common.image.BitmapDecoder
import com.naiwa.core.common.model.FrogAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户导入的奶蛙素材仓库。
 *
 * 存储结构（全部在应用私有目录，卸载即清除，不会污染用户相册）：
 * ```
 * filesDir/frogs/imported/
 *   ├─ 1730000000000.png      抠好 alpha 的奶蛙图
 *   ├─ 1730000000001.png
 *   └─ index.json             索引：文件名 / 显示名 / 授权 / 是否带 alpha
 * ```
 *
 * 本类**不做任何图像计算**（抠图由 `ImagePipeline.prepareFrog` 负责），只负责落盘与索引，
 * 这是「:data 只做数据来源」这条依赖纪律的体现。
 *
 * 授权说明：导入素材一律登记为 `self-drawn`（用户自己拍/自己画）。
 * 这条不适用于内置素材 —— 内置素材必须逐条在 `assets/frogs/manifest.json` 中人工登记。
 */
@Singleton
class ImportedFrogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dir: File get() = File(context.filesDir, "$IMPORTED_DIR_NAME").apply { mkdirs() }
    private val indexFile: File get() = File(dir, INDEX_FILE_NAME)

    /** 列出全部已导入素材（新的在前）。 */
    suspend fun listImported(): List<FrogAsset> = withContext(Dispatchers.IO) {
        readIndex().asReversed().mapNotNull { entry ->
            val file = File(dir, entry.file)
            if (!file.exists()) {
                Log.w(TAG, "索引里有 ${entry.file} 但文件不存在，已跳过")
                null
            } else {
                FrogAsset(
                    id = FrogAsset.IMPORTED_ID_PREFIX + entry.file,
                    displayName = entry.displayName,
                    uriString = file.toURI().toString(),
                    license = FrogAsset.License.SELF_DRAWN,
                    hasAlpha = entry.hasAlpha,
                    builtIn = false,
                    attribution = "用户导入",
                )
            }
        }
    }

    /**
     * 保存一张准备好的奶蛙图（已带 alpha）。
     *
     * @param bitmap 已经抠好、带 alpha 的位图
     * @param displayName 展示名
     * @param hasAlpha 抠图后是否确实具备透明通道
     * @return 写入成功返回素材信息；写盘失败返回 null（不抛异常，由调用方提示）
     */
    suspend fun save(bitmap: Bitmap, displayName: String, hasAlpha: Boolean): FrogAsset? =
        withContext(Dispatchers.IO) {
            val fileName = "${System.currentTimeMillis()}.png"
            val target = File(dir, fileName)
            try {
                target.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
                        Log.w(TAG, "PNG 编码失败: $fileName")
                        return@withContext null
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "写入导入素材失败: ${e.message}")
                target.delete()
                return@withContext null
            }

            val entries = readIndex().toMutableList()
            entries += IndexEntry(
                file = fileName,
                displayName = displayName.ifBlank { "导入素材" },
                hasAlpha = hasAlpha,
                addedAt = System.currentTimeMillis(),
            )
            writeIndex(entries)

            FrogAsset(
                id = FrogAsset.IMPORTED_ID_PREFIX + fileName,
                displayName = displayName.ifBlank { "导入素材" },
                uriString = target.toURI().toString(),
                license = FrogAsset.License.SELF_DRAWN,
                hasAlpha = hasAlpha,
                builtIn = false,
                attribution = "用户导入",
            )
        }

    /** 加载导入素材的位图。 */
    suspend fun loadBitmap(assetId: String, maxShortSide: Int = 1024): Bitmap? =
        withContext(Dispatchers.IO) {
            val fileName = assetId.removePrefix(FrogAsset.IMPORTED_ID_PREFIX)
            if (fileName == assetId) return@withContext null
            val file = File(dir, fileName)
            if (!file.exists()) return@withContext null
            BitmapDecoder.decodeFile(file, maxShortSide)
        }

    /** 删除导入素材（同时清理索引与文件）。 */
    suspend fun delete(assetId: String): Boolean = withContext(Dispatchers.IO) {
        val fileName = assetId.removePrefix(FrogAsset.IMPORTED_ID_PREFIX)
        if (fileName == assetId) return@withContext false
        val removed = File(dir, fileName).delete()
        val entries = readIndex().filterNot { it.file == fileName }
        writeIndex(entries)
        removed
    }

    // ------------------------------------------------------------ 边缘预处理版本

    /**
     * 该素材是否还需要做边缘预处理（规格 6.1~6.4）。
     *
     * 存在的理由：alpha 柔化**不幂等** —— 反复柔化会把边缘越磨越虚。
     * 导入时已经处理过并写了版本号，所以之后每次选中都应跳过；
     * 而**旧版本导入的素材**（索引里没有该字段 → 读作 0）会在第一次选中时补做一次。
     */
    suspend fun needsEdgePrep(assetId: String): Boolean = withContext(Dispatchers.IO) {
        val fileName = assetId.removePrefix(FrogAsset.IMPORTED_ID_PREFIX)
        if (fileName == assetId) return@withContext false
        val entry = readIndex().firstOrNull { it.file == fileName } ?: return@withContext false
        entry.edgePrepVersion < EDGE_PREP_VERSION
    }

    /** 记录"该素材已完成边缘预处理"。 */
    suspend fun markEdgePrepared(assetId: String) = withContext(Dispatchers.IO) {
        val fileName = assetId.removePrefix(FrogAsset.IMPORTED_ID_PREFIX)
        if (fileName == assetId) return@withContext
        val entries = readIndex().map { entry ->
            if (entry.file == fileName) entry.copy(edgePrepVersion = EDGE_PREP_VERSION) else entry
        }
        writeIndex(entries)
    }

    // ------------------------------------------------------------ 索引读写

    private data class IndexEntry(
        val file: String,
        val displayName: String,
        val hasAlpha: Boolean,
        val addedAt: Long,
        /** 边缘预处理版本；0 = 未处理（含旧版本写入的索引）。 */
        val edgePrepVersion: Int = 0,
    )

    private fun readIndex(): List<IndexEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val array = root.optJSONArray("items") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val file = obj.optString("file", "")
                if (file.isBlank()) return@mapNotNull null
                IndexEntry(
                    file = file,
                    displayName = obj.optString("name", "导入素材"),
                    hasAlpha = obj.optBoolean("hasAlpha", true),
                    addedAt = obj.optLong("addedAt", 0L),
                    edgePrepVersion = obj.optInt("edgePrepVersion", 0),
                )
            }.sortedBy { it.addedAt }
        } catch (t: Throwable) {
            Log.w(TAG, "导入素材索引损坏，已重建: ${t.message}")
            emptyList()
        }
    }

    private fun writeIndex(entries: List<IndexEntry>) {
        val array = JSONArray()
        entries.sortedBy { it.addedAt }.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("file", e.file)
                    put("name", e.displayName)
                    put("hasAlpha", e.hasAlpha)
                    put("addedAt", e.addedAt)
                    put("edgePrepVersion", e.edgePrepVersion)
                    put("license", FrogAsset.License.SELF_DRAWN.id)
                },
            )
        }
        try {
            indexFile.writeText(JSONObject().put("version", 1).put("items", array).toString())
        } catch (e: IOException) {
            Log.w(TAG, "写入导入素材索引失败: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "NaiwaCamera"
        const val IMPORTED_DIR_NAME = "frogs/imported"
        const val INDEX_FILE_NAME = "index.json"
        /** PNG 质量参数对 PNG 无效（PNG 无损），传 100 仅为接口占位。 */
        const val PNG_QUALITY = 100

        /**
         * 当前边缘预处理版本。算法变更（改半径、加步骤）时递增，
         * 已导入的素材会在下次选中时**自动补做一次**。
         */
        const val EDGE_PREP_VERSION = 1
    }
}
