// 自动抽取自 core-cv/.../gles/GlesLayerPipeline.kt（运行期以 Kotlin 源串为准，本文件仅供评审）
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2  uLeakPos;         // 漏光中心（归一化，可随滑杆微调）
uniform vec2  uAspect;
uniform vec3  uLeakColor;       // 线性空间
uniform float uStrength;
uniform float uPhase;           // UV 微调相位（滑杆/时间）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 c = texture(uSrc, uv).rgb;

    vec2 d = (uv - uLeakPos) * uAspect;
    float r = length(d);
    float leak = exp(-r * r * 4.0) * 0.85 + exp(-r * r * 1.2) * 0.25;

    // 低频扰动：真实漏光从来不是标准圆形
    float wobble = 0.85 + 0.15 * sin(uv.x * 9.0 + uPhase) * sin(uv.y * 7.0 - uPhase * 0.7);
    leak = max(leak * wobble, 0.0);

    vec3 add = uLeakColor * leak * uStrength;
    fragColor = vec4(1.0 - (1.0 - c) * (1.0 - clamp(add, 0.0, 1.0)), 1.0);   // Screen
}