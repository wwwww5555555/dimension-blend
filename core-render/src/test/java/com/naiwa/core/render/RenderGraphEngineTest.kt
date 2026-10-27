package com.naiwa.core.render

import com.naiwa.core.common.model.project.Project
import com.naiwa.core.common.model.project.RenderGraphTopology
import com.naiwa.core.common.model.project.RenderNode
import com.naiwa.core.common.model.project.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DAG 引擎契约测试（纯 JVM，不需要设备/GL）。
 *
 * 覆盖"局部重算"这件事的三个必要条件：
 *  1. 拓扑序正确（父先于子）；
 *  2. 改一个节点只重算它 + 下游，其余命中缓存；
 *  3. 链式 ColorMatrix 被合并（少一个 pass），矩阵乘积正确。
 */
class RenderGraphEngineTest {

    private fun project() = ProjectPresets.fusionProject(
        id = "p1",
        backgroundUri = "file://bg.jpg",
        foregroundUri = "file://fg.png",
    )

    // ------------------------------------------------------------ 拓扑

    @Test
    fun `拓扑序保证父先于子`() {
        val p = project()
        val ordered = RenderGraphTopology.sort(p.nodes)
        assertNotNull("默认图应无环", ordered)
        val pos = ordered!!.withIndex().associate { it.value.id to it.index }
        p.nodes.forEach { n ->
            n.inputs.forEach { input ->
                assertTrue(
                    "父 $input 必须先于子 ${n.id}",
                    (pos[input] ?: -1) < (pos[n.id] ?: Int.MAX_VALUE),
                )
            }
        }
    }

    @Test
    fun `环被检出`() {
        val cyclic = listOf(
            RenderNode("A", NodeType.COLOR_MATRIX, inputs = listOf("B")),
            RenderNode("B", NodeType.COLOR_MATRIX, inputs = listOf("A")),
        )
        assertTrue(RenderGraphTopology.hasCycle(cyclic))
        assertNull(RenderGraphTopology.sort(cyclic))
    }

    @Test
    fun `非法工程拒绝渲染`() {
        val p = project().copy(outputNodeId = "不存在的节点")
        assertTrue(p.validate().isNotEmpty())
    }

    // ------------------------------------------------------------ 脏传播

    @Test
    fun `首次执行全量重算`() {
        val engine = RenderGraphEngine()
        val plan = engine.plan(project())
        assertEquals("首次全部脏", project().nodes.size, plan.dirtyNodes.size)
        assertTrue(plan.passes.none { it.reuseCached })
    }

    @Test
    fun `改边缘光只重算它和下游`() {
        val engine = RenderGraphEngine()
        val p0 = project()
        engine.plan(p0)   // 建立基线

        val p1 = p0.withParam(ProjectPresets.P2_MAIN, "strength", "0.5")
        val plan = engine.plan(p1, ProjectPresets.P2_MAIN)

        assertTrue("改动节点要重算", ProjectPresets.P2_MAIN in plan.dirtyNodes)
        assertTrue("下游 P2_WRAP 要重算", ProjectPresets.P2_WRAP in plan.dirtyNodes)
        assertTrue("下游 MIX 要重算", ProjectPresets.MIX_REFLECT in plan.dirtyNodes)
        assertFalse("P1 分支不受影响", ProjectPresets.P1_FLARE in plan.dirtyNodes)
        assertFalse("抠图不受影响", ProjectPresets.P2_KEY in plan.dirtyNodes)

        // P1 分支的 pass 必须标记为可复用
        val p1Passes = plan.passes.filter { it.node.id.startsWith("P1_") }
        assertTrue("P1 全部命中缓存", p1Passes.all { it.reuseCached })
    }

    @Test
    fun `参数没变不触发重算`() {
        val engine = RenderGraphEngine()
        val p0 = project()
        engine.plan(p0)
        // 写入相同值：fingerprint 不变 → 不应有任何脏节点
        val p1 = p0.withParam(ProjectPresets.P2_MAIN, "strength", p0.node(ProjectPresets.P2_MAIN)!!.params["strength"]!!)
        val plan = engine.plan(p1, ProjectPresets.P2_MAIN)
        assertTrue("同值写入不应触发重算", plan.dirtyNodes.isEmpty())
    }

