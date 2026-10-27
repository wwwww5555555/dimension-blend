package com.naiwa.core.cv.blend

import com.naiwa.core.cv.fusion.RepDetailRefiner
import com.naiwa.core.cv.util.BoxBlur
import com.naiwa.core.cv.util.DistanceField
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 边缘三段式的第三段：**α 加权回贴**（消光晕的关键）。
 *
 * 为什么不能只用一种结果：
 *  - `seamlessClone` 会把目标区域的**所有**像素替换掉 → 在高频背景（草地、木纹、砂石）上会糊成一块；
 *  - 羽化直贴保留了背景纹理，但在过渡带会留下**色温断层**（直贴没做梯度域求解）。
 * 因此把两者按权重混合：
 * ```
 * 中心区（内缩内）：        out = 泊松/多频段结果
 * 过渡带（外扩 feather）：  out = (1-w) × 羽化直贴 + w × 融合结果,  w 由 smoothstep 从 0 升到 1
 * 过渡带之外：             out = 场景原像素
 * ```
 *
 * 【feather 有量纲】`feather_px = clamp(0.02 × min(w,h), 6, 24)` 是在 **1080p 工作域**下的值，
 * 其它工作分辨率按 `workShortSide / 1080` 线性缩放，并保留 2px 下限
 * —— 没有量纲的"外扩 N 像素"在不同分辨率/不同设备上完全不可复现。
 *
 * 【可选的第四段：细节保真】三段式解决了"糊多少"的问题，但没有找回丢掉的**高频细节**。
 * 高配设备上会额外走一步 [RepDetailRefiner]（结构重参数化的多尺度高通），
 * 把原始背景的纹理补回过渡带。
 *
 * 修正权重取 `band = smoothstep(core) × (1 - 奶蛙alpha)`，两个因子各有其含义：
 *  - `smoothstep(core)` 就是混合式里的 `w` —— **融合结果在多大程度上取代了羽化直贴**，
 *    丢掉的高频量正比于它（早期版本用 `1 - core`，在 w≈0 的外圈重复补偿，实测把误差从 11.59 放大到 26.68）；
 *  - `1 - alpha` —— 补偿只对**背景像素**成立，奶蛙本体上的像素补背景细节等于污染奶蛙。
 * 两者相乘后天然满足：奶蛙本体（alpha=1）为 0、远离掩膜（w=0）为 0，不需要额外门控。
 * 因为高通的直流增益为 0，所以这一步不改变整体亮度，属于纯加法改进；
 * `detailStrength = 0` 时行为与从前逐像素一致（有单测）。
 *
 * 【为什么回报 `DetailReport`】这一步是**增强而非主链路**：它可能因为内存紧张或参数异常而失败。
 * 失败时必须退回未修正的结果（不能把预览打挂），同时**如实告诉调用方"没做成"** ——
 * 否则管线会照旧上报"已启用细节保真"，那就成了谎报。
 */
object EdgeHaloFix {

    /**
     * 细节保真的执行回报。
     *
     * 存在的理由见类 KDoc：管线要据此把 `RenderResult.backend` 改成实际生效的那一个。
     * 不是线程安全的"结果对象"，每次调用新建一个即可。
     */
    class DetailReport {
        /** 是否真的应用了修正。 */
        @Volatile
        var applied: Boolean = false

        /** 未应用的原因（用于日志），应用成功时为 null。 */
        @Volatile
        var fallbackReason: String? = null
    }

    /**
     * 羽化基准区间（规格 6.5 给定）。
     *
     * ⚠️ **数值变更记录**：早期规格给的是 `clamp(0.02 × min(w,h), 6, 24)`，
     * 本轮边缘修复规格 6.5 改为 `clamp(0.015 × min(w,h), 4, 20)`，已按后者执行。
     * 差别：1080p 工作域从 21px 收到 16px。**这是刻意的取舍** ——
     * 过渡带越窄，"泊松结果"的占比越小、直贴背景的占比越大，白边与糊块的可见度都降低；
     * 代价是融合的梯度域解在边缘的影响范围变窄（更依赖 6.2 去白把颜色本身修对）。
     * 想回退只改这三个常量。
     */
    private const val FEATHER_RATIO = 0.015f
    private const val FEATHER_MIN_PX = 4f
    private const val FEATHER_MAX_PX = 20f
    private const val FEATHER_ABS_MIN_PX = 2f

    /** 中心区的额外内缩：保证替换区完全落在掩膜内部，不把边缘背景色带进来。 */
    private const val CORE_SHRINK_PX = 8

