package com.naiwa.core.cv.gles

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Kotlin 传来的 uniform 必须在对应 shader 里声明」的静态校验。
 *
 * ## 为什么这条必须有
 * `uni1f("uXxx", v)` 找不到 uniform 时，`glGetUniformLocation` 返回 -1，
 * 而 `glUniform1f(-1, v)` 在 GL 规范里是**静默忽略**——不报错、不崩溃、不写日志。
 * 结果就是"参数明明传了，画面上毫无变化"，而且**没有任何信号**。
 *
 * 上一版已经踩过一次同类问题（`ShaderUniformContractTest` 的反向版本：
 * shader 用了未声明的 uniform → 编译失败 → 整条管线降级）。这一次是笔误的另一个方向，
 * 本轮重构一口气重命名了 20+ 个 uniform（P2W → LIGHTWRAP、bloom → emit/glow），
 * 光靠肉眼 review 一定会漏。
 *
 * ## 实现
 * 1. 从源码解析 `"emit" to FRAG_EMIT` 建立 program 名 → shader 常量 的映射；
 * 2. 用括号配对扫描每个 `draw(programs["x"]!!) { ... }` 与 `postPass("x") { ... }` 块；
 * 3. 抽出块里所有 uniform 名（`uni*f` / `uni3f` / `uniMat3` / `uniSrgbLinear` / `bind`），
 *    逐个对照该 shader 的 `uniform` 声明。
 */
class ShaderUniformSendContractTest {

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

    private fun shaders(): Map<String, String> =
        Regex(
            "private const val (FRAG_[A-Z0-9_]+) = \"\"\"(.*?)\"\"\"",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(src()).associate { it.groupValues[1] to it.groupValues[2] }

    private fun declared(body: String): Set<String> =
        Regex("""uniform\s+\w+\s+([^;]+);""").findAll(body)
            .flatMap { m -> m.groupValues[1].split(',').map { it.trim() } }
            .toSet()

    /** `"emit" to FRAG_EMIT` → program 名到 shader 名。 */
    private fun programMap(): Map<String, String> =
        Regex("\"(\\w+)\"\\s+to\\s+(FRAG_[A-Z0-9_]+)").findAll(src())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * 从 `from` 位置开始找第一个 `{`，返回配对 `}` 之后的索引。
     * 会跳过字符串、行注释、块注释里的花括号（GLSL 里 `{` 很常见）。
     */
    private fun blockEnd(s: String, from: Int): Int {
        var i = from
        var depth = 0
        var started = false
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
                i++; continue
            }
            when {
                c == '"' -> inStr = true
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> {
                    val nl = s.indexOf('\n', i)
                    i = if (nl < 0) s.length else nl
                    continue
                }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val e = s.indexOf("*/", i)
                    i = if (e < 0) s.length else e + 2
                    continue
                }
                c == '{' -> { depth++; started = true }
                c == '}' -> {
                    depth--
                    if (started && depth == 0) return i
                }
            }
            i++
        }
        return s.length
    }

    private val UNI_NAME =
        Regex("\\b(?:uni1f|uni2f|uni3f|uni1i|uniSrgbLinear|uniMat3|bind)\\s*\\(\\s*\"([^\"]+)\"")

    /** 收集所有 (programName, 块文本) 对。 */
    private fun drawBlocks(s: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val drawRe = Regex("""draw\(\s*programs\["(\w+)"\]!!""")
        drawRe.findAll(s).forEach { m ->
            val end = blockEnd(s, m.range.last + 1)
            out += m.groupValues[1] to s.substring(m.range.last + 1, end)
        }
        // ⚠️ 正则必须吃掉 program 名的后引号：否则 blockEnd 会从那个引号开始，
        //    把后引号当成"字符串开始"，一路乱掉，最后的块边界会吞掉后面所有 pass。
        val postRe = Regex("postPass\\(\\s*\"(\\w+)\"")
        postRe.findAll(s).forEach { m ->
            val end = blockEnd(s, m.range.last + 1)
            out += m.groupValues[1] to s.substring(m.range.last + 1, end)
        }
        return out
    }

    @Test
    fun `解析到了 draw 块与 program 映射`() {
        assertTrue("program 映射为空，解析规则失效了", programMap().size >= 10)
        assertTrue("没解析到任何 draw 块，解析规则失效了", drawBlocks(src()).size >= 15)
    }

    @Test
    fun `每个传给 shader 的 uniform 都必须被声明`() {
        val sh = shaders()
        val prog = programMap()
        val problems = ArrayList<String>()
        drawBlocks(src()).forEach { (name, body) ->
            val shaderName = prog[name] ?: return@forEach
            val body2 = sh[shaderName] ?: return@forEach
            val declared = declared(body2)
            UNI_NAME.findAll(body).map { it.groupValues[1] }.toSet().forEach { u ->
                if (u !in declared) problems += "programs[\"$name\"] 传了 $u，但 $shaderName 没声明"
            }
        }
        assertTrue(
            "这些 uniform 会被 glUniform 静默忽略（参数传了却不生效）：\n" +
                problems.sorted().joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /** 反向兜底：本轮重命名后不允许再出现旧名字（否则就是改了一半）。 */
    @Test
    fun `旧版光包裹的命名已彻底退场`() {
        val s = src()
        listOf("uDilatePx", "uP2W", "FRAG_P2W", "uKeyLightColor", "bloomExtract", "bloomApply")
            .forEach { old ->
                assertTrue("源码里仍残留旧标识 $old", !s.contains(old))
            }
    }
}
