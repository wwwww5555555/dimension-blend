package com.naiwa.feature.editor

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 素材列表管理（增删选）的静态契约。
 *
 * 这里守的是四条"改起来很自然、坏起来很贵"的不变式：
 *  1. **列表只存元数据**，位图只有"当前选中"那一张 —— 否则 10 张素材就是 40MB 常驻；
 *  2. **删除必须先出列表**（IO 在后），否则点了叉号缩略图还杵着，等于"点了没反应"；
 *  3. **删掉的正是选中项**时要接续（优先前一个）或清空，不能留一个指向已删 id 的选中态；
 *  4. **换下来的位图必须走延迟回收**（管线会继续读它），且回收前要等管线空闲。
 */
class MaterialListContractTest {

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

    private fun itemSrc(): String =
        resolve("feature-editor/src/main/java/com/naiwa/feature/editor/MaterialItem.kt").readText()

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

    private fun stripComments(s: String): String =
        s.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    // ---------------------------------------------------------------- 数据结构

    @Test
    fun `列表项不得持有位图`() {
        val s = stripComments(itemSrc())
        // 只检查 data class 的字段区（KDoc 里会提到 Bitmap，那是解释文字）
        val decl = s.substringAfter("data class MaterialItem(").substringBefore(") {")
        assertTrue(
            "MaterialItem 的字段里不能有 Bitmap —— 全尺寸位图只允许存在 selectedMaterialId 对应的那一张",
            !decl.contains("Bitmap"),
        )
        assertTrue("列表项必须带 id", decl.contains("val id: String"))
        assertTrue("列表项必须能区分内置/导入（决定删除时要不要落盘）", decl.contains("val builtIn: Boolean"))
    }

    @Test
    fun `选中状态只存 id 不存第二份素材对象`() {
        val state = stripComments(viewModelSrc().substringBefore("@HiltViewModel"))
        assertTrue("必须有 selectedMaterialId", state.contains("val selectedMaterialId: String?"))
        assertTrue("必须有 materials 列表", state.contains("val materials: List<MaterialItem>"))
        assertTrue(
            "不能再留一份 frogAsset（与 selectedMaterialId 会是两个事实来源）",
            !state.contains("val frogAsset"),
        )
        assertTrue("不能再用 allFrogs 拼接（列表已经是唯一来源）", !state.contains("val allFrogs"))
    }

    // ---------------------------------------------------------------- 删除

    @Test
    fun `删除必须先出列表再做 IO`() {
        val b = stripComments(body(viewModelSrc(), "removeMaterialFromList"))
        val update = b.indexOf("materials = remaining")
        val io = b.indexOf("importedFrogRepository.delete")
        assertTrue("必须先更新列表", update >= 0)
        assertTrue("必须有落盘删除", io >= 0)
        assertTrue("列表更新必须早于落盘删除（否则点了叉号缩略图还杵着）", update < io)
    }

    @Test
    fun `删除选中项时必须清空选中态`() {
        val b = stripComments(body(viewModelSrc(), "removeMaterialFromList"))
        assertTrue(
            "wasSelected 时 selectedMaterialId 必须置空（不能指向已删除的 id）",
            Regex("""selectedMaterialId\s*=\s*if\s*\(wasSelected\)\s*null""").containsMatchIn(b),
        )
    }

    @Test
    fun `删除选中项后优先接续前一个否则清空`() {
        val b = stripComments(body(viewModelSrc(), "removeMaterialFromList"))
        assertTrue(
            "必须选被删项的**前一个**（用户视线仍在原位置）",
            Regex("""remaining\.getOrNull\(index\s*-\s*1\)""").containsMatchIn(b),
        )
        assertTrue("没有前一个时退回第一个", b.contains("?: remaining.first()"))
        assertTrue("列表空了必须清画布", b.contains("clearForegroundMaterial()"))
        assertTrue("接续必须真的把它装进画布", b.contains("installMaterial(next)"))
    }

    @Test
    fun `只有导入素材才落盘删除`() {
        val b = stripComments(body(viewModelSrc(), "removeMaterialFromList"))
        assertTrue(
            "内置素材没有文件，必须用 !item.builtIn 挡住 delete",
            Regex("""if\s*\(!item\.builtIn\)""").containsMatchIn(b),
        )
    }

    // ---------------------------------------------------------------- 回收

    @Test
    fun `换素材时旧位图必须延迟回收`() {
        val b = stripComments(body(viewModelSrc(), "installMaterial"))
        assertTrue("installMaterial 必须回收上一张", b.contains("retireBitmaps(previous)"))
        assertTrue("同一实例不能重复回收", b.contains("previous !== bitmap"))
    }

    @Test
    fun `回收前必须等渲染管线空闲`() {
        val b = stripComments(body(viewModelSrc(), "retireBitmaps"))
        val wait = b.indexOf("awaitPipelineIdle()")
        val recycle = b.indexOf(".recycle()")
        assertTrue("retireBitmaps 必须等管线空闲", wait >= 0)
        assertTrue("等待必须早于 recycle", wait < recycle)
        // 管线空闲之前 recycle = native 层读到已释放内存（SIGSEGV，抓不到堆栈）
        val idle = stripComments(body(viewModelSrc(), "awaitPipelineIdle"))
        assertTrue("必须等预览渲染结束", idle.contains("renderJob?.join()"))
        assertTrue("必须等全量融图释放锁", idle.contains("fuseMutex.withLock"))
        assertTrue("必须有超时兜底", idle.contains("withTimeoutOrNull"))
    }

    // ---------------------------------------------------------------- UI

    @Test
    fun `素材栏每一项都有删除角标且自己消费点击`() {
        val s = stripComments(screenSrc())
        assertTrue("素材栏必须遍历 materials", s.contains("items(state.materials"))
        assertTrue("必须有删除回调", s.contains("onRemoveMaterial(item.id)"))
        assertTrue(
            "删除角标必须是 Material Icons 的 Close",
            s.contains("Icons.Default.Close"),
        )
        // 角标自带 clickable 才会吃掉这次点击；否则会先触发外层的"选中"
        val badge = s.substringAfter("align(Alignment.TopEnd)")
            .substringBefore("contentDescription = \"删除")
        assertTrue("角标必须自己消费点击（interactionSource + indication = null）",
            badge.contains("clickable(") && badge.contains("onRemoveMaterial"))
        assertTrue(
            "选中高亮必须由 selectedMaterialId 决定",
            s.contains("item.id == state.selectedMaterialId"),
        )
    }
}
