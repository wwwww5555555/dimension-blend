// 自动抽取自 core-cv/.../gles/GlesLayerPipeline.kt（运行期以 Kotlin 源串为准，本文件仅供评审）
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2  uAspect;
uniform float uRadius;          // 开始压暗的归一化距离
uniform float uSoftness;        // 过渡宽度
uniform float uStrength;        // 0 = 关闭
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 c = texture(uSrc, uv).rgb;
    float dist = length((uv - 0.5) * uAspect) / 0.7071;              // 归一化：对角 = 1
    float v = 1.0 - smoothstep(uRadius - uSoftness, uRadius, dist);   // 中心 1 → 边缘 0
    fragColor = vec4(c * mix(1.0, v, uStrength), 1.0);
}