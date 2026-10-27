package com.naiwa.core.common.render

import android.opengl.GLES30

/**
 * 2D 图像 pass 的 GL 状态基线。
 *
 * ## 为什么每个 pass 都要设一遍
 * 新建的 EGL 上下文里 `GL_DEPTH_TEST` / `GL_CULL_FACE` / `GL_BLEND` 默认都是关的，
 * 所以「只测一次」在**今天**是够的。但下面三种情况会让它不再成立，而且症状都不像状态问题：
 *
 * 1. **第三方滤镜切上下文**（DAG 的 `THIRD_PARTY_FILTER` → gpuimage-plus 内部自建 EGL），
 *    回来时驱动里残留什么状态没有契约保证；
 * 2. 将来任何一处 `glEnable(GL_BLEND)` 做半透明叠加，后面所有 pass 的 alpha 都会参与混合，
 *    表现为"人物边缘发灰/整图变暗"，几乎不会有人往 blend 上想；
 * 3. `GL_SCISSOR_TEST` 一旦被打开且矩形没重置，**只有一小块区域被绘制**，其余保持上一次内容 ——
 *    看起来像"渲染被跳过了"，正是最难排查的那种。
 *
 * 六个调用在移动 GPU 上的总开销远低于 1μs（纯状态位写入，无同步），
 * 每个 pass 设一遍比"想清楚谁会污染"便宜得多。
 *
 * `GL_CULL_FACE` 尤其要点名：全屏 quad 的顶点顺序是 `(0,0)→(2,0)→(0,2)→(2,2)`，
 * 这是**顺时针**。一旦哪里开了背面剔除，整个 quad 被剔除 → FBO 保持上一次内容或全黑，
 * 而 `glGetError()` **不会报错**（剔除是合法行为）。这正是"翻转/矩阵改动之后画面变黑、
 * 后续 pass 全读空白纹理"的经典成因。
 */
object GlState {

    /** 强制 2D 合成的状态基线：无深度、无剔除、无混合、无裁剪、写满 RGBA。 */
    fun ensure2dPass() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        // 混合必须由 shader 自己算（本项目所有混合都在片元里显式做），
        // 开着 GL_BLEND 会让"输出 alpha"参与混合 → 半透明区被重复叠色
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDepthMask(false)
        GLES30.glColorMask(true, true, true, true)
    }
}
