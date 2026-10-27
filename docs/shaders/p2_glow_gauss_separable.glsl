#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2  uTexel;      // 源纹理 texel
uniform vec2  uDir;        // (1,0) 水平 / (0,1) 垂直
uniform float uSigma;      // 以源 texel 为单位的 σ
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec2 d = uDir * uTexel * max(uSigma, 0.5);
    vec3 c = texture(uTex, uv).rgb * 0.2270270270;
    c += (texture(uTex, clamp(uv + d * 1.3846153846, 0.0, 1.0)).rgb
        + texture(uTex, clamp(uv - d * 1.3846153846, 0.0, 1.0)).rgb) * 0.3162162162;
    c += (texture(uTex, clamp(uv + d * 3.2307692308, 0.0, 1.0)).rgb
        + texture(uTex, clamp(uv - d * 3.2307692308, 0.0, 1.0)).rgb) * 0.0702702703;
    fragColor = vec4(max(c, 0.0), 1.0);
}