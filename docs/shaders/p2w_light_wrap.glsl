#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uPerson;        // 人物 RGBA（已调色、已腐蚀）
uniform sampler2D uBgBlur;        // P1 背景模糊光
uniform sampler2D uShadowMask;    // P2-c：Multiply 因子（1 = 无遮挡）
uniform vec2  uTexel;
uniform vec3  uLightDir;
uniform vec3  uKeyLight;          // 已整形的发光色（线性）
uniform vec3  uAmbientLight;      // 环境色（线性）
uniform float uWrapPx;            // 向内渗透深度（5~10px，1080p 基准）
uniform float uWrapStrength;
uniform float uColorMix;          // 用双色替换背景光色的比例
uniform float uHeightStart;
uniform float uHeightEnd;
uniform float uShadowOccl;
uniform float uShadowCut;         // 熔断强度（1 = 背光/阴影处主光完全归零）
uniform float uAmbientFloor;      // 熔断后保留的环境散射（0.15）
uniform float uAmbientLift;
uniform float uMaxLuma;
uniform vec2  uLightPos;          // 光源屏幕坐标（归一化）——动态光向的来源
uniform int   uDebug;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float aAt(vec2 uv) { return texture(uPerson, clamp(uv, 0.0, 1.0)).a; }

/** 像素空间：当前像素 → 光源的单位向量（宽高比修正 + 退化保护），与 EMIT 同一套。 */
vec2 lightDirPx(vec2 uv) {
    vec2 d = (uLightPos - uv) / uTexel;
    float l = length(d);
    return l > 1.0 ? d / l : vec2(0.0, -1.0);
}

