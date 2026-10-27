package com.naiwa.core.common.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.naiwa.core.common.debug.DebugReport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FBO 回读的**唯一入口**（含 Y 轴方向）。
 *
 * ## 为什么必须收敛到一处
 * 本项目曾有三份各自实现的回读：
 * ```
 *  GlesLayerPipeline.readPixels     —— 不翻转（并写了推导注释）
 *  GlesFusionRenderer.readPixels    —— 翻转
 *  RenderGraphExecutor.readPixels   —— 翻转
 * ```
 * 三份代码都"看起来对"，但**方向不可能同时对**：一键融图正常走第一条，一旦它失败降级到
 * 第二条，画面立刻上下颠倒 —— 用户看到的就是"点了融图之后素材翻过来了"，
 * 而"光效没了"其实是同一件事的另一面（降级到了残缺的旧管线）。
 *
 * ## 方向约定（推导，不是猜）
 * ```
 * 上传：GLUtils.texImage2D 把 Bitmap 第 0 行（顶部）放到纹理 t = 0；
 * 顶点：vTexCoord = p，gl y = -1（帧缓冲底部）对应 t = 0
 *       → 帧缓冲底部画的是原图顶部；
 * 回读：glReadPixels 从帧缓冲底部开始逐行返回
 *       → 缓冲区第 0 行 = 原图第 0 行。
 * ```
 * 因此「上传 → 绘制 → 回读」这一整圈是**恒等**的，**不需要翻转**；再翻一次才是上下颠倒。
 * 这正是 [GlesLayerPipeline] 一直正确、另外两处出错的原因。
 *
 * ## 但仍然要实测
 * 上面的推导依赖两个驱动行为（GLUtils 的 texImage2D 行序、glReadPixels 的行序）。
 * 两者都是规范行为，但"规范行为"在个别国产 ROM 的 GLES 驱动上翻过车。所以这里
 * 首次调用会**用 2×2 的已知图案跑一次真实的 上传→绘制→回读**，用实测结果决定是否翻转：
 *  - 图案：第 0 行纯红、第 1 行纯蓝（左右镜像对方向不敏感，只测纵向）；
 *  - 回读后看缓冲区第 0 行：红 → 无需翻转；蓝 → 需要翻转；
 *  - 探测失败（shader 编译不过 / 2×2 FBO 建不出来）→ 退回规范推导的结论 [DEFAULT_FLIP]。
 *
 * 探测结果缓存在进程内，只跑一次（约 1ms），之后 [read] 直接走快路径。
 */
object GlReadback {

    private const val TAG = "NaiwaCamera"

    /** 探测失败时的兜底：按规范推导，**不翻转**。 */
    const val DEFAULT_FLIP: Boolean = false

    /** 探测图案：第 0 行红 / 第 1 行蓝。 */
    private const val PROBE_TOP = 0xFFFF0000.toInt()
    private const val PROBE_BOTTOM = 0xFF0000FF.toInt()

    /** 探测结果缓存：null = 尚未探测。 */
    @Volatile
    private var flipCached: Boolean? = null

    /** 实测结论（null = 还没探测过）。调试面板/日志可直接读。 */
    val measuredFlip: Boolean? get() = flipCached

    /** 测试用：清掉探测缓存，让下一次 [read] 重新探测。 */
    fun resetProbe() {
        flipCached = null
    }

    /** 测试用：直接写入探测结果，跳过真实 GL 探测（JVM 单测里没有 GL 上下文）。 */
    fun overrideForTest(flip: Boolean?) {
        flipCached = flip
    }

    /**
     * 纯函数部分：给定"回读缓冲区首行是否等于原图首行"，返回是否需要翻转行序。
     *
     * 单独抽出来是为了能在 JVM 单测里锁住这条判定（GL 部分没法在本地跑）。
     */
    fun needsFlip(firstReadRowIsSourceTop: Boolean): Boolean = !firstReadRowIsSourceTop

