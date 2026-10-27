package com.naiwa.core.cv.color

/**
 * 3D LUT 采样的 GPU（GLES）实现方案占位 —— 规格 8.1 的优化项。
 *
 * 【当前状态】CPU 三线性采样（[CubeLut3d.sample]）已实现且**数值正确性已用单测钉住**，
 * 是产品当前使用的路径。GLES 是**后续优化项**（当 CPU 采样成为瓶颈时再启用）。
 *
 * 【GLES 方案】把 3D LUT 作为 3D 纹理（GL_TEXTURE_3D）上传，采样用 `texture3D()`。
 * 关键设计点：
 *  - **纹理格式**：LUT 顶点是 0~1 浮点，用 `GL_RGBA16F` 或 `GL_RGB10_A2`（若精度足够）；
 *    `GL_LINEAR` 过滤 + `GL_CLAMP_TO_EDGE` 环绕，GPU 硬件自动做三线性插值，零 CPU 开销。
 *  - **Shader**：片元着色器里 `vec3 mapped = texture(lut3d, rgb).rgb;` 一行完成映射。
 *  - **作用域**：与 CPU 版一致，只作用于前景 —— 用 alpha 作为混合因子（`mix(original, mapped, foregroundMask)`）。
 *  - **正确性验证**：GLES 输出必须与 CPU 三线性采样**逐位一致**（或误差 < 1/255），
 *    否则说明纹理格式/精度选错。这条用同输入对拍即可验证。
 *
 * 【为什么不现在就上 GLES】CPU 三线性采样 17³ LUT 在 1080p 前景上约几十毫秒，**不是当前瓶颈**；
 * 而 GLES 涉及 EGL 上下文、纹理格式兼容性、真机 GPU 驱动差异——没有真机验证就上，风险大于收益。
 * 等 CPU 采样实测成为瓶颈（前景 > 4MP 或 LUT 边长 > 33）再启用。
 *
 * 【接口预留】本类只声明接口，不实现（避免"写了却没人调用的死代码"，见项目的死代码教训）。
 * 将来实现时：把 [CubeLut3d.table] 上传为 3D 纹理，用上面 shader 采样，输出与 CPU 对拍。
 */
object GlesLutSampler {
    /** 标记 GLES 路径是否可用（当前恒 false，CPU 路径兜底）。 */
    const val AVAILABLE: Boolean = false
}
