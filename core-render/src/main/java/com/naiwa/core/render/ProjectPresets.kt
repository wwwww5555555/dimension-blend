package com.naiwa.core.render

import com.naiwa.core.common.model.project.Edge
import com.naiwa.core.common.model.project.NodeType
import com.naiwa.core.common.model.project.Project
import com.naiwa.core.common.model.project.RenderNode

/**
 * P1/P2 渲染树的 DAG 预设（规格三）。
 *
 * ```
 *  BG_SRC ─→ P1_FLARE ─→ P1_GLOW ─→ P1_CC ─→ P1_OUT ┐
 *                                                     ├─ MIX_SHADOW(Multiply) ─┐
 *  FG_SRC ─→ P2_KEY ─→ P2_MATRIX ─→ P2_MAIN ─→ P2_WRAP ─→ P2_OUT ─────────────┤
 *                                      │                                      ├─ MIX_MAIN(over) ─→ MIX_REFLECT(screen) ─→ OUT_FXAA
 *                                      ├─ P2_BOKEH ─→ P2B ────────────────────┘
 *                                      └─ P2_SOLID ─→ P2_RADIAL ─→ P2C ── (阴影)
 * ```
 *
 * 注意 [NodeType.MIX] 是二元节点，所以"四层合成"被拆成三个串联 MIX —— 顺序严格按
 * 规格三：P1×阴影 → over 主层 → screen 反光。
 */
object ProjectPresets {

    const val BG_SRC = "P1_Source"
    const val FG_SRC = "P2_Source"
    const val P2_KEY = "P2_Key"
    const val P2_MATRIX = "P2_ColorMatrix"
    const val P2_MAIN = "P2_Main"
    const val P2_WRAP = "P2_LightWrap"
    const val P2_OUT = "P2_Out"
    const val P2_BOKEH = "P2b_Bokeh"
    const val P2B = "P2b_Reflect"
    const val P2_SOLID = "P2c_Solid"
    const val P2_RADIAL = "P2c_RadialBlur"
    const val P2C = "P2c_Shadow"
    const val P1_FLARE = "P1_LensFlare"
    const val P1_GLOW = "P1_Glow"
    const val P1_CC = "P1_ColorGrade"
    const val P1_OUT = "P1_Out"
    const val MIX_SHADOW = "MIX_Shadow"
    const val MIX_MAIN = "MIX_Main"
    const val MIX_REFLECT = "MIX_Reflect"

    // 全局后处理节点（合成之后、FXAA 之前，顺序固定）
    const val POST_DOF = "Post_Dof"
    const val POST_GRADE = "Post_ColorGrade"
    const val POST_LOOKS = "Post_Looks"
    const val POST_VIGNETTE = "Post_Vignette"

