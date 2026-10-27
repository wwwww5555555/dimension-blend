package com.naiwa.core.cv.color

import android.graphics.Bitmap
import com.naiwa.core.common.color.KelvinRgb
import com.naiwa.core.common.model.LightParams

/**
 * 颜色与亮度协调（Reinhard 迁移 + lerp 力度 + 分位数亮度匹配 + 色温增益）。
 *
 * 【本文件的核心修正】力度**不乘在 σ 上**：
 * ```
 * 错：c_out = (c - μ_frog) * (σ_scene / σ_frog) * strength + μ_scene
 *     —— strength = 0 时仍有 μ_scene 平移，"力度归零"居然还改图；且力度变化时均值与对比度耦合抖动。
 * 对：c_ref  = (c_frog - μ_frog) * (σ_scene / σ_frog) + μ_scene   // 标准 Reinhard
 *     c_out  = lerp(c_frog, c_ref, strength)                     // 力度用线性插值施加
 * ```
 * 修正后：strength = 0 严格等于原图（恒等变换），strength = 1 严格等于完整迁移，中间连续可调。
 *
 * 【三路机制的分工】最终只对 L、a/b、色温三处各施一份力，避免任何单一机制过强：
 *  - **L 通道**：均值-方差 + 分位数映射（权重最强，亮度匹配是"看起来同一个场景"的关键）；
 *  - **a/b 通道**：均值-方差迁移 × [AB_TRANSFER_WEIGHT]（0.75：参考图级的染色深度要求
 *    色度迁移占主导 —— 夕阳把肤色染橙、雪天把角色染灰蓝都靠它；仍保留 0.25 余量防止
 *    局部背景色污染把奶蛙整体带偏）；
 *  - **色温**：由 [KelvinRgb] 施加 × [COLOR_TEMP_WEIGHT]（与自动估计、手动滑杆共用同一套映射）。
 */
object ColorTransfer {

