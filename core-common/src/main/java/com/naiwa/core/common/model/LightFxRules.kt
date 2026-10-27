package com.naiwa.core.common.model

/**
 * 高级光效的**规则层** —— 纯函数，不碰像素、不依赖 Android，可直接单测。
 *
 * 存在理由：7.1~7.6 里"什么情况下开哪个光效、开多强"全是判断，而像素循环在 `:core-cv`。
 * 如果把判断散在管线和 UI 里，就会出现"滑杆显示 0.3、实际跑了 0"这类对不上的状态。
 */
data class LightFxPlan(
    /** 是否判定为逆光场景（决定阴影投位、边缘光配色与强度）。 */
    val backlit: Boolean,
    /** 光源方位与"相机侧"的夹角（度），仅用于诊断显示。 */
    val angleToCameraDeg: Float,
    /** 边缘光强度，0 = 不执行该步骤（省性能）。 */
    val rimStrength: Float,
    /** 辉光强度，0 = 不执行。 */
    val glowStrength: Float,
    /** 四色渐变环境光强度，0 = 不执行。 */
    val lightWrapStrength: Float,
) {
    /** 是否有任何光效要跑（全 0 时管线可以整段跳过）。 */
    val hasAny: Boolean get() = rimStrength > 0f || glowStrength > 0f || lightWrapStrength > 0f

    /**
     * 面向用户的一行摘要，把"这张图加了哪些光效"如实说出来。
     *
     * 与 [hasAny] 的语义略有不同：即使三项全 0（例如低端机关了辉光与环境色、边缘光也因主光太弱归 0），
     * 也给出「逆光」/「无光效」等可读结论，而不是空字符串 —— 用户需要知道"为什么这里没有亮边"。
     */
    val summary: String
        get() {
            val parts = ArrayList<String>(4)
            if (backlit) parts += "逆光"
            if (rimStrength > 0f) parts += "边缘光"
            if (glowStrength > 0f) parts += "辉光"
            if (lightWrapStrength > 0f) parts += "环境色"
            return if (parts.isEmpty()) "无光效" else parts.joinToString("+")
        }
}

object LightFxRules {

    /**
     * "相机侧"的方位角。
     *
     * 约定：图像坐标 x 向右、y 向下（见 [LightParams] 的文档），相机位于画面**下方**看向场景，
     * 因此"相机那一侧"投影到图像平面就是 90°（下方）。
     * 逆光 = 光源在相机对面 → 方位角与 90° 的夹角 > [BACKLIT_ANGLE_DEG]。
     */
    const val CAMERA_SIDE_AZIMUTH_DEG = 90f

    /** 逆光判定阈值（规格 7.1：夹角 > 120°）。 */
    const val BACKLIT_ANGLE_DEG = 120f

    /** 主光强度下限（规格 7.1：> 0.5 才可能是逆光；弱光下没有可用的轮廓光）。 */
    const val MIN_KEY_INTENSITY = 0.5f

    /** 逆光时的边缘光基准增益（参考图级：白发金边/半剪影的强轮廓光，强度 = keyIntensity × 0.45）。 */
    const val RIM_BACKLIT_GAIN = 0.45f

    /** 非逆光时的基准增益：取一半 —— 侧光也有轮廓光，但远弱于逆光。 */
    const val RIM_SIDE_GAIN = 0.15f

    /** 边缘光强度上限（规格 7.1：滑杆 0~0.5）。 */
    const val RIM_MAX = 0.5f

    /** 辉光默认强度（规格 7.2：0.15~0.25，默认 0.2）。 */
    const val GLOW_BACKLIT = 0.2f

    /** 普通场景辉光减半（规格 7.2）。 */
    const val GLOW_NORMAL = 0.1f

    /** 辉光上限：过量就是"发光贴纸"，不是物理光效。 */
    const val GLOW_MAX = 0.3f

    /** 四色渐变默认强度（参考图级的边缘光包裹观感，默认 0.25）。 */
    const val WRAP_DEFAULT = 0.25f

    /** 四色渐变上限（规格 7.4：滑杆 0~0.4）。 */
    const val WRAP_MAX = 0.4f

    /**
     * 逆光判定（规格 7.1）。
     *
     * 三个条件缺一不可：光源方位与相机侧夹角 > 120°、主光强度 > 0.5、主光确实有方向性。
     * 第三条是必要的补充：纯环境光（keyIntensity 高但被估计成漫射）不该被当成逆光打亮边。
     */
    fun isBacklit(light: LightParams): Boolean =
        angleToCameraDeg(light.keyAzimuthDeg) > BACKLIT_ANGLE_DEG &&
            light.keyIntensity > MIN_KEY_INTENSITY

    /** 光源方位与"相机侧"（90°）的夹角，[0, 180]。 */
    fun angleToCameraDeg(azimuthDeg: Float): Float {
        val d = kotlin.math.abs(LightParams.normalizeDegrees(azimuthDeg) - CAMERA_SIDE_AZIMUTH_DEG) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * 生成本次渲染的光效计划。
     *
     * @param light 已经施加过手动覆盖的光照参数
     * @param overrides 用户滑杆（null = 自动；0 = 显式关闭该步骤）
     * @param allowExpensiveFx 是否允许执行"贵的"两步（辉光、四色渐变）。
     *   规格 7.6：**只有在导出且非低端机**时才允许 —— 预览阶段只做边缘打光。
     */
    fun plan(
        light: LightParams,
        overrides: LightOverrides,
        allowExpensiveFx: Boolean,
    ): LightFxPlan {
        val backlit = isBacklit(light)
        val autoRim = light.keyIntensity * if (backlit) RIM_BACKLIT_GAIN else RIM_SIDE_GAIN
        val rim = (overrides.effectiveRimStrength ?: autoRim).coerceIn(0f, RIM_MAX)

        val autoGlow = if (backlit) GLOW_BACKLIT else GLOW_NORMAL
        val glow = if (allowExpensiveFx) {
            (overrides.effectiveGlowStrength ?: autoGlow).coerceIn(0f, GLOW_MAX)
        } else {
            0f
        }

        val wrap = if (allowExpensiveFx) {
            (overrides.effectiveLightWrapStrength ?: WRAP_DEFAULT).coerceIn(0f, WRAP_MAX)
        } else {
            0f
        }

        return LightFxPlan(
            backlit = backlit,
            angleToCameraDeg = angleToCameraDeg(light.keyAzimuthDeg),
            rimStrength = rim,
            glowStrength = glow,
            lightWrapStrength = wrap,
        )
    }
}
