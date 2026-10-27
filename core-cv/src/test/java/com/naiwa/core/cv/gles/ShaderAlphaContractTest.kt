package com.naiwa.core.cv.gles

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha 契约的静态校验。
 *
 * 【为什么需要】
 * v0.24 出现过一个只有真机能看出来的 bug：`FRAG_P2` 为了去白边做了 1.5px 的
 * Shrink Mask（形态学腐蚀），顺手把**输出 alpha** 也用了腐蚀后的值 ——
 * 于是最外圈真的变透明，人物看起来"融化"进背景。
 *
 * GLSL 写在 Kotlin 字符串里，编译器查不出；而这类错误只在特定的光效强度下才明显，
 * 很容易被误判成"光包裹太强/太弱"。所以把三条不变式钉成断言：
 *
 * 1. `FRAG_P2` 的输出 alpha 必须来自**未被修改的** `p.a`（`aOut`）；
 * 2. 光包裹缓冲 `FRAG_P2W` 必须输出不透明（它只承载"光"，不承载人物）；
 * 3. 合成与最终输出必须不透明。
 */
class ShaderAlphaContractTest {

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

    @Test
    fun `P2 输出 alpha 来自未经腐蚀的原始 mask`() {
        val p2 = shaders()["FRAG_P2"] ?: error("找不到 FRAG_P2")
        assertTrue(
            "FRAG_P2 必须先保存原始 alpha：float aOut = p.a;",
            Regex("""float\s+aOut\s*=\s*p\.a\s*;""").containsMatchIn(p2),
        )
        assertTrue(
            "输出必须用 aOut（原始 alpha），否则腐蚀会让人物边缘真的变透明",
            p2.contains("fragColor = vec4(c, aOut);"),
        )
        assertFalse(
            "禁止把腐蚀后的 a 写进输出 alpha —— 这正是 v0.24「人物融化」的根因",
            p2.contains("fragColor = vec4(c, a);"),
        )
    }

    @Test
    fun `光包裹缓冲必须不透明`() {
        val p2w = shaders()["FRAG_LIGHTWRAP"] ?: error("找不到 FRAG_LIGHTWRAP")
        assertTrue(
            "光包裹只承载附加光，alpha 必须恒为 1.0",
            Regex("""fragColor\s*=\s*vec4\([^)]*,\s*1\.0\s*\)""").containsMatchIn(p2w),
        )
    }

    /**
     * v0.26 的核心不变式：所有发光/泛光只能在 RGB 上做。
     *
     * 三个新 pass 都必须满足：
     * - `FRAG_EMIT` / `FRAG_GAUSS` / `FRAG_BLOOM_UP` / `FRAG_GLOW` 输出 alpha 恒 1.0；
     * - `FRAG_GLOW` 读 `uBase` 后**不得**把 mask alpha 写进输出（否则人物会真的变透明）。
     */
    @Test
    fun `发光链的每个 pass 都不碰 alpha`() {
        val all = shaders()
        listOf("FRAG_EMIT", "FRAG_GAUSS", "FRAG_BLOOM_UP", "FRAG_GLOW").forEach { name ->
            val body = all[name] ?: error("找不到 $name")
            assertTrue(
                "$name 的 alpha 必须恒为 1.0（发光只能改 RGB）",
                Regex("""fragColor\s*=\s*vec4\([^;]*,\s*1\.0\s*\)\s*;""").containsMatchIn(body),
            )
            assertFalse(
                "$name 不允许把采样到的 alpha 直接写回输出",
                body.contains("fragColor = vec4(c, a)"),
            )
        }
    }

    /**
     * 光包裹必须"向内"。
     *
     * 旧的 P2-w 用 `dilate(mask, 5px)` 把蒙版**外扩**出去，必然在外侧画一圈描边 ——
     * 这轮重构的直接动因。新实现必须满足两条：
     * 1. 只按 `smoothstep(0.45, 0.80, a)` 这类**人物内部**门控；
     * 2. 源码里不允许再出现"外扩/膨胀"关键字（防止有人把旧写法抄回来）。
     */
    @Test
    fun `光包裹只能向内渗透`() {
        val body = shaders()["FRAG_LIGHTWRAP"] ?: error("找不到 FRAG_LIGHTWRAP")
        assertTrue(
            "必须存在人物内部门控 inside = smoothstep(0.45, 0.80, a)",
            Regex("""inside\s*=\s*smoothstep\(\s*0\.45\s*,\s*0\.80\s*,\s*a\s*\)""").containsMatchIn(body),
        )
        assertFalse("禁止再出现 dilate 外扩写法", body.contains("dil = max(dil"))
        assertFalse("禁止再用 uDilatePx（它语义上就是「往外扩」）", body.contains("uDilatePx"))
    }

