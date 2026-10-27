package com.naiwa.core.common.model

/**
 * 阴影投位（规格 7.3）—— 纯数据 + 纯函数，可 JUnit 直接验证。
 *
 * 规格要求按"逆光 / 侧光 / 顺光"三种情形分别决定阴影的**方向、长度与浓度**，
 * 而不是像早期实现那样永远沿光方向 shear。
 *
 * @param azimuthDeg 光源来向（阴影沿其反向投射）
 * @param lengthPerHeight 阴影长度 / 物体高度 的比值（已含 tan(90-elevation) 与情形系数）
 * @param strengthScale 浓度倍率（逆光 0.5、顺光 0.6、侧光 1.0）
 * @param contactOnly 是否只保留接触阴影（顺光：阴影几乎不可见）
 */
data class ShadowPlacement(
    val azimuthDeg: Float,
    val lengthPerHeight: Float,
    val strengthScale: Float,
    val contactOnly: Boolean,
)

object ShadowPlacementRule {

    /** 光源方位与相机侧夹角 > 此值 → 逆光（与 [LightFxRules] 同源）。 */
    const val BACKLIT_ANGLE_DEG = LightFxRules.BACKLIT_ANGLE_DEG

    /** 顺光判定：夹角 < 30°（规格 7.3）。 */
    const val FRONT_ANGLE_DEG = 30f

    /** 逆光阴影长度系数（规格 7.3：×0.6）。 */
    const val BACKLIT_LENGTH_SCALE = 0.6f

    /** 逆光阴影浓度系数（规格 7.3：×0.5）。 */
    const val BACKLIT_STRENGTH_SCALE = 0.5f

    /** 顺光接触阴影的浓度系数：几乎不可见，但不能完全没有（否则像"浮在空中"）。 */
    const val FRONT_STRENGTH_SCALE = 0.6f

    /** 顺光接触阴影的最大长度：物体高度的 12%。 */
    const val FRONT_MAX_LENGTH = 0.12f

    /** 逆光时阴影统一投向"画面下方"（前景）——相机侧在图像下方，即 90°。 */
    const val FRONT_OF_VIEW_AZIMUTH_DEG = LightFxRules.CAMERA_SIDE_AZIMUTH_DEG

    /** tan 的下限保护：高度角接近 0 时 1/tan 会爆掉。 */
    private const val MAX_LENGTH_PER_HEIGHT = 6f

    /**
     * 解析阴影投位。
     *
     * | 情形 | 判定 | 方向 | 长度 | 浓度 |
     * | --- | --- | --- | --- | --- |
     * | 逆光 | 夹角 > 120° 且主光 > 0.5 | 画面下方 | h·tan(90°−elev)·0.6 | ×0.5 |
     * | 侧光 | 30° ~ 120° | 沿光方向 | h·tan(90°−elev) | ×1.0 |
     * | 顺光 | < 30° | 画面下方 | ≤ h·0.12（接触阴影） | ×0.6 |
     *
     * @param light 已施加手动覆盖的光照参数
     * @param backlit 逆光判定结果（由 [LightFxRules.isBacklit] 给出，避免两处各算一套）
     */
    fun resolve(light: LightParams, backlit: Boolean): ShadowPlacement {
        val angle = LightFxRules.angleToCameraDeg(light.keyAzimuthDeg)
        // tan(90° − elevation) 与 1/tan(elevation) 等价，用前者更直观（规格就是按它写的）
        val elev = light.keyElevationDeg.coerceIn(3f, 90f)
        val tanComplement = kotlin.math.tan(
            Math.toRadians((90f - elev).toDouble()),
        ).toFloat().coerceIn(0f, MAX_LENGTH_PER_HEIGHT)

        return when {
            backlit && angle > BACKLIT_ANGLE_DEG -> ShadowPlacement(
                azimuthDeg = FRONT_OF_VIEW_AZIMUTH_DEG,
                lengthPerHeight = (tanComplement * BACKLIT_LENGTH_SCALE).coerceAtMost(MAX_LENGTH_PER_HEIGHT),
                strengthScale = BACKLIT_STRENGTH_SCALE,
                contactOnly = false,
            )

            angle < FRONT_ANGLE_DEG -> ShadowPlacement(
                // 顺光：光源在相机一侧，阴影被物体自己挡住，只留脚下一小片
                azimuthDeg = FRONT_OF_VIEW_AZIMUTH_DEG,
                lengthPerHeight = minOf(tanComplement, FRONT_MAX_LENGTH),
                strengthScale = FRONT_STRENGTH_SCALE,
                contactOnly = true,
            )

            else -> ShadowPlacement(
                azimuthDeg = light.keyAzimuthDeg,
                lengthPerHeight = tanComplement,
                strengthScale = 1f,
                contactOnly = false,
            )
        }
    }
}
