package com.naiwa.core.ai.light

import com.naiwa.core.common.model.LightParams

/**
 * 光照估计器契约。
 *
 * 两个实现：模型回归（[TfliteLightEstimator]）与传统 CV（[CvLightEstimator]）。
 * 二者返回同一个 [LightParams]，由调用方依据 [LightParams.confidence] 决定采信哪一个。
 *
 * 输入刻意设计成"原始像素 + 宽高"而不是 Bitmap：
 *  - 估计算法能与 Android 框架解耦，可纯 JVM 单测（合成图 → 断言估计值）；
 *  - 调用方负责降采样与生命周期，估计器只管算。
 */
interface LightEstimator {

    /** 实现名，写进日志便于判断本次是模型还是兜底生效。 */
    val name: String

    /**
     * 估计光照参数。
     *
     * 契约：**不抛异常**。任何失败都返回 [LightParams.NEUTRAL]（confidence = 0）。
     *
     * @param pixels ARGB_8888 像素，行优先，长度 >= width * height
     * @param width 图像宽
     * @param height 图像高
     */
    fun estimate(pixels: IntArray, width: Int, height: Int): LightParams
}
