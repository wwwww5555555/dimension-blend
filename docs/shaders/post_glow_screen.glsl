#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBase;      // composite 输出（线性）
uniform sampler2D uBloom;     // 金字塔顶（1/2 域加权和）
uniform sampler2D uMask;      // 人物 alpha（texP2）
uniform float uStrength;
uniform float uAirRatio;
uniform float uClamp;         // 单次可注入的最大光量（防止极端参数烧白）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 base = texture(uBase, uv).rgb;
    vec3 gl = texture(uBloom, uv).rgb;
    float a = texture(uMask, uv).a;
    float w = mix(uAirRatio, 1.0, smoothstep(0.05, 0.55, a));
    vec3 add = clamp(gl * uStrength * w, 0.0, uClamp);
    vec3 c = 1.0 - (1.0 - clamp(base, 0.0, 1.0)) * (1.0 - add);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}