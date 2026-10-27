// 自动抽取自 core-cv/.../gles/GlesLayerPipeline.kt（运行期以 Kotlin 源串为准）
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uToneMap;          // 1 = Reinhard（0 = 关闭，仅调试用）
uniform float uExposure;         // 线性曝光补偿（1 = 不补偿）
uniform float uToneKnee;         // 膝点：0 = 整幅 Reinhard；0.6 = 只压高光（默认）

vec3 fetch(vec2 uv) { return texture(uSrc, clamp(uv, 0.0, 1.0)).rgb; }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 cM = fetch(uv);
    vec3 cN = fetch(uv + vec2(0.0, uTexel.y));
    vec3 cS = fetch(uv - vec2(0.0, uTexel.y));
    vec3 cE = fetch(uv + vec2(uTexel.x, 0.0));
    vec3 cW = fetch(uv - vec2(uTexel.x, 0.0));

    float lM = luma(cM);
    float lMin = min(lM, min(min(luma(cN), luma(cS)), min(luma(cE), luma(cW))));
    float lMax = max(lM, max(max(luma(cN), luma(cS)), max(luma(cE), luma(cW))));
    float contrast = lMax - lMin;

    // 平坦区（占比绝大多数）零成本直出，只在有边缘的地方做混合
    vec3 outColor = cM;
    if (contrast > 0.06) {
        vec3 avg = (cN + cS + cE + cW) * 0.25;
        outColor = mix(cM, avg, clamp(contrast * 2.0, 0.0, 1.0) * 0.5);
    }
    // ===== HDR 保护：先曝光补偿，再 Reinhard（线性空间）=====
    vec3 c = max(outColor, 0.0) * uExposure;

    // Reinhard 肩部压缩：膝点以下**严格恒等**，只把膝点以上按 c/(1+c) 压回 [0,1)。
    //
    //   整幅 Reinhard（knee = 0）会把中间调一起压灰：线性 0.69 → 0.41（sRGB 0.85 → 0.67），
    //   画面会整体变暗发闷。肩部版把膝点以下的色彩分布完整保留，只对真正过曝的部分做
    //   Reinhard —— 公式完全一致，只是作用在"超出膝点的那部分"上。
    //   `uToneKnee = 0` 时 shoulder = c/(1+c)，与规格给的公式逐字相同。
    float knee = clamp(uToneKnee, 0.0, 0.95);
    vec3 over = max(c - knee, 0.0);
    vec3 shoulder = (1.0 - knee) * (over / (over + vec3(1.0)));
    vec3 toned = c - over + shoulder;          // over = 0 时严格等于 c（恒等）
    c = mix(c, toned, clamp(uToneMap, 0.0, 1.0));

    // 到这里 c 已经在 [0,1) 内，clamp 只防浮点误差，不再承担"压高光"的职责
    fragColor = vec4(pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}