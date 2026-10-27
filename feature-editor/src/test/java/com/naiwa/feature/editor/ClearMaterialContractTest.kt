package com.naiwa.feature.editor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清除人像素材」的静态契约。
 *
 * 为什么值得用源码扫描而不是普通单测：这里要守的两条都是**时序/时序副作用**，
 * 在 JVM 单测里既起不来 ViewModel（需要 Android + Hilt），也看不见"Compose 正在绘制"。
 * 但它们出错的后果都很具体：
 *
 *  1. **先改状态，后取消渲染** → 已经跑完的那一帧把人物又贴回来（"删了又自己回来"）；
 *  2. **同步回收位图** → 淡出动画仍在绘制它，Compose 下一帧抛
 *     `Canvas: trying to use a recycled bitmap`，编辑器直接崩。
 *
 * 两条都在实现时真实犹豫过（谁先谁后、要不要 recycle），所以钉死。
 */
class ClearMaterialContractTest {

    // ---------------------------------------------------------------- 工具

    private fun resolve(rel: String): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir?.parentFile
        }
        error("找不到 $rel（cwd=${File("").absolutePath}）")
    }

    private fun viewModelSrc(): String =
        resolve("feature-editor/src/main/java/com/naiwa/feature/editor/EditorViewModel.kt").readText()

    private fun screenSrc(): String =
        resolve("feature-editor/src/main/java/com/naiwa/feature/editor/EditorScreen.kt").readText()

    /** 取 `fun xxx(` 到配对右花括号之间的函数体（含签名）。 */
    private fun body(src: String, funName: String): String {
        val start = src.indexOf("fun $funName(")
        require(start >= 0) { "找不到函数 $funName" }
        var i = src.indexOf('{', start)
        var depth = 1
        while (i < src.length - 1 && depth > 0) {
            i++
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return src.substring(start, i)
    }

    /** 去掉行注释，避免注释文字里的 `recycle()` 之类把断言骗了。 */
    private fun stripComments(s: String): String =
        s.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    // ---------------------------------------------------------------- 契约

    @Test
    fun `清空必须先把在途渲染掐掉再改状态`() {
        val b = stripComments(body(viewModelSrc(), "clearForegroundMaterial"))
        val cancel = b.indexOf("renderJob?.cancel()")
        val reset = b.indexOf("frogBitmap = null")
        assertTrue("清空里必须取消 renderJob（否则完成中的那一帧会把人物贴回来）", cancel >= 0)
        assertTrue("清空里必须把 frogBitmap 置空", reset >= 0)
        assertTrue("取消渲染必须发生在状态重置之前（cancel=$cancel, reset=$reset）", cancel < reset)
        assertTrue("必须同时停掉融合任务，否则一键融图会在清空后落一张带人物的图", b.contains("isFusing = false"))
    }

    @Test
    fun `清空方法内禁止同步回收位图`() {
        val b = stripComments(body(viewModelSrc(), "clearForegroundMaterial"))
        assertTrue(
            "clearForegroundMaterial 里不能直接 recycle（淡出动画仍在绘制那张位图）",
            !b.contains(".recycle("),
        )
        assertTrue("必须走延迟回收", b.contains("retireBitmaps("))
    }

    @Test
    fun `延迟回收必须先等待再 recycle`() {
        val b = stripComments(body(viewModelSrc(), "retireBitmaps"))
        val wait = b.indexOf("delay(PREVIEW_RETIRE_MS)")
        val recycle = b.indexOf(".recycle()")
        assertTrue("retireBitmaps 里必须有 delay", wait >= 0)
        assertTrue("retireBitmaps 里必须有 recycle", recycle >= 0)
        assertTrue("必须先 delay 再 recycle（wait=$wait, recycle=$recycle）", wait < recycle)
        // 场景图是全程共用的，回收它等于把背景也删了
        assertTrue("必须排除 scenePreview", b.contains("bmp === scene"))
        assertTrue("必须排除 sceneDrag", b.contains("bmp === drag"))
    }

    @Test
    fun `回收窗口必须大于淡出动画`() {
        val src = viewModelSrc()
        val ms = Regex("""PREVIEW_RETIRE_MS\s*=\s*(\d+)L""").find(src)?.groupValues?.get(1)?.toLong()
            ?: error("找不到 PREVIEW_RETIRE_MS")
        assertTrue("回收等待 $ms ms 必须大于 Crossfade 的 300ms", ms > 300L)
    }

    @Test
    fun `清空必须把位置与预览一起复位`() {
        val b = stripComments(body(viewModelSrc(), "clearForegroundMaterial"))
        assertTrue("位置必须归零（否则下次导入会落在上一张的位置上）", b.contains("placement = Placement.DEFAULT"))
        assertTrue(
            "previewBitmap 必须置 null（置成 scenePreview 会让引用判断变复杂）",
            b.contains("previewBitmap = null"),
        )
    }

    /**
     * 融合协程必须能被取消，且结果提交前要再核对一次"素材还在不在"。
     *
     * `Job.cancel()` 只是请求取消：本工程的渲染管线内部**没有取消检查点**，
     * 一张已经在算的图照样会算完并回调。只取消不校验 = 删除后仍会落一张带人物的图。
     */
    @Test
    fun `清空必须取消融合任务`() {
        val b = stripComments(body(viewModelSrc(), "clearForegroundMaterial"))
        assertTrue("清空里必须 fuseJob?.cancel()", b.contains("fuseJob?.cancel()"))
        val src = viewModelSrc()
        assertTrue("triggerFullRender 必须把任务存进 fuseJob", src.contains("fuseJob = viewModelScope.launch"))
    }

    @Test
    fun `渲染结果提交前必须核对素材是否已被清空`() {
        // 两处写法都可以：renderOnce 直接读 `_state.value`，commitRenderResult 已经
        // 把 state 存进局部变量 `current`（它开头就取了快照）。
        val guardRe = Regex("""(?:current|_state\.value)\.frogBitmap == null""")
        listOf("renderOnce", "commitRenderResult").forEach { fn ->
            val b = stripComments(body(viewModelSrc(), fn))
            val guard = guardRe.find(b)?.range?.first ?: -1
            assertTrue("$fn 必须在提交前检查 frogBitmap == null", guard >= 0)
            val write = b.indexOf("previewBitmap = result")
            if (write >= 0) {
                assertTrue("$fn 的检查必须写在写 previewBitmap 之前（guard=$guard, write=$write）", guard < write)
            }
        }
        // 融合结果那条路径必须先 return，再轮到 fusedResult 赋值
        val c = stripComments(body(viewModelSrc(), "commitRenderResult"))
        assertTrue(
            "丢弃结果时不能把它记进 fusedResult（否则下一轮会被当成缓存回收）",
            (guardRe.find(c)?.range?.first ?: Int.MAX_VALUE) < c.indexOf("fusedResult = resultBitmap"),
        )
    }

    @Test
    fun `空渲染必须被拦截并给出可读提示`() {
        val b = stripComments(body(viewModelSrc(), "triggerFullRender"))
        val guard = b.indexOf("frogBitmap == null")
        assertTrue("triggerFullRender 必须先判断有没有人像", guard >= 0)
        assertTrue("必须提示用户先导入人像", b.contains("请先导入人像素材"))
        val firstReturn = b.indexOf("return", guard)
        assertTrue("提示后必须直接返回，不能继续跑管线", firstReturn > guard)
    }

    @Test
    fun `删除按钮只在该有人像时出现`() {
        val s = stripComments(screenSrc())
        assertTrue(
            "删除按钮必须用 AnimatedVisibility 包裹",
            Regex("""AnimatedVisibility\(\s*visible = state\.frogBitmap != null""").containsMatchIn(s),
        )
        assertTrue("必须有 onClearFrog 回调", s.contains("onClick = onClearFrog"))
        assertTrue(
            "删除图标必须是 Material Icons 的 Delete",
            Regex("""imageVector = Icons\.Default\.Delete""").containsMatchIn(s),
        )
    }
}