    /**
     * 对奶蛙做颜色与亮度协调。
     *
     * @param frog 奶蛙位图（必须带 alpha；透明区不参与统计也不被修改）
     * @param scene 场景位图（只读，用于取参考统计量）
     * @param placementRect `[left, top, right, bottom]`，奶蛙在场景中的外接矩形。
     *        场景统计**只在这个矩形内取样**：整图统计会被大片天空/墙面主导，与奶蛙实际所处的局部光照无关。
     * @param light 自动估计（或手动覆盖）后的光照参数
     * @param strength 力度，[0,1]。0 = 原样返回，1 = 完全迁移
     * @return 新的位图（调用方负责回收）；输入不会被修改
     */
    fun harmonize(
        frog: Bitmap,
        scene: Bitmap,
        placementRect: IntArray,
        light: LightParams,
        strength: Float,
    ): Bitmap {
        val w = frog.width
        val h = frog.height
        val frogPixels = IntArray(w * h)
        frog.getPixels(frogPixels, 0, w, 0, 0, w, h)

        val sceneW = scene.width
        val sceneH = scene.height
        val scenePixels = IntArray(sceneW * sceneH)
        scene.getPixels(scenePixels, 0, sceneW, 0, 0, sceneW, sceneH)

        val out = transferPixels(
            frogPixels = frogPixels,
            width = w,
            height = h,
            scenePixels = scenePixels,
            sceneWidth = sceneW,
            sceneHeight = sceneH,
            placementRect = placementRect,
            light = light,
            strength = strength,
        )

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 纯计算版（可直接 JVM 单测，不依赖 Android 框架）。
     *
     * 色彩空间转换走 [LabColor]，其内部的 cbrt / pow 已由一维查找表承担
     * （见 `Lut1d` 的 KDoc：三维点变换 LUT 试过，实测更慢且精度不够，已弃用）。
     *
     * @return 处理后的 ARGB 像素（新数组）；alpha 与输入完全一致
     */
    fun transferPixels(
        frogPixels: IntArray,
        width: Int,
        height: Int,
        scenePixels: IntArray,
        sceneWidth: Int,
        sceneHeight: Int,
        placementRect: IntArray,
        light: LightParams,
        strength: Float,
    ): IntArray {
        val result = frogPixels.copyOf()
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return result

        // 参考统计量：奶蛙侧在 alpha 掩膜内，场景侧在奶蛙所占的局部矩形内
        val frogStats = LabColor.LabStats.compute(frogPixels, width, height)
        val sceneStats = LabColor.LabStats.compute(
            scenePixels, sceneWidth, sceneHeight, region = placementRect,
        )
        val frogPercentiles = LabColor.lumaPercentiles(frogPixels, width, height)
        val scenePercentiles = LabColor.lumaPercentiles(
            scenePixels, sceneWidth, sceneHeight, region = placementRect,
        )

        val gain = KelvinRgb.kelvinToRgbGain(light.colorTempK.toInt()).let {
            // 色温增益只施加一部分：a/b 通道迁移已经承担了一部分"融入场景色偏"的职责，
            // 全额叠加会变成双重上色（例如暖光场景里奶蛙会过黄）。
            floatArrayOf(
                1f + (it[0] - 1f) * COLOR_TEMP_WEIGHT * s,
                1f + (it[1] - 1f) * COLOR_TEMP_WEIGHT * s,
                1f + (it[2] - 1f) * COLOR_TEMP_WEIGHT * s,
            )
        }

        val stdRatioL = sceneStats.stdL / frogStats.stdL.coerceAtLeast(0.5f)
        val stdRatioA = sceneStats.stdA / frogStats.stdA.coerceAtLeast(0.5f)
        val stdRatioB = sceneStats.stdB / frogStats.stdB.coerceAtLeast(0.5f)

        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for (i in 0 until width * height) {
            val argb = frogPixels[i]
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < MIN_ALPHA) continue
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF

            LabColor.rgbToLab(r, g, b, lab)

            // ---- L 通道：Reinhard + 分位数映射，两者取平均后与原始值做 lerp ----
            val lReinhard = (lab[0] - frogStats.meanL) * stdRatioL + sceneStats.meanL
            val lPercentile = percentileMap(
                lab[0], frogPercentiles[0], frogPercentiles[1], frogPercentiles[2],
                scenePercentiles[0], scenePercentiles[1], scenePercentiles[2],
            )
            val lTarget = (lReinhard * 0.5f + lPercentile * 0.5f).coerceIn(0f, 100f)
            val lOut = lerp(lab[0], lTarget, s)

            // ---- a/b 通道：只取一半权重（色度易被局部背景污染） ----
            val aReinhard = (lab[1] - frogStats.meanA) * stdRatioA + sceneStats.meanA
            val bReinhard = (lab[2] - frogStats.meanB) * stdRatioB + sceneStats.meanB
            val aOut = lerp(lab[1], lerp(lab[1], aReinhard, s), s * AB_TRANSFER_WEIGHT)
            val bOut = lerp(lab[2], lerp(lab[2], bReinhard, s), s * AB_TRANSFER_WEIGHT)

            LabColor.labToRgb(lOut, aOut, bOut, rgb)

            // ---- 色温增益（与自动估计同源） ----
            val rr = (rgb[0] * gain[0]).toInt().coerceIn(0, 255)
            val gg = (rgb[1] * gain[1]).toInt().coerceIn(0, 255)
            val bb = (rgb[2] * gain[2]).toInt().coerceIn(0, 255)

            result[i] = (alpha shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return result
    }

    /**
     * 三点分位数映射：把 [fp5, fp50, fp95] 线性映射到 [sp5, sp50, sp95]。
     * 分两段线性（暗端与亮端各自映射），比单段线性更贴合实际直方图形状。
     */
    private fun percentileMap(
        value: Float,
        fp5: Float, fp50: Float, fp95: Float,
        sp5: Float, sp50: Float, sp95: Float,
    ): Float {
        val lowSpan = (fp50 - fp5).coerceAtLeast(0.5f)
        val highSpan = (fp95 - fp50).coerceAtLeast(0.5f)
        return if (value <= fp50) {
            sp5 + (value - fp5) / lowSpan * ((sp50 - sp5).coerceAtLeast(0f))
        } else {
            sp50 + (value - fp50) / highSpan * ((sp95 - sp50).coerceAtLeast(0f))
        }
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private const val MIN_ALPHA = 8

    /**
     * a/b 通道迁移的权重：0.75 = 参考图级的染色深度（夕阳染橙/雪天染灰蓝），
     * 同时保留 0.25 余量防止局部背景色污染。
     */
    private const val AB_TRANSFER_WEIGHT = 0.75f

    /** 色温增益的权重：与 a/b 迁移分摊"融入场景色偏"，0.7 补足主光方向的色偏。 */
    private const val COLOR_TEMP_WEIGHT = 0.7f
}
