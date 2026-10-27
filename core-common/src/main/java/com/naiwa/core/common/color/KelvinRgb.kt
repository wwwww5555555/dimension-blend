package com.naiwa.core.common.color

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 色温 ↔ RGB 增益的**互逆函数对**。
 *
 * 为什么必须是一对：自动估计出的色温与手动色温滑杆**必须走同一套映射**。
 * 如果正解用一套近似公式、反解再写另一套，两者在中间色温段会产生系统性偏移，
 * 用户会看到"自动估计是 4200K，但手动滑杆拖到 4200K 效果不一样"这种诡异现象。
 * 因此反解**不是另写公式**，而是对前向函数做数值反演，从构造上保证互逆。
 *
 * 放在 `:core-common` 而不是 `:core-cv`：它是纯数学、零依赖，而 `:core-ai` 的光照估计器也要用它；
 * 依赖纪律规定 `:core-cv` 与 `:core-ai` 不能互相依赖，下沉到公共层是唯一不破坏纪律的落点。
 *
 * 归一化约定：**6500 K 恒等于 [1, 1, 1]**。增益只表达相对偏移，不携带整体亮度信息
 * （整体亮度由 `LightParams.brightness` 单独负责）。
 */
object KelvinRgb {

    /** 中性色温参考点（与 `LightParams.NEUTRAL_TEMP_K` 一致）。 */
    const val NEUTRAL_KELVIN = 6500

    /** 搜索区间：低于 1500 K 现实中不存在（烛光约 1800 K），高于 15000 K 已是纯蓝。 */
    const val MIN_KELVIN = 1500
    const val MAX_KELVIN = 15000

    /** 粗扫步长（K）。100K 足以定位到正确区间，剩下的交给细化。 */
    private const val COARSE_STEP_KELVIN = 100

    /** 三分搜索迭代次数：40 次把 200K 窗口收敛到远小于 1K。 */
    private const val REFINE_ITERATIONS = 40

    /** 6500 K 的参考 RGB，只算一次。 */
    private val reference: FloatArray by lazy { blackbodyRgb(NEUTRAL_KELVIN.toFloat()) }

