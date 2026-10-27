package com.naiwa.core.cv.shadow

import android.graphics.Bitmap
import com.naiwa.core.common.model.LightFxRules
import com.naiwa.core.common.model.LightParams
import com.naiwa.core.common.model.ShadowPlacement
import com.naiwa.core.common.model.ShadowPlacementRule
import com.naiwa.core.cv.scene.ScenePalette
import com.naiwa.core.cv.util.BoxBlur
import kotlin.math.cos
import kotlin.math.sin

/**
 * 方向性阴影合成。
 *
 * 【实现方式：沿光方向的"扫掠"投影，而不是仿射 shear】
 * 早期方案（ROADMAP v0.2 里记录的）是"沿光方向 shear + 垂直压扁"，那只是**仿射近似**：
 * 阴影轮廓是整只奶蛙掩膜的仿射变换结果，**不会跟随局部形状**（跷起的腿、耳朵不会有各自的投影），
 * 复杂姿态下看起来像贴纸。本实现改为：
 * ```
 * 对掩膜里每个像素，沿"背光方向"逐步推进 t ∈ (0, 1]，把贡献累加到目标位置，权重 (1-t)^衰减
 * ```
 * 这在几何上等价于把掩膜沿光方向做**带衰减的 1D 卷积**（也就是"沿光方向的距离变换"的效果），
 * 于是阴影轮廓自然跟随奶蛙形状，且越远越淡 —— 这正是原计划放到 P3 的升级方案。
 *
 * 【性能】扫掠的复杂度是 O(步数 × 掩膜像素)。1080p 掩膜直接扫要上亿次操作，
 * 因此在 **1/4 分辨率**上生成阴影再上采样（架构文档 §7 的阴影阶段就是这么预算的）。
 * 1080p → 约 13 万像素 × 24 步 ≈ 300 万次操作，几十毫秒级。
 */
object DirectionalShadow {

    /** 1/4 分辨率生成阴影。 */
    private const val SHADOW_SCALE = 4

    /** 扫掠步数：24 步在 1/4 分辨率下已足够平滑，再多只增加耗时。 */
    private const val SWEEP_STEPS = 24

