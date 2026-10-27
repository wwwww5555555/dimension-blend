package com.naiwa.core.cv.gles

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回读方向 / GL 状态的静态契约（跨模块巡检）。
 *
 * ## 为什么必须用测试钉住
 * 「点一键融图之后素材上下颠倒，而且光效全没了」这两个症状其实来自**同一件事**：
 *
 * ```
 *  一键融图 → GlesLayerPipeline（9 个 pass，全特效）
 *               └─ 任一步返回 null → 降级 GlesFusionRenderer（3 个 pass）
 *                                      └─ 它的 readPixels 多翻了一次 → 画面上下颠倒
 *                                      └─ 它本来就没有 v0.26+ 的光效链 → "光影被跳过"
 * ```
 * 两个渲染器的回读**方向相反**，而"走哪条路"取决于运行时是否出了 GL 错误 ——
 * 单元测试永远同时覆盖不到，只有靠源码级断言才能防止再出现第三份实现。
 *
 * 断言分三类：
 * 1. 回读只能有一个入口（`GlReadback.read`），且**禁止**任何地方手工倒序行；
 * 2. 每个 draw 之前必须设 2D 状态基线（深度/剔除/混合/裁剪）；
 * 3. 可选光效 pass 失败后不允许把整帧扔掉（`?: return null` 不能出现在光效链里）。
 */
class GlesReadbackOrientationContractTest {

    /** 从测试工作目录向上找到仓库根（同时含 core-cv 与 core-render 的那一层）。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val d = dir ?: error("向上找不到仓库根（cwd=${File(".").absolutePath}）")
            if (File(d, "core-cv").isDirectory && File(d, "core-render").isDirectory) return d
            dir = d.parentFile
        }
        error("向上找不到仓库根（cwd=${File(".").absolutePath}）")
    }

    private fun read(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("缺少文件 $rel", f.exists())
        return f.readText()
    }

    private fun stripComments(s: String): String =
        s.lines().joinToString("\n") { l ->
            val i = l.indexOf("//")
            if (i >= 0) l.substring(0, i) else l
        }

    private val renderers = listOf(
        "core-cv/src/main/java/com/naiwa/core/cv/gles/GlesLayerPipeline.kt",
        "core-cv/src/main/java/com/naiwa/core/cv/gles/GlesFusionRenderer.kt",
        "core-render/src/main/java/com/naiwa/core/render/RenderGraphExecutor.kt",
    )

    // ------------------------------------------------------------------ ① 回读唯一入口

    @Test
    fun `三个渲染器都必须走 GlReadback`() {
        val missing = renderers.filterNot { read(it).contains("GlReadback.read(") }
        assertTrue(
            "这些渲染器没有使用统一回读（方向会再次分叉）：\n$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `禁止任何地方手工倒序像素行`() {
        // 合法的倒序只允许出现在 GlReadback 内部（唯一真相）。
        // 这里抓的是回读专用的写法：`for (y in h - 1 downTo 0)` 或 `h - 1 downTo 0`。
        val bad = ArrayList<String>()
        renderers.forEach { rel ->
            if (Regex("""h\s*-\s*1\s+downTo\s+0""").containsMatchIn(stripComments(read(rel)))) {
                bad += rel
            }
        }
        assertTrue(
            "回读行序只能由 GlReadback 决定，禁止在各渲染器里手工翻转：\n$bad",
            bad.isEmpty(),
        )
    }

    @Test
    fun `回读必须显式绑定目标 FBO`() {
        // 旧版 GlesLayerPipeline 依赖"上一 pass 恰好还绑着正确 FBO"，
        // 一旦 postPass 的乒乓交换被跳过，就会静默读到另一张纹理。
        val layer = read(renderers[0])
        assertTrue(
            "图层管线的回读必须传入 curFbo（不能只传宽高）",
            Regex("""readPixels\(\s*curFbo\s*,""").containsMatchIn(layer),
        )
        assertTrue(
            "GlReadback 内部必须自己 glBindFramebuffer（不能信任当前绑定）",
            read("core-common/src/main/java/com/naiwa/core/common/render/GlReadback.kt")
                .contains("glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)"),
        )
    }

    // ------------------------------------------------------------------ ② GL 状态基线

    @Test
    fun `每个 draw 之前都必须设 2D 状态基线`() {
        // 全屏 quad 的顶点序是顺时针，一旦开着 GL_CULL_FACE 整个 quad 被剔除，
        // 且 glGetError **不会报错** —— 表现是"画面变黑/后续 pass 读空白纹理"。
        val missing = renderers.filterNot { read(it).contains("GlState.ensure2dPass()") }
        assertTrue("这些渲染器的 draw 没有关闭深度/剔除/混合/裁剪：\n$missing", missing.isEmpty())
    }

    @Test
    fun `状态基线覆盖四项且写满颜色通道`() {
        val s = read("core-common/src/main/java/com/naiwa/core/common/render/GlState.kt")
        listOf(
            "glDisable(GLES30.GL_DEPTH_TEST)",
            "glDisable(GLES30.GL_CULL_FACE)",
            "glDisable(GLES30.GL_BLEND)",
            "glDisable(GLES30.GL_SCISSOR_TEST)",
            "glDepthMask(false)",
            "glColorMask(true, true, true, true)",
        ).forEach { assertTrue("GlState 缺少 $it", s.contains(it)) }
    }

    // ------------------------------------------------------------------ ③ 可选 pass 不许拖垮整帧

    @Test
    fun `发光链失败不能终止整帧`() {
        val layer = stripComments(read(renderers[0]))
        // 发光链被抽成局部函数，返回值失败时只关发光
        assertTrue("发光链应抽成局部函数 buildGlow()", layer.contains("fun buildGlow(): Int?"))
        assertTrue(
            "发光链失败必须降级为黑纹理而不是 return null",
            Regex("""buildGlow\(\)\s*\?:\s*0""").containsMatchIn(layer),
        )
    }

    @Test
    fun `可选层失败后退化为黑纹理`() {
        val layer = stripComments(read(renderers[0]))
        // P2-b / LIGHTWRAP / GLOW 三个可选层都必须有 ok 判定 + black1x1 兜底
        assertTrue("P2-b 必须有 p2bOk 判定", layer.contains("if (!p2bOk)"))
        assertTrue("LIGHTWRAP 必须有 ok 判定", layer.contains("LIGHTWRAP 失败"))
        assertTrue("GLOW 必须有 ok 判定", layer.contains("GLOW 失败"))
    }

    @Test
    fun `后处理失败时不能交换乒乓纹理`() {
        val layer = stripComments(read(renderers[0]))
        val idx = layer.indexOf("fun postPass(")
        assertTrue("找不到 postPass", idx >= 0)
        val body = layer.substring(idx, (idx + 1600).coerceAtMost(layer.length))
        assertTrue(
            "postPass 必须先判断 draw 是否成功，失败时 return（不交换）",
            Regex("""if\s*\(!ok\)\s*\{""").containsMatchIn(body),
        )
    }
}
