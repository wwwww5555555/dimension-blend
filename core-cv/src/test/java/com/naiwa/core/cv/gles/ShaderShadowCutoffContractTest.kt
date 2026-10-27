package com.naiwa.core.cv.gles

import java.io.File

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阴影熔断与逆光压暗的静态契约。
 *
 * ## 为什么这里要单独一个测试类
 * v0.27 的两个修正都**只能靠真机肉眼看**（暗部够不够暗、阴影边缘漏不漏光），
 * 而它们各自有一个"静默失效"的写法：
 *
 * 1. **`smoothstep(0.2, -0.2, x)`** —— 规格伪代码就是这个形状，但 GLSL 规定
 *    `edge0 >= edge1` 是**未定义行为**，多数驱动返回 0，于是压暗/熔断整段等于没写。
 *    这是本项目第二次踩（v0.20 的 `smoothstep(0.95, 0.8)`），所以扫描全部 shader。
 * 2. **法向取反** —— `alpha` 梯度指向内部，外法向必须取负。P2-b 从 v0.20 起一直写成
 *    `+grad`，导致"立体反光"只在**背光侧**出现（正好与设计相反）。这轮修掉了，
 *    用断言防止回退。
 */
class ShaderShadowCutoffContractTest {

    private fun sourceFile(): File {
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            val f = File(dir, "src/main/java/com/naiwa/core/cv/gles/GlesLayerPipeline.kt")
            if (f.exists()) return f
            val nested = File(dir, "core-cv/src/main/java/com/naiwa/core/cv/gles/GlesLayerPipeline.kt")
            if (nested.exists()) return nested
            dir = dir?.parentFile
        }
        error("找不到 GlesLayerPipeline.kt（cwd=${File(".").absolutePath}）")
    }

    private fun src(): String = sourceFile().readText()

    /** 去掉 `//` 行注释：注释里写"反例"不能触发断言。 */
    private fun stripComments(s: String): String =
        s.lines().joinToString("\n") { l ->
            val i = l.indexOf("//")
            if (i >= 0) l.substring(0, i) else l
        }

    private fun shaders(): Map<String, String> =
        Regex(
            "private const val (FRAG_[A-Z0-9_]+) = \"\"\"(.*?)\"\"\"",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(src()).associate { it.groupValues[1] to it.groupValues[2] }

    // ------------------------------------------------------------------ ① UB 扫描

    @Test
    fun `没有任何 smoothstep 的 edge0 大于 edge1`() {
        // 只查两个字面量参数的情况；表达式参数（如 uThreshold-uKnee）无法静态判定
        val re = Regex("""smoothstep\(\s*(-?\d*\.?\d+)\s*,\s*(-?\d*\.?\d+)\s*,""")
        val bad = ArrayList<String>()
        shaders().forEach { (name, body) ->
            re.findAll(stripComments(body)).forEach { m ->
                val e0 = m.groupValues[1].toFloatOrNull()
                val e1 = m.groupValues[2].toFloatOrNull()
                if (e0 != null && e1 != null && e0 >= e1) {
                    bad += "$name: smoothstep($e0, $e1, ...) —— edge0 >= edge1 是 UB"
                }
            }
        }
        assertTrue("这些 smoothstep 在部分驱动上会直接返回 0（整段效果静默失效）：\n$bad", bad.isEmpty())
    }

    @Test
    fun `反序写法在本轮修正的四个 shader 里都不存在`() {
        val targets = listOf("FRAG_P2", "FRAG_EMIT", "FRAG_LIGHTWRAP", "FRAG_P2B")
        val sh = shaders()
        targets.forEach { name ->
            val body = stripComments(sh[name] ?: error("找不到 $name"))
            assertTrue("$name 仍在使用反序 smoothstep(0.2, -0.2, ...)", !body.contains("smoothstep(0.2, -0.2"))
        }
    }

    // ------------------------------------------------------------------ ② 逆光压暗

    @Test
    fun `P2 有逆光分支且被 backlit 门控`() {
        val p2 = shaders()["FRAG_P2"] ?: error("找不到 FRAG_P2")
        assertTrue("缺少逆光门控 if (uBacklitMode > 0.5)", p2.contains("if (uBacklitMode > 0.5)"))
        // v0.30：改用 litRatio（smoothstep(-0.2,0.3,N·L) × 轮廓带），阴影权重严格互补
        assertTrue(
            "必须有 litRatio = smoothstep(-0.20, 0.30, N·L) * eAmp",
            Regex("""litRatio\s*=\s*smoothstep\(-0\.20,\s*0\.30,""").containsMatchIn(p2),
        )
        assertTrue(
            "阴影权重必须与 litRatio 严格互补",
            Regex("""shadowW\s*=\s*1\.0\s*-\s*litRatio""").containsMatchIn(p2),
        )
        // 反序 smoothstep 的 UB 由 `没有任何 smoothstep 的 edge0 大于 edge1` 全量扫描兜住
        assertTrue("必须同时乘全局压暗 uBacklitGlobal", p2.contains("uBacklitGlobal"))
        assertTrue("必须同时乘背光侧压暗 uBacklitDarken", p2.contains("uBacklitDarken"))
        assertTrue("暗部必须染色（不能纯黑死灰）", p2.contains("uBacklitChroma"))
    }

    /**
     * v0.29 换了公式：不再是"染色后还原亮度"，而是**逐通道乘环境吸收色**。
     *
     * 两者都能避免"越染越亮"，但新的更直接：
     * `absorb = mix(基准暗度, 深暗部色相, 0.7)` 本身就是乘法因子，亮度只降不升。
     */
    @Test
    fun `逆光压暗必须逐通道吸收环境色并做三级高度`() {
        val p2 = shaders()["FRAG_P2"] ?: error("找不到 FRAG_P2")
        assertTrue(
            "必须用 mix(基准暗度, uDeepShadowColor, 吸收比) 作为乘法因子",
            Regex("""absorb\s*=\s*mix\(vec3\(uBacklitDarken\),\s*uDeepShadowColor""").containsMatchIn(p2),
        )
        assertTrue("必须有三级高度阶梯 tier", Regex("""float\s+tier\s*=""").containsMatchIn(p2))
        assertTrue(
            "必须与迎光轮廓系数混合（否则金边一起被压黑）",
            Regex("""mix\(vec3\(uBacklitGlobal\),\s*target,\s*shadowW\)""").containsMatchIn(p2),
        )
    }

    /** 暗部绝不能被后续的 Add/Screen 提亮回去。 */
    @Test
    fun `所有 Screen 项在暗部都被门控`() {
        val p2 = shaders()["FRAG_P2"] ?: error("找不到 FRAG_P2")
        assertTrue("必须有 litGate", p2.contains("litGate"))
        // 伪光、环境亮部 Screen 都必须乘门控
        assertTrue(
            "伪立体光照的 Screen 必须乘 litGate",
            Regex("""uFakeLight\s*\*\s*0\.6\s*\*\s*litGate""").containsMatchIn(p2),
        )
        assertTrue(
            "环境亮部 Screen 必须乘 litGate",
            Regex("""0\.15\s*\*\s*litGate""").containsMatchIn(p2),
        )
        // 反弹光保留 25% 而不是清零（物理上确实打到暗部）
        assertTrue("反弹光在暗部应保留 25%", Regex("""mix\(0\.25,\s*1\.0,\s*litGate\)""").containsMatchIn(p2))
    }

    // ------------------------------------------------------------------ ③ 熔断

    @Test
    fun `EMIT 的发光源必须被阴影遮罩熔断`() {
        val e = shaders()["FRAG_EMIT"] ?: error("找不到 FRAG_EMIT")
        assertTrue("EMIT 必须声明 uShadowMask", e.contains("uniform sampler2D uShadowMask;"))
        assertTrue("EMIT 必须声明 uShadowCut", e.contains("uniform float uShadowCut;"))
        assertTrue("lit 必须乘以 (1 - shadowAmt * cut)", e.contains("lit *= 1.0 - shadowAmt"))
    }

    @Test
    fun `LIGHTWRAP 的遮挡必须取背光权重与遮罩的最大值`() {
        val w = shaders()["FRAG_LIGHTWRAP"] ?: error("找不到 FRAG_LIGHTWRAP")
        assertTrue("必须有 backW = 1 - smoothstep(-0.2, 0.2, facing)", w.contains("1.0 - smoothstep(-0.2, 0.2, facing)"))
        assertTrue("必须是 max(backW, shadowAmt * ...) 的双判据", Regex("""max\(\s*backW\s*,""").containsMatchIn(w))
        assertTrue("熔断后必须按环境地板混合", Regex("""mix\(uAmbientFloor,\s*1\.0,\s*keep\)""").containsMatchIn(w))
    }

    @Test
    fun `P2B 的立体反光也必须熔断`() {
        val b = shaders()["FRAG_P2B"] ?: error("找不到 FRAG_P2B")
        assertTrue("P2B 必须声明 uShadowMask", b.contains("uniform sampler2D uShadowMask;"))
        assertTrue("facing 必须被阴影熔断", b.contains("facing *= 1.0 - shadowAmt"))
    }

    @Test
    fun `P2B 的外法向必须是取负的梯度`() {
        val b = shaders()["FRAG_P2B"] ?: error("找不到 FRAG_P2B")
        // 法向取反 bug：`+grad` 会让反光出现在背光侧（与设计相反）
        // v0.28 起法向在像素空间算（差分 / (2*uTexel)），但仍必须取负。
        assertTrue(
            "外法向必须写成 -gPx / gLen，否则反光方向反了",
            Regex("""nPx\s*=\s*gLen\s*>\s*1e-6\s*\?\s*-gPx\s*/\s*gLen""").containsMatchIn(b),
        )
        assertTrue("必须 normalize 光源向量（estimate 的向量不保证单位长度）",
            Regex("""normalize\(uLightDir""").containsMatchIn(b))
    }

    @Test
    fun `相关 uniform 全部声明`() {
        val need = mapOf(
            "FRAG_P2" to listOf("uBacklitMode", "uBacklitDarken", "uBacklitGlobal", "uBacklitChroma"),
            "FRAG_EMIT" to listOf("uShadowMask", "uShadowCut"),
            "FRAG_LIGHTWRAP" to listOf("uShadowCut", "uAmbientFloor"),
            "FRAG_P2B" to listOf("uShadowMask", "uShadowCut"),
        )
        val sh = shaders()
        need.forEach { (name, list) ->
            val body = sh[name] ?: error("找不到 $name")
            list.forEach { u ->
                assertTrue("$name 缺少 uniform $u", Regex("""uniform\s+\w+\s+$u\s*;""").containsMatchIn(body))
            }
        }
    }

    // ------------------------------------------------------------------ ③b 投影阴影

    /**
     * 接触阴影必须**向上**采样（朝人物脚底）。
     *
     * 本工程 `uv.y = 1` 是画面底部。旧写法 `uv + uContactPx * uTexel.y` 是向下看，
     * 而人物正下方的背景再往下还是背景 → 采样恒为 0 → 接触阴影**从未生效**，
     * 这就是"人物像飘在空中"的直接原因。方向写反不会有任何报错。
     */
    @Test
    fun `接触阴影必须向上采样`() {
        val p = shaders()["FRAG_P2C"] ?: error("找不到 FRAG_P2C")
        val code = stripComments(p)
        assertTrue(
            "必须用 -uContactPx 采样（向上 = 朝脚底）",
            Regex("""-\s*uContactPx\s*\*\s*uTexel\.y""").containsMatchIn(code),
        )
        assertTrue(
            "禁止出现 +uContactPx（那是向下看，恒为 0）",
            Regex("""\+\s*uContactPx\s*\*\s*uTexel\.y""").findAll(code).count() == 0,
        )
    }

    /**
     * 主投影的暗度上限必须接近死黑。
     *
     * 旧版把 `uStrength`（默认 0.3）直接乘在 mask 上，根部最多 0.3 的暗度 ——
     * 在明亮背景上等于看不见。现在先算本体深度（硬核 0.95 / 软层 0.55）再乘强度。
     */
    @Test
    fun `主投影必须有接近死黑的硬核层`() {
        val p = shaders()["FRAG_P2C"] ?: error("找不到 FRAG_P2C")
        assertTrue("硬核层必须 ×0.95（接近死黑）", p.contains("* 0.95"))
        assertTrue("软层必须有本体暗度 ×0.55", p.contains("* 0.55"))
        assertTrue(
            "末端必须用 smoothstep 淡出（线性衰减在末端导数不为 0，会留硬边）",
            Regex("""1\.0\s*-\s*smoothstep\(0\.35,\s*1\.0,""").containsMatchIn(p),
        )
        assertTrue("末端仍保留 5% 不至于完全断掉", Regex("""mix\(0\.05,\s*1\.0,\s*tail\)""").containsMatchIn(p))
    }

    // ------------------------------------------------------------------ ④ 顺序

    /**
     * EMIT / P2-b 都读 P2-c 的遮挡，所以 P2-c 必须在它们之前被绘制。
     * 顺序错了不会编译失败，只会**读到上一帧或空白纹理**（画面表现为"熔断不生效"）。
     */
    @Test
    fun `P2-c 的绘制早于 EMIT 与 P2-b`() {
        val s = src()
        val i2c = s.indexOf("""draw(programs["p2c"]""")
        val iEmit = s.indexOf("""draw(programs["emit"]""")
        val i2b = s.indexOf("""draw(programs["p2b"]""")
        assertTrue("找不到 p2c 的 draw", i2c > 0)
        assertTrue("找不到 emit 的 draw", iEmit > 0)
        assertTrue("找不到 p2b 的 draw", i2b > 0)
        assertTrue("p2c($i2c) 必须在 emit($iEmit) 之前", i2c < iEmit)
        assertTrue("p2c($i2c) 必须在 p2b($i2b) 之前", i2c < i2b)
    }

    /** 常量必须单一来源：shader 里的默认值与 `BacklitShade` 不能各写一套。 */
    @Test
    fun `管线传参来自 BacklitShade 而不是硬编码`() {
        // ⚠️ 这里不能靠 `path.replace("core-cv/...", "core-pipeline/...")` —— Windows 下路径是
        //    反斜杠，replace 不生效，于是 pipe 仍指向本模块的文件、断言自然成立 ——
        //    一个"永远通过"的假测试。改成从本文件向上找仓库根，找不到就直接失败。
        val rel = "core-pipeline/src/main/java/com/naiwa/core/pipeline/DefaultImagePipeline.kt"
        var root: File? = sourceFile().parentFile
        var pipe: File? = null
        while (root != null) {
            val f = File(root, rel)
            if (f.exists()) { pipe = f; break }
            root = root.parentFile
        }
        val target = pipe ?: error("找不到 $rel（cwd=${File(".").absolutePath}）")
        val t = target.readText()
        listOf("DARKEN_BACK", "DARKEN_GLOBAL", "CHROMA_WEIGHT", "CUT_STRENGTH", "AMBIENT_FLOOR")
            .forEach { assertTrue("DefaultImagePipeline 应引用 BacklitShade.$it", t.contains("BacklitShade.$it")) }
        // 遮挡不能再打折：1.0 才能让 max(背光, 遮罩) 真的熔断
        assertTrue("wrapShadowOcclusion 必须是 1.0", t.contains("wrapShadowOcclusion = 1.0f"))
    }

    @Test
    fun `smoothstep 扫描确实扫到了内容`() {
        // 防止正则失效后测试变成"永远通过"
        val re = Regex("""smoothstep\(\s*(-?\d*\.?\d+)\s*,\s*(-?\d*\.?\d+)\s*,""")
        val n = shaders().values.sumOf { re.findAll(stripComments(it)).count() }
        assertTrue("字面量 smoothstep 只有 $n 处，正则可能失效", n > 30)
    }
}