    /**
     * 把方向性阴影乘到场景上。
     *
     * @param scene 场景位图（不被修改）
     * @param frogAlpha 奶蛙 alpha 掩膜（0~255），尺寸为 alphaWidth × alphaHeight
     * @param placementRect 奶蛙在场景中的外接矩形 `[left, top, right, bottom]`
 * @param light 光照参数：用 [LightParams.shadowStrength]（阴影浓度）、[LightParams.shadowSoftness]（软硬），
 *              以及 [LightParams.keyAzimuthDeg] / [LightParams.keyElevationDeg]（经
 *              [ShadowPlacementRule] 换算成投位 —— 逆光投向前景、侧光沿光方向、顺光只剩接触阴影）
 * @param darkColorOverride 阴影颜色。null 表示自动取"场景暗部中位色再压暗"
 *        —— 用纯黑会让暖色场景里出现发灰的脏影子，这是最容易被看出破绽的地方。
 * @param palette 场景调色板。传入时直接用它的 [ScenePalette.shadowColor]，避免重复统计
 * @return 新的场景位图（调用方负责回收）
 */
fun composite(
    scene: Bitmap,
    frogAlpha: IntArray,
    alphaWidth: Int,
    alphaHeight: Int,
    placementRect: IntArray,
    light: LightParams,
    darkColorOverride: Int? = null,
    palette: ScenePalette? = null,
): Bitmap {
    val w = scene.width
    val h = scene.height
    val pixels = IntArray(w * h)
    scene.getPixels(pixels, 0, w, 0, 0, w, h)

    // 投位规则集中在 ShadowPlacementRule（纯函数、有单测），这里不重复判断逆光
    val placement = ShadowPlacementRule.resolve(light, LightFxRules.isBacklit(light))

    val shadow = buildShadowField(
        frogAlpha = frogAlpha,
        alphaWidth = alphaWidth,
        alphaHeight = alphaHeight,
        placementRect = placementRect,
        sceneWidth = w,
        sceneHeight = h,
        placement = placement,
        softness = light.shadowSoftness,
        strength = (light.shadowStrength * placement.strengthScale).coerceIn(0f, 1f),
    )

    val shadowColor = darkColorOverride ?: palette?.shadowColor ?: estimateShadowColor(pixels)
        val sr = (shadowColor shr 16) and 0xFF
        val sg = (shadowColor shr 8) and 0xFF
        val sb = shadowColor and 0xFF

        for (i in 0 until w * h) {
            val amount = shadow[i]
            if (amount <= 0.004f) continue
            val c = pixels[i]
            val a = (c ushr 24) and 0xFF
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            // 规格 8.4：Multiply（正片叠底）+ 图层不透明度 amount，替代旧的 Normal 混合。
            // result = pixel × (1 - amount + amount × shadowColor/255) —— 阴影随背景自身
            // 明暗变化（亮处压得多、暗处几乎不动），带环境色倾向，不会死黑也不会发灰。
            val fr = 1f - amount + amount * sr / 255f
            val fg = 1f - amount + amount * sg / 255f
            val fb = 1f - amount + amount * sb / 255f
            val nr = (r * fr).toInt().coerceIn(0, 255)
            val ng = (g * fg).toInt().coerceIn(0, 255)
            val nb = (b * fb).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 生成阴影强度场（纯计算，可单测）。
     *
     * @param placement 投位（方向 + 长度系数 + 浓度系数），由 [ShadowPlacementRule] 给出
     * @return 长度为 sceneWidth × sceneHeight 的浮点场，0 = 无阴影，1 = 全黑阴影
     */
    fun buildShadowField(
        frogAlpha: IntArray,
        alphaWidth: Int,
        alphaHeight: Int,
        placementRect: IntArray,
        sceneWidth: Int,
        sceneHeight: Int,
        placement: ShadowPlacement,
        softness: Float,
        strength: Float,
    ): FloatArray {
        val field = FloatArray(sceneWidth * sceneHeight)
        if (strength <= 0.01f) return field

        // --- 1) 把掩膜缩到 1/4 分辨率，并把坐标平移到场景坐标系 ---
        val sw = (sceneWidth / SHADOW_SCALE).coerceAtLeast(1)
        val sh = (sceneHeight / SHADOW_SCALE).coerceAtLeast(1)
        val mask = FloatArray(sw * sh)

        val rectLeft = placementRect[0]
        val rectTop = placementRect[1]
        val rectRight = placementRect[2]
        val rectBottom = placementRect[3]
        val rectW = (rectRight - rectLeft).coerceAtLeast(1)
        val rectH = (rectBottom - rectTop).coerceAtLeast(1)

        for (y in 0 until sh) {
            for (x in 0 until sw) {
                // 场景(1/4 分辨率)坐标 → 掩膜坐标
                val sceneX = (x * SHADOW_SCALE - rectLeft).toFloat() / rectW * alphaWidth
                val sceneY = (y * SHADOW_SCALE - rectTop).toFloat() / rectH * alphaHeight
                val mx = sceneX.toInt()
                val my = sceneY.toInt()
                if (mx < 0 || my < 0 || mx >= alphaWidth || my >= alphaHeight) continue
                mask[y * sw + x] = frogAlpha[my * alphaWidth + mx] / 255f
            }
        }

        // --- 2) 计算阴影偏移：投位规则已决定方向与长度系数（规格 7.3）---
        // 逆光 → 投向前景（画面下方），长度 ×0.6、浓度 ×0.5；
        // 侧光 → 沿光方向；顺光 → 只剩脚下一小片接触阴影。
        val azimuthRad = Math.toRadians(placement.azimuthDeg.toDouble())
        // 阴影长度上限：不超过"物体高度的 4 倍"，否则贴地光会生成一整片灰雾（v0.2 §6.5 的约定）
        val maxLen = rectH * SHADOW_LENGTH_CAP / SHADOW_SCALE
        val shadowLen = (rectH / SHADOW_SCALE * placement.lengthPerHeight).coerceAtMost(maxLen)

        val dirX = -cos(azimuthRad).toFloat()   // 背光方向 = 光源来向的反向
        val dirY = -sin(azimuthRad).toFloat()

        // --- 3) 扫掠累加 ---
        val swept = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val a = mask[y * sw + x]
                if (a <= 0.01f) continue
                for (step in 1..SWEEP_STEPS) {
                    val t = step.toFloat() / SWEEP_STEPS
                    val tx = (x + dirX * shadowLen * t).toInt()
                    val ty = (y + dirY * shadowLen * t).toInt()
                    if (tx < 0 || ty < 0 || tx >= sw || ty >= sh) continue
                    // 距离衰减：越远越淡。指数 1.6 让近处保持较实的边缘，远处快速淡出
                    val falloff = (1f - t).let { it * it * it }.toFloat()
                    val idx = ty * sw + tx
                    val v = a * falloff
                    if (v > swept[idx]) swept[idx] = v
                }
            }
        }

        // --- 4) 软硬：用 σ 控制模糊半径。softness=0 硬边，1 极柔 ---
        val sigma = (rectH / SHADOW_SCALE * (0.04f + softness * 0.55f)).coerceAtLeast(0.6f)
        val blurred = BoxBlur.blur(swept, sw, sh, BoxBlur.radiusForSigma(sigma), passes = 2)

        // --- 5) 上采样回场景分辨率，并乘上浓度 ---
        val upsampled = BoxBlur.upsample(blurred, sw, sh, sceneWidth, sceneHeight)
        for (i in field.indices) {
            field[i] = (upsampled[i] * strength).coerceIn(0f, MAX_SHADOW_AMOUNT)
        }
        return field
    }

    /**
     * 阴影颜色 = 场景暗部（下 20% 亮度）的中位色，再压暗到 [SHADOW_DARKEN]。
     * 用纯黑会在暖色场景里显得发灰，这是"一眼假"的典型来源。
     */
    private fun estimateShadowColor(pixels: IntArray): Int {
        val step = (pixels.size / 4096).coerceAtLeast(1)
        val samples = ArrayList<Int>(4096)
        var i = 0
        while (i < pixels.size) {
            samples += pixels[i]
            i += step
        }
        if (samples.isEmpty()) return 0xFF101010.toInt()
        samples.sortBy { c ->
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        val darkCount = (samples.size * 0.2f).toInt().coerceAtLeast(1)
        var r = 0L
        var g = 0L
        var b = 0L
        for (k in 0 until darkCount) {
            val c = samples[k]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        val rr = (r / darkCount * SHADOW_DARKEN).toInt().coerceIn(0, 255)
        val gg = (g / darkCount * SHADOW_DARKEN).toInt().coerceIn(0, 255)
        val bb = (b / darkCount * SHADOW_DARKEN).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** 阴影长度上限：物体高度的 4 倍（§6.5）。 */
    private const val SHADOW_LENGTH_CAP = 4f

    /** 阴影浓度上限。留 0.85 是刻意的：全黑阴影在任何真实光照下都不存在。 */
    private const val MAX_SHADOW_AMOUNT = 0.85f

    /** 暗部颜色再压暗的比例。 */
    private const val SHADOW_DARKEN = 0.55f
}
