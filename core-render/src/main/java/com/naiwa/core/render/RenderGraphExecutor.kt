package com.naiwa.core.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.naiwa.core.common.model.project.NodeType
import com.naiwa.core.common.model.project.Project
import com.naiwa.core.common.model.project.RenderNode
import com.naiwa.core.common.render.GlReadback
import com.naiwa.core.common.render.GlState

/**
 * DAG 渲染执行器：按 [RenderGraphEngine.Plan] 逐节点渲染到 FBO。
 *
 * 【纹理缓存池（规格二.2 / 输出要求 3）】
 * ```
 * slots: Map<NodeId, Slot(tex, fbo, w, h)>
 * ```
 *  - **跨帧保留**：这是局部重算的前提 —— 未变节点直接复用 Slot 里的纹理，不重画；
 *  - **尺寸变化即全部失效**：分辨率切换（预览/导出）时整池重建；
 *  - **显式 dispose()**：EGL 上下文销毁前必须调用，否则 GL 纹理随 context 一起泄漏到驱动层，
 *    GC 看不见（这是 Android GLES 最典型的"显存泄漏"来源）；
 *  - **数量上限**：节点数 × 单张纹理字节数超过 [MAX_CACHE_BYTES] 时拒绝执行并返回 null
 *    （调用方降级），而不是让驱动 OOM。
 *
 * 【本轮实现的节点】SOURCE_*、GREEN_SCREEN_KEY、COLOR_MATRIX（含合并矩阵）、BLUR、
 * SOLID_COLOR、MIX（over/screen/multiply/add）、FXAA。其余类型（LIGHT_WRAP/RIM_LIGHT/
 * BOKEH/RADIAL_BLUR/LENS_FLARE/GLOW）本轮为 **pass-through**（直接引用上游纹理），
 * 它们的 shader 已存在于 `GlesLayerPipeline`，接入 DAG 是下一步（见交付说明）。
 */
class RenderGraphExecutor {

    private val TAG = "NaiwaCamera"

    /** 单个纹理缓存上限（256MB）：超了拒绝执行，避免驱动层 OOM。 */
    private val MAX_CACHE_BYTES = 256L * 1024 * 1024

    private data class Slot(
        val tex: Int,
        val fbo: Int,
        val w: Int,
        val h: Int,
    )

    private val slots = HashMap<String, Slot>()
    private val programs = HashMap<NodeType, Int>()
    private val engine = RenderGraphEngine()


    private var egl: EglEnv? = null
    private var vao = 0
    private var currentProgram = 0
    private var cacheW = 0
    private var cacheH = 0