    // ------------------------------------------------------------ 矩阵合并

    @Test
    fun `链式ColorMatrix合并为一个pass`() {
        // A → B 都是 ColorMatrix，B 只被 C 消费
        val nodes = listOf(
            RenderNode("SRC", NodeType.SOURCE_BACKGROUND),
            RenderNode("A", NodeType.COLOR_MATRIX, params = mapOf("m0" to "2"), inputs = listOf("SRC")),
            RenderNode("B", NodeType.COLOR_MATRIX, params = mapOf("m4" to "3"), inputs = listOf("A")),
            RenderNode("OUT", NodeType.FXAA, inputs = listOf("B")),
        )
        val p = Project(
            id = "m", backgroundUri = "", foregroundUri = "",
            nodes = nodes, outputNodeId = "OUT",
        )
        val plan = RenderGraphEngine().plan(p)
        val ids = plan.passes.map { it.node.id }
        assertFalse("上游 A 应被合并掉", "A" in ids)
        assertTrue("B 保留", "B" in ids)

        // B 的输入应追溯到 SRC
        val bPlan = plan.passes.first { it.node.id == "B" }
        assertEquals(listOf("SRC"), bPlan.inputs)

        // 合并矩阵 = B × A（A 先作用）：A 是 R×2，B 是 G×3，对角应为 identity 的行列
        val m = bPlan.mergedMatrix!!
        assertEquals("R 通道 = A 的 2", 2f, m[0], 1e-4f)
        assertEquals("G 通道 = B 的 3", 3f, m[4], 1e-4f)
        assertEquals("B 通道 = 1", 1f, m[8], 1e-4f)
    }

    @Test
    fun `上游被多处引用时不合并`() {
        val nodes = listOf(
            RenderNode("SRC", NodeType.SOURCE_BACKGROUND),
            RenderNode("A", NodeType.COLOR_MATRIX, inputs = listOf("SRC")),
            RenderNode("B1", NodeType.COLOR_MATRIX, inputs = listOf("A")),
            RenderNode("B2", NodeType.COLOR_MATRIX, inputs = listOf("A")),
            RenderNode("OUT", NodeType.MIX, inputs = listOf("B1", "B2")),
        )
        val p = Project(
            id = "m2", backgroundUri = "", foregroundUri = "",
            nodes = nodes, outputNodeId = "OUT",
        )
        val ids = RenderGraphEngine().plan(p).passes.map { it.node.id }
        assertTrue("A 有两个消费者，不能被合并", "A" in ids)
    }

    @Test
    fun `矩阵乘法顺序正确`() {
        val a = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)
        val b = floatArrayOf(3f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 3f)
        val m = RenderGraphEngine.multiply3x3(a, b)
        assertEquals(6f, m[0], 1e-4f)
        assertEquals(6f, m[4], 1e-4f)
        assertEquals(6f, m[8], 1e-4f)
    }

    // ------------------------------------------------------------ 序列化

    @Test
    fun `工程JSON往返一致`() {
        val p = project()
        val json = p.toJson()
        val back = Project.fromJson(json)
        assertNotNull("应能解析", back)
        assertEquals(p.id, back!!.id)
        assertEquals(p.backgroundUri, back.backgroundUri)
        assertEquals(p.nodes.size, back.nodes.size)
        assertEquals(p.outputNodeId, back.outputNodeId)
        // 每个节点的参数必须原样回来（否则滑杆值会在存/读后丢失）
        p.nodes.forEach { n ->
            val b = back.node(n.id)
            assertNotNull("节点 ${n.id} 丢失", b)
            assertEquals("节点 ${n.id} 参数不一致", n.params, b!!.params)
            assertEquals("节点 ${n.id} 输入不一致", n.inputs, b.inputs)
        }
    }

    @Test
    fun `损坏JSON返回null不抛异常`() {
        assertNull(Project.fromJson(""))
        assertNull(Project.fromJson("{不是 json"))
        assertNull(Project.fromJson("{\"nodes\":[]}"))
    }
}
