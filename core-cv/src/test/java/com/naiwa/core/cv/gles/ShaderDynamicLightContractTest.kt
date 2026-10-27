package com.naiwa.core.cv.gles

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「动态光影跟随」的静态契约（v0.28）。
 *
 * ## 修的是什么
 * 用户报的现象：光源移到左/右/正中，光包裹区域不跟随。
 * 根因不是点积写错，而是**点积的第二个操作数是全局常数**：
 *
 * ```glsl
 * vec2 l2 = uLightDir.xy;              // CV 方位角直方图 → 一个常数
 * float facing = dot(nOut, l2 / ll);   // 整张图共用同一个方向
 * ```
 *
 * 而光源坐标 `uLightPos` 来自**另一套统计**（NMS 最亮连通区质心）。
 * 两个来源一旦不一致（多光源、光源偏心），方向就指错边；
 * 更糟的是它有"看起来对"的时候，所以很难靠肉眼定位。
 *
 * 正确写法是**逐像素**算：
 * ```glsl
 * vec2 d = (uLightPos - uv) / uTexel;   // 像素空间，宽高比已修正
 * vec2 dynamicLightDir = length(d) > 1.0 ? d / length(d) : vec2(0.0, -1.0);
 * ```
 *
 * 这个测试把"不许退回全局常数方向"钉死。
 */
class ShaderDynamicLightContractTest {

