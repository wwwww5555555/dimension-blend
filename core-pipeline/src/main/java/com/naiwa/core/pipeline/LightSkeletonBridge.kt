package com.naiwa.core.pipeline

import com.naiwa.core.ai.light.CvLightEstimator
import com.naiwa.core.ai.light.CvLightParams

/**
 * CV 几何骨架 → GPU uniform 的桥接层（**纯函数，无 GL / 无 Android 依赖**）。
 *
 * 存在理由：`:core-ai` 的 [CvLightParams] 与 `:core-cv` 的 `GlesFusionRenderer.Params`
 * 分属两个禁止互相依赖的模块（模块纪律），只有本模块（编排层）能同时看到两者。
 * 把"角度 → 3D 向量 → 阴影方向"的换算集中在这里，UI / 管线 / 单测共用同一套，
 * 杜绝"面板一个方向、渲染另一个方向"。
 *
 * 坐标约定（与 [CvLightEstimator] 一致）：图像 x 向右、y 向下；
 * 0° = 光从右侧来，90° = 从下方来，180° = 从左侧来，270° = 从上方来。
 * **无深度信息**，因此 z 分量由高度角按物理映射给出（仰角越高水平分量越短），不臆造像素级深度。
 */
object LightSkeletonBridge {

    /** 默认仰角：日景/室内主光的常见值；过高会让所有方向都变成顶光、点积失去区分度。 */
    const val BASE_ELEVATION_DEG = 35f

    private val estimator = CvLightEstimator()

    /** GPU 侧需要的几何量。 */
    data class Geometry(
        /** 光源中心（归一化，NMS 结果）——供镜头光晕定位与射线模糊中心。 */
        val posX: Float,
        val posY: Float,
        /** 指向光源的单位向量（z 含高度角），供法向点积。 */
        val dirX: Float,
        val dirY: Float,
        val dirZ: Float,
        /** 是否强逆光（决定阴影形态与锐利度）。 */
        val backlit: Boolean,
    ) {
        /** 水平投影是否有效（正顶光时方向性消失，阴影不该乱飘）。 */
        val hasHorizontalDirection: Boolean
            get() = kotlin.math.abs(dirX) + kotlin.math.abs(dirY) > 1e-3f
    }

    /**
     * 骨架 → 几何量。
     *
     * @param p CV 测光结果
     * @param elevationDeg 主光仰角（0~89，越大越接近顶光）
     */
    fun toGeometry(p: CvLightParams, elevationDeg: Float = BASE_ELEVATION_DEG): Geometry {
        val v = estimator.toLightVector(p.angle, elevationDeg)
        return Geometry(
            posX = p.posX.coerceIn(0f, 1f),
            posY = p.posY.coerceIn(0f, 1f),
            dirX = v[0],
            dirY = v[1],
            dirZ = v[2],
            backlit = p.isBacklit,
        )
    }

    /**
     * 投影（阴影）方向：**背离光源**的水平单位向量。
     *
     * ⚠️ 必须是 `-lightDir`：沿光方向偏移等于把影子画到光源那一侧，是反的（已在管线里踩过一次）。
     *
     * @return 归一化的 (x, y)；方向退化（正顶光）时返回 (0, 0)，由 shader 侧的 `uBacklit`
     *   走"从光源向外发散"的射线路径，而不是乱飘。
     */
    fun shadowDirection(g: Geometry): FloatArray {
        val sx = -g.dirX
        val sy = -g.dirY
        val len = kotlin.math.sqrt((sx * sx + sy * sy).toDouble()).toFloat()
        return if (len > 1e-4f) floatArrayOf(sx / len, sy / len) else floatArrayOf(0f, 0f)
    }
}
