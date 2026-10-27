package com.naiwa.core.common.model.project

import org.json.JSONArray
import org.json.JSONObject

/**
 * 节点类型（DAG 的最小执行单元）。
 *
 * 每个类型对应一个 shader（或 pass-through），参数通过 [RenderNode.params] 传入，
 * 取值键名由各类型约定（见 `RenderGraphEngine` 的文档）。
 */
enum class NodeType {
    /** 背景源：从 Project.backgroundUri 解码。 */
    SOURCE_BACKGROUND,

    /** 前景源：从 Project.foregroundUri 解码。 */
    SOURCE_FOREGROUND,

    /** 绿幕抠像（YCbCr 色键 + spill suppression）。 */
    GREEN_SCREEN_KEY,

    /** 3×3 颜色矩阵变换（AI 只允许出这个节点）。 */
    COLOR_MATRIX,

    /** 高斯/盒式模糊（背景光、低频）。 */
    BLUR,

    /** 光包裹（边缘环绕溢出光，Screen）。 */
    LIGHT_WRAP,

    /** 边缘光（法向·光向，Add）。 */
    RIM_LIGHT,

    /** 光斑模糊（Bokeh 圆盘采样，立体反光层）。 */
    BOKEH,

    /** 射线模糊（沿光源方向发散，阴影/背光层）。 */
    RADIAL_BLUR,

    /** 纯色填充（阴影层用黑色）。 */
    SOLID_COLOR,

    /** 镜头光晕（定位到光源坐标）。 */
    LENS_FLARE,

    /** 辉光（高光区模糊后 Screen）。 */
    GLOW,

    /** 混合：按 params["mode"] = screen|multiply|add|over 合成多输入。 */
    MIX,

    /**
     * 景深（差异化虚化）：主体清晰、背景模糊，边缘 smoothstep 过渡。
     *
     * params:
     *  - `radius`：最大弥散半径（像素，1080p 基准，0 = 关闭）
     *  - `focusNear` / `focusFar`：主体/背景的 Mask 阈值（默认 0.9 / 0.12）
     */
    DOF,

    /**
     * 全局调色（统一 P1 与 P2 的色空间）。
     *
     * params:
     *  - `m0..m8`：3×3 矩阵（行优先）
     *  - `contrast` / `saturation`：线性空间以 18% 灰为支点缩放
     */
    COLOR_GRADE,

    /**
     * Looks 加光（漏光/胶片光晕，Screen）。
     *
     * params:
     *  - `strength`：0 = 关闭
     *  - `posX` / `posY`：漏光中心（归一化）
     *  - `color`：漏光色（sRGB，如 0xFFFF9A4D）
     *  - `phase`：UV 微调相位
     */
    LOOKS,

    /**
     * 暗角（压暗四周，收敛视觉中心）。
     *
     * params:
     *  - `radius`：开始压暗的归一化距离（默认 0.72）
     *  - `softness`：过渡宽度（默认 0.45）
     *  - `strength`：0 = 关闭
     */
    VIGNETTE,

    /**
     * 第三方滤镜（wasabeef/android-gpuimage 封装）。
     *
     * params:
     *  - `spec`：滤镜描述。两种语法：
     *    · `gp:sepia` / `gp:contrast:1.3` —— 走 GPUImage 内置滤镜类
     *    · `es:brightness(0.12);saturation(1.2);sepia(0.6)` —— 走内置 EffectString 解析器
     *      （`android-gpuimage-plus` 的文本滤镜语法在此库中不存在，见交付说明）
     */
    THIRD_PARTY_FILTER,

    /** 最终输出（FXAA 抗锯齿 + sRGB 编码）。 */
    FXAA,
}

/**
 * 渲染节点（DAG 顶点）。
 *
 * @param id 唯一标识（如 "P2_Glow"）
 * @param type 节点类型
 * @param params 参数表。统一用 `Map<String, String>`：JSON 天然可序列化，
 *   取值由各类型约定并由 [float] 等辅助方法解析 —— 避免为每类节点维护一个
 *   data class 的样板代码，同时保持"所有参数都能无损存进工程文件"。
 * @param inputs 输入节点 ID（DAG 入边）。顺序即 Mix 的图层顺序（底 → 顶）。
 */