    private fun sourceFile(): File {
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            val f = File(dir, "src/main/java/com/naiwa/core/cv/gles/GlesLayerPipeline.kt")
            if (f.exists()) return f
            val nested = File(dir, "core-cv/src/main/java/com/naiwa/core/cv/gles/GlesLayerPipeline.kt")
            if (nested.exists()) return nested
            dir = dir?.parentFile
        }
        error("找不到 GlesLayerPipeline.kt（cwd=${File(".").absolutePath}）")
    }

    private fun src(): String = sourceFile().readText()

    /** 去掉 `//` 行注释：注释里写"反例"不能触发断言。 */
    private fun stripComments(s: String): String =
        s.lines().joinToString("\n") { l ->
            val i = l.indexOf("//")
            if (i >= 0) l.substring(0, i) else l
        }

    private fun shaders(): Map<String, String> =
        Regex(
            "private const val (FRAG_[A-Z0-9_]+) = \"\"\"(.*?)\"\"\"",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(src()).associate { it.groupValues[1] to it.groupValues[2] }

    /** 所有参与"迎光/受光"判定的 pass。 */
    private val lightPasses = listOf("FRAG_P2", "FRAG_EMIT", "FRAG_LIGHTWRAP", "FRAG_P2B", "FRAG_COMPOSITE")

    @Test
    fun `每个光效 pass 都有 uLightPos 与 lightDirPx`() {
        val sh = shaders()
        lightPasses.forEach { name ->
            val body = sh[name] ?: error("找不到 $name")
            assertTrue("$name 缺少 uniform uLightPos", Regex("""uniform\s+vec2\s+uLightPos\s*;""").containsMatchIn(body))
            assertTrue("$name 缺少 lightDirPx() 辅助函数", body.contains("vec2 lightDirPx(vec2 uv)"))
            assertTrue(
                "$name 的 lightDirPx 必须用 (uLightPos - uv) 算，不能是常数",
                Regex("""lightDirPx[\s\S]{0,300}?\(uLightPos\s*-\s*uv\)""").containsMatchIn(body),
            )
        }
    }

    /**
     * 禁止"全局常数方向点法向"的老写法回流。
     *
     * 特征写法：`dot(..., uLightDir.xy / length(uLightDir.xy))` 或先取 `vec2 l2 = uLightDir.xy`。
     */
    @Test
    fun `没有 pass 再用全局常数方向做点积`() {
        val sh = shaders()
        val problems = ArrayList<String>()
        lightPasses.forEach { name ->
            val body = stripComments(sh[name] ?: error("找不到 $name"))
            // ① 直接对 uLightDir.xy 归一化后点积
            if (Regex("""dot\([^)]*uLightDir\.xy\s*/""").containsMatchIn(body)) {
                problems += "$name：对 uLightDir.xy 归一化后点积（全局方向）"
            }
            // ② 用 dot(vec3(n, 0.0), uLightDir) 这种"3D 全局方向"（P2B 的老写法）
            if (Regex("""dot\(vec3\([^)]*\),\s*uLightDir\)""").containsMatchIn(body)) {
                problems += "$name：dot(vec3(n,0), uLightDir) 是全局方向，不会跟随光源位置"
            }
            // ③ 先存成局部变量再点积
            if (Regex("""vec2\s+l2\s*=\s*uLightDir\.xy\s*;""").containsMatchIn(body)) {
                problems += "$name：`vec2 l2 = uLightDir.xy` 缓存了全局方向"
            }
            // ④ 任何一行 dot(...) 里出现 uLightDir —— 全局方向直接参与点积
            //    （uLightDir 在这里只剩一个合法用途：在 normalize() 里取 z 反推 elevation）
            Regex("""dot\([^;\n]*uLightDir[^;\n]*\)""").findAll(body).forEach { m ->
                problems += "$name：`${m.value.trim()}` 用全局方向做点积"
            }
        }
        assertTrue("这些写法会让光包裹指错边：\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `法向在像素空间计算且取负`() {
        val sh = shaders()
        // 像素空间：差分除以 2*uTexel
        lightPasses.forEach { name ->
            val body = stripComments(sh[name] ?: error("找不到 $name"))
            // v0.30：基线从 1 texel 换成"宽基线"（低频 alpha / 环带半径 / 3 texel），
            // 目的正是让过渡带从 2~3px 扩到 8~14px。这里只要求"基线确实变宽了"。
            val wideBaseline = body.contains("uPersonLoA") ||
                Regex("""vec2\s+nB\s*=\s*vec2\(u(Band|Wrap)Px\)""").containsMatchIn(body) ||
                Regex("""3\.0\s*\*\s*uTexel""").containsMatchIn(body)
            assertTrue("$name 的法向必须用宽基线（否则明暗交界只有 2~3px）", wideBaseline)
            // 取负：绝不允许 +normalize(grad)
            assertTrue(
                "$name 的外法向必须取负（alpha 梯度指内）",
                Regex("""-\s*normalize\(gWide\)""").containsMatchIn(body) ||
                    Regex("""-\s*\w+[Pp]x\s*/\s*\w+""").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `顶光偏置存在且基于 uLightPos 的水平居中`() {
        val sh = shaders()
        listOf("FRAG_EMIT", "FRAG_LIGHTWRAP", "FRAG_P2B").forEach { name ->
            val body = stripComments(sh[name] ?: error("找不到 $name"))
            assertTrue("$name 缺少 topBias（光源水平居中时的顶光处理）", body.contains("topBias"))
            assertTrue(
                "$name 的 topBias 必须基于 abs(uLightPos.x - 0.5)",
                body.contains("abs(uLightPos.x - 0.5)"),
            )
            assertTrue("$name 顶光时要用上边缘权重（-nPx.y）", body.contains("-nPx.y"))
        }
    }

    /** 反向兜底：不许有人把 aspect 修正去掉（宽高比会让 45° 边缘偏角）。 */
    @Test
    fun `光向使用 1 texel 的倒数做宽高比修正`() {
        val sh = shaders()
        lightPasses.forEach { name ->
            val body = stripComments(sh[name] ?: error("找不到 $name"))
            assertTrue(
                "$name 的 lightDirPx 必须除以 uTexel（乘 w,h 修正宽高比）",
                Regex("""\(uLightPos\s*-\s*uv\)\s*/\s*uTexel""").containsMatchIn(body),
            )
        }
    }

    @Test
    fun `CPU 端按光源位置取色`() {
        val rel = "core-pipeline/src/main/java/com/naiwa/core/pipeline/DefaultImagePipeline.kt"
        var root: File? = sourceFile().parentFile
        var target: File? = null
        while (root != null) {
            val f = File(root, rel)
            if (f.exists()) { target = f; break }
            root = root.parentFile
        }
        val t = (target ?: error("找不到 $rel")).readText()
        assertTrue("必须调用 LocalLightSample.extract", t.contains("LocalLightSample.extract"))
        assertTrue("keyLightColorRgb 必须用局部采样结果", t.contains("keyLightColorRgb = keyLightColor"))
        assertTrue("取不到时必须回退", t.contains("if (localKey != 0) localKey else stats.keyRgb"))
    }
}
