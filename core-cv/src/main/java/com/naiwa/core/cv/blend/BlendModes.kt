package com.naiwa.core.cv.blend

/**
 * Photoshop 混合模式的**规范参考实现**（规格 8.1）—— CPU 版是"逐位对拍基准"，
 * GLSL 版（见 docs/GPU-SHADERS.md）必须与这里的结果一致（误差 < 1/255）才允许启用。
 *
 * 三个模式在管线中的职责：
 *  - [screen] 滤色：辉光（Glow）、边缘高光、光包裹（LightWrap）的溢出光 —— 只会变亮，
 *    且"黑加不上、白加满"，符合光的加性直觉；
 *  - [multiply] 正片叠底：阴影 —— 只会变暗，且"白乘不变、黑乘压死"，是物理遮挡的正确模型；
 *  - [softLight] 柔光：四色渐变环境光着色（LightWrapGradient 已内置同式实现）——
 *    让奶蛙色调与背景"化学反应"而不是生硬覆盖。
 *
 * 全部输入输出归一化到 [0,1]，与 GLSL 的 float 语义一一对应，方便逐行对照。
 */
object BlendModes {

    /**
     * 滤色：`result = 1 - (1 - a) * (1 - b)`。
     * GLSL: `vec3 r = 1.0 - (1.0 - a) * (1.0 - b);`
     */
    fun screen(a: Float, b: Float): Float = 1f - (1f - a) * (1f - b)

    /**
     * 正片叠底：`result = a * b`。
     * GLSL: `vec3 r = a * b;`
     *
     * 带图层不透明度的常用形式（阴影合成用的就是它）：
     * `result = a * (1 - opacity + opacity * b)` —— b=1（白）时不变，b=0（黑）时按 opacity 压暗。
     */
    fun multiply(a: Float, b: Float): Float = a * b

    /** 正片叠底 + 图层不透明度（Photoshop 的图层 Opacity 语义）。 */
    fun multiplyWithOpacity(a: Float, b: Float, opacity: Float): Float =
        a * (1f - opacity + opacity * b)

    /**
     * 柔光（pegtop 近似，与 LightWrapGradient 内置实现同式）：
     * `result = (1 - 2b)·a² + 2b·a`（b ≤ 0.5 压暗区）/ 线性近似（b > 0.5 提亮区）。
     * GLSL: `vec3 r = (1.0 - 2.0*b)*a*a + 2.0*b*a;`
     */
    fun softLight(a: Float, b: Float): Float =
        ((1f - 2f * b) * a * a + 2f * b * a).coerceIn(0f, 1f)
}
