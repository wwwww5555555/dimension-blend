package com.naiwa.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.naiwa.core.common.model.FusionTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore 委托必须是**文件级**属性，否则每次创建 Repository 都会新建一个 DataStore 实例，
 * 同一个文件被多个实例打开会抛 IllegalStateException。
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "naiwa_settings")

/**
 * 用户设置。
 *
 * 刻意只存三样极小的东西 —— 设置项越多，越容易变成"用户不知道自己在哪一项改了什么东西"。
 * 真正需要记住的用户输入（摆放位置、光照滑杆）属于「本次编辑」，退出编辑页即失效，不进设置。
 *
 * @param lastFrogId 上次选中的奶蛙素材 id，下次打开编辑页自动选中（省掉每次重复操作）
 * @param fusionTierPreference **实际生效**的融合档位偏好；`null` = 自动（按设备能力与实测耗时自适应）。
 *   这是编辑页档位选择器的唯一数据源。
 * @param preferHighQualityFusion 历史字段：早期版本只有这个布尔值表达"偏好高质量融合"。
 *   它此前**只写不读**（存进 DataStore 却无人消费）。现在作为 [fusionTierPreference] 缺省时的
 *   兼容回退被真正消费 —— 旧用户的设置不再形同虚设，也不必做数据迁移。
 * @param preferRepDetailFusion 是否偏好**细节保真融合**（双轨制的高配轨道，实验特性）。
 *   默认 `true` = "能上就上"：真正的门槛是设备档位（低端机一律否决），
 *   而不是用户的默认设置。支持的设备上默认开启，用户可随时关掉
 * @param lastSaveFolder 上次保存目录名（仅用于展示，不参与逻辑）
 */
data class UserSettings(
    val lastFrogId: String? = null,
    val fusionTierPreference: FusionTier? = null,
    val preferHighQualityFusion: Boolean = false,
    val preferRepDetailFusion: Boolean = true,
    val lastSaveFolder: String? = null,
) {
    companion object {
        val DEFAULT = UserSettings()
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val storedTier = prefs[KEY_FUSION_TIER]?.let { name ->
            // 脏数据（枚举被删改、手改配置文件）一律当"未设置"，不抛异常
            FusionTier.entries.firstOrNull { it.name == name }
        }
        val storedTierExists = prefs[KEY_FUSION_TIER] != null
        val highQuality = prefs[KEY_PREFER_HIGH_QUALITY] == "true"

        UserSettings(
            lastFrogId = prefs[KEY_LAST_FROG_ID],
            fusionTierPreference = when {
                storedTier != null -> storedTier
                // 兼容回退：只写过历史字段的旧用户，等价于"偏好 720p 高质量档"
                !storedTierExists && highQuality -> FusionTier.FUSION_720
                else -> null
            },
            preferHighQualityFusion = highQuality,
            // 缺省（键不存在）视为开启：见 UserSettings.preferRepDetailFusion 的说明
            preferRepDetailFusion = prefs[KEY_PREFER_REP_DETAIL] != "false",
            lastSaveFolder = prefs[KEY_LAST_SAVE_FOLDER],
        )
    }

    suspend fun setLastFrogId(assetId: String) {
        context.settingsDataStore.edit { it[KEY_LAST_FROG_ID] = assetId }
    }

    /**
     * 写入融合档位偏好。
     *
     * 同时维护历史字段 [UserSettings.preferHighQualityFusion]，保持两个键一致 ——
     * 只由一个写入点负责同步，避免"两处各写一半"导致旧键与新键说法不一。
     *
     * @param tier `null` = 自动
     */
    suspend fun setFusionTierPreference(tier: FusionTier?) {
        context.settingsDataStore.edit { prefs ->
            if (tier == null) {
                prefs.remove(KEY_FUSION_TIER)
            } else {
                prefs[KEY_FUSION_TIER] = tier.name
            }
            prefs[KEY_PREFER_HIGH_QUALITY] = (tier == FusionTier.FUSION_720).toString()
        }
    }

    /** 保留给旧调用方；等价于 [setFusionTierPreference]`(true → 720p 档 / false → 自动)`。 */
    suspend fun setPreferHighQualityFusion(enabled: Boolean) {
        setFusionTierPreference(if (enabled) FusionTier.FUSION_720 else null)
    }

    suspend fun setPreferRepDetailFusion(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_PREFER_REP_DETAIL] = enabled.toString() }
    }

    suspend fun setLastSaveFolder(folder: String) {
        context.settingsDataStore.edit { it[KEY_LAST_SAVE_FOLDER] = folder }
    }

    private companion object {
        val KEY_LAST_FROG_ID = stringPreferencesKey("last_frog_id")
        val KEY_FUSION_TIER = stringPreferencesKey("fusion_tier_preference")
        val KEY_PREFER_HIGH_QUALITY = stringPreferencesKey("prefer_high_quality_fusion")
        val KEY_PREFER_REP_DETAIL = stringPreferencesKey("prefer_rep_detail_fusion")
        val KEY_LAST_SAVE_FOLDER = stringPreferencesKey("last_save_folder")
    }
}