/** 环境光权重：0 迎光 → 0.30 过渡 → 1.0 背光（与 LightMix.ambientWeight 一致）。 */
float ambientWeight(float l) {
    float mid  = 0.30 * (1.0 - smoothstep(0.35, 1.0, l));
    float deep = 1.0 - smoothstep(0.0, 0.35, l);
    return clamp(max(mid, deep), 0.0, 1.0);
}

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    float a = aAt(uv);

    // ---------- ⓿ 早退：背景像素完全不需要跑这 24 次探针 ----------
    // 全分辨率 pass 里这是最值钱的一个分支：背景通常占画面 50%~80%。
    if (a < 0.02) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // ---------- ① 外法向 → N·L（v0.28：像素空间 + 动态光向）----------
    // 宽基线（v0.30）：用渗透半径当差分基线，过渡带与 P2 同宽
    vec2 nB = vec2(uWrapPx) * uTexel;
    vec2 gPx = vec2(
        aAt(uv + vec2(nB.x, 0.0)) - aAt(uv - vec2(nB.x, 0.0)),
        aAt(uv + vec2(0.0, nB.y)) - aAt(uv - vec2(0.0, nB.y))
    );
    float gLen = length(gPx);
    vec2 nPx = gLen > 1e-6 ? -gPx / gLen : vec2(0.0);
    vec2 lDir = lightDirPx(uv);
    vec3 din = normalize(uLightDir + vec3(1e-5));
    float wXY = sqrt(max(1.0 - din.z * din.z, 0.0));
    float dirDot = dot(nPx, lDir) * wXY;
    // 顶光：光源水平居中时左右两侧点积同时趋 0，包裹光会对称糊成一片 →
    // 把权重压到人物的上边缘（uv.y 向下，故 nPx.y<0 为上缘）。
    float topBias = 1.0 - smoothstep(0.06, 0.20, abs(uLightPos.x - 0.5));
    float upperEdge = smoothstep(0.0, 0.6, -nPx.y);
    float facing = mix(dirDot, upperEdge, topBias * 0.8);
    // 规格 1：交界带平滑过渡（背光侧也保留一点蔓延）
    facing = smoothstep(-0.20, 0.30, facing);

    // ---------- ② 向内距离场（8 方向 × 3 半径）----------
    float prox = 1.0;
    vec2 rp = vec2(uWrapPx) * uTexel;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.7853982;
        vec2 dir = vec2(cos(ang), sin(ang));
        for (int k = 1; k <= 3; k++) {
            float rr = float(k) / 3.0;
            prox = min(prox, aAt(uv + dir * rp * rr));
        }
    }
    float depth = clamp(1.0 - prox, 0.0, 1.0);

    // ---------- ③ 只在人物内部（严格）：a 低 → 权重低 → 越过轮廓即 0 ----------
    float inside = smoothstep(0.45, 0.80, a);
    // pow 1.8：把权重压到最外 5~10px，深处几乎不出光（否则整张脸发亮）
    float wrapMask = pow(depth, 1.8) * inside;

    // ---------- ④ 颜色：背景模糊光的亮度 × 冷暖双色 ----------
    vec3 bg = pow(clamp(texture(uBgBlur, uv).rgb, 0.0, 1.0), vec3(2.2));
    float bgL = max(dot(bg, LUMA), 1e-3);
    bg *= min(2.0, uMaxLuma / bgL);                 // 暗背景也能提亮，亮背景不过曝

    // 环境色提亮：暗部均值可能接近纯黑，直接叠会糊成灰泥
    vec3 amb = uAmbientLight;
    float al = max(dot(amb, LUMA), 1e-3);
    amb *= max(al, uAmbientLift) / al;
    amb = mix(vec3(dot(amb, LUMA)), amb, 1.15);     // 轻微提饱和，保留"有光感"

    // 主光 / 环境严格分层（v0.31）：迎光面环境权重为 0，过渡最多 0.30
    float aW = ambientWeight(facing);
    vec3 duo = mix(uKeyLight, amb, aW);
    // ⚠️ 迎光时**不再混背景色**（colorMix → 1）：原来固定 0.75 意味着
    //    25% 的蓝色背景被插进暖色包裹里，怎么调都发蓝。
    //    背光面本来就该由背景光主导，那里才按 uColorMix 混。
    float colorMixW = mix(uColorMix, 1.0, smoothstep(0.35, 1.0, facing));
    vec3 tinted = mix(bg, duo * (0.55 + 0.45 * clamp(dot(bg, LUMA), 0.0, 1.0)), colorMixW);

    // ---------- ⑤ 高度 / 遮挡衰减 ----------
    // 本工程 uv.y = 1 是画面**底部**，脚底不该被环境光包裹（会像浮着）
    float heightW = 1.0 - smoothstep(uHeightStart, uHeightEnd, uv.y);
    vec3 sh = texture(uShadowMask, uv).rgb;
    float shadowAmt = clamp(1.0 - dot(sh, LUMA), 0.0, 1.0);

    // ---------- ⑤b 阴影边缘漏光熔断（v0.27，规格 2）----------
    // 漏光的根因是**伪法线在阴影处不可信**：阴影边缘 alpha 梯度几乎为 0，
    // N·L 落在 0 附近，被"迎光门"当成略微迎光 → 漏出一圈光。
    // 解法是双判据取最大：背光权重（看 N·L）与 投影遮罩（跟法线无关）任意一条成立就熔断。
    //   · 规格伪代码 `smoothstep(0.2,-0.2,N·L)` 是 UB，这里用 1-smoothstep(-0.2,0.2,x)
    //   · 熔断后**主光完全归零**，但保留 uAmbientFloor 的环境散射 —— 背光侧真实存在的
    //     只有环境光；给 0 会让暗部变成死黑，与"人物融进背景"没有区别。
    float backW = 1.0 - smoothstep(-0.2, 0.2, facing);
    float occ = max(backW, shadowAmt * clamp(uShadowOccl, 0.0, 1.0));
    float keep = 1.0 - occ * clamp(uShadowCut, 0.0, 1.0);
    float occl = mix(uAmbientFloor, 1.0, keep);

    vec3 spill = tinted * wrapMask * heightW * occl * uWrapStrength * 2.0;

    // ---------- ⑥ Debug 通道 ----------
    // 1 法线 2 迎光权重 3 双色 4 高度 5 向内渗透带 6 遮挡
    if (uDebug > 0) {
        vec3 dbg = vec3(0.0);
        if (uDebug == 1) {
            dbg = vec3(nPx, 0.0) * 0.5 + vec3(0.5);    // 平涂区应为中性灰
        } else if (uDebug == 2) {
            dbg = vec3(keyW, 1.0 - keyW, 0.0);
        } else if (uDebug == 3) {
            dbg = mix(uAmbientLight, uKeyLight, step(0.5, uv.x));
        } else if (uDebug == 4) {
            dbg = vec3(heightW);
        } else if (uDebug == 5) {
            dbg = vec3(wrapMask);                        // ★ 只在人物内侧 5~10px 非零
        } else {
            dbg = vec3(occl);
        }
        fragColor = vec4(clamp(dbg, 0.0, 1.0), 1.0);
        return;
    }

    fragColor = vec4(clamp(spill, 0.0, 1.0), 1.0);
}