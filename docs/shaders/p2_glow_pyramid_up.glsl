#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uLow;        // 更粗一级
uniform sampler2D uHigh;       // 更细一级（本级）
uniform vec2  uLowTexel;
uniform float uWeight;
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec2 t = uLowTexel;
    vec3 s = texture(uLow, uv).rgb * 4.0;
    s += texture(uLow, clamp(uv + vec2( t.x, 0.0), 0.0, 1.0)).rgb * 2.0;
    s += texture(uLow, clamp(uv + vec2(-t.x, 0.0), 0.0, 1.0)).rgb * 2.0;
    s += texture(uLow, clamp(uv + vec2(0.0,  t.y), 0.0, 1.0)).rgb * 2.0;
    s += texture(uLow, clamp(uv + vec2(0.0, -t.y), 0.0, 1.0)).rgb * 2.0;
    s += texture(uLow, clamp(uv + vec2( t.x,  t.y), 0.0, 1.0)).rgb;
    s += texture(uLow, clamp(uv + vec2(-t.x,  t.y), 0.0, 1.0)).rgb;
    s += texture(uLow, clamp(uv + vec2( t.x, -t.y), 0.0, 1.0)).rgb;
    s += texture(uLow, clamp(uv + vec2(-t.x, -t.y), 0.0, 1.0)).rgb;
    s /= 16.0;
    vec3 high = texture(uHigh, uv).rgb;
    fragColor = vec4(max(high + s * uWeight, 0.0), 1.0);
}