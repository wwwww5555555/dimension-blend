package com.naiwa.core.common.model

/**
 * 奶蛙在场景中的摆放（拖动 / 缩放 / 旋转）。
 *
 * 全部使用**归一化坐标**，与场景分辨率解耦：
 *  - 预览用低清位图、导出用全分辨率位图，同一个 Placement 直接可用，无需换算；
 *  - 屏幕手势产生的位移按「控件尺寸」归一化后写入，不依赖像素密度。
 *
 * @param centerX       奶蛙中心点的场景归一化横坐标，[0, 1]（0 = 左边缘，1 = 右边缘）
 * @param centerY       奶蛙中心点的场景归一化纵坐标，[0, 1]
 * @param scale         奶蛙宽度相对**场景短边**的比例，[0.02, 5.0]。0.5 表示奶蛙宽度约为场景短边一半
 * @param rotationDeg   旋转角度，[0, 360)，顺时针为正
 * @param flipHorizontal 是否水平镜像（让同一个素材能朝向不同方向）
 */
data class Placement(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val scale: Float = DEFAULT_SCALE,
    val rotationDeg: Float = 0f,
    val flipHorizontal: Boolean = false,
) {

    /** 钳制到合法范围并归一化角度。手势/滑杆输入后必须调用，避免越界值流入位图变换。 */
    fun clamped(): Placement = copy(
        centerX = centerX.coerceIn(MIN_CENTER, MAX_CENTER),
        centerY = centerY.coerceIn(MIN_CENTER, MAX_CENTER),
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        rotationDeg = LightParams.normalizeDegrees(rotationDeg),
    )

    /** 以场景短边为参考，计算奶蛙目标宽度的像素值。 */
    fun targetWidthPx(sceneWidth: Int, sceneHeight: Int): Int =
        (minOf(sceneWidth, sceneHeight) * scale).toInt().coerceAtLeast(1)

    companion object {
        const val MIN_CENTER = 0f
        const val MAX_CENTER = 1f
        /** 奶蛙最小占场景短边的 2%（再小就看不清），最大 5 倍（允许只露出局部做前景虚化）。 */
        const val MIN_SCALE = 0.02f
        const val MAX_SCALE = 5.0f

        /** 初始摆放：居中、宽度约为场景短边的 40%。 */
        const val DEFAULT_SCALE = 0.4f

        val DEFAULT = Placement()
    }
}
