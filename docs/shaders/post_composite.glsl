#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uP1;
uniform sampler2D uP2;
uniform sampler2D uP2B;
uniform sampler2D uP2C;
uniform sampler2D uWrapIn;      // 光包裹：向内渗透的光（FRAG_LIGHTWRAP）
uniform sampler2D uEmitTex;     // 发光源图（仅 debug 通道 7 使用）
uniform sampler2D uBloomTex;    // 金字塔结果（仅 debug 通道 8 使用）
uniform vec2  uTexel;
uniform vec3  uLightDir;
uniform float uBleedPxMin;      // 迎光侧渗透宽度（10px）
uniform float uBleedPxMax;      // 背光侧渗透宽度（20px）
uniform float uBleedStrength;   // 0 = 关闭
uniform vec3  uAmbientColor;    // 背景代表色（线性）
uniform float uAmbientTint;     // 0.05
uniform vec2  uLightPos;        // 光源屏幕坐标（归一化）
uniform int   uDebug;           // >0：调试通道原样上屏

float aAtP2(vec2 uv) { return texture(uP2, clamp(uv, 0.0, 1.0)).a; }

/** 像素空间：当前像素 → 光源的单位向量（与其它光效 pass 同一套）。 */
vec2 lightDirPx(vec2 uv) {
    vec2 d = (uLightPos - uv) / uTexel;
    float l = length(d);
    return l > 1.0 ? d / l : vec2(0.0, -1.0);
}

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);

    // 调试通道必须绕过 P1/P2 混合，否则输出的不是中间量而是"混合后的画"。
    // 通道 1~6 由 LIGHTWRAP 输出；7/8 是发光源与金字塔，只能从各自的纹理取。
    if (uDebug > 0) {
        if (uDebug == 7) {
            fragColor = vec4(clamp(texture(uEmitTex, uv).rgb, 0.0, 1.0), 1.0);
        } else if (uDebug == 8) {
            fragColor = vec4(clamp(texture(uBloomTex, uv).rgb, 0.0, 1.0), 1.0);
        } else {
            fragColor = vec4(clamp(texture(uWrapIn, uv).rgb, 0.0, 1.0), 1.0);
        }
        return;
    }
    vec3 p1 = texture(uP1, uv).rgb;
    vec3 p2c = texture(uP2C, uv).rgb;
    vec4 p2 = texture(uP2, uv);
    vec4 p2b = texture(uP2B, uv);
    vec3 p2w = texture(uWrapIn, uv).rgb;

    // ---- ① 方向性羽化 ----
    float aL = aAtP2(uv - vec2(uTexel.x, 0.0));
    float aR = aAtP2(uv + vec2(uTexel.x, 0.0));
    float aU = aAtP2(uv - vec2(0.0, uTexel.y));
    float aD = aAtP2(uv + vec2(0.0, uTexel.y));
    // 像素空间外法向（零梯度必须显式归零，`+vec2(1e-5)` 会伪造 45° 法线）
    vec2 gPx = vec2(aR - aL, aD - aU) / (2.0 * uTexel);
    float gLen = length(gPx);
    vec2 nPx = gLen > 1e-6 ? -gPx / gLen : vec2(0.0);

    // v0.28：羽化宽度也必须跟着光源走，否则"迎光侧硬边"会留在旧方位
    float facing = dot(nPx, lightDirPx(uv));
    float lit = clamp(facing * 0.5 + 0.5, 0.0, 1.0);          // 1 迎光 / 0 背光
    // 背光侧过去给到 0.45：那是 0.05~0.95 的 alpha 全范围混合，
    // 等于让边缘 2~3px 大面积透出背景 —— 观感就是"融化"。收窄到 0.18/0.07。
    float width = mix(0.18, 0.07, lit);
    float blend = smoothstep(0.5 - width, 0.5 + width, p2.a);

    vec3 c = p1 * p2c;                                         // Multiply 阴影
    c = mix(c, p2.rgb, blend);                                 // 方向性 Alpha

    // ---- ② 大气透视：背景色向人物内部渗透（迎光窄、背光宽）----
    if (uBleedStrength > 0.001) {
        float bleedPx = mix(uBleedPxMax, uBleedPxMin, lit);
        float minA = 1.0;
        for (int i = 0; i < 8; i++) {
            float ang = float(i) * 0.7853982;
            vec2 off = vec2(cos(ang), sin(ang)) * bleedPx * uTexel;
            minA = min(minA, aAtP2(uv + off));
        }
        float innerProx = 1.0 - minA;                          // 贴轮廓=1，内部=0
        float inside = smoothstep(0.55, 0.95, p2.a);
        float bleedW = innerProx * innerProx * inside;         // 平方：只渗最外一圈
        vec3 bleed = p1 * uBleedStrength * bleedW;
        c = 1.0 - (1.0 - c) * (1.0 - clamp(bleed, 0.0, 1.0));  // Screen 渗入
    }

    // ---- ③ 光包裹 / 立体反光 ----
    c = 1.0 - (1.0 - c) * (1.0 - clamp(p2w, 0.0, 1.0));
    c = 1.0 - (1.0 - c) * (1.0 - clamp(p2b.rgb, 0.0, 1.0));

    // ---- ④ 环境色罩：只在人物区域，极低透明度 ----
    if (uAmbientTint > 0.001) {
        c = mix(c, uAmbientColor, uAmbientTint * smoothstep(0.2, 0.9, p2.a));
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}