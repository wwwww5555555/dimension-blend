#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2  uTexel;      // 源（全分辨率）texel
uniform float uScale;      // 模糊半径（像素）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    float s = 0.0;
    float w = 0.0;
    #define T(dx, dy, ww) { s += texture(uTex, clamp(uv + uTexel * vec2(dx,dy) * uScale, 0.0, 1.0)).a * ww; w += ww; }
    T(0.0, 0.0, 0.20)
    T(1.0, 0.0, 0.12) T(-1.0, 0.0, 0.12) T(0.0, 1.0, 0.12) T(0.0, -1.0, 0.12)
    T(2.0, 2.0, 0.06) T(-2.0, 2.0, 0.06) T(2.0, -2.0, 0.06) T(-2.0, -2.0, 0.06)
    #undef T
    fragColor = vec4(vec3(s / max(w, 1e-4)), 1.0);
}