    /**
     * 执行一次渲染。
     *
     * @param sources SOURCE 节点的位图（nodeId → Bitmap）
     * @param changedNodeId 本次改动节点（局部重算）；null = 全新工程（整图重算）
     * @return 输出节点结果；失败返回 null（调用方降级 CPU 管线）
     */
    fun execute(
        project: Project,
        sources: Map<String, Bitmap>,
        width: Int,
        height: Int,
        changedNodeId: String? = null,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val errs = project.validate()
        if (errs.isNotEmpty()) {
            Log.w(TAG, "工程非法，拒绝渲染：${errs.joinToString("；")}")
            return null
        }
        // 分辨率变化 → 缓存整池失效（纹理尺寸必须一致才能复用）
        if (width != cacheW || height != cacheH) {
            releaseSlots()
            engine.reset()
            cacheW = width
            cacheH = height
        }

        val plan = engine.plan(project, changedNodeId)
        plan.error?.let {
            Log.w(TAG, "执行计划失败：$it")
            return null
        }

        // 显存预算：每个节点一张 RGBA8
        val perNode = width.toLong() * height * 4
        if (perNode * project.nodes.size > MAX_CACHE_BYTES) {
            Log.w(TAG, "纹理缓存超预算（${perNode * project.nodes.size / 1024 / 1024}MB），降级")
            return null
        }

        return try {
            ensureContext(width, height) ?: return null
            egl?.makeCurrent()

            // ★ 逐节点容错：**任何单个节点失败都不允许终止整条 DAG**。
            //   旧写法在循环体里 `?: return null`，一个槽位分配失败（显存不足）就会让
            //   整张图放弃 GPU 路径 —— 而那正是最需要"降级继续画"的时刻。
            //   现在的语义：坏节点 → 直通上游（或清成透明）→ 后续节点照常跑，
            //   最终图至少是"缺了一个效果"，而不是"黑屏/翻转/回到旧管线"。
            var failed = 0
            for (pass in plan.passes) {
                val cached = slots[pass.node.id]
                val reusable = pass.reuseCached && cached != null &&
                    cached.w == width && cached.h == height
                if (reusable) continue   // ★ 命中缓存：一个像素都不重算

                val slot = ensureSlot(pass.node.id, width, height)
                if (slot == null) {
                    // 槽位都建不出来（显存不足）：这个节点只能缺席，继续下一个
                    Log.w(TAG, "节点 ${pass.node.id} 无可用槽位，跳过（显存不足？）")
                    failed++
                    continue
                }
                val ok = try {
                    renderNode(pass, project, sources, slot)
                } catch (t: Throwable) {
                    Log.w(TAG, "节点 ${pass.node.id}（${pass.node.type}）渲染异常：${t.message}", t)
                    false
                }
                if (!ok) {
                    failed++
                    fallbackFor(pass, project, sources, slot)
                }
            }
            if (failed > 0) Log.w(TAG, "DAG 有 $failed 个节点降级为直通（共 ${plan.passes.size} 个）")

            // 输出节点结果回读
            val outSlot = slots[project.outputNodeId] ?: run {
                Log.w(TAG, "输出节点 ${project.outputNodeId} 无槽位")
                return null
            }
            readPixels(outSlot)
        } catch (t: Throwable) {
            Log.w(TAG, "DAG 执行失败：${t.message}", t)
            null
        }
    }

