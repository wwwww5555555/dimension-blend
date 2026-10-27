// 自动抽取自 core-cv/.../gles/GlesLayerPipeline.kt（运行期以 Kotlin 源串为准）
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBackground;
uniform sampler2D uBgBlur;
uniform vec2  uLightPos;        // 光源中心（归一化，NMS 结果）
uniform vec2  uAspect;          // (w/h, 1) 保持光晕圆形
uniform float uFlareStrength;
uniform float uFlareRadius;     // 半径倍率
uniform float uGlowStrength;
uniform mat3  uColorMatrix;     // 全局 CC（3x3）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 bg = pow(texture(uBackground, uv).rgb, vec3(2.2));

    // ---- 镜头光晕：核心 + 大光晕 + 横向拉丝 + 沿轴鬼影 ----
    vec2 dv = (uv - uLightPos) * uAspect / max(uFlareRadius, 0.2);
    float d = length(dv);
    float core   = exp(-d * d * 26.0);                  // 高光核
    float halo   = exp(-d * d * 3.2) * 0.26;            // 大光晕
    float streak = exp(-abs(dv.y) * 55.0) * exp(-abs(dv.x) * 2.6) * 0.30;  // 变形镜头横向拉丝
    // 沿"光源→画面中心"轴线的鬼影（真实镜头内部反射）
    vec2 axis = vec2(0.5) - uLightPos;
    float ghost = 0.0;
    for (int i = 1; i <= 3; i++) {
        vec2 gp = uLightPos + axis * (float(i) * 0.55);
        float gd = length((uv - gp) * uAspect);
        ghost += exp(-gd * gd * 70.0) * (0.09 / float(i));
    }
    // 光晕本体（不含中心核）：外围的柔和衰减
    vec3 flareHalo = vec3(1.0, 0.93, 0.78) * (halo + streak + ghost) * uFlareStrength;
    // 中心核单独拿出来走 Add（下面解释原因）
    vec3 flareCore = vec3(1.0, 0.96, 0.86) * (core * 0.85 * uFlareStrength);

    // ---- 辉光：背景模糊光 ----
    vec3 blur = pow(texture(uBgBlur, uv).rgb, vec3(2.2));
    vec3 glow = blur * uGlowStrength;

    vec3 c = clamp(uColorMatrix * bg, 0.0, 4.0);                       // 全局调色
    c = 1.0 - (1.0 - c) * (1.0 - clamp(glow, 0.0, 1.0));               // Screen 辉光

    // ① 中心核：**Add**。真实镜头的核心高光远超人眼/传感器的 1.0，Screen 的上界被卡在 1，
    //    永远做不出"过曝的白心"；相加才能把中心推到 1 以上（后面统一 clamp）。
    c += flareCore;

    // ② 光晕/拉丝/鬼影：**Screen**（提亮但不吞掉底图细节）
    c = 1.0 - (1.0 - c) * (1.0 - clamp(flareHalo, 0.0, 1.0));

    // ③ 外圈衰减：**Multiply 压暗**。镜筒渐晕 + veiling glare 会降低外围对比，
    //    只加不减会让整块画面发白发飘 —— 这是"镜头感"与"贴了个光斑"的分水岭。
    float ring = smoothstep(0.28, 0.80, d) * (1.0 - smoothstep(0.80, 1.60, d));
    c *= 1.0 - 0.22 * ring * uFlareStrength;

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}