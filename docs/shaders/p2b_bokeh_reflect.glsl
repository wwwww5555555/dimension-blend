#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uPerson;
uniform sampler2D uShadowMask;   // P2-c 投影遮罩（必须在本 pass 之前算好）
uniform vec2  uTexel;
uniform vec3  uLightDir;
uniform float uBokehRadius;      // 像素
uniform float uReflectStrength;
uniform float uShadowCut;
uniform vec2  uLightPos;         // 光源屏幕坐标（归一化）——动态光向的来源

/** 像素空间：当前像素 → 光源的单位向量（与 EMIT / LIGHTWRAP 同一套）。 */
vec2 lightDirPx(vec2 uv) {
    vec2 d = (uLightPos - uv) / uTexel;
    float l = length(d);
    return l > 1.0 ? d / l : vec2(0.0, -1.0);
}

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    float a = texture(uPerson, uv).a;

    // 受光面判定（与 P2 同一套法向）
    // 反光层用 1 texel 采样：它只需要"哪一侧受光"，不需要边缘光的宽度
    float aL = texture(uPerson, clamp(uv - vec2(uTexel.x, 0.0), 0.0, 1.0)).a;
    float aR = texture(uPerson, clamp(uv + vec2(uTexel.x, 0.0), 0.0, 1.0)).a;
    float aU = texture(uPerson, clamp(uv - vec2(0.0, uTexel.y), 0.0, 1.0)).a;
    float aD = texture(uPerson, clamp(uv + vec2(0.0, uTexel.y), 0.0, 1.0)).a;
    // ⚠️ 三条铁律（v0.27 修掉、v0.28 保留）：
    //  ① alpha 梯度指向**内部**，外法向必须取负（`+grad` 会让反光跑到背光侧）；
    //  ② 零梯度时不能 `+ vec2(1e-5)` 伪造 45° 法线，必须显式归零；
    //  ③ 法向与光向都要在**像素空间**，否则宽高比会把 45° 边缘的方向拉偏。
    vec2 gPx = vec2(aR - aL, aD - aU) / (2.0 * uTexel);
    float gLen = length(gPx);
    vec2 nPx = gLen > 1e-6 ? -gPx / gLen : vec2(0.0);
    // 光向逐像素算（光源在哪就朝哪），不再用全局常数方向
    vec2 lDir = lightDirPx(uv);
    vec3 din = normalize(uLightDir + vec3(1e-5));
    float wXY = sqrt(max(1.0 - din.z * din.z, 0.0));
    float dirDot = dot(nPx, lDir) * wXY;
    float topBias = 1.0 - smoothstep(0.06, 0.20, abs(uLightPos.x - 0.5));
    float facing = max(0.0, mix(dirDot, smoothstep(0.0, 0.6, -nPx.y), topBias * 0.8));
    float edge = a * (1.0 - a) * 4.0;

    // 阴影熔断：投影覆盖处不产生"立体反光"（否则阴影边缘继续漏光）
    float shadowAmt = clamp(1.0 - dot(texture(uShadowMask, uv).rgb, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
    facing *= 1.0 - shadowAmt * clamp(uShadowCut, 0.0, 1.0);

    // Bokeh：16 点黄金角圆盘，半径分三档（模拟光斑而非均匀模糊）
    vec3 acc = vec3(0.0); float wsum = 0.0;
    for (int i = 0; i < 16; i++) {
        float ang = float(i) * 2.39996;                       // 黄金角
        float rad = uBokehRadius * (0.35 + 0.65 * float(i % 4) / 3.0);
        vec2 off = vec2(cos(ang), sin(ang)) * rad * uTexel;
        vec4 s = texture(uPerson, clamp(uv + off, 0.0, 1.0));
        float w = s.a;
        acc += pow(max(s.rgb, 0.0), vec3(2.2)) * w;
        wsum += w;
    }
    vec3 col = wsum > 0.001 ? acc / wsum : vec3(1.0);
    float alpha = clamp(edge * facing * uReflectStrength, 0.0, 1.0);
    fragColor = vec4(col * alpha, alpha);                    // 预乘
}