    /**
     * 开尔文色温 → 归一化 RGB 增益。
     *
     * @param kelvin 色温（超出 [MIN_KELVIN, MAX_KELVIN] 会被钳制）
     * @return 长度 3 的增益；6500 K 时恒为 [1, 1, 1]。
     *         数值 > 1 表示"该通道需要被增强才能让画面呈现这个色温"
     *         （低色温偏暖 → 红增益偏高、蓝增益 < 1）。
     */
    fun kelvinToRgbGain(kelvin: Int): FloatArray =
        gainAt(kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN).toFloat())

    /**
     * RGB 增益 → 开尔文色温（与 [kelvinToRgbGain] 严格互逆，实测往返误差 < 5 K）。
     *
     * 【为什么不用二分搜索单调量】最直觉的做法是对 R/B 比值二分，但有两个坑：
     *  1. 前向增益是**除以 6500K 参考归一化**过的，反解若拿原始比值比较会差一个常数项
     *     （表现为几十到上百 K 的系统偏移，暖端冷端还不一样）；
     *  2. 黑白体近似在 R 通道饱和（T ≤ 66 时 R 直接取 255），6600~6690K 这段 R/B 出现**平台**，
     *     单调性被破坏，二分搜索会停在平台边缘。
     * 因此这里改为：在 log-RGB 空间（按绿通道归一化以消除整体缩放）
     * 用「粗扫定位 + 光滑函数上的三分细化」求最小值。不依赖单调性假设。
     *
     * 关键细节：细化阶段必须用**浮点 K** 的前向函数算距离。
     * 早期版本在距离里对 K 取整，距离函数变成阶梯状，
     * 三分搜索在平台上会一路漂移，实测最坏误差达到 953 K。
     *
     * @param gain 长度 3 的增益数组，可含任意正的整体缩放；含非正值时返回 [NEUTRAL_KELVIN]
     */
    fun rgbGainToKelvin(gain: FloatArray): Int {
        require(gain.size == 3) { "增益数组长度必须为 3，当前为 ${gain.size}" }
        if (gain[0] <= 0f || gain[1] <= 0f || gain[2] <= 0f) return NEUTRAL_KELVIN

        // 按绿通道归一化：色温只取决于相对比值，整体缩放（绝对亮度）不参与
        val targetLogR = ln(gain[0] / gain[1])
        val targetLogB = ln(gain[2] / gain[1])

        // 1) 粗扫定位
        var bestK = MIN_KELVIN
        var bestDistance = Float.MAX_VALUE
        var k = MIN_KELVIN
        while (k <= MAX_KELVIN) {
            val d = logDistanceAt(k.toFloat(), targetLogR, targetLogB)
            if (d < bestDistance) {
                bestDistance = d
                bestK = k
            }
            k += COARSE_STEP_KELVIN
        }

        // 2) 在最优点的邻域内三分细化（光滑距离函数在该窗口内单峰）
        var low = (bestK - COARSE_STEP_KELVIN).coerceAtLeast(MIN_KELVIN).toFloat()
        var high = (bestK + COARSE_STEP_KELVIN).coerceAtMost(MAX_KELVIN).toFloat()
        repeat(REFINE_ITERATIONS) {
            val third = (high - low) / 3f
            val m1 = low + third
            val m2 = high - third
            if (logDistanceAt(m1, targetLogR, targetLogB) <
                logDistanceAt(m2, targetLogR, targetLogB)
            ) {
                high = m2
            } else {
                low = m1
            }
        }
        return ((low + high) * 0.5f).roundToInt().coerceIn(MIN_KELVIN, MAX_KELVIN)
    }

    /**
     * 把色温增益施加到单个 ARGB 像素上（alpha 保持不变）。
     * 供纯 Kotlin 路径使用；OpenCV 路径直接对通道做乘法。
     */
    fun applyGainToPixel(argb: Int, gain: FloatArray): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (((argb shr 16) and 0xFF) * gain[0]).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * gain[1]).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * gain[2]).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------ 内部

    /** 浮点色温的归一化增益（前向函数的浮点版本，供反解使用）。 */
    private fun gainAt(kelvin: Float): FloatArray {
        val rgb = blackbodyRgb(kelvin.coerceIn(MIN_KELVIN.toFloat(), MAX_KELVIN.toFloat()))
        return floatArrayOf(rgb[0] / reference[0], rgb[1] / reference[1], rgb[2] / reference[2])
    }

    /** log-RGB 空间的两维距离（已按绿通道归一化，因此与整体缩放无关）。 */
    private fun logDistanceAt(kelvin: Float, targetLogR: Float, targetLogB: Float): Float {
        val gain = gainAt(kelvin)
        if (gain[0] <= 0f || gain[1] <= 0f || gain[2] <= 0f) return Float.MAX_VALUE
        val dr = ln(gain[0] / gain[1]) - targetLogR
        val db = ln(gain[2] / gain[1]) - targetLogB
        return dr * dr + db * db
    }

    /**
     * 黑白体辐射近似（Tanner Helland 拟合式）：计算极轻、可见范围内误差可接受。
     *
     * 分段原因：该拟合在 6600 K 附近 R 与 B 的表达式不同
     * （小于 6600 K 时 R 饱和到 255，大于时 B 饱和到 255），
     * 用单段多项式在两端会明显偏离。
     * 饱和带来的直接后果是：**2600 K 的红增益等于 1.0 而不是 > 1**，
     * 色温信息主要体现在蓝通道被压低上 —— 这是近似式的固有性质，不是 bug。
     *
     * @return 未归一化的线性 RGB，范围 1~255
     */
    private fun blackbodyRgb(kelvin: Float): FloatArray {
        val t = kelvin / 100f
        val r: Float
        val g: Float
        val b: Float

        if (t <= 66f) {
            r = 255f
            g = 99.4708f * ln(t) - 161.1196f
            b = if (t <= 19f) 0f else 138.5177f * ln(t - 10f) - 305.0448f
        } else {
            r = 329.6987f * (t - 60f).pow(-0.1332047f)
            g = 288.1221f * (t - 60f).pow(-0.0755148f)
            b = 255f
        }
        return floatArrayOf(
            r.coerceIn(1f, 255f),
            g.coerceIn(1f, 255f),
            b.coerceIn(1f, 255f),
        )
    }
}
