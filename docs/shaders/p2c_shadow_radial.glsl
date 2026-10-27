#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uPerson;
uniform vec2  uTexel;
uniform vec2  uShadowDir;       // CPU：背离光源，dirY > 0
uniform vec2  uAnchor;          // 脚底锚点（距离衰减/模糊都以它为准）
uniform float uShear;           // 横向错切
uniform float uReachPx;         // 投影长度（像素）
uniform float uFalloff;         // 距离衰减（0 = 均匀，1 = 末端消失）
uniform float uBlurBasePx;      // 根部一维模糊步长
uniform float uBlurGrowPx;      // 模糊随距离的增长量
uniform float uBacklit;
uniform float uStrength;
uniform float uSoftness;
uniform float uContactPx;
uniform float uContactStrength;
uniform vec3  uShadowColor;
uniform vec3  uContactColor;

float aAt(vec2 uv) { return texture(uPerson, clamp(uv, 0.0, 1.0)).a; }

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    float a = aAt(uv);

    // ---------- ① 全局方向（CPU 算好；这里只做最后一道保险）----------
    vec2 dir = uShadowDir;
    dir.y = max(dir.y, 0.0);                       // 方向本身也不允许向上
    dir = normalize(dir + vec2(0.0, 1e-4));        // 退化时默认朝下
    vec2 perp = vec2(-dir.y, dir.x);

    // ---------- ② 当前像素沿轴距锚点的距离 ----------
    vec2 deltaPx = (uv - uAnchor) / uTexel;        // 转成像素
    float along = max(0.0, dot(deltaPx, dir));
    float distK = clamp(along / max(uReachPx, 1.0), 0.0, 1.0);

    float reach = uReachPx * mix(uSoftness, 1.0, uBacklit);
    float blurPx = uBlurBasePx + distK * uBlurGrowPx * uShear;   // 越远越糊

    // ---------- ③ 沿轴一维模糊（12 tap）----------
    const int STEPS = 12;
    float acc = 0.0;
    for (int i = 0; i < STEPS; i++) {
        float k = (float(i) + 0.5) / float(STEPS) - 0.5;         // -0.46 .. +0.46
        float d = clamp(along + k * blurPx, 0.0, reach);         // 沿轴的位移（像素）

        vec2 offPx = dir * d;
        offPx.y = max(0.0, offPx.y);                             // ★ 安全垫：绝不向上

        float lat = k * blurPx * uShear * 2.0;                   // 横向展宽（错切）
        vec2 sp = clamp(uv - offPx * uTexel + perp * lat * uTexel, 0.0, 1.0);
        acc += aAt(sp);
    }
    float occ = acc / float(STEPS);

    // ---------- ④ 距离衰减：根部死黑、远端不彻底消失（v0.29 加深）----------
    // ⚠️ 旧版把 `uStrength`（默认 0.3）直接乘在 mask 上，根部最多 0.3 的暗度，
    //    在明亮背景上几乎看不见 —— 这就是"底部阴影完全消失"的主因之一。
    //    现在先算出**本体的暗度上限**（硬核 0.95 / 软层 0.55），再乘强度。
    // 软层：覆盖整段投影
    float softLayer = smoothstep(0.10, 0.60, occ) * 0.55;
    // 硬核：只允许出现在根部 30% 范围内，接近死黑
    float coreLayer = smoothstep(0.45, 0.90, occ) * (1.0 - smoothstep(0.0, 0.30, distK)) * 0.95;

    float depth = max(softLayer, coreLayer);
    // ★ 末端淡出必须用 smoothstep（规格 4）：线性衰减在 distK=1 处导数不为 0，
    //   会在影子末端留一条"突然断掉"的硬边。smoothstep 两端导数都是 0，自然消散。
    float tail = 1.0 - smoothstep(0.35, 1.0, clamp(distK, 0.0, 1.0));
    depth *= mix(0.05, 1.0, tail);
    float shadowMask = clamp(depth * clamp(uStrength, 0.0, 1.0), 0.0, 1.0);
    shadowMask *= 1.0 - smoothstep(0.55, 0.90, a);               // 人物自身不压暗

    // ---------- ⑤ 接触 AO：逆光也要"踩实"（v0.29 修复方向）----------
    // ⚠️ 旧版采样 `uv + uContactPx * uTexel.y`，在本工程（uv.y = 1 是底部）是**向下**看，
    //    而人物正下方的背景再往下还是背景 → 采样恒为 0 → 接触阴影**从未生效**。
    //    正确方向是**向上**（朝人物脚底）：uv.y 减小。
    float cAccNear = 0.0;      // 3~5px：死黑核心
    float cAccSoft = 0.0;      // 12px：过渡
    for (int i = -1; i <= 1; i++) {
        cAccNear += aAt(uv + vec2(float(i) * uTexel.x, -uContactPx * uTexel.y));
        cAccSoft += aAt(uv + vec2(float(i) * uTexel.x, -uContactPx * 3.0 * uTexel.y));
    }
    // 边界放宽（0.28~0.75）：原来 0.40~0.70 在 4px 内完成 → 接触带有一条硬边
    float contactNear = smoothstep(0.28, 0.75, cAccNear / 3.0);
    // 外圈给更大的权重（0.40 → 0.55）：只靠 4px 内圈会像一条硬线
    float contactSoft = smoothstep(0.18, 0.60, cAccSoft / 3.0) * 0.55;
    float contact = max(contactNear, contactSoft);
    // 主要落在画面下部（脚底/裙摆），但保留 25% 基础量防止"人物站在画面中部"时全无接触感
    contact *= mix(0.25, 1.0, smoothstep(0.45, 0.85, uv.y));
    contact *= (1.0 - smoothstep(0.80, 0.95, a)) * uContactStrength;

    vec3 f = mix(vec3(1.0), uShadowColor, shadowMask) * mix(vec3(1.0), uContactColor, contact);
    fragColor = vec4(clamp(f, 0.0, 1.0), 1.0);
}