    /**
     * 把 [fbo] 的像素读成 Bitmap，方向由实测结论决定。
     *
     * 与旧实现的三点差异：
     * 1. **显式绑定 [fbo]** —— 旧版依赖"上一 pass 恰好还绑着正确的 FBO"，
     *    一旦某个 pass 提前返回或改了绑定，就会读到另一张纹理（表现为整图错位/黑屏）；
     * 2. `glPixelStorei(GL_PACK_ALIGNMENT, 1)` —— 宽度不是 4 的倍数时行会被补齐错位
     *    （RGBA8 每像素 4 字节，正常不会触发，但别的格式复用本函数时会）；
     * 3. 只在**确实需要**时才做整图行拷贝（省一次全分辨率内存拷贝）。
     */
    fun read(fbo: Int, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0 || fbo == 0) return null
        val flip = flipCached ?: runProbe()

        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Log.w(TAG, "glReadPixels 失败 0x${Integer.toHexString(err)}（${w}x$h）")
            return null
        }
        buf.rewind()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                if (flip) copyPixelsFromBuffer(reverseRows(buf, w, h)) else copyPixelsFromBuffer(buf)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "回读转 Bitmap 失败：${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------- 行序工具

    /** 逐行倒序拷贝（唯一允许出现"倒序"的地方）。 */
    private fun reverseRows(src: ByteBuffer, w: Int, h: Int): ByteBuffer {
        val stride = w * 4
        val row = ByteArray(stride)
        val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (y in h - 1 downTo 0) {
            src.position(y * stride)
            src.get(row)
            out.put(row)
        }
        out.rewind()
        return out
    }

    // ---------------------------------------------------------------- 方向探测

    /**
     * 用 2×2 图案实测一次「上传 → 绘制 → 回读」的方向。
     *
     * 注意：这里**必须自己建 program**，不能复用调用方的 —— 探测发生在任意 pass 之后，
     * 调用方的 program 状态不可依赖。
     */
    private fun runProbe(): Boolean {
        var srcTex = 0
        var dstTex = 0
        var dstFbo = 0
        var prog = 0
        val prevFbo = IntArray(1)
        val prevViewport = IntArray(4)

        fun measure(): Boolean {
            GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

            val pattern = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, PROBE_TOP); setPixel(1, 0, PROBE_TOP)
                setPixel(0, 1, PROBE_BOTTOM); setPixel(1, 1, PROBE_BOTTOM)
            }
            srcTex = newTexture()
            if (srcTex == 0) { pattern.recycle(); return DEFAULT_FLIP }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, pattern, 0)
            pattern.recycle()

            dstTex = newTexture()
            if (dstTex == 0) return DEFAULT_FLIP
            dstFbo = newFbo(dstTex)
            if (dstFbo == 0) return DEFAULT_FLIP

            prog = buildProbeProgram() ?: return DEFAULT_FLIP
            GLES30.glUseProgram(prog)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dstFbo)
            GLES30.glViewport(0, 0, 2, 2)
            GlState.ensure2dPass()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uSrc"), 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            val px = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, 2, 2, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return DEFAULT_FLIP
            px.rewind()
            // 缓冲区第 0 个像素 = 回读的第 0 行（gl 底部）。红 → 它就是原图第 0 行。
            val r = px.get(0).toInt() and 0xFF
            val b = px.get(2).toInt() and 0xFF
            return when {
                r > 150 && b < 100 -> needsFlip(firstReadRowIsSourceTop = true)
                b > 150 && r < 100 -> needsFlip(firstReadRowIsSourceTop = false)
                else -> DEFAULT_FLIP      // 杂色说明探测本身不可信，按规范推导
            }
        }

        val result = try {
            measure()
        } catch (t: Throwable) {
            Log.w(TAG, "回读方向探测失败，按规范推导取 ${if (DEFAULT_FLIP) "翻转" else "不翻转"}：${t.message}")
            DEFAULT_FLIP
        } finally {
            runCatching {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
                if (prevViewport[2] > 0 && prevViewport[3] > 0) {
                    GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
                }
            }
            runCatching { if (dstFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(dstFbo), 0) }
            runCatching { if (dstTex != 0) GLES30.glDeleteTextures(1, intArrayOf(dstTex), 0) }
            runCatching { if (srcTex != 0) GLES30.glDeleteTextures(1, intArrayOf(srcTex), 0) }
            runCatching { if (prog != 0) GLES30.glDeleteProgram(prog) }
        }

        flipCached = result
        DebugReport.readbackFlip = result
        Log.i(
            TAG,
            "回读方向实测：" + if (result) "需要垂直翻转（驱动行为与规范推导不符，已自动纠正）"
            else "无需翻转（与规范推导一致）",
        )
        return result
    }

    private fun newTexture(): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        if (t[0] == 0) return 0
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        // 探测必须**逐像素精确**，用 NEAREST，避免线性过滤把 2×2 的两种颜色混起来
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 2, 2, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        return t[0]
    }

    private fun newFbo(tex: Int): Int {
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        if (f[0] == 0) return 0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0,
        )
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, f, 0)
            return 0
        }
        return f[0]
    }

    /** 探测专用最简 program：uv = p，位置 = p*2-1（与各管线的全屏 quad 完全一致）。 */
    private fun buildProbeProgram(): Int? {
        val vsSrc = """
#version 300 es
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vTexCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""
        val fsSrc = """
#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
void main() { fragColor = texture(uSrc, vTexCoord); }"""
        val vs = shader(GLES30.GL_VERTEX_SHADER, vsSrc) ?: return null
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fsSrc) ?: return null
        val p = GLES30.glCreateProgram()
        if (p == 0) return null
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w(TAG, "探测 program link 失败：${GLES30.glGetProgramInfoLog(p)}")
            GLES30.glDeleteProgram(p)
            return null
        }
        return p
    }

    private fun shader(type: Int, src: String): Int? {
        val s = GLES30.glCreateShader(type)
        if (s == 0) return null
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w(TAG, "探测 shader 编译失败：${GLES30.glGetShaderInfoLog(s)}")
            GLES30.glDeleteShader(s)
            return null
        }
        return s
    }
}
