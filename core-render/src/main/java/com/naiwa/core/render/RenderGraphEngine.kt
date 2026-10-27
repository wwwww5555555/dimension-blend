package com.naiwa.core.render

import com.naiwa.core.common.model.project.NodeType
import com.naiwa.core.common.model.project.Project
import com.naiwa.core.common.model.project.RenderGraphTopology
import com.naiwa.core.common.model.project.RenderNode

/**
 * DAG 渲染的**决策层**（纯逻辑，不碰 GL，可纯 JVM 单测）。
 *
 * 三件事：
 *  1. **拓扑排序**：确定执行顺序（复用 [RenderGraphTopology]，带环检测）；
 *  2. **脏标记传播**：只有"参数变了的节点 + 其全部下游"需要重算，
 *     其余节点直接复用上次的 FBO 纹理 —— 这是"改一个滑杆不重抠图"的关键；
 *  3. **Shader 合并优化**：链式 `COLOR_MATRIX`（上游只被这一个下游消费）在计划阶段
 *     就乘成一个矩阵，运行时少一个 pass、少一张全分辨率纹理。
 *
 * 执行器（[RenderGraphExecutor]）只消费 [Plan]，不自己做判断 ——
 * 决策与执行分离，才能让"局部重算"这件事被单测钉死。
 */
class RenderGraphEngine {

    /** 一个 pass 的执行计划。 */
    data class PassPlan(
        val node: RenderNode,
        /** 本次实际输入（合并优化后可能跨过被跳过的上游）。 */
        val inputs: List<String>,
        /** true = 结果可从缓存复用，不需要重画。 */
        val reuseCached: Boolean,
        /** 合并后的 3×3 矩阵（仅 COLOR_MATRIX 链有值，行优先）。 */
        val mergedMatrix: FloatArray? = null,
    )

    data class Plan(
        /** 拓扑序（已剔除被合并掉的上游节点）。 */
        val passes: List<PassPlan>,
        /** 需要重算的节点 ID 集合。 */
        val dirtyNodes: Set<String>,
        /** 非空表示计划不可执行（成环/输出节点缺失）。 */
        val error: String? = null,
    )

    /** 上次各节点指纹（用于脏判定）。 */
    private var lastFingerprints: Map<String, Int> = emptyMap()

    /** 全量失效（切换工程 / 改分辨率 / 首次执行）。 */
    fun reset() {
        lastFingerprints = emptyMap()
    }

    /**
     * 生成执行计划。
     *
     * @param project 当前工程
     * @param changedNodeId 本次改动的节点（滑杆拖动时传入）；null = 全量重算
     */
    fun plan(project: Project, changedNodeId: String? = null): Plan {
        val ordered = RenderGraphTopology.sort(project.nodes)
            ?: return Plan(emptyList(), emptySet(), "DAG 存在环，无法渲染")
        if (project.node(project.outputNodeId) == null) {
            return Plan(emptyList(), emptySet(), "输出节点 ${project.outputNodeId} 不存在")
        }

        // ---- ① 脏标记 ----
        val currentFp = project.nodes.associate { it.id to it.fingerprint }
        val dirty: Set<String> = when {
            lastFingerprints.isEmpty() -> project.nodes.map { it.id }.toSet()
            changedNodeId != null -> {
                // 只有指纹真的变了才算脏（同一值重复 set 不应触发重算）
                if (currentFp[changedNodeId] == lastFingerprints[changedNodeId] &&
                    lastFingerprints.containsKey(changedNodeId)
                ) {
                    emptySet()
                } else {
                    RenderGraphTopology.downstreamOf(project.nodes, changedNodeId) + changedNodeId
                }
            }
            // 没有显式指定 changed 但指纹有变化 → 变化节点及其下游
            else -> {
                val changed = project.nodes.filter { currentFp[it.id] != lastFingerprints[it.id] }
                    .map { it.id }
                changed.flatMap { RenderGraphTopology.downstreamOf(project.nodes, it) + it }.toSet()
            }
        }
        lastFingerprints = currentFp

        // ---- ② 矩阵合并：上游 COLOR_MATRIX 被唯一消费时并入下游 ----
        val consumerCount = HashMap<String, Int>()
        project.nodes.forEach { n -> n.inputs.forEach { consumerCount[it] = (consumerCount[it] ?: 0) + 1 } }

        val skipped = HashSet<String>()
        val mergedMatrices = HashMap<String, FloatArray>()
        ordered.forEach { n ->
            if (n.type != NodeType.COLOR_MATRIX || n.inputs.size != 1) return@forEach
            val up = project.node(n.inputs[0]) ?: return@forEach
            if (up.type != NodeType.COLOR_MATRIX) return@forEach
            if ((consumerCount[up.id] ?: 0) != 1) return@forEach   // 上游还被别的节点用，不能合并
            if (up.id in skipped) return@forEach

            val upMat = mergedMatrices[up.id] ?: matrixOf(up)
            val mat = matrixOf(n)
            mergedMatrices[n.id] = multiply3x3(mat, upMat)   // 先施加 upstream，再施加自身
            skipped += up.id
        }

        // ---- ③ 生成 pass 计划 ----
        val passes = ArrayList<PassPlan>(ordered.size)
        ordered.forEach { n ->
            if (n.id in skipped) return@forEach
            // 输入若被合并掉，向上追溯直到未被跳过的祖先
            val effectiveInputs = n.inputs.map { input ->
                var cur = input
                var guard = 0
                while (cur in skipped && guard++ < 32) {
                    cur = project.node(cur)?.inputs?.firstOrNull() ?: break
                }
                cur
            }
            passes += PassPlan(
                node = n,
                inputs = effectiveInputs,
                reuseCached = n.id !in dirty,
                mergedMatrix = mergedMatrices[n.id],
            )
        }
        return Plan(passes, dirty)
    }

    // ---------------------------------------------------------------- 矩阵工具

    /** 从节点参数读 3×3（params: m0..m8，缺省单位阵）。 */
    fun matrixOf(node: RenderNode): FloatArray = FloatArray(9) { i ->
        node.float("m$i", if (i % 4 == 0) 1f else 0f)
    }

    companion object {
        /** 3×3 行优先矩阵乘法：`a × b`（先 b 后 a 的复合顺序）。 */
        fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
            if (a.size < 9 || b.size < 9) return FloatArray(9) { i -> if (i % 4 == 0) 1f else 0f }
            val out = FloatArray(9)
            for (r in 0 until 3) {
                for (c in 0 until 3) {
                    var sum = 0f
                    for (k in 0 until 3) {
                        sum += a[r * 3 + k] * b[k * 3 + c]
                    }
                    out[r * 3 + c] = sum
                }
            }
            return out
        }
    }
}