data class RenderNode(
    val id: String,
    val type: NodeType,
    val params: Map<String, String> = emptyMap(),
    val inputs: List<String> = emptyList(),
) {
    fun float(key: String, default: Float): Float = params[key]?.toFloatOrNull() ?: default
    fun int(key: String, default: Int): Int = params[key]?.toIntOrNull() ?: default
    fun str(key: String, default: String): String = params[key] ?: default
    fun bool(key: String, default: Boolean): Boolean = params[key]?.toBooleanStrictOrNull() ?: default

    /** 内容指纹：参数或类型变化 → 指纹变化 → 该节点及下游需要重算。 */
    val fingerprint: Int
        get() = (id + "|" + type.name + "|" + params.toSortedMap() + "|" + inputs.joinToString(",")).hashCode()

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("params", JSONObject().apply { params.forEach { (k, v) -> put(k, v) } })
        put("inputs", JSONArray().apply { inputs.forEach { put(it) } })
    }

    companion object {
        internal fun fromJson(o: JSONObject): RenderNode = RenderNode(
            id = o.getString("id"),
            type = NodeType.valueOf(o.getString("type")),
            params = o.optJSONObject("params")?.let { p ->
                buildMap { p.keys().forEach { k -> put(k, p.optString(k)) } }
            } ?: emptyMap(),
            inputs = o.optJSONArray("inputs")?.let { a ->
                List(a.length()) { a.getString(it) }
            } ?: emptyList(),
        )
    }
}

/** 有向边（DAG 出边声明；与 [RenderNode.inputs] 冗余，序列化时两者都写，加载时以 inputs 为准）。 */
data class Edge(val fromNodeId: String, val toNodeId: String)

/**
 * 工程文件（非破坏性编辑的**唯一事实来源**）。
 *
 * 设计原则：**UI 只改这个对象，渲染引擎按需重算** —— 工程里没有任何"像素结果"，
 * 只有"怎么算"的描述。因此：拖动滑杆 = 改 params；换背景 = 改 uri；
 * 保存工程 = 整个对象序列化成 JSON（几 KB），与分辨率无关。
 *
 * @param id 工程 ID
 * @param backgroundUri 背景图 URI（content:// 或 file://）
 * @param foregroundUri 前景（绿幕立绘）URI
 * @param nodes DAG 顶点
 * @param edges DAG 边
 * @param outputNodeId 最终输出节点（通常是 FXAA 节点）
 */
