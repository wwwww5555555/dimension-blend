package com.naiwa.data.frog

import com.naiwa.core.common.model.FrogAsset
import org.json.JSONObject

/**
 * `assets/frogs/manifest.json` 的一条素材登记。
 *
 * @param file        文件名（只允许纯文件名，禁止任何路径分隔符）
 * @param displayName UI 展示名
 * @param license     授权类型，**必须**能从 [FrogAsset.License] 解析出来，否则该条目不会出现在本对象里
 * @param author      作者/作者来源
 * @param source      获取方式或生成脚本，便于追溯
 */
data class FrogManifestEntry(
    val file: String,
    val displayName: String,
    val license: FrogAsset.License,
    val author: String? = null,
    val source: String? = null,
) {
    /** 素材在 assets 中的完整路径，例如 `frogs/frog_classic_01.png`。 */
    val assetPath: String get() = "${FrogManifest.ASSET_DIR}/$file"

    /** 内置素材的稳定 id。 */
    val assetId: String get() = FrogAsset.BUILTIN_ID_PREFIX + file
}

/**
 * manifest.json 解析器（纯逻辑，可 JVM 单测）。
 *
 * 合规要求（架构文档 §4、需求硬约束 4）：
 * **没有 license 或 license 非法的素材一律不加载**，而不是"加载了再提示"。
 * 因此解析器的返回值里不存在"无 license 的条目"这种状态 —— 它在类型层面就被排除了。
 *
 * 容错策略：单条非法只跳过该条并记 warning；整个文件解析失败返回空列表 + 一条 warning，
 * **绝不抛异常**（素材加载失败不应该让拍照主流程崩掉）。
 */
object FrogManifest {

    /** 素材所在 assets 子目录。 */
    const val ASSET_DIR = "frogs"

    /** 清单文件名。 */
    const val FILE_NAME = "manifest.json"

    /** 清单完整路径。 */
    const val ASSET_PATH = "$ASSET_DIR/$FILE_NAME"

    private const val KEY_ASSETS = "assets"
    private const val KEY_FILE = "file"
    private const val KEY_NAME = "name"
    private const val KEY_LICENSE = "license"
    private const val KEY_AUTHOR = "author"
    private const val KEY_SOURCE = "source"

    /** 只接受 PNG：透明通道是奶蛙素材的硬性要求。 */
    private const val REQUIRED_SUFFIX = ".png"

    /**
     * 解析结果。
     * @param entries 通过全部校验的登记项（保证 license 合法、文件名合法、无重复）
     * @param warnings 每个被跳过的条目的原因，调用方应写进 logcat（tag: NaiwaCamera）
     */
    data class Outcome(
        val entries: List<FrogManifestEntry>,
        val warnings: List<String>,
    )

    /**
     * 解析 manifest.json。
     * @param json 文件原文
     * @return 合法条目 + 警告列表；输入为空/非法时返回空列表，不抛异常
     */
    fun parse(json: String): Outcome {
        if (json.isBlank()) {
            return Outcome(emptyList(), listOf("$ASSET_PATH 内容为空"))
        }

        val entries = mutableListOf<FrogManifestEntry>()
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return Outcome(emptyList(), listOf("$ASSET_PATH 不是合法 JSON：${t.message}"))
        }

        val array = root.optJSONArray(KEY_ASSETS)
        if (array == null) {
            return Outcome(emptyList(), listOf("$ASSET_PATH 缺少 \"$KEY_ASSETS\" 数组"))
        }

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item == null) {
                warnings += "assets[$i] 不是 JSON 对象，已跳过"
                continue
            }

            val file = item.optString(KEY_FILE, "").trim()
            if (file.isEmpty()) {
                warnings += "assets[$i] 缺少 \"$KEY_FILE\" 字段，已跳过"
                continue
            }
            if (!file.endsWith(REQUIRED_SUFFIX, ignoreCase = true)) {
                warnings += "$file 不是 PNG，已跳过（奶蛙素材必须带透明通道）"
                continue
            }
            // 防目录穿越：manifest 是随包资源，但仍不允许出现路径分隔符，避免读到 assets 之外
            if (file.contains('/') || file.contains('\\') || file.contains("..")) {
                warnings += "$file 文件名非法（含路径分隔符），已跳过"
                continue
            }
            if (!seen.add(file)) {
                warnings += "$file 重复登记，已跳过后续重复项"
                continue
            }

            val licenseRaw = item.optString(KEY_LICENSE, "")
            val license = FrogAsset.License.fromId(licenseRaw)
            if (license == null) {
                warnings += "$file license=\"$licenseRaw\" 缺失或非法" +
                    "（只允许 ${FrogAsset.License.entries.joinToString(" / ") { it.id }}），已跳过"
                continue
            }

            entries += FrogManifestEntry(
                file = file,
                displayName = item.optString(KEY_NAME, "").ifBlank { file.substringBeforeLast('.') },
                license = license,
                author = item.optString(KEY_AUTHOR, "").ifBlank { null },
                source = item.optString(KEY_SOURCE, "").ifBlank { null },
            )
        }

        return Outcome(entries, warnings)
    }
}
