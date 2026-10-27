package com.naiwa.core.cv.color

import android.content.Context
import com.naiwa.core.common.model.LutPreset

/**
 * 内置 3D LUT 仓库（规格 8.1）。
 *
 * 从 assets/luts/ 加载 .cube 文件并缓存。加载失败（文件缺失/损坏）时返回 null，
 * 由管线走"无 LUT"的兜底路径（仅传统颜色迁移），**不抛异常、不把预览打挂**。
 *
 * 缓存策略：解析 .cube 是纯 CPU 文本解析（17³ ≈ 4913 顶点），耗时毫秒级，
 * 但每次渲染都重读 assets 是浪费，所以解析结果按 preset 缓存一次。
 */
class LutRepository(private val context: Context) {

    private val cache = HashMap<LutPreset, CubeLut3d?>()

    /** 同步加载并缓存一个 LUT。失败返回 null。 */
    @Synchronized
    fun load(preset: LutPreset): CubeLut3d? {
        cache[preset]?.let { return it }
        val lut = try {
            val text = context.assets.open(ASSET_DIR + preset.assetName)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            CubeLut3d.parse(text)
        } catch (t: Throwable) {
            null
        }
        cache[preset] = lut
        return lut
    }

    /** 预加载全部四套 LUT（可选的启动预热）。返回成功加载的数量。 */
    fun preloadAll(): Int =
        LutPreset.entries.count { load(it) != null }

    private companion object {
        const val ASSET_DIR = "luts/"
    }
}
