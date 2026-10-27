package com.naiwa.core.ai.light

/**
 * 纯 CV 测光结果 —— 物理骨架（需求中的 `LightParams(posX, posY, angle, isBacklit)`）。
 *
 * 【为什么叫 CvLightParams 而不是 LightParams】
 * `core-common` 已有 `LightParams`（色温/曝光/对比度/方位角/高度角/强度…12 个字段，
 * 面向光照模型与调色）。两者语义不同、字段不重叠，同名会在引用处编译失败。
 * 这里刻意区分：**CvLightParams = CV 算出的几何骨架**，`LightParams` = 调色参数集。
 *
 * @param posX 主光源中心 X（归一化 0~1，画面坐标，NMS 找最亮连通区域后取加权质心）
 * @param posY 主光源中心 Y（归一化 0~1）
 * @param angle 主光**来向**方位角（度，[0,360)）。
 *   坐标系：图像 x 向右、y 向下 → 0° = 光从右侧来，90° = 从下方来，
 *   180° = 从左侧来，270° = 从上方来。
 *   转单位向量：`(cos θ, sin θ)`；转 3D：见 [CvLightEstimator.toLightVector]。
 * @param isBacklit 是否强逆光（背景高光显著亮于人物内部平均亮度）
 */
data class CvLightParams(
    val posX: Float,
    val posY: Float,
    val angle: Float,
    val isBacklit: Boolean,
) {
    /** 水平方向单位向量 X（供 Shader 的 `uLightDir.x`）。 */
    val dirX: Float get() = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat()

    /** 水平方向单位向量 Y。 */
    val dirY: Float get() = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()

    companion object {
        /** 无法测光时的中性值：中心 + 顶光 + 非逆光。 */
        val NEUTRAL = CvLightParams(posX = 0.5f, posY = 0.5f, angle = 270f, isBacklit = false)
    }
}
