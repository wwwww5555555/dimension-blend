package com.naiwa.core.cv.shadow

/**
 * 阴影投射轴：由"人物脚下锚点 → 光源"连线推出**背离光源**的延伸方向，并给出错切/模糊参数。
 *
 * 【为什么必须在 CPU 算】
 * 投影方向是**全场一致**的：平行光在承接面上的延伸只有一个方向。
 * 若在 shader 里逐像素用 `normalize(uv - lightPos)`，人物上半身与脚底的延伸方向会不同，
 * 影子会呈放射状（这正是之前 Radial Blur 看起来"炸开"的原因）。
 * 用脚底锚点算一次，全场共用一个轴才是物理正确的。
 *
 * 坐标约定：图像坐标系，x 向右、y **向下**（与 `CvLightParams` 一致）。
 */
object ShadowShear {

    /** 阴影方向的最小向下分量：逆光时影子必须朝画面前方，不允许出现水平/向上。 */
    const val MIN_DOWN = 0.25f

    /** 侧向错切系数（光偏左/右时影子斜着拉长）。 */
    const val SHEAR_GAIN = 0.6f

    /** 错切下限：正顶光也有一点展宽，避免像"平移的剪影"。 */
    const val SHEAR_BASE = 0.25f

    /**
     * 打包好的 uniform 值（字段与 GLSL uniform 一一对应）。
     *
     * 存在理由：阴影有 7 个 uniform，散在调用处手写 `uni2f/uni1f` 极易错位
     * （把 shear 传给 falloff 这种错误编译器不会报，只会"看起来怪"）。
     * 集中成一个结构体后，单测可以直接断言打包结果。
     */
    data class ShadowUniforms(
        /** 投影方向（单位向量，dirY 恒 > 0）。 */
        val dirX: Float,
        val dirY: Float,
        /** 锚点（归一化，脚底）。距离衰减以它为原点。 */
        val anchorX: Float,
        val anchorY: Float,
        /** 横向错切/展宽量。 */
        val shear: Float,
        /** 投影长度（像素）。 */
        val reachPx: Float,
        /** 距离衰减：0 = 均匀，1 = 末端消失。 */
        val falloff: Float,
        /** 根部的一维模糊基准步长（像素）。 */
        val blurBasePx: Float,
        /** 模糊随距离增长的幅度（像素）。 */
        val blurGrowPx: Float,
    )

    /**
     * 投影轴。
     *
     * @param dirX 阴影延伸方向 X（单位向量）
     * @param dirY 阴影延伸方向 Y（>0 = 向画面下方）
     * @param shear 水平错切量
     * @param lengthY 从锚点到光源的垂直距离（归一化），用于估算投影长度
     */
    data class Axis(
        val dirX: Float,
        val dirY: Float,
        val shear: Float,
        val lengthY: Float,
    ) {
        /**
         * 打包成 uniform 值。
         *
         * @param anchorX/[@param] anchorY 脚底锚点（必须与算轴时用的同一个点，否则距离衰减会错位）
         */
        fun pack(
            anchorX: Float,
            anchorY: Float,
            reachPx: Float,
            falloff: Float,
            blurBasePx: Float,
            blurGrowPx: Float,
        ): ShadowUniforms = ShadowUniforms(
            dirX = dirX,
            dirY = dirY,
            anchorX = anchorX,
            anchorY = anchorY,
            shear = shear,
            reachPx = reachPx.coerceIn(0f, 4096f),
            falloff = falloff.coerceIn(0f, 1f),
            blurBasePx = blurBasePx.coerceIn(0.5f, 40f),
            blurGrowPx = blurGrowPx.coerceIn(0f, 200f),
        )
    }

    /**
     * 计算阴影投射轴。
     *
     * ```
     * 方向 = 归一化(锚点 - 光源)        // 背离光源
     * 若 dirY < MIN_DOWN → 抬到 0.25     // 绝不允许影子向上
     * shear = 0.25 + |dirX| * 0.6        // 光越偏侧，影子越斜越宽
     * ```
     *
     * @param anchorX 人物脚底 X（归一化，通常 0.5）
     * @param anchorY 人物脚底 Y（归一化，通常 0.85~0.95）
     * @param lightX 光源 X（归一化 0~1）
     * @param lightY 光源 Y（越小越靠上）
     */
    fun calculateShadowAxis(
        anchorX: Float,
        anchorY: Float,
        lightX: Float,
        lightY: Float,
    ): Axis {
        var dx = anchorX - lightX
        var dy = anchorY - lightY

        // 光在锚点下方（或更低）时 dy <= 0：物理上不会有向前的投影，
        // 退化成"向画面下方"的默认轴，避免影子朝上（观感极其错误）。
        if (dy < MIN_DOWN) dy = MIN_DOWN

        val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1e-4f) return Axis(0f, 1f, SHEAR_BASE, 1f)

        val ux = dx / len
        val uy = dy / len
        return Axis(
            dirX = ux,
            dirY = uy,
            shear = SHEAR_BASE + kotlin.math.abs(ux) * SHEAR_GAIN,
            lengthY = dy.coerceIn(0f, 1f),
        )
    }

    /**
     * 阴影层的错切矩阵（行优先 2×3，作用于**归一化 UV**，以 (0.5, 1.0) 为错切基点）：
     * ```
     * | 1  shear  -shear * 1.0 |
     * | 0    1          0      |
     * ```
     * 用于 CPU 侧（或将来 DAG 的 transform 节点）把阴影层拉成平行四边形。
     * GPU 侧在 shader 里用逐采样横向偏移实现等价效果，不需要额外一次全图变换。
     */
    fun shearMatrix(axis: Axis): FloatArray = floatArrayOf(
        1f, axis.shear, -axis.shear,   // x' = x + shear * y - shear * 1.0
        0f, 1f, 0f,                    // y' = y
    )
}