    /**
     * 构建默认溶图工程。
     *
     * @param rimStrength 边缘光强度（滑杆"边缘光强"直接写这个节点的 params）
     * @param shadowStrength 阴影浓度
     * @param bokehRadius 反光 Bokeh 半径
     * @param dofRadius 景深半径（像素；0 = 关闭，低端机应传 0）
     * @param looksStrength 漏光强度（0 = 关闭）
     */
    fun fusionProject(
        id: String,
        backgroundUri: String,
        foregroundUri: String,
        rimStrength: Float = 0.35f,
        shadowStrength: Float = 0.5f,
        bokehRadius: Float = 6f,
        dofRadius: Float = 4f,
        looksStrength: Float = 0f,
        /** 阴影环境色（背景暗部中位色）。**禁止传 0x000000**：纯黑 Multiply 会抹掉底图细节。 */
        shadowColor: Int = 0xFF1A1C22.toInt(),
    ): Project {
        val nodes = listOf(
            // ---------- P1 背景分支 ----------
            RenderNode(BG_SRC, NodeType.SOURCE_BACKGROUND),
            RenderNode(
                P1_FLARE, NodeType.LENS_FLARE,
                params = mapOf("strength" to "0.55"),
                inputs = listOf(BG_SRC),
            ),
            RenderNode(
                P1_GLOW, NodeType.GLOW,
                params = mapOf("strength" to "0.2"),
                inputs = listOf(P1_FLARE),
            ),
            RenderNode(
                P1_CC, NodeType.COLOR_MATRIX,
                params = colorMatrixParams(identity()),
                inputs = listOf(P1_GLOW),
            ),
            RenderNode(P1_OUT, NodeType.MIX, params = mapOf("mode" to "over"), inputs = listOf(P1_CC)),

            // ---------- P2 人像分支 ----------
            RenderNode(FG_SRC, NodeType.SOURCE_FOREGROUND),
            RenderNode(
                P2_KEY, NodeType.GREEN_SCREEN_KEY,
                params = mapOf("t1" to "0.08", "t2" to "0.20"),
                inputs = listOf(FG_SRC),
            ),
            RenderNode(
                P2_MATRIX, NodeType.COLOR_MATRIX,
                params = colorMatrixParams(identity()) + ("strength" to "0.6"),
                inputs = listOf(P2_KEY),
            ),
            RenderNode(
                P2_MAIN, NodeType.RIM_LIGHT,
                params = mapOf("strength" to rimStrength.toString()),
                inputs = listOf(P2_MATRIX),
            ),
            RenderNode(
                P2_WRAP, NodeType.LIGHT_WRAP,
                params = mapOf("strength" to "0.3"),
                inputs = listOf(P2_MAIN),
            ),
            RenderNode(P2_OUT, NodeType.MIX, params = mapOf("mode" to "over"), inputs = listOf(P2_WRAP)),

            // ---------- P2-b 立体反光 ----------
            RenderNode(
                P2_BOKEH, NodeType.BOKEH,
                params = mapOf("radius" to bokehRadius.toString()),
                inputs = listOf(P2_MAIN),
            ),
            RenderNode(P2B, NodeType.MIX, params = mapOf("mode" to "over"), inputs = listOf(P2_BOKEH)),

            // ---------- P2-c 阴影/背光 ----------
            RenderNode(
                P2_SOLID, NodeType.SOLID_COLOR,
                // 填充**环境暗色**而不是纯黑：base * 0 = 0 会把底图细节整块抹掉（死黑、发脏）
                params = mapOf("color" to "${shadowColor}", "alpha" to "1"),
                inputs = listOf(P2_MAIN),
            ),
            RenderNode(
                P2_RADIAL, NodeType.RADIAL_BLUR,
                params = mapOf("reach" to "1.2"),
                inputs = listOf(P2_SOLID),
            ),
            RenderNode(
                P2C, NodeType.MIX,
                params = mapOf("mode" to "multiply", "opacity" to shadowStrength.toString()),
                inputs = listOf(P2_RADIAL),
            ),

            // ---------- 最终合成链（严格按规格三顺序） ----------
            RenderNode(
                MIX_SHADOW, NodeType.MIX,
                params = mapOf("mode" to "multiply", "opacity" to "1"),
                inputs = listOf(P1_OUT, P2C),
            ),
            RenderNode(
                MIX_MAIN, NodeType.MIX,
                params = mapOf("mode" to "over", "opacity" to "1"),
                inputs = listOf(MIX_SHADOW, P2_OUT),
            ),
            RenderNode(
                MIX_REFLECT, NodeType.MIX,
                params = mapOf("mode" to "screen", "opacity" to "1"),
                inputs = listOf(MIX_MAIN, P2B),
            ),

            // ---------- 全局后处理（合成之后、FXAA 之前，顺序不可调换） ----------
            RenderNode(
                POST_DOF, NodeType.DOF,
                params = mapOf("radius" to dofRadius.toString(), "ringPx" to "20"),
                // 第二输入 = Mask 源（人物层的 alpha）：景深要靠它区分主体/背景
                inputs = listOf(MIX_REFLECT, P2_MAIN),
            ),
            RenderNode(
                POST_GRADE, NodeType.COLOR_GRADE,
                params = colorMatrixParams(identity()) +
                    mapOf("contrast" to "1.05", "saturation" to "1.06", "globalMultiply" to "0.85"),
                inputs = listOf(POST_DOF),
            ),
            RenderNode(
                POST_LOOKS, NodeType.LOOKS,
                // 默认关（strength=0）：风格化效果不替用户决定
                params = mapOf("strength" to looksStrength.toString(), "posX" to "0.12", "posY" to "0.08"),
                inputs = listOf(POST_GRADE),
            ),
            RenderNode(
                POST_VIGNETTE, NodeType.VIGNETTE,
                params = mapOf("radius" to "0.72", "softness" to "0.45", "strength" to "0.22"),
                inputs = listOf(POST_LOOKS),
            ),
            RenderNode(
                Project.DEFAULT_OUTPUT_ID, NodeType.FXAA,
                inputs = listOf(POST_VIGNETTE),
            ),
        )

        val edges = nodes.flatMap { n -> n.inputs.map { Edge(it, n.id) } }
        return Project(
            id = id,
            backgroundUri = backgroundUri,
            foregroundUri = foregroundUri,
            nodes = nodes,
            edges = edges,
            outputNodeId = Project.DEFAULT_OUTPUT_ID,
        )
    }

    private fun identity() = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    private fun colorMatrixParams(m: FloatArray): Map<String, String> =
        (0 until 9).associate { i -> "m$i" to m[i].toString() }
}