data class Project(
    val id: String,
    val backgroundUri: String,
    val foregroundUri: String,
    val nodes: List<RenderNode>,
    val edges: List<Edge> = emptyList(),
    val outputNodeId: String = DEFAULT_OUTPUT_ID,
    val version: Int = CURRENT_VERSION,
) {

    /** 按 ID 索引（渲染时高频访问）。 */
    val nodeMap: Map<String, RenderNode> by lazy { nodes.associateBy { it.id } }

    fun node(id: String): RenderNode? = nodeMap[id]

    /** 修改某个节点的参数，返回新 Project（不可变更新，天然支持撤销/重做）。 */
    fun withNodeParams(nodeId: String, params: Map<String, String>): Project {
        val idx = nodes.indexOfFirst { it.id == nodeId }
        if (idx < 0) return this
        val updated = nodes.toMutableList()
        updated[idx] = updated[idx].copy(params = params)
        return copy(nodes = updated)
    }

    /** 只改一个参数键（滑杆拖动的主入口）。 */
    fun withParam(nodeId: String, key: String, value: String): Project {
        val node = node(nodeId) ?: return this
        return withNodeParams(nodeId, node.params + (key to value))
    }

    /** 结构化校验：环检测 + 悬空输入 + 输出节点存在性。返回错误列表（空 = 合法）。 */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        val ids = nodes.map { it.id }.toSet()
        if (nodes.size != ids.size) errors += "节点 ID 重复"
        nodes.forEach { n ->
            n.inputs.forEach { i -> if (i !in ids) errors += "节点 ${n.id} 的输入 $i 不存在" }
        }
        if (outputNodeId !in ids) errors += "输出节点 $outputNodeId 不存在"
        if (RenderGraphTopology.hasCycle(nodes)) errors += "DAG 存在环"
        return errors
    }

    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("id", id)
        put("backgroundUri", backgroundUri)
        put("foregroundUri", foregroundUri)
        put("outputNodeId", outputNodeId)
        put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        put("edges", JSONArray().apply {
            edges.forEach { e ->
                put(JSONObject().apply {
                    put("from", e.fromNodeId)
                    put("to", e.toNodeId)
                })
            }
        })
    }.toString(2)

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_OUTPUT_ID = "OUT_FXAA"

        /**
         * 解析工程 JSON。
         *
         * 返回 null 的三种情况（都视为"加载失败"，由 UI 提示用户，而不是加载出半残工程）：
         *  - JSON 语法损坏 / 字段类型不符；
         *  - **nodes 为空**（空图无法渲染）；
         *  - **outputNodeId 不在 nodes 中**（输出悬空）。
         */
        fun fromJson(text: String): Project? = runCatching {
            if (text.isBlank()) return null
            val o = JSONObject(text)
            val nodesArr = o.getJSONArray("nodes")
            val nodes = List(nodesArr.length()) { RenderNode.fromJson(nodesArr.getJSONObject(it)) }
            if (nodes.isEmpty()) return null
            val outputId = o.optString("outputNodeId", DEFAULT_OUTPUT_ID)
            if (nodes.none { it.id == outputId }) return null
            val edgesArr = o.optJSONArray("edges")
            val edges = if (edgesArr == null) emptyList() else {
                List(edgesArr.length()) { i ->
                    val e = edgesArr.getJSONObject(i)
                    Edge(e.getString("from"), e.getString("to"))
                }
            }
            Project(
                id = o.optString("id", "project"),
                backgroundUri = o.optString("backgroundUri", ""),
                foregroundUri = o.optString("foregroundUri", ""),
                nodes = nodes,
                edges = edges,
                outputNodeId = outputId,
                version = o.optInt("version", CURRENT_VERSION),
            )
        }.getOrNull()
    }
}

/**
 * DAG 拓扑工具（纯函数，可 JVM 单测）。
 *
 * 独立成对象而不是塞进执行器：拓扑排序/环检测是**图算法**，
 * 与 OpenGL、Android 无关，放在这里就能在没有设备的环境下验证正确性。
 */
object RenderGraphTopology {

    /**
     * Kahn 拓扑排序。
     *
     * @return 拓扑序；**存在环时返回 null**（调用方必须据此放弃渲染，
     *   而不是用"部分顺序"硬跑 —— 那会产出顺序错误的图层）
     */
    fun sort(nodes: List<RenderNode>): List<RenderNode>? {
        val byId = nodes.associateBy { it.id }
        val indegree = HashMap<String, Int>(nodes.size)
        val children = HashMap<String, MutableList<String>>(nodes.size)
        nodes.forEach { n ->
            indegree[n.id] = n.inputs.count { it in byId }
            n.inputs.forEach { i ->
                if (i in byId) children.getOrPut(i) { ArrayList() } += n.id
            }
        }
        val queue = ArrayDeque(nodes.filter { (indegree[it.id] ?: 0) == 0 })
        val ordered = ArrayList<RenderNode>(nodes.size)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            ordered += n
            children[n.id]?.forEach { child ->
                val d = (indegree[child] ?: 0) - 1
                indegree[child] = d
                if (d == 0) byId[child]?.let { queue += it }
            }
        }
        return if (ordered.size == nodes.size) ordered else null
    }

    fun hasCycle(nodes: List<RenderNode>): Boolean = sort(nodes) == null

    /** 某节点的全部下游（含间接），用于脏标记传播。 */
    fun downstreamOf(nodes: List<RenderNode>, nodeId: String): Set<String> {
        val children = HashMap<String, MutableList<String>>()
        nodes.forEach { n -> n.inputs.forEach { i -> children.getOrPut(i) { ArrayList() } += n.id } }
        val result = LinkedHashSet<String>()
        val stack = ArrayDeque<String>().apply { add(nodeId) }
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            children[cur]?.forEach { c ->
                if (result.add(c)) stack += c
            }
        }
        return result
    }
}
