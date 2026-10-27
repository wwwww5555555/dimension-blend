package com.naiwa.core.render

import com.naiwa.core.common.model.project.NodeType
import com.naiwa.core.common.model.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局后处理链的**顺序契约**。
 *
 * 顺序错了不会崩溃、也不会报错，只会"画面看着不对"——比如先暗角后景深，
 * 虚化会把暗角的黑边一起糊开，出现一圈发灰的脏边。所以用拓扑序把它钉死。
 */
class PostChainTest {

    private fun project() = ProjectPresets.fusionProject(
        id = "test",
        backgroundUri = "bg.jpg",
        foregroundUri = "fg.png",
    )

    @Test
    fun `后处理链顺序为 DOF 调色 Looks 暗角 FXAA`() {
        val passes = RenderGraphEngine().plan(project()).passes
        val ids = passes.map { it.node.id }
        val idx = { id: String -> ids.indexOf(id) }

        assertTrue("链上必须包含景深节点", idx(ProjectPresets.POST_DOF) >= 0)
        assertTrue("链上必须包含调色节点", idx(ProjectPresets.POST_GRADE) >= 0)
        assertTrue("链上必须包含 Looks 节点", idx(ProjectPresets.POST_LOOKS) >= 0)
        assertTrue("链上必须包含暗角节点", idx(ProjectPresets.POST_VIGNETTE) >= 0)

        assertTrue("景深必须在调色之前", idx(ProjectPresets.POST_DOF) < idx(ProjectPresets.POST_GRADE))
        assertTrue("调色必须在 Looks 之前", idx(ProjectPresets.POST_GRADE) < idx(ProjectPresets.POST_LOOKS))
        assertTrue("Looks 必须在暗角之前", idx(ProjectPresets.POST_LOOKS) < idx(ProjectPresets.POST_VIGNETTE))
    }

    @Test
    fun `FXAA 必须是最后一个 pass`() {
        val ids = RenderGraphEngine().plan(project()).passes.map { it.node.id }
        assertEquals("最后一步必须是 FXAA", Project.DEFAULT_OUTPUT_ID, ids.last())
    }

    @Test
    fun `后处理在合成之后`() {
        val ids = RenderGraphEngine().plan(project()).passes.map { it.node.id }
        val mixReflect = ids.indexOf(ProjectPresets.MIX_REFLECT)
        assertTrue("四层合成必须先于全部后处理", mixReflect >= 0 && mixReflect < ids.indexOf(ProjectPresets.POST_DOF))
    }

    @Test
    fun `景深节点带 Mask 输入`() {
        val dof = project().nodes.first { it.id == ProjectPresets.POST_DOF }
        assertEquals("景深必须有第二输入（Mask 源）", 2, dof.inputs.size)
        assertEquals(ProjectPresets.P2_MAIN, dof.inputs[1])
    }

    @Test
    fun `关掉景深不改变链结构`() {
        val p = ProjectPresets.fusionProject("t", "bg.jpg", "fg.png", dofRadius = 0f)
        val passes = RenderGraphEngine().plan(p).passes.map { it.node.id }
        // radius=0 由执行期判断跳过，图结构仍完整（保证参数可来回切换而缓存不失效）
        assertTrue(passes.contains(ProjectPresets.POST_DOF))
        assertEquals(Project.DEFAULT_OUTPUT_ID, passes.last())
    }

    @Test
    fun `节点类型齐全`() {
        val types = project().nodes.map { it.type }.toSet()
        assertTrue(types.contains(NodeType.DOF))
        assertTrue(types.contains(NodeType.COLOR_GRADE))
        assertTrue(types.contains(NodeType.LOOKS))
        assertTrue(types.contains(NodeType.VIGNETTE))
    }

    /**
     * 景深不得回到"整张背景糊死"：半径必须 ≤5px，且必须带环形带宽（只糊交界）。
     * 这是上一版的致命回归点 —— 半径 9px + 全背景模糊会让背景彻底变砖。
     */
    @Test
    fun `景深半径受限于5px且带环形带宽`() {
        val dof = project().nodes.first { it.id == ProjectPresets.POST_DOF }
        val radius = dof.params["radius"]?.toFloat() ?: error("景深缺少 radius 参数")
        assertTrue("景深半径必须 ≤5px（实际 $radius）", radius <= 5f)
        assertEquals("必须显式给出环形带宽", "20", dof.params["ringPx"])
    }
}
