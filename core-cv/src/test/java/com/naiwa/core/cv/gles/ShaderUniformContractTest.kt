package com.naiwa.core.cv.gles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shader uniform 契约的静态校验。
 *
 * 【为什么需要这个测试】
 * GLSL 是写死在 Kotlin 字符串里的，**Kotlin 编译期完全查不出错误**；到了真机才由驱动编译，
 * 失败后 `GlesLayerPipeline.render()` 会 catch 住并返回 null → 整条图层管线降级回旧的
 * `GlesFusionRenderer`。用户看到的现象是"所有新效果都消失、甚至比以前更差"，
 * 但日志里只有一条 warn，很难与"某个 shader 少了一个 uniform"联系起来。
 *
 * v0.19 就踩过：用脚本批量替换"4 向 alpha 采样"时没限定次数，`FRAG_P2` 与 `FRAG_P2B`
 * 有相同代码块，两处都被改成 `uRimWidthPx`，而 P2B 没声明这个 uniform。
 *
 * 这个测试把"每个 `uXxx` 必须被声明"变成断言，几毫秒跑完，不需要设备。
 */
class ShaderUniformContractTest {

    /** 向上找源码文件（不同环境下测试工作目录可能是模块目录或仓库根）。 */
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

    private fun shaders(): Map<String, String> {
        val src = sourceFile().readText()
        val re = Regex("""private const val (FRAG_[A-Z0-9_]+) = \"\"\"(.*?)\"\"\"""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(src).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun declaredUniforms(body: String): List<String> =
        Regex("""uniform\s+\w+\s+([^;]+);""").findAll(body)
            .flatMap { m -> m.groupValues[1].split(',').map { it.trim() } }
            .toList()

    @Test
    fun `所有 shader 都找到了`() {
        val names = shaders().keys
        assertTrue("至少应解析出 10 个 shader，实际 ${names.size}", names.size >= 10)
        listOf(
            "FRAG_KEY", "FRAG_P1", "FRAG_P2", "FRAG_P2B", "FRAG_P2C", "FRAG_COMPOSITE",
            // v0.26 发光链（提取 → 高斯 → 上采样 → Screen）
            "FRAG_EMIT", "FRAG_GAUSS", "FRAG_BLOOM_UP", "FRAG_GLOW", "FRAG_LIGHTWRAP",
        ).forEach { assertTrue("缺少 $it", names.contains(it)) }
    }

    /**
     * 核心断言：用在表达式里的每个 `uXxx` 都必须有对应的 uniform 声明。
     * 声明缺失 = 驱动编译失败 = 整条管线静默降级。
     */
    @Test
    fun `每个被使用的 uniform 都已声明`() {
        val problems = ArrayList<String>()
        shaders().forEach { (name, body) ->
            val declared = declaredUniforms(body).toSet()
            val used = Regex("""\b(u[A-Z]\w*)\b""").findAll(body).map { it.groupValues[1] }.toSet()
            val missing = (used - declared).sorted()
            if (missing.isNotEmpty()) problems += "$name 未声明: $missing"
        }
        assertTrue("存在会导致 GLSL 编译失败的 shader：$problems", problems.isEmpty())
    }

    @Test
    fun `没有重复声明的 uniform`() {
        val problems = ArrayList<String>()
        shaders().forEach { (name, body) ->
            val all = declaredUniforms(body)
            val dup = all.groupBy { it }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) problems += "$name 重复声明: $dup"
        }
        assertTrue("重复声明同样会编译失败：$problems", problems.isEmpty())
    }

    @Test
    fun `大括号成对且只有一个入口函数`() {
        shaders().forEach { (name, body) ->
            assertEquals("$name 大括号不平衡", body.count { it == '{' }, body.count { it == '}' })
            assertEquals("$name 的 main 数量异常", 1, Regex("""\bvoid\s+main\s*\(""").findAll(body).count())
        }
    }

    /** 防止有人把 `#version 300 es` 删掉（ES 2.0 下 in/out、texture() 全都不合法）。 */
    @Test
    fun `每个 shader 都是 GLES 3 且带精度声明`() {
        shaders().forEach { (name, body) ->
            assertTrue("$name 缺少 #version 300 es", body.contains("#version 300 es"))
            assertTrue("$name 缺少 float 精度声明", body.contains("precision highp float")
                || body.contains("precision mediump float"))
        }
    }
}