    /**
     * 计算羽化半径（像素），带量纲换算。
     *
     * `feather = clamp(0.02 × 工作域短边, 6, 24) × 手动倍率`，下限 2px。
     *
     * 注意**不要**再乘一次 `workShortSide / 1080`：`0.02 × min(w,h)` 本身已经随分辨率线性变化，
     * 再乘一次会变成随分辨率的平方变化（720p 会算出 9px 而不是正确的 14px）。
     * 钳制区间 [6, 24] 是以 1080p 为基准给出的经验值，其它分辨率由线性项自然缩放。
     *
     * @param workShortSide 当前工作域的短边
     * @param featherScale 手动倍率（P3 滑杆，默认 1.0，范围 0.5~2.0）
     */
    fun featherPx(workShortSide: Int, featherScale: Float = 1f): Int {
        val base = (FEATHER_RATIO * workShortSide).coerceIn(FEATHER_MIN_PX, FEATHER_MAX_PX)
        val scaled = base * featherScale
        return max(FEATHER_ABS_MIN_PX, scaled).toInt()
    }

    /**
     * α 加权回贴（纯计算，可单测）。
     *
     * @param scenePixels 原始场景
     * @param featheredPixels 羽化直贴结果（奶蛙已做颜色协调与轮廓光）
     * @param fusedPixels 泊松/多频段融合结果
     * @param mask 前景掩膜（0~255），与图像同尺寸
     * @param detailStrength **细节保真修正强度**（0 = 关闭，行为与不带本参数时逐像素相同）。
     *   大于 0 时，过渡带会由 `RepDetailRefiner` 把原始背景的高频细节补回来
     *   —— 修的是"泊松结果上采样后过渡带发糊"这个已知缺陷。
     *   中心区（α=1）与掩膜外不受影响，所以这一路是**加法改进**，不会破坏已调好的行为。
     * @param detailReport 非 null 时回填"是否真的应用了修正"；失败不会抛异常，只会退回未修正结果
     * @return 新数组
     */
    fun blend(
        scenePixels: IntArray,
        featheredPixels: IntArray,
        fusedPixels: IntArray,
        mask: IntArray,
        width: Int,
        height: Int,
        featherPx: Int,
        detailStrength: Float = 0f,
        detailReport: DetailReport? = null,
        lightAzimuthDeg: Float? = null,
    ): IntArray {
        val out = IntArray(scenePixels.size)

        // 掩膜按 feather 距离做两级模糊：用来构造平滑的 w 权重
        val maskFloat = FloatArray(width * height) { mask[it] / 255f }
        val coreRadius = CORE_SHRINK_PX.coerceAtMost(featherPx)
        val softMask = BoxBlur.blur(
            maskFloat, width, height,
            BoxBlur.radiusForSigma(featherPx.coerceAtLeast(1).toFloat()),
            passes = 2,
        )
        // 中心区：把软掩膜再"提纯"（阈值化 + 模糊），保证 α=1 的区域完全采用融合结果
        val coreMask = FloatArray(width * height) { i ->
            if (maskFloat[i] > 0.99f) 1f else 0f
        }
        val core = if (coreRadius > 0) {
            BoxBlur.blur(coreMask, width, height, coreRadius, passes = 1)
        } else {
            coreMask
        }

        // ---- 规格 8.3：方向性渐变（迎光面窄硬 / 背光面宽软） ----
        // 光源来向单位向量（LightParams 约定：azimuth = 光源方位角，图像坐标 x 右 y 下）。
        // 迎光判定：外法向 · 光源来向 > 0。迎光侧过渡带窄（w 提前到 1，边缘硬），
        // 背光侧过渡带长（w 推迟，边缘软）—— 真实镜头景深下背光边缘总是更"雾"。
        val directional = lightAzimuthDeg != null
        var field: FloatArray? = null
        var lx = 0f
        var ly = 0f
        if (directional) {
            val az = Math.toRadians(lightAzimuthDeg!!.toDouble())
            lx = cos(az).toFloat()
            ly = sin(az).toFloat()
            val bin = IntArray(width * height) { if (mask[it] >= 128) 1 else 0 }
            field = DistanceField.compute(bin, width, height)
        }

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val srcAlpha = maskFloat[i]
                // 完全在掩膜外：保留场景原像素
                if (srcAlpha <= 0.001f && core[i] <= 0.001f && softMask[i] <= 0.001f) {
                    out[i] = scenePixels[i]
                    continue
                }

                // w = 1（中心）→ 用融合结果；w = 0（过渡带外沿）→ 用羽化直贴
                var w = smoothStep(core[i].coerceIn(0f, 1f))

                // 方向性修正只在过渡带内起作用（w=0/1 处无变化），null 时行为与旧版逐位一致
                if (directional && w > 0.001f && w < 0.999f) {
                    val n = DistanceField.outwardNormal(field!!, width, height, x, y)
                    val facing = n[0] * lx + n[1] * ly
                    if (facing != 0f) {
                        w = (w + 0.35f * w * (1f - w) * facing).coerceIn(0f, 1f)
                    }
                }

                val fused = fusedPixels[i]
                val feathered = featheredPixels[i]

                val r = (feathered shr 16 and 0xFF) * (1f - w) + (fused shr 16 and 0xFF) * w
                val g = (feathered shr 8 and 0xFF) * (1f - w) + (fused shr 8 and 0xFF) * w
                val b = (feathered and 0xFF) * (1f - w) + (fused and 0xFF) * w

                // 最终 alpha 取"场景不透明（自身 alpha=0 表示场景本来就是不透明的）"：
                // 我们只在场景上做合成，所以输出 alpha 恒为 255（除场景本身带 alpha 的情况）
                val srcA = (scenePixels[i] ushr 24) and 0xFF
                val a = if (srcA > 0) 255 else (srcAlpha * 255f).toInt().coerceIn(0, 255)
                out[i] = (a shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }

        if (detailStrength <= 0f) return out

        // ---- 细节保真：只补"融合结果取代了背景"的那部分损失 ----
        //
        // 权重推导（这一步必须对，否则就是过冲）：
        //   混合结果 out = (1 - w) × 羽化直贴 + w × 融合结果，  w = smoothstep(core)
        //   羽化直贴保留了原始背景纹理，融合结果（泊松域上采样）丢了高频。
        //   所以"丢失的细节量"正比于 **w**，而不是 1 - core。
        //   早期版本用 band = 1 - core：在 w≈0 的外圈（细节本来就在）又补了一遍，
        //   实测把过渡带误差从 11.59 放大到 26.68。这是**过冲**，不是修复。
        //
        // 再乘 (1 - 奶蛙 alpha)：
        //   补偿只对**背景像素**成立；奶蛙身上的像素不属于背景，补背景细节等于污染奶蛙。
        //   两个因子相乘后天然满足：掩膜深处 alpha=1 → 0；外圈 w=0 → 0。无需额外门控。
        val bandWeightAt = { i: Int ->
            val w = smoothStep(core[i].coerceIn(0f, 1f))
            w * (1f - maskFloat[i].coerceIn(0f, 1f))
        }

        // 两步走：先扫出非零 band 的包围盒，**再**按包围盒大小分配数组。
        // 一次性分配整图 FloatArray 在 4096² 下要 67MB，而真正非零的只有一圈窄环。
        var bx0 = Int.MAX_VALUE
        var by0 = Int.MAX_VALUE
        var bx1 = Int.MIN_VALUE
        var by1 = Int.MIN_VALUE
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (bandWeightAt(row + x) <= 0f) continue
                if (x < bx0) bx0 = x
                if (y < by0) by0 = y
                if (x > bx1) bx1 = x
                if (y > by1) by1 = y
            }
        }
        if (bx0 > bx1 || by0 > by1) {
            detailReport?.fallbackReason = "无过渡带像素"
            return out
        }

        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        val band = FloatArray(bw * bh)
        for (y in by0..by1) {
            val row = y * width
            val bandRow = (y - by0) * bw
            for (x in bx0..bx1) {
                val v = bandWeightAt(row + x)
                if (v > 0f) band[bandRow + (x - bx0)] = v
            }
        }

        return try {
            val refined = RepDetailRefiner.refine(
                scenePixels = scenePixels,
                blendedPixels = out,
                bandWeight = band,
                width = width,
                height = height,
                region = intArrayOf(bx0, by0, bx1 + 1, by1 + 1),
                strength = detailStrength,
            )
            if (refined == null) {
                detailReport?.fallbackReason = "输入不合法或区域为空，已跳过"
                out
            } else {
                detailReport?.applied = true
                refined
            }
        } catch (t: Throwable) {
            // 细节保真是**增强**，不是主链路：任何失败都必须退回未修正结果，
            // 绝不能因为一个可选优化把预览/导出打挂（也绝不能静默谎报成功）
            detailReport?.fallbackReason = t.message ?: t::class.java.simpleName
            out
        }
    }

    /** 3t² - 2t³：两端一阶导为 0，避免过渡带出现可见的"折线"。 */
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)
}
