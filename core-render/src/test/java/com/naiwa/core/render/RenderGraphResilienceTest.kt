package com.naiwa.core.render

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DAG 执行器的**容错契约**。
 *
 * 这一轮修的是一类"不崩溃但整条管线静默失效"的写法，它们在真机上的表现完全一样：
 * 后面几个节点没跑，画面黑或只剩贴图，而日志里只有一条 GL 错误或一条异常。
 *
 * 三个具体模式（全部在旧代码里出现过）：
 * ```
 * ① 循环体里 `?: return null`   —— 一个槽位分配失败（显存不足）就整张图放弃 GPU；
 * ② 节点函数里 `?: return`      —— 该节点槽位保持**未初始化**，下游读到脏数据；
 * ③ unbind → 第三方滤镜 → makeCurrent  没有 finally
 *                               —— 滤镜抛一次，上下文永久停在解绑态，之后所有 GL 调用失效。
 * ```
 */
class RenderGraphResilienceTest {

    private fun source(): String {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val d = dir ?: error("找不到源码（cwd=${File(".").absolutePath}）")
            val f = File(d, "core-render/src/main/java/com/naiwa/core/render/RenderGraphExecutor.kt")
            if (f.exists()) return f.readText()
            val nested = File(d, "src/main/java/com/naiwa/core/render/RenderGraphExecutor.kt")
            if (nested.exists()) return nested.readText()
            dir = d.parentFile
        }
        error("找不到 RenderGraphExecutor.kt（cwd=${File(".").absolutePath}）")
    }

    private fun stripComments(s: String): String =
        s.lines().joinToString("\n") { l ->
            val i = l.indexOf("//")
            if (i >= 0) l.substring(0, i) else l
        }

    private fun body(from: String, until: String, len: Int = 3000): String {
        val s = stripComments(source())
        val a = s.indexOf(from)
        assertTrue("找不到 `$from`", a >= 0)
        val b = s.indexOf(until, a)
        return s.substring(a, if (b > a) b else (a + len).coerceAtMost(s.length))
    }

    @Test
    fun `execute 的 pass 循环不得用 forEach 加非局部 return`() {
        // ⚠️ 结束标记必须用**代码**而不是注释：body() 会先去掉注释，注释标记永远找不到，
        //    切片就会一路吃到 "输出节点" 那段（那里有一句合法的 return null）。
        val loop = body("for (pass in plan.passes)", "if (failed > 0)")
        assertFalse(
            "pass 循环里不允许 `return null`（会终止整条 DAG，而不是跳过一个节点）",
            Regex("""return\s+null""").containsMatchIn(loop),
        )
        assertTrue("槽位拿不到时应 continue 而不是中止", loop.contains("continue"))
    }

    @Test
    fun `单个节点必须有 try catch`() {
        val loop = body("for (pass in plan.passes)", "if (failed > 0)")
        assertTrue(
            "renderNode 必须包在 try/catch 里，异常时降级而不是冒泡",
            Regex("""try\s*\{[^}]*renderNode\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(loop) ||
                (loop.contains("try {") && loop.contains("catch (t: Throwable)")),
        )
        assertTrue("失败节点必须有降级动作", loop.contains("fallbackFor("))
    }

    @Test
    fun `renderNode 必须返回是否成功`() {
        val s = source()
        assertTrue(
            "renderNode 必须返回 Boolean，让调用方知道槽位里有没有画东西",
            Regex("""private fun renderNode\([\s\S]{0,400}?\)\s*:\s*Boolean""").containsMatchIn(s),
        )
        // 失败分支要显式 false，成功结尾要 true
        assertTrue("失败分支必须 return false", s.contains("return false"))
        assertTrue("成功结尾必须 return true", s.contains("return true"))
    }

    @Test
    fun `失败降级必须先直通上游再清透明`() {
        val s = stripComments(source())
        val i = s.indexOf("private fun fallbackFor(")
        assertTrue("缺少 fallbackFor", i >= 0)
        val b = s.substring(i, (i + 1800).coerceAtMost(s.length))
        assertTrue("应优先直通上游纹理", b.contains("blit(src, slot)"))
        assertTrue("无上游时清成透明（保证像素良定义）", b.contains("glClear"))
    }

    @Test
    fun `第三方滤镜的上下文切换必须 finally 恢复`() {
        val s = stripComments(source())
        val i = s.indexOf("GpuImagePlusNode.apply(")
        assertTrue("找不到第三方滤镜调用", i >= 0)
        val b = s.substring(i, (i + 1400).coerceAtMost(s.length))
        assertTrue("apply 之后必须有 finally", b.contains("finally"))
        assertTrue("finally 里必须 makeCurrent 拿回上下文", b.contains("makeCurrent()"))
    }

    @Test
    fun `回读与状态基线统一`() {
        val s = source()
        assertTrue("回读必须走 GlReadback", s.contains("GlReadback.read("))
        assertTrue("draw 必须设 2D 状态基线", s.contains("GlState.ensure2dPass()"))
        assertFalse(
            "禁止手工倒序行（方向只能由 GlReadback 决定）",
            Regex("""h\s*-\s*1\s+downTo\s+0""").containsMatchIn(stripComments(s)),
        )
    }
}
