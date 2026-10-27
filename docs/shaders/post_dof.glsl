// 自动抽取自 core-cv/.../gles/GlesLayerPipeline.kt（运行期以 Kotlin 源串为准）
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform sampler2D uMask;        // 人物 Mask（P2 alpha）
uniform vec2  uTexel;
uniform float uMaxRadiusPx;     // 最大弥散半径（3~5px）
uniform float uRingPx;          // 交界环形带宽度（约 20px）

float aAt(vec2 uv) { return texture(uMask, clamp(uv, 0.0, 1.0)).a; }

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 sharp = texture(uSrc, uv).rgb;
    float a = aAt(uv);

    // ---- ① 环形带：外扩 uRingPx 内有人物，且自己不是实心主体 ----
    float near = 0.0;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.7853982;
        vec2 off = vec2(cos(ang), sin(ang)) * uRingPx * uTexel;
        near = max(near, aAt(uv + off));
    }
    float ring = smoothstep(0.02, 0.50, near) * (1.0 - smoothstep(0.55, 0.92, a));

    // 远处背景与人物内部：不做任何模糊（省 12 次采样，且细节全保留）
    if (ring < 0.01) {
        fragColor = vec4(sharp, 1.0);
        return;
    }

    // ---- ② 轻微模糊：半径随 ring 渐变，上限 ≤5px ----
    float r = uMaxRadiusPx * ring;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float ang = float(i) * 2.39996;
        float rad = r * sqrt((float(i) + 1.0) / 12.0);
        vec2 sp = clamp(uv + vec2(cos(ang), sin(ang)) * rad * uTexel, 0.0, 1.0);
        acc += texture(uSrc, sp).rgb;
    }
    vec3 blurred = acc / 12.0;

    fragColor = vec4(mix(sharp, blurred, ring), 1.0);
}