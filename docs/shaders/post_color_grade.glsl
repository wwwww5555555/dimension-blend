// 自动抽取自 core-cv/.../gles/GlesLayerPipeline.kt（运行期以 Kotlin 源串为准）
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform mat3  uMatrix;          // 3D LUT 退化为 3×3（端侧 CPU 侧已烘进矩阵）
uniform float uContrast;
uniform float uSaturation;
uniform float uGlobalMultiply;  // 1 = 关闭；0.85 = 暗部压暗
void main() {
    vec3 c = texture(uSrc, clamp(vTexCoord, 0.0, 1.0)).rgb;
    c = uMatrix * c;
    c = (c - 0.18) * uContrast + 0.18;                        // 线性空间支点
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, uSaturation);

    // 全局 Multiply：暗部压暗，高光保持（保留细节，不做死黑）
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float darkMask = 1.0 - smoothstep(0.02, 0.30, lum);
    c *= mix(1.0, uGlobalMultiply, darkMask);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}