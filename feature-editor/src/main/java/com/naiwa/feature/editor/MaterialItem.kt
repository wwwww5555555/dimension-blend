package com.naiwa.feature.editor

import android.net.Uri
import com.naiwa.core.common.model.FrogAsset

/**
 * 素材栏里的一项（列表管理的最小单位）。
 *
 * ## 为什么这里**故意不放 Bitmap**
 *
 * 需求原话是"MaterialItem 包含 Uri、Bitmap、Id"。把位图塞进列表项是**最容易 OOM 的写法**：
 * 一次导入 10 张 1024² 的素材，ARGB_8888 就是 `10 × 1024 × 1024 × 4B ≈ 40MB` 常驻，
 * 而列表里真正需要显示的只有 56dp 的缩略图。
 *
 * 所以这里的分工是：
 *  - **列表项**：只存元数据（id / 名字 / 是否内置 / 来源 uri），几十字节；
 *  - **缩略图**：UI 按需解码 128px（[EditorViewModel.loadThumbnail]），滚出屏幕就随组合释放；
 *  - **全尺寸位图**：只有"当前选中"的那一张常驻（`EditorUiState.frogBitmap`），
 *    因为渲染管线每次都要用它。
 *
 * 这也是"删除素材要回收内存"能成立的前提：被删项本身没有位图，
 * 需要回收的永远只有"它如果正被选中"的那一张。
 */
data class MaterialItem(
    /** 稳定标识：内置素材是资源名，导入素材是 `imported:<文件名>`。 */
    val id: String,
    val displayName: String,
    /** 内置素材只读（删不掉文件，只能从本次会话的列表里移除）。 */
    val builtIn: Boolean,
    /** 导入素材的 `file://` 地址；内置素材为 null。 */
    val uri: Uri? = null,
) {
    companion object {
        fun from(asset: FrogAsset): MaterialItem = MaterialItem(
            id = asset.id,
            displayName = asset.displayName,
            builtIn = asset.builtIn,
            uri = asset.uriString?.let { runCatching { Uri.parse(it) }.getOrNull() },
        )
    }
}
