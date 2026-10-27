#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uPerson;
uniform vec2  uTexel;          // 源（全分辨率）texel
uniform vec3  uLightDir;
uniform vec3  uKeyLight;       // 已整形的发光色（线性）
uniform vec3  uAmbientLight;   // 环境色（线性），用于背光侧的冷色过渡
uniform float uBandPx;         // 环带半径（全分辨率像素）
uniform float uGain;
uniform sampler2D uShadowMask;   // P2-c：1 = 无遮挡，越暗遮挡越强
uniform float uShadowCut;        // 熔断强度（1 = 完全熔断）
uniform vec2  uLightPos;         // 光源屏幕坐标（归一化）——动态光向的来源
uniform float uKeyBoost;         // 主光亮度补偿（0.35）
uniform int   uDebug;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float aAt(vec2 uv) { return texture(uPerson, clamp(uv, 0.0, 1.0)).a; }

/**
 * 环境光权重：0（迎光）→ 0.30（过渡中段）→ 1.0（纯背光）。
 * 与 `core-common/model/LightMix.ambientWeight` 同一套分段，改一边必须改另一边。
 */
float ambientWeight(float l) {
    float mid  = 0.30 * (1.0 - smoothstep(0.35, 1.0, l));
    float deep = 1.0 - smoothstep(0.0, 0.35, l);
    return clamp(max(mid, deep), 0.0, 1.0);
}

/**
 * 像素空间：从当前像素**指向光源**的单位向量。
 *
 * `(uLightPos - uv) / uTexel` = 乘上 (w, h)，于是 x/y 分量单位一致，
 * 不会因为宽高比把方向拉斜。光源与像素几乎重合时 normalize 会产生 NaN，
 * 退化为"正上方"（顶光假设）。
 */
vec2 lightDirPx(vec2 uv) {
    vec2 d = (uLightPos - uv) / uTexel;
    float l = length(d);
    return l > 1.0 ? d / l : vec2(0.0, -1.0);
}

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    float a = aAt(uv);

    // ---------- ① 形态学梯度环带 ----------
    // 8 方向 × 半径 uBandPx：取邻域 alpha 的 min / max。
    // 内部（全 1）差值为 0，远处背景（全 0）差值为 0，只有轮廓上差值 ≈ 1。
    float aMin = 1.0;
    float aMax = 0.0;
    vec2 r = vec2(uBandPx) * uTexel;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.7853982;
        vec2 o = vec2(cos(ang), sin(ang)) * r;
        float s = aAt(uv + o);
        aMin = min(aMin, s);
        aMax = max(aMax, s);
    }
    float band = smoothstep(0.08, 0.55, aMax - aMin);
    // 确实有人物才发光（防极淡的蒙尘噪点在空白区被当成轮廓）
    band *= smoothstep(0.02, 0.25, aMax);
    if (band < 0.01) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    // ---------- ② 外法向 → 迎光判定（v0.28：动态光向）----------
    // ★ 必须在**像素空间**做：uv 空间里 1 个 texel 在 x/y 上对应的长度不同，
    //   直接用 uv 差分方向会让 45° 边缘的法向被宽高比拉伸，指向偏掉。
    // ★ 宽基线法向（v0.30）：差分基线取**环带半径**，不是 1 texel。
    //   1 texel 基线让法向在 2~3px 内翻转 180° → 明暗交界只有 2~3px，看起来像刀切。
    vec2 nB = vec2(uBandPx) * uTexel;
    vec2 gPx = vec2(
        aAt(uv + vec2(nB.x, 0.0)) - aAt(uv - vec2(nB.x, 0.0)),
        aAt(uv + vec2(0.0, nB.y)) - aAt(uv - vec2(0.0, nB.y))
    );
    float gLen = length(gPx);
    vec2 nPx = gLen > 1e-6 ? -gPx / gLen : vec2(0.0);   // 梯度指内 → 取负为外法向

    // ★ 光向**逐像素**由光源坐标算出来，不再用全局常数方向。
    //   原因：全局方向来自 CV 的"方位角直方图"，光源坐标来自"NMS 最亮连通区"，
    //   两套独立统计一旦不一致（多光源、光源偏心），光就指错边。
    vec2 lDir = lightDirPx(uv);
    vec3 din = normalize(uLightDir + vec3(1e-5));
    float wXY = sqrt(max(1.0 - din.z * din.z, 0.0));    // cos(elevation)：越高水平方向性越弱
    float dirDot = dot(nPx, lDir) * wXY;

    // 顶光（光源水平居中）：光向几乎垂直，左右两侧的点积同时趋 0 →
    // 会变成"左右对称的大白边"。这时把权重压到人物的上边缘（头顶/肩线）。
    // 本工程 uv.y = 1 是**画面底部**，所以"法向朝上"是 nPx.y < 0。
    float topBias = 1.0 - smoothstep(0.06, 0.20, abs(uLightPos.x - 0.5));
    float upperEdge = smoothstep(0.0, 0.6, -nPx.y);
    float facing = mix(dirDot, upperEdge, topBias * 0.8);

    // 规格 1：交界带用 smoothstep(-0.2, 0.3, N·L)，不是从 0 开始硬切
    float lit = smoothstep(-0.20, 0.30, facing);

    // ---------- ②b 阴影熔断（v0.27，规格 2）----------
    // 迎光门控只看 N·L，而**伪法线在投影与人物交界处不可信**：alpha 梯度几乎为 0，
    // N·L 落在 0 附近被当成"略微迎光" → 阴影边缘漏出一圈光。
    // 这里再叠一道与法线无关的判据：P2-c 的投影遮罩。两条里任意一条成立就熄灭。
    float shadowAmt = clamp(1.0 - dot(texture(uShadowMask, uv).rgb, LUMA), 0.0, 1.0);
    lit *= 1.0 - shadowAmt * clamp(uShadowCut, 0.0, 1.0);

    // ---------- ③ 颜色：主光 / 环境 **严格分层**（v0.31）----------
    // ⚠️ 旧写法 `mix(uAmbientLight*0.45, uKeyLight, smoothstep(-0.1,0.55,facing))`：
    //    在 facing≈0（轮廓侧面，占环带很大比例）时 smoothstep=0.13 → **87% 是环境色**。
    //    蓝调背景 + 暖色主光，整条边缘就泛蓝 —— 这就是"蓝色荧光"的主因。
    //    现在迎光面环境权重严格为 0，过渡区最多 0.30，背光面才到 1.0。
    float aW = ambientWeight(lit);
    vec3 col = mix(uKeyLight, uAmbientLight, aW);
    // 主光亮度补偿（规格 3）：冷背景里暖光要靠增益压住蓝调，只加在迎光侧
    col *= 1.0 + uKeyBoost * lit;

    // ---------- ④ 内 / 外权重 ----------
    // a > 0.5 → 内侧，权重 1；a → 0 → 外侧，权重 0.62（光会溢出去，但比内侧弱）
    float outer = 1.0 - smoothstep(0.05, 0.5, a);
    float sideW = mix(1.0, 0.62, outer);

    vec3 emit = col * (band * lit * uGain * sideW);

    if (uDebug > 0) {
        // 通道 7：直接看发光源图（环带 + 迎光门控是否正确）
        fragColor = vec4(clamp(emit * 2.0, 0.0, 1.0), 1.0);
        return;
    }
    fragColor = vec4(clamp(emit, 0.0, 4.0), 1.0);
}