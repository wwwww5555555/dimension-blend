package com.naiwa.core.common.model

/**
 * 奶蛙素材的元信息。
 *
 * 授权门控是本项目的硬性合规要求（架构文档 §4 / data/assets/frogs/README.md）：
 * **只有 [License] 能解析出来的素材才会被加载**，其余条目在仓库层直接丢弃并打 warning，
 * 绝不进入 UI 列表，避免"手滑把网络上抓的图打进包里"。
 *
 * @param id           稳定唯一标识。内置素材用 `builtin:<fileName>`，导入素材用 `imported:<时间戳>`
 * @param displayName   UI 展示名
 * @param uriString     素材内容位置：内置为 `file:///android_asset/frogs/xxx.png`，导入为 `content://` 或私有目录 file uri
 * @param license       授权类型，见 [License]
 * @param hasAlpha      是否已带有效 alpha 通道。false 时进入编辑页前必须先走抠图流程
 * @param builtIn       是否随包内置（决定是否允许"删除"）
 * @param attribution   署名/来源备注，展示在素材详情里（CC0 也建议标注来源）
 */
data class FrogAsset(
    val id: String,
    val displayName: String,
    val uriString: String,
    val license: License,
    val hasAlpha: Boolean,
    val builtIn: Boolean,
    val attribution: String? = null,
) {

    /**
     * 允许内置的授权类型。**枚举是白名单**：manifest.json 里出现其他值一律不加载。
     *
     * - [CC0]：公共领域/CC0 协议素材，可商用可再分发
     * - [SELF_DRAWN]：用户自绘或自有版权素材
     */
    enum class License(val id: String) {
        CC0("CC0"),
        SELF_DRAWN("self-drawn"),
        ;

        companion object {
            /**
             * 解析 license 字符串（大小写与首尾空格不敏感）。
             * @return 合法返回对应枚举；缺失或非法返回 `null` —— 调用方必须据此**跳过该素材**
             */
            fun fromId(raw: String?): License? {
                val v = raw?.trim()?.lowercase() ?: return null
                return entries.firstOrNull { it.id.lowercase() == v }
            }
        }
    }

    companion object {
        const val BUILTIN_ID_PREFIX = "builtin:"
        const val IMPORTED_ID_PREFIX = "imported:"
    }
}
