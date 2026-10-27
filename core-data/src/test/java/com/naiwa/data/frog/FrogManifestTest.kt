package com.naiwa.data.frog

import com.naiwa.core.common.model.FrogAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * manifest.json 解析 + **授权门控**的回归测试。
 *
 * 这块逻辑的价值不在"能解析 JSON"，而在"没有 license 的素材绝不进入列表"——
 * 它是一条合规红线，必须有测试兜住，避免后续重构时被顺手放宽。
 *
 * 注意：Android 的 org.json 在 JVM 单测里是空桩，本模块已加 `testImplementation(libs.json)`
 * 用真实实现替换，否则这些用例会抛 "not mocked"。
 */
class FrogManifestTest {

    @Test
    fun `合法条目被完整解析`() {
        val outcome = FrogManifest.parse(
            """
            {"assets":[
              {"file":"frog_classic_01.png","name":"经典奶蛙 01","license":"self-drawn","author":"self","source":"gen.py"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, outcome.entries.size)
        assertEquals(0, outcome.warnings.size)
        val entry = outcome.entries.first()
        assertEquals("frog_classic_01.png", entry.file)
        assertEquals("经典奶蛙 01", entry.displayName)
        assertEquals(FrogAsset.License.SELF_DRAWN, entry.license)
        assertEquals("frogs/frog_classic_01.png", entry.assetPath)
        assertEquals("builtin:frog_classic_01.png", entry.assetId)
    }

    @Test
    fun `缺少 license 的条目必须被跳过并给出 warning`() {
        val outcome = FrogManifest.parse(
            """
            {"assets":[
              {"file":"frog_bad.png","name":"来路不明的奶蛙"},
              {"file":"frog_ok.png","license":"CC0"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, outcome.entries.size)
        assertEquals("frog_ok.png", outcome.entries.first().file)
        assertEquals(1, outcome.warnings.size)
        assertTrue(outcome.warnings.first().contains("frog_bad.png"))
        assertTrue(outcome.warnings.first().contains("license"))
    }

    @Test
    fun `license 非法值同样被拒绝`() {
        val outcome = FrogManifest.parse(
            """
            {"assets":[
              {"file":"frog_a.png","license":"CC-BY-NC"},
              {"file":"frog_b.png","license":"  cc0  "}
            ]}
            """.trimIndent(),
        )

        // cc0 大小写与空格不敏感，应被接受
        assertEquals(1, outcome.entries.size)
        assertEquals(FrogAsset.License.CC0, outcome.entries.first().license)
        assertEquals(1, outcome.warnings.size)
        assertTrue(outcome.warnings.first().contains("CC-BY-NC"))
    }

    @Test
    fun `非 PNG 与含路径分隔符的文件名被拒绝`() {
        val outcome = FrogManifest.parse(
            """
            {"assets":[
              {"file":"frog.jpg","license":"CC0"},
              {"file":"../secrets.png","license":"CC0"},
              {"file":"sub/dir.png","license":"CC0"},
              {"file":"frog_good.png","license":"CC0"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, outcome.entries.size)
        assertEquals("frog_good.png", outcome.entries.first().file)
        assertEquals(3, outcome.warnings.size)
    }

    @Test
    fun `重复登记只保留第一条`() {
        val outcome = FrogManifest.parse(
            """
            {"assets":[
              {"file":"dup.png","license":"CC0"},
              {"file":"dup.png","license":"self-drawn"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, outcome.entries.size)
        assertEquals(FrogAsset.License.CC0, outcome.entries.first().license)
        assertEquals(1, outcome.warnings.size)
    }

    @Test
    fun `空内容与坏 JSON 都不抛异常`() {
        val empty = FrogManifest.parse("")
        assertTrue(empty.entries.isEmpty())
        assertEquals(1, empty.warnings.size)

        val broken = FrogManifest.parse("{ this is not json ")
        assertTrue(broken.entries.isEmpty())
        assertEquals(1, broken.warnings.size)

        val noArray = FrogManifest.parse("""{"assets":null}""")
        assertTrue(noArray.entries.isEmpty())
        assertEquals(1, noArray.warnings.size)
    }
}