    /** 释放全部 GL 资源（Activity/ViewModel 销毁时必须调用）。 */
    fun dispose() {
        egl?.makeCurrent()
        releaseSlots()
        programs.values.forEach { runCatching { GLES30.glDeleteProgram(it) } }
        programs.clear()
        if (vao != 0) {
            runCatching { GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0) }
            vao = 0
        }
        egl?.release()
        egl = null
        cacheW = 0
        cacheH = 0
        engine.reset()
    }

    // ---------------------------------------------------------------- 节点渲染

    private fun renderNode(
        pass: RenderGraphEngine.PassPlan,
        project: Project,
        sources: Map<String, Bitmap>,
        slot: Slot,
    ): Boolean {
        val node = pass.node
        when (node.type) {
            NodeType.SOURCE_BACKGROUND, NodeType.SOURCE_FOREGROUND -> {
                val bmp = sources[node.id] ?: return false
                uploadInto(bmp, slot)
            }

            NodeType.GREEN_SCREEN_KEY -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val prog = program(NodeType.GREEN_SCREEN_KEY) ?: return false
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    uni1f("uGreen", node.float("green", 1f))
                    uni1f("uT1", node.float("t1", 0.08f))
                    uni1f("uT2", node.float("t2", 0.20f))
                }
            }

            NodeType.COLOR_MATRIX -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val prog = program(NodeType.COLOR_MATRIX) ?: return false
                val m = pass.mergedMatrix ?: engine.matrixOf(node)
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    matrix3("uMatrix", m)
                    uni1f("uStrength", node.float("strength", 0.6f))
                }
            }

            NodeType.BLUR -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val prog = program(NodeType.BLUR) ?: return false
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    uni2f("uTexel", 1f / slot.w, 1f / slot.h)
                    uni1f("uScale", node.float("scale", 1.7f))
                }
            }

            NodeType.SOLID_COLOR -> {
                val prog = program(NodeType.SOLID_COLOR) ?: return false
                draw(prog, slot) {
                    val c = node.int("color", 0x000000)
                    uni4f(
                        "uColor",
                        ((c shr 16) and 0xFF) / 255f,
                        ((c shr 8) and 0xFF) / 255f,
                        (c and 0xFF) / 255f,
                        node.float("alpha", 1f),
                    )
                }
            }

            NodeType.MIX -> {
                val prog = program(NodeType.MIX) ?: return false
                val inputs = pass.inputs.mapNotNull { texOf(it) }
                if (inputs.isEmpty()) return false
                draw(prog, slot) {
                    bind("uBase", inputs.first(), 0)
                    bind("uTop", inputs.getOrElse(1) { inputs.first() }, 1)
                    uni2f("uTexel", 1f / slot.w, 1f / slot.h)
                    uni1f("uMode", modeCode(node.str("mode", "over")))
                    uni1f("uOpacity", node.float("opacity", 1f))
                }
            }

            NodeType.FXAA -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val prog = program(NodeType.FXAA) ?: return false
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    uni2f("uTexel", 1f / slot.w, 1f / slot.h)
                }
            }

            // ---------------------------------------------------- 全局后处理（FXAA 之前）
            NodeType.DOF -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                // 第二个输入是 Mask 源（通常是 P2_MAIN 的 alpha）；缺省时用自身 alpha
                val mask = pass.inputs.getOrNull(1)?.let { texOf(it) } ?: src
                val prog = program(NodeType.DOF) ?: return false
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    bind("uMask", mask, 1)
                    uni2f("uTexel", 1f / slot.w, 1f / slot.h)
                    // 半径以 1080p 为基准等比缩放，但**上限死守 5px**（整张背景糊死是上一版的致命问题）
                    uni1f("uMaxRadiusPx", (node.float("radius", 4f) * slot.h / 1080f).coerceIn(1f, 5f))
                    uni1f("uRingPx", (node.float("ringPx", 20f) * slot.h / 1080f).coerceIn(8f, 40f))
                }
            }

            NodeType.COLOR_GRADE -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val prog = program(NodeType.COLOR_GRADE) ?: return false
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    matrix3("uMatrix", engine.matrixOf(node))
                    uni1f("uContrast", node.float("contrast", 1f))
                    uni1f("uSaturation", node.float("saturation", 1f))
                    uni1f("uGlobalMultiply", node.float("globalMultiply", 1f).coerceIn(0.5f, 1f))
                }
            }

            NodeType.LOOKS -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val s = node.float("strength", 0f)
                if (s <= 0.001f) {
                    blit(src, slot)   // 关掉的节点直通，不打破缓存链
                    return true
                }
                val prog = program(NodeType.LOOKS) ?: return false
                val c = node.int("color", 0xFF9A4D)
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    uni2f("uLeakPos", node.float("posX", 0.12f), node.float("posY", 0.08f))
                    uni2f("uAspect", slot.w.toFloat() / slot.h, 1f)
                    uni3f(
                        "uLeakColor",
                        Math.pow(((c shr 16) and 0xFF) / 255.0, 2.2).toFloat(),
                        Math.pow(((c shr 8) and 0xFF) / 255.0, 2.2).toFloat(),
                        Math.pow((c and 0xFF) / 255.0, 2.2).toFloat(),
                    )
                    uni1f("uStrength", s)
                    uni1f("uPhase", node.float("phase", 0f))
                }
            }

            NodeType.VIGNETTE -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val s = node.float("strength", 0f)
                if (s <= 0.001f) {
                    blit(src, slot)
                    return true
                }
                val prog = program(NodeType.VIGNETTE) ?: return false
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    uni2f("uAspect", slot.w.toFloat() / slot.h, 1f)
                    uni1f("uRadius", node.float("radius", 0.72f))
                    uni1f("uSoftness", node.float("softness", 0.45f))
                    uni1f("uStrength", s)
                }
            }

            NodeType.THIRD_PARTY_FILTER -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                val spec = node.str("spec", "")

                // ---- 路线 A：gpuimage-plus（C++，Bitmap 进出）----
                // 库不暴露 FBO 纹理，只能 glReadPixels 回读 → 滤镜 → 重新上传。
                // 每次多两次全图拷贝（1080p +15~30ms），这是库的架构限制。
                val cgeEffect = GpuImagePlusNode.effectOf(spec)
                if (cgeEffect != null) {
                    val srcSlot = slots[pass.inputs.firstOrNull() ?: ""]
                    val bitmap = srcSlot?.let { readPixels(it) }
                    if (bitmap == null) {
                        blit(src, slot)
                        return true
                    }
                    // ★ 关键：让出 GL 上下文，C++ 内部要自己建/绑 EGL。
                    //   `unbind → apply → makeCurrent` 之间只要 apply 抛一次（native 崩溃、
                    //   so 版本不匹配、OOM），上下文就**永久停在解绑状态**：之后每个 GL 调用
                    //   都失败但不抛异常、glGetError 在无上下文时也靠不住 —— 表现正是
                    //   "后面的光效节点全被跳过/画面变黑，而日志里只有一条 filter 异常"。
                    //   所以恢复动作必须放 finally。
                    var filtered: Bitmap? = null
                    try {
                        egl?.unbind()
                        filtered = GpuImagePlusNode.apply(
                            effect = cgeEffect,
                            src = bitmap,
                            intensity = node.float("intensity", 1f),
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "第三方滤镜异常，节点 ${node.id} 直通：${t.message}", t)
                    } finally {
                        bitmap.recycle()
                        // 无论成败都要拿回上下文，否则后续节点全部失效
                        runCatching { egl?.makeCurrent() }
                    }
                    val out = filtered
                    if (out != null) {
                        uploadInto(out, slot)
                        out.recycle()
                    } else {
                        blit(src, slot)   // 滤镜不可用 → 直通，不中断 DAG
                    }
                    return true
                }

                // ---- 路线 B：内置单 pass 文本滤镜链（es: / gp:）----
                val effects = EffectStringParser.parse(spec)
                if (effects.isEmpty()) {
                    blit(src, slot)   // 空链/未知键 → 直通，绝不中断整条 DAG
                    return true
                }
                val prog = program(NodeType.THIRD_PARTY_FILTER) ?: return false
                val uniforms = EffectStringParser.uniformsFor(effects)
                draw(prog, slot) {
                    bind("uSrc", src, 0)
                    // 一条文本滤镜链 = 一次 pass（参数全走 uniform，不重新编译 program）
                    uniforms.forEach { (name, v) -> uni1f(name, v) }
                }
            }

            // 高级光效节点：本轮 pass-through（shader 已在 GlesLayerPipeline 中实现）
            else -> {
                val src = firstInputTexture(pass, project, sources) ?: return false
                blit(src, slot)
            }
        }
        return true
    }

    /**
     * 节点失败的降级：**先把"有东西"这件事保证住**。
     *
     * 槽位是一张刚分配的纹理，内容未定义（可能是上一帧残留、也可能是随机显存）。
     * 如果节点失败就把它留空，下游节点读到的不是"缺效果"而是"脏数据"，
     * 表现是整块区域变成上一帧的残影或彩色噪点 —— 比黑屏更难判断。
     *
     * 顺序：① 有上游 → blit 上游（等价"这个效果没生效"）；
     *       ② 无上游（SOURCE 节点本身失败）→ 清成透明，让下游 `over` 混合保留底图。
     */
    private fun fallbackFor(
        pass: RenderGraphEngine.PassPlan,
        project: Project,
        sources: Map<String, Bitmap>,
        slot: Slot,
    ) {
        val src = attempt("${pass.node.id}.fallback") { firstInputTexture(pass, project, sources) }
            ?: srcFallback(pass, sources)
        if (src != null) {
            attempt("${pass.node.id}.blit") { blit(src, slot) }
            return
        }
        // 连上游都没有：清透明，至少保证是"良定义"的像素
        runCatching {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, slot.fbo)
            GLES30.glViewport(0, 0, slot.w, slot.h)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
    }

    /** 兜底里的兜底：SOURCE 节点失败时直接用调用方给的位图上到该槽位。 */
    private fun srcFallback(pass: RenderGraphEngine.PassPlan, sources: Map<String, Bitmap>): Int? {
        val bmp = sources[pass.node.id] ?: return null
        runCatching { uploadInto(bmp, slots[pass.node.id] ?: return null) }
        return slots[pass.node.id]?.tex
    }

    private inline fun <T> attempt(where: String, block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "降级动作失败（$where）：${t.message}")
        null
    }

    /** 混合模式编码：与 MIX shader 的常量一一对应。 */
    private fun modeCode(mode: String): Float = when (mode.lowercase()) {
        "screen" -> 1f
        "multiply" -> 2f
        "add" -> 3f
        else -> 0f   // over
    }

    private fun firstInputTexture(
        pass: RenderGraphEngine.PassPlan,
        project: Project,
        sources: Map<String, Bitmap>,
    ): Int? {
        val inputId = pass.inputs.firstOrNull() ?: return null
        // SOURCE 节点可能还没渲染（拓扑序保证它先跑，这里只是防御）
        texOf(inputId)?.let { return it }
        sources[inputId]?.let { bmp ->
            val slot = ensureSlot(inputId, bmp.width, bmp.height) ?: return null
            uploadInto(bmp, slot)
            return slot.tex
        }
        return null
    }

    private fun texOf(nodeId: String): Int? = slots[nodeId]?.tex

    // ---------------------------------------------------------------- FBO 槽位

    private fun ensureSlot(nodeId: String, w: Int, h: Int): Slot? {
        slots[nodeId]?.let {
            if (it.w == w && it.h == h) return it
            // 尺寸变了：先毁旧的再建新的（避免同时存在两张全分辨率纹理）
            runCatching { GLES30.glDeleteFramebuffers(1, intArrayOf(it.fbo), 0) }
            runCatching { GLES30.glDeleteTextures(1, intArrayOf(it.tex), 0) }
            slots.remove(nodeId)
        }
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        if (tex[0] == 0) return null
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, tex, 0)
            Log.w(TAG, "纹理分配失败 ${w}x$h（显存不足）")
            return null
        }
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(1, tex, 0)
            return null
        }
        val slot = Slot(tex[0], fbo[0], w, h)
        slots[nodeId] = slot
        return slot
    }

    private fun releaseSlots() {
        if (slots.isEmpty()) return
        val texs = slots.values.map { it.tex }.toIntArray()
        val fbos = slots.values.map { it.fbo }.toIntArray()
        runCatching { GLES30.glDeleteFramebuffers(fbos.size, fbos, 0) }
        runCatching { GLES30.glDeleteTextures(texs.size, texs, 0) }
        slots.clear()
    }

    // ---------------------------------------------------------------- GL 辅助

    private fun ensureContext(w: Int, h: Int): EglEnv? {
        egl?.let { return it }
        val created = createEgl(w, h) ?: return null
        created.makeCurrent()
        val v = IntArray(1)
        GLES30.glGenVertexArrays(1, v, 0)
        vao = v[0]
        GLES30.glBindVertexArray(vao)
        egl = created
        return created
    }

    private fun program(type: NodeType): Int? {
        programs[type]?.let { return it }
        val src = shaderFor(type) ?: return null
        val prog = buildProgram(src) ?: return null
        programs[type] = prog
        return prog
    }

    private fun shaderFor(type: NodeType): String? = when (type) {
        NodeType.GREEN_SCREEN_KEY -> SHADER_KEY
        NodeType.COLOR_MATRIX -> SHADER_MATRIX
        NodeType.BLUR -> SHADER_BLUR
        NodeType.SOLID_COLOR -> SHADER_SOLID
        NodeType.MIX -> SHADER_MIX
        NodeType.FXAA -> SHADER_FXAA
        NodeType.DOF -> SHADER_DOF
        NodeType.COLOR_GRADE -> SHADER_GRADE
        NodeType.LOOKS -> SHADER_LOOKS
        NodeType.VIGNETTE -> SHADER_VIGNETTE
        NodeType.THIRD_PARTY_FILTER -> EffectStringParser.SHADER_EFFECT
        else -> null
    }

    private fun draw(prog: Int, slot: Slot, setup: () -> Unit) {
        currentProgram = prog
        GLES30.glUseProgram(prog)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, slot.fbo)
        GLES30.glViewport(0, 0, slot.w, slot.h)
        GlState.ensure2dPass()
        setup()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "GL 错误 0x${Integer.toHexString(err)}")
    }

    /** 直接拷贝纹理（pass-through/blit）。 */
    private fun blit(srcTex: Int, slot: Slot) {
        val prog = program(NodeType.MIX) ?: return
        draw(prog, slot) {
            bind("uBase", srcTex, 0)
            bind("uTop", srcTex, 1)
            uni2f("uTexel", 1f / slot.w, 1f / slot.h)
            uni1f("uMode", 0f)      // over：uTop 覆盖（alpha=1 时不透明）
            uni1f("uOpacity", 0f)   // 0 不透明度 → 保留底图，等价直通
        }
    }

    private fun uploadInto(bmp: Bitmap, slot: Slot) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slot.tex)
        val tmp = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return
        tmp.setPremultiplied(false)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, tmp, 0)
        tmp.recycle()
    }

    /**
     * 回读 —— 统一走 [GlReadback]。
     *
     * 本文件曾经自己实现了一份"翻转行序"的版本（三处渲染器各写一套，方向还不一致）。
     * 方向是"要么全对要么全错"的约定，只能有一个来源，见 [GlReadback] 的推导与实测。
     */
    private fun readPixels(slot: Slot): Bitmap? = GlReadback.read(slot.fbo, slot.w, slot.h)

    private fun bind(name: String, tex: Int, unit: Int) {
        val loc = GLES30.glGetUniformLocation(currentProgram, name)
        if (loc < 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc, unit)
    }

    private fun u(name: String) = GLES30.glGetUniformLocation(currentProgram, name)
    private fun uni1f(name: String, v: Float) = GLES30.glUniform1f(u(name), v)
    private fun uni2f(name: String, x: Float, y: Float) = GLES30.glUniform2f(u(name), x, y)
    private fun uni4f(name: String, x: Float, y: Float, z: Float, w: Float) =
        GLES30.glUniform4f(u(name), x, y, z, w)

    private fun uni3f(name: String, x: Float, y: Float, z: Float) =
        GLES30.glUniform3f(u(name), x, y, z)

    private fun matrix3(name: String, m: FloatArray) {
        val loc = u(name)
        if (loc < 0) return
        val colMajor = floatArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        )
        GLES30.glUniformMatrix3fv(loc, 1, false, colMajor, 0)
    }

    private fun buildProgram(fragSrc: String): Int? = try {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w(TAG, "program link 失败：${GLES30.glGetProgramInfoLog(prog)}")
            null
        } else prog
    } catch (t: Throwable) {
        Log.w(TAG, "shader 构建异常：${t.message}")
        null
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader 编译失败：${GLES30.glGetShaderInfoLog(s)}")
        return s
    }

    private class EglEnv(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface,
    ) {
        fun makeCurrent() {
            EGL14.eglMakeCurrent(display, surface, surface, context)
        }

        /** 让出当前上下文（native 库内部要自己 eglMakeCurrent，不能与之抢）。 */
        fun unbind() {
            runCatching {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
            }
        }

        fun release() {
            runCatching {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun createEgl(w: Int, h: Int): EglEnv? = try {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) null else {
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
                val surface = EGL14.eglCreatePbufferSurface(
                    display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0,
                )
                val context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
                if (surface != EGL14.EGL_NO_SURFACE && context != EGL14.EGL_NO_CONTEXT) {
                    EglEnv(display, context, surface)
                } else null
            } else null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "EGL 初始化异常：${t.message}")
        null
    }

    // ---------------------------------------------------------------- shaders

    private companion object {
        const val VERT = """
#version 300 es
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vTexCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        /** 绿幕色键（YCbCr 绿色距离 + smoothstep + spill）。 */
        const val SHADER_KEY = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc; uniform float uGreen, uT1, uT2;
void main() {
    vec4 c = texture(uSrc, clamp(vTexCoord, 0.0, 1.0));
    float keyness = c.g - max(c.r, c.b) * uGreen;
    float alpha = 1.0 - smoothstep(uT1, uT2, keyness);
    vec3 rgb = c.rgb;
    rgb.g = mix(rgb.g, min(rgb.g, max(rgb.r, rgb.b)), (1.0 - alpha) * 0.8);
    fragColor = vec4(rgb, alpha * c.a);
}"""

        /** 3×3 颜色矩阵（带强度混合）。 */
        const val SHADER_MATRIX = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc; uniform mat3 uMatrix; uniform float uStrength;
void main() {
    vec4 c = texture(uSrc, clamp(vTexCoord, 0.0, 1.0));
    vec3 m = clamp(uMatrix * pow(max(c.rgb, 0.0), vec3(2.2)), 0.0, 4.0);
    vec3 lin = pow(max(c.rgb, 0.0), vec3(2.2));
    vec3 outLin = mix(lin, m, uStrength * smoothstep(0.05, 0.4, c.a));
    fragColor = vec4(pow(outLin, vec3(1.0/2.2)), c.a);
}"""

        /** 13-tap 模糊。 */
        const val SHADER_BLUR = """
#version 300 es
precision mediump float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc; uniform vec2 uTexel; uniform float uScale;
void main() {
    vec4 sum = vec4(0.0); float w = 0.0;
    #define T(dx,dy,ww) { vec4 t = texture(uSrc, clamp(vTexCoord + uTexel*vec2(dx,dy)*uScale, 0.0, 1.0)); sum += t*ww; w += ww; }
    T(0.0,0.0,0.15)
    T(2.5,0.0,0.10) T(-2.5,0.0,0.10) T(0.0,2.5,0.10) T(0.0,-2.5,0.10)
    T(5.0,0.0,0.05) T(-5.0,0.0,0.05) T(0.0,5.0,0.05) T(0.0,-5.0,0.05)
    T(2.5,2.5,0.0625) T(-2.5,2.5,0.0625) T(2.5,-2.5,0.0625) T(-2.5,-2.5,0.0625)
    #undef T
    fragColor = sum / max(w, 1e-4);
}"""

        /** 纯色填充。 */
        const val SHADER_SOLID = """
#version 300 es
precision mediump float;
out vec4 fragColor; uniform vec4 uColor;
void main() { fragColor = uColor; }"""

        /** 混合：0=over 1=screen 2=multiply 3=add。 */
        const val SHADER_MIX = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uBase; uniform sampler2D uTop;
uniform vec2 uTexel; uniform float uMode; uniform float uOpacity;
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec4 b = texture(uBase, uv);
    vec4 t = texture(uTop, uv);
    vec3 base = pow(max(b.rgb, 0.0), vec3(2.2));
    vec3 top  = pow(max(t.rgb, 0.0), vec3(2.2));
    vec3 c;
    if (uMode < 0.5)      c = mix(base, top, t.a * uOpacity);
    else if (uMode < 1.5) c = 1.0 - (1.0 - base) * (1.0 - top * uOpacity);   // Screen
    else if (uMode < 2.5) c = base * mix(vec3(1.0), top, uOpacity);          // Multiply
    else                  c = base + top * uOpacity;                          // Add
    float a = max(b.a, t.a * uOpacity);
    fragColor = vec4(pow(clamp(c, 0.0, 1.0), vec3(1.0/2.2)), a);
}"""

        /** 简化 FXAA + sRGB。 */
        const val SHADER_FXAA = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc; uniform vec2 uTexel;
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec4 c = texture(uSrc, uv);
    float lC = luma(c.rgb);
    float lN = min(min(luma(texture(uSrc, clamp(uv + vec2(uTexel.x,0.0),0.0,1.0)).rgb),
                       luma(texture(uSrc, clamp(uv - vec2(uTexel.x,0.0),0.0,1.0)).rgb)),
                   min(luma(texture(uSrc, clamp(uv + vec2(0.0,uTexel.y),0.0,1.0)).rgb),
                       luma(texture(uSrc, clamp(uv - vec2(0.0,uTexel.y),0.0,1.0)).rgb)));
    float edge = clamp(abs(lC - lN) * 6.0, 0.0, 1.0);
    vec3 cL = texture(uSrc, clamp(uv + vec2(-uTexel.x, 0.0), 0.0, 1.0)).rgb;
    vec3 cR = texture(uSrc, clamp(uv + vec2(uTexel.x, 0.0), 0.0, 1.0)).rgb;
    vec3 mixed = mix(c.rgb, (cL + cR) * 0.5, edge * 0.5);
    fragColor = vec4(mixed, c.a);
}"""

        // ------------------------------------------------ 全局后处理（FXAA 之前）
        /** 景深：**环形焦外** —— 只糊人物轮廓外 uRingPx 一圈，半径 ≤5px，远处背景保持清晰。 */
        const val SHADER_DOF = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc;
uniform sampler2D uMask;
uniform vec2 uTexel;
uniform float uMaxRadiusPx;
uniform float uRingPx;
float aAt(vec2 uv) { return texture(uMask, clamp(uv, 0.0, 1.0)).a; }
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 sharp = texture(uSrc, uv).rgb;
    float a = aAt(uv);
    float near = 0.0;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.7853982;
        vec2 off = vec2(cos(ang), sin(ang)) * uRingPx * uTexel;
        near = max(near, aAt(uv + off));
    }
    float ring = smoothstep(0.02, 0.50, near) * (1.0 - smoothstep(0.55, 0.92, a));
    if (ring < 0.01) { fragColor = vec4(sharp, 1.0); return; }
    float r = uMaxRadiusPx * ring;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float ang = float(i) * 2.39996;
        float rad = r * sqrt((float(i) + 1.0) / 12.0);
        vec2 sp = clamp(uv + vec2(cos(ang), sin(ang)) * rad * uTexel, 0.0, 1.0);
        acc += texture(uSrc, sp).rgb;
    }
    fragColor = vec4(mix(sharp, acc / 12.0, ring), 1.0);
}"""

        /** 全局调色：3×3 + 对比度 + 饱和度（18% 灰支点）。 */
        const val SHADER_GRADE = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc;
uniform mat3 uMatrix;
uniform float uContrast;
uniform float uSaturation;
uniform float uGlobalMultiply;
void main() {
    vec3 c = texture(uSrc, clamp(vTexCoord, 0.0, 1.0)).rgb;
    c = uMatrix * c;
    c = (c - 0.18) * uContrast + 0.18;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, uSaturation);
    // 全局 Multiply：只压暗部（高光不动），保住底图细节
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c *= mix(1.0, uGlobalMultiply, 1.0 - smoothstep(0.02, 0.30, lum));
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""

        /** Looks 漏光（程序化，Screen）。 */
        const val SHADER_LOOKS = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2 uLeakPos;
uniform vec2 uAspect;
uniform vec3 uLeakColor;
uniform float uStrength;
uniform float uPhase;
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 c = texture(uSrc, uv).rgb;
    vec2 d = (uv - uLeakPos) * uAspect;
    float r = length(d);
    float leak = exp(-r * r * 4.0) * 0.85 + exp(-r * r * 1.2) * 0.25;
    float wobble = 0.85 + 0.15 * sin(uv.x * 9.0 + uPhase) * sin(uv.y * 7.0 - uPhase * 0.7);
    leak = max(leak * wobble, 0.0);
    vec3 add = uLeakColor * leak * uStrength;
    fragColor = vec4(1.0 - (1.0 - c) * (1.0 - clamp(add, 0.0, 1.0)), 1.0);
}"""

        /** 暗角（edge0 < edge1 的合法写法，等价于规格里的反向 smoothstep）。 */
        const val SHADER_VIGNETTE = """
#version 300 es
precision highp float;
in vec2 vTexCoord; out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2 uAspect;
uniform float uRadius;
uniform float uSoftness;
uniform float uStrength;
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 c = texture(uSrc, uv).rgb;
    float dist = length((uv - 0.5) * uAspect) / 0.7071;
    float v = 1.0 - smoothstep(uRadius - uSoftness, uRadius, dist);
    fragColor = vec4(c * mix(1.0, v, uStrength), 1.0);
}"""
    }
}
