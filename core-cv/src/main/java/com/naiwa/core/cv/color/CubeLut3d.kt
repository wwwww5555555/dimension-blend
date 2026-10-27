package com.naiwa.core.cv.color

import kotlin.math.floor

/**
 * 3D 颜色查找表（Color Grading LUT）—— 规格 8.1。
 *
 * 与之前被否定的"三维点变换 LUT"是**两件不同的事**，务必区分：
 *  - 之前否定的：用 33³ 三维表**替代逐像素 sRGB↔Lab 超越函数**（cbrt/pow），
 *    否定理由是"比解析实现慢、且跨不过 gamma 色域钳制面"（见 docs/ARCHITECTURE.md §6.10）。
 *  - 本类：**影视调色的 3D color grade LUT**，把前景 RGB 映射到"匹配场景色温/氛围"的新 RGB。
 *    它是对颜色的**审美映射**（暖光、冷夜、阴天、逆光），不是数学变换，也没有"跨不过钳制面"的问题。
 *
 * 【作用域】只作用于奶蛙前景的 RGB（alpha > 0），背景像素完全不碰 —— 由调用方传入 alpha 判断。
 *
 * 【格式】解析 `.cube`（Adobe/Resolve 通用格式），支持关键字：
 *   TITLE、LUT_3D_SIZE、DOMAIN_MIN、DOMAIN_MAX，以及逐行 RGB 三元组（0~1 浮点或 0~255 整数自动识别）。
 *   采样用**三线性插值**，LUT 顶点布局为「R 变化最快、B 变化最慢」的标准约定。
 */
class CubeLut3d private constructor(
    val title: String,
    val size: Int,
    private val domainMin: Float,
    private val domainMax: Float,
    /** 展开的 LUT 数据，长度 size³×3，逐像素 R/G/B 依次存放（0~1 浮点）。 */
    private val table: FloatArray,
) {

    /** 用 LUT 映射一个前景像素的 RGB（原地语义：返回新的 RGB 三元组，不碰 alpha）。 */
    fun map(r: Int, g: Int, b: Int): IntArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val out = sample(rf, gf, bf)
        return intArrayOf(
            (out[0] * 255f).toInt().coerceIn(0, 255),
            (out[1] * 255f).toInt().coerceIn(0, 255),
            (out[2] * 255f).toInt().coerceIn(0, 255),
        )
    }

    /** 三线性插值采样。输入/输出均为 0~1 浮点。 */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        // 归一化到 domain 区间，再映射到 [0, size-1] 网格坐标
        val dr = normalize(r)
        val dg = normalize(g)
        val db = normalize(b)

        val scale = (size - 1).toFloat()
        val fr = dr * scale
        val fg = dg * scale
        val fb = db * scale

        val r0 = floor(fr).toInt().coerceIn(0, size - 1)
        val g0 = floor(fg).toInt().coerceIn(0, size - 1)
        val b0 = floor(fb).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)

        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0

        val out = FloatArray(3)
        for (c in 0 until 3) {
            val c000 = at(r0, g0, b0, c)
            val c100 = at(r1, g0, b0, c)
            val c010 = at(r0, g1, b0, c)
            val c110 = at(r1, g1, b0, c)
            val c001 = at(r0, g0, b1, c)
            val c101 = at(r1, g0, b1, c)
            val c011 = at(r0, g1, b1, c)
            val c111 = at(r1, g1, b1, c)

            // 先沿 R 轴（x）插值
            val c00 = c000 + (c100 - c000) * tr
            val c10 = c010 + (c110 - c010) * tr
            val c01 = c001 + (c101 - c001) * tr
            val c11 = c011 + (c111 - c011) * tr
            // 再沿 G 轴（y）
            val c0 = c00 + (c10 - c00) * tg
            val c1 = c01 + (c11 - c01) * tg
            // 最后沿 B 轴（z）
            out[c] = c0 + (c1 - c0) * tb
        }
        return out
    }

    /** 把 0~1 输入归一化到 domain 区间。domain 默认 0~1 时原样返回。 */
    private fun normalize(v: Float): Float {
        if (domainMin == 0f && domainMax == 1f) return v.coerceIn(0f, 1f)
        val t = (v - domainMin) / (domainMax - domainMin)
        return t.coerceIn(0f, 1f)
    }

    /** 取 LUT 顶点 (r, g, b) 的通道 c。布局：R 变最快、B 最慢。 */
    private fun at(r: Int, g: Int, b: Int, c: Int): Float =
        table[((b * size + g) * size + r) * 3 + c]

    companion object {
        /**
         * 解析 .cube 文本。
         *
         * @return 解析成功返回 LUT；格式非法返回 null（调用方走无 LUT 的兜底路径，不抛异常）
         */
        fun parse(text: String): CubeLut3d? {
            var title = ""
            var size = 0
            var domainMin = 0f
            var domainMax = 1f
            val values = ArrayList<Float>(4096)

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                // 跳过注释
                if (line.startsWith("#")) continue
                // 关键字
                if (line.startsWith("TITLE")) {
                    title = line.removePrefix("TITLE").trim().trim('"')
                    continue
                }
                if (line.startsWith("LUT_3D_SIZE")) {
                    size = line.removePrefix("LUT_3D_SIZE").trim().toIntOrNull() ?: return null
                    continue
                }
                if (line.startsWith("DOMAIN_MIN")) {
                    val parts = line.removePrefix("DOMAIN_MIN").trim().split(Regex("\\s+"))
                    if (parts.size < 3) return null
                    domainMin = parts[0].toFloatOrNull() ?: return null
                    continue
                }
                if (line.startsWith("DOMAIN_MAX")) {
                    val parts = line.removePrefix("DOMAIN_MAX").trim().split(Regex("\\s+"))
                    if (parts.size < 3) return null
                    domainMax = parts[0].toFloatOrNull() ?: return null
                    continue
                }
                // 数据行：RGB 三元组
                val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size < 3) continue
                for (p in parts) {
                    val v = p.toFloatOrNull() ?: return null
                    // 自动识别 0~255 整数：> 1 视为 0~255 编码，归一化到 0~1
                    values += if (v > 1f) v / 255f else v
                }
            }

            if (size < 2) return null
            val expected = size * size * size * 3
            if (values.size < expected) return null
            // 截断到期望长度（容许多余的空白/尾随数据）
            val table = FloatArray(expected) { values[it] }
            return CubeLut3d(title, size, domainMin, domainMax, table)
        }
    }
}