    @Test
    fun `合成与最终输出必须不透明`() {
        val all = shaders()
        // 整条后处理链都要不透明：任何一个 pass 把 src.a 原样传出，最终 Bitmap 就会带透明区，
        // 编辑器把它画到别的东西上时就"露出背景"。
        listOf("FRAG_COMPOSITE", "FRAG_DOF", "FRAG_GRADE", "FRAG_LOOKS", "FRAG_VIGNETTE", "FRAG_FXAA")
            .forEach { name ->
                val body = all[name] ?: error("找不到 $name")
                assertTrue(
                    "$name 的输出 alpha 必须是 1.0（后处理链不允许带透明度）",
                    Regex("""fragColor\s*=\s*vec4\([^;]*,\s*1\.0\s*\)\s*;""").containsMatchIn(body),
                )
            }
    }

    // ---------------------------------------------------------------- v0.36 边缘实心

    /**
     * 实心区必须被"锁定"为 100% 人物。
     *
     * 症状：背光面（尤其底部）边缘半透明、像要融化。根因**不是** alpha 被写坏
     * （`FRAG_P2` 输出的是原始 `aOut`），而是 composite 里的
     * `blend = smoothstep(0.5-w, 0.5+w, p2.a)` —— 羽化一旦伸进实心区，背景就从那里透出来。
     * 实测（tools/sim_alpha_lock.py）：mask≥0.99 的实心像素，人物纯度只有 **0.650**。
     */
    @Test
    fun `合成必须把 mask 核心区锁成完全不透明`() {
        val c = shaders()["FRAG_COMPOSITE"] ?: error("找不到 FRAG_COMPOSITE")
        assertTrue(
            "必须有核心区锁定：blend = max(blend, smoothstep(0.90, 0.97, p2.a))",
            Regex("""blend\s*=\s*max\(\s*blend\s*,\s*smoothstep\(0\.90,\s*0\.97,\s*p2\.a\)\s*\)""")
                .containsMatchIn(c),
        )
        assertTrue(
            "底部（uv.y→1，人物与地面相接处）必须有实心保护",
            Regex("""bottomSolid\s*=\s*smoothstep\(0\.86,\s*0\.96,\s*uv\.y\)""").containsMatchIn(c),
        )
    }

    /**
     * 大气透视（把背景 Screen 进人物）只允许发生在 alpha 过渡带。
     *
     * `innerProx` 的探针半径是 bleedPx（背光侧 20px），在这个半径内它恒为 1 ——
     * 少了 `edgeOnly` 这一项，背景会以 35% 强度灌进人物内侧整整 20px。
     */
    @Test
    fun `背景渗透必须被 alpha 过渡带限制`() {
        val c = shaders()["FRAG_COMPOSITE"] ?: error("找不到 FRAG_COMPOSITE")
        assertTrue(
            "bleed 必须乘 edgeOnly（a ≥ 0.99 时归零）",
            Regex("""edgeOnly\s*=\s*1\.0\s*-\s*smoothstep\(0\.90,\s*0\.99,\s*p2\.a\)""").containsMatchIn(c),
        )
        assertTrue(
            "bleedW 必须同时受 edgeOnly 与底部保护约束",
            Regex("""bleedW\s*=\s*innerProx\s*\*\s*innerProx\s*\*\s*inside\s*\*\s*edgeOnly\s*\*\s*\(1\.0\s*-\s*bottomSolid\)""")
                .containsMatchIn(c),
        )
    }

    /** 腐蚀（Shrink Mask）只允许削过渡带，不许吃掉实心内部。 */
    @Test
    fun `腐蚀不得吃掉 mask 核心区`() {
        val p2 = shaders()["FRAG_P2"] ?: error("找不到 FRAG_P2")
        assertTrue(
            "腐蚀之后必须有核心区回填：a = max(a, smoothstep(0.95, 0.99, p.a))",
            Regex("""a\s*=\s*max\(\s*a\s*,\s*smoothstep\(0\.95,\s*0\.99,\s*p\.a\)\s*\)""")
                .containsMatchIn(p2),
        )
    }
}
