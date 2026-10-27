#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uPerson;
uniform sampler2D uPersonLow;    // 1/4 域低频频（Unsharp 用，RGB）
uniform sampler2D uPersonLoA;     // 1/4 域低频 **alpha**（过渡带法向的宽基线）
uniform float uPenumbraPx;        // 过渡带半宽（像素，1080p 基准）
uniform vec3  uAmbientColor;      // 背景代表色（过渡带的"桥梁色"）
uniform vec2  uTexel;
uniform mat3  uColorMatrix;
uniform float uMatrixStrength;   // 0.5：矩阵只贡献色相，亮度由保亮度约束还原
uniform vec3  uTint;
uniform vec3  uLightDir;
uniform vec3  uRimColor;         // 背景高光中位色（仅用于 ⑤ 环境色呼应）
uniform float uToneContrast;     // S 曲线强度（0 = 关闭）
uniform float uSCurveLow;
uniform float uSCurveHigh;
uniform float uUnsharp;          // 局部对比度（0 = 关闭）
uniform vec3  uQuadTop;
uniform vec3  uQuadBottom;
uniform vec3  uQuadLeft;
uniform vec3  uQuadRight;
uniform vec2  uAspect;
uniform float uQuadStrength;     // 四色 SoftLight 强度（非加性，安全）
uniform float uFakeLight;        // 伪立体打光强度（点缀级，0.15）
uniform vec3  uShadowColor;      // 背景暗部色（线性）
uniform vec2  uLightPos;         // 光源中心（径向渐变原点）
uniform float uGradMin;          // 渐变内径（越近越亮）
uniform float uGradMax;          // 渐变外径（越远越暗）
uniform float uGradStrength;     // 渐变混合比例（0 = 关闭）
uniform float uFeetStart;
uniform float uFeetStrength;
uniform float uSpecThreshold;
uniform float uSpecStrength;     // 高光保护强度（0.12）
uniform float uEnvSplit;         // 环境暗部 Multiply 强度（0.45）
uniform float uShrinkPx;         // Shrink Mask：向内腐蚀像素（1~2px，切掉最外圈污染像素）
uniform float uDefringe;         // Defringe 强度：边缘 RGB 向内部颜色收缩（0 = 关闭）
uniform float uBounceLight;      // 底部反弹光强度（0.10~0.15）
uniform float uBacklitMode;      // 1 = 逆光（强制剪影压暗），0 = 关闭
uniform float uBacklitDarken;    // 背光面基准暗度（0.20，规格 `color*0.2`）
uniform float uBacklitGlobal;    // 逆光全局压暗（0.82）
uniform float uBacklitChroma;    // 环境吸收混合比（0.70）：0=只压暗 1=完全吸背景暗部色相
uniform vec3  uDeepShadowColor;  // 环境吸收色：色相归一 × 0.42（**乘法因子**，不是 sRGB 颜色）

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

/** Pegtop SoftLight：s=0.5 恒等，s<0.5 压暗但不归零，s>0.5 提亮且不烧白。 */
float softLightCh(float b, float s) {
    return (1.0 - 2.0 * s) * b * b + 2.0 * s * b;
}
vec3 softLight(vec3 b, vec3 s) {
    return vec3(softLightCh(b.r, s.r), softLightCh(b.g, s.g), softLightCh(b.b, s.b));
}

/** 原图（sRGB）亮度：高光阈值 0.85 只在这个空间有直觉。 */
float lumaAt(vec2 uv) {
    return dot(texture(uPerson, clamp(uv, 0.0, 1.0)).rgb, vec3(0.299, 0.587, 0.114));
}

float aAt(vec2 uv) { return texture(uPerson, clamp(uv, 0.0, 1.0)).a; }

/** 像素空间：当前像素 → 光源的单位向量（与 EMIT / LIGHTWRAP / P2B 同一套）。 */
vec2 lightDirPx(vec2 uv) {
    vec2 d = (uLightPos - uv) / uTexel;
    float l = length(d);
    return l > 1.0 ? d / l : vec2(0.0, -1.0);
}

void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec4 p = texture(uPerson, uv);

    // ===== ①a Shrink Mask：向内腐蚀 1~2px =====
    // 抠图最外圈 1~2px 的 RGB 一定是被抠掉的背景色（白/绿），alpha 也在 0~0.5 之间。
    // 直接把这圈 alpha 腐蚀掉，等于**不画这一圈污染像素** —— 这是去白边最直接的手段。
    // 用 4 邻域取 min（形态学腐蚀），比逐向采样更省且不会产生方向性偏移。
    // ★ aOut 是**输出 alpha**，必须严格等于原 Mask 的 alpha（绿幕抠图的结果）。
    //   腐蚀出来的 `a` 只用于光效/门控，绝不能写进 fragColor.a ——
    //   否则最外圈 1.5px 会真的变透明，人物看起来"融进背景"。
    float aOut = p.a;
    float a = p.a;
    if (uShrinkPx > 0.05) {
        vec2 er = vec2(uShrinkPx) * uTexel;
        float aE = min(
            min(aAt(uv + vec2(er.x, 0.0)), aAt(uv - vec2(er.x, 0.0))),
            min(aAt(uv + vec2(0.0, er.y)), aAt(uv - vec2(0.0, er.y)))
        );
        a = min(a, aE);
    }

    // ===== ① sRGB → 线性（不改像素内容）=====
    vec3 c = pow(max(p.rgb, 0.0), vec3(2.2)) * uTint;

    // ===== ①b Defringe：边缘 RGB 向内部颜色收缩 =====
    // 半透明边缘的 RGB 往往仍是被抠掉的背景色（白/绿），只靠 alpha 腐蚀会把轮廓削细，
    // 这部分改为"换色"：取 4 邻域中 alpha 最大者的颜色作为内部色，只在 a<0.95 处混入。
    // 内部实心区（a≈1）权重为 0，完全不受影响。
    if (uDefringe > 0.001) {
        vec2 dp = vec2(uShrinkPx + 1.0) * uTexel;
        vec4 s0 = texture(uPerson, clamp(uv + vec2(dp.x, 0.0), 0.0, 1.0));
        vec4 s1 = texture(uPerson, clamp(uv - vec2(dp.x, 0.0), 0.0, 1.0));
        vec4 s2 = texture(uPerson, clamp(uv + vec2(0.0, dp.y), 0.0, 1.0));
        vec4 s3 = texture(uPerson, clamp(uv - vec2(0.0, dp.y), 0.0, 1.0));
        vec3 inner = p.rgb;
        float bestA = p.a;
        if (s0.a > bestA) { bestA = s0.a; inner = s0.rgb; }
        if (s1.a > bestA) { bestA = s1.a; inner = s1.rgb; }
        if (s2.a > bestA) { bestA = s2.a; inner = s2.rgb; }
        if (s3.a > bestA) { bestA = s3.a; inner = s3.rgb; }
        float w = uDefringe * (1.0 - smoothstep(0.55, 0.95, a));
        c = mix(c, pow(max(inner, 0.0), vec3(2.2)) * uTint, w);
    }

    // ===== ② S 曲线：只拉明暗，色相不变 =====
    float l = dot(c, LUMA);
    float target = mix(l, smoothstep(uSCurveLow, uSCurveHigh, l), uToneContrast);
    // 下限 0.08 只防除零：0.20 会给黑场设 20% 地板，暗部永远压不下去（发灰的主因）
    c *= clamp(target / max(l, 1e-4), 0.08, 3.0);

    // ===== ②b 局部对比度（Unsharp）：只作用于人物内部 =====
    vec3 lowFreq = pow(max(texture(uPersonLow, uv).rgb, 0.0), vec3(2.2));
    c += (c - lowFreq) * uUnsharp * smoothstep(0.1, 0.5, a);
    c = max(c, 0.0);

    // ===== ②c 基础径向渐变（替代伪法线）=====
    // `1 - smoothstep(min, max, dist)` 与规格的 `smoothstep(0.8, 0.2, dist)` 数学等价，
    // 但符合 GLSL 规范（edge0 > edge1 是未定义行为，部分驱动会返回 0）。
    // 只乘不改加：0.60（背光）~ 1.10（迎光），不产生任何超过 1.1 倍的增益。
    float dist = length((uv - uLightPos) * vec2(uAspect.x, 1.0));
    float gradient = 1.0 - smoothstep(uGradMin, uGradMax, dist);
    float gradK = mix(0.60, 1.10, gradient);
    c *= mix(1.0, gradK, uGradStrength);

    // ===== ②d 脚底垂直渐变：接触地面的一小段自然压暗 =====
    float feetK = smoothstep(uFeetStart, 1.0, uv.y);
    c *= 1.0 - uFeetStrength * feetK;

    // 背景暗部的**色相**（按亮度归一；直接乘线性暗色 ≈0.01 等于纯黑）
    float shadowLuma = dot(uShadowColor, LUMA);
    vec3 shadowChroma = clamp(uShadowColor / max(shadowLuma, 1e-4), 0.4, 2.2);

    // ===== ③ 伪立体光照：弱 Screen + 弱 Multiply（点缀，不是主光源）=====
    // v0.28：方向必须在**像素空间**逐像素算。
    //  · 原来的 `uv - 0.5` 在宽高比 ≠ 1 时是椭圆径向 → 方位角偏移；
    //  · 原来的 `uLightDir.xy` 是 CV 方位角直方图给的**全局常数**，与光源坐标
    //    （NMS 质心）是两套独立统计，一旦不一致，"光在左边、包裹却在右边"。
    vec2 relPx = (uv - 0.5) / uTexel;                    // 画面中心 → 像素（像素单位）
    float rl = length(relPx);
    vec2 nRad = rl > 1e-4 ? relPx / rl : vec2(0.0);
    vec2 lDir = lightDirPx(uv);                          // 像素 → 光源
    float lw = dot(nRad, lDir);                          // +1 该像素在光源侧 / -1 背侧
    float litK = smoothstep(-0.6, 0.8, lw);

    // ===== ②e 背光权重（v0.29：提前算，供后续所有 Add/Screen 项门控）=====
    // 逆光下"人物正面 = 背光面"，只有迎光侧窄轮廓不压。判据与 ⑥ 一致（轮廓带 + N·L）。
    // 提前到这里的唯一原因：③④⑤⑦ 里的 Screen 项必须在暗部**强制乘 0**，
    // 否则压暗刚做完就被提亮回去 —— 这是"压不黑"的第二个根因。
    // ★ 宽基线法向（v0.30）：从 1/4 域低频 alpha 上取 ±uPenumbraPx 的差分。
    //   1 texel 差分让法向在 2~3px 内翻转 180°，过渡带就只有 2~3px —— 这才是"刀切"的根因。
    //   低频 alpha 已经把轮廓抹成 8~16px 的斜坡，在它上面求方向，过渡带自然就宽。
    vec2 pbUv = vec2(uPenumbraPx) * uTexel;
    float loL = texture(uPersonLoA, clamp(uv - vec2(pbUv.x, 0.0), 0.0, 1.0)).r;
    float loR = texture(uPersonLoA, clamp(uv + vec2(pbUv.x, 0.0), 0.0, 1.0)).r;
    float loU = texture(uPersonLoA, clamp(uv - vec2(0.0, pbUv.y), 0.0, 1.0)).r;
    float loD = texture(uPersonLoA, clamp(uv + vec2(0.0, pbUv.y), 0.0, 1.0)).r;
    vec2 gWide = vec2(loR - loL, loD - loU);
    float eAmp = smoothstep(0.02, 0.30, length(gWide));   // 轮廓带（宽 ~8px）→ 1，平涂内部 → 0
    vec2 eN = length(gWide) > 1e-4 ? -normalize(gWide) : vec2(0.0);

    // ★ 明暗交界带（规格 1）：smoothstep(-0.2, 0.3, N·L)。
    //   edge0 < edge1 是硬要求（反序在 GLSL 里是 UB）。
    //   乘 eAmp：平涂内部没有法向，N·L 恒 0 会给一个 0.35 的"微受光"，内部就压不黑。
    float litRatio = smoothstep(-0.20, 0.30, dot(eN, lDir)) * eAmp;
    // 阴影权重与迎光比例**严格互补**（规格 2）：交界处光"融化"进影、影顺着光延伸，不会两边都空。
    float shadowW = 1.0 - litRatio;
    // 光效门控：暗部归零；非逆光时保持 1（不受影响）
    float litGate = uBacklitMode > 0.5 ? litRatio : 1.0;

    if (uFakeLight > 0.001) {
        float luma = dot(c, LUMA);
        float hiMask = smoothstep(0.45, 0.95, luma);
        float spec = pow(max(lw, 0.0), 2.0) * hiMask;
        // 0.6 系数 + 默认强度 0.15 ⇒ 峰值增量 ≈0.09，只做点缀
        vec3 add = vec3(1.0, 0.97, 0.90) * spec * uFakeLight * 0.6 * litGate;
        c = 1.0 - (1.0 - c) * (1.0 - clamp(add, 0.0, 1.0));               // Screen

        float darkMask = 1.0 - smoothstep(0.08, 0.50, luma);
        float back = max(-lw, 0.0) * darkMask;
        c *= mix(vec3(1.0), shadowChroma, back * 0.30 * uFakeLight);
        c *= 1.0 - back * 0.20 * uFakeLight;
    }

    // ===== ④ 四色环境光：SoftLight（非加性，s=0.5 恒等）=====
    if (uQuadStrength > 0.001) {
        vec3 quadH = mix(uQuadLeft, uQuadRight, uv.x);
        vec3 quadV = mix(uQuadTop, uQuadBottom, uv.y);
        vec3 quad = mix(quadH, quadV, 0.5);

        float r = length(relPx) / (0.7071 * length(vec2(0.5) / uTexel));
        float edgeW = smoothstep(0.20, 0.95, r);       // 中心保留原色，边缘多受环境光
        float k = uQuadStrength * mix(0.55, 1.15, litK) * mix(0.30, 1.0, edgeW);
        c = mix(c, softLight(c, quad), k * smoothstep(0.15, 0.60, a));
    }

    // ===== ⑤ 环境色融合：暗部 Multiply 环境暗色 / 亮部 Screen 0.15 =====
    if (uEnvSplit > 0.001) {
        // gradient < 0.3 视为暗部：只染背景暗部的**色相**，不改明暗层次
        float darkW = 1.0 - smoothstep(0.0, 0.30, gradient);
        c *= mix(vec3(1.0), shadowChroma, darkW * uEnvSplit);

        // gradient > 0.7 视为亮部：极少量的背景亮色（0.15）做环境呼应
        float brightW = smoothstep(0.70, 1.0, gradient);
        vec3 warm = uRimColor * brightW * 0.15 * litGate;
        c = 1.0 - (1.0 - c) * (1.0 - clamp(warm, 0.0, 1.0));              // Screen
    }

    // ===== ⑤b 底部反弹光：背景底部主色 Screen 到人物下半身 =====
    // 雪地/亮马路的反射光会把人物下半身"托起来"。颜色取四区色板的**下区中位色**，
    // 强度 0.10~0.15；用 alpha 门控，避免整块矩形蒙版。
    if (uBounceLight > 0.001) {
        float lower = smoothstep(0.55, 1.0, uv.y);
        // 反弹光在暗部**确实存在**（地面反射打到下摆），所以不清零，只压到 25%
        vec3 bounce = uQuadBottom * (uBounceLight * lower) * smoothstep(0.30, 0.80, a)
                    * mix(0.25, 1.0, litGate);
        c = 1.0 - (1.0 - c) * (1.0 - clamp(bounce, 0.0, 1.0));            // Screen
    }

    // ===== ⑥ 边缘光：**已迁出本 pass**（v0.26）=====
    // 旧实现在这里做 `c += uRimColor * pow(facing,1.5) * edgeBand(a) * strength`：
    //   · `edgeBand(a)` 是固定的 alpha 过渡区 → 等宽；
    //   · `uRimColor` 取背景高光中位色 → 常常接近纯白；
    //   · 加完直接 clamp，没有任何扩散 → 光只能"贴边"。
    // 三点叠加的必然结果就是一圈生硬的白描边，与真实光源无关。
    //
    // 现在这条链路拆成三个 pass：FRAG_EMIT（提取发光源，含迎光门控）
    // → 高斯金字塔（多尺度扩散）→ FRAG_GLOW（合成之后 Screen）。
    // 这里不再保留任何"沿 alpha 环带直接叠加"的代码 —— 那正是被废弃的写法。

    // ===== ⑥ 逆光剪影压暗（v0.29：三级高度 + 环境色吸收）=====
    // 只在 isBacklit 时执行（uBacklitMode = 1）：非逆光场景整段不跑，零成本。
    //
    // 【三件事，缺一不可】
    //  ① 背光面判据（②e 已算好 backW）：内部=背光，只有迎光侧窄轮廓不压；
    //  ② 环境色乘叠：暗部不是"变暗"，而是"沉进背景暗部色"（深蓝/暗紫）。
    //     `mix(color*0.2, color*uDeepShadowColor, 0.7)` —— 用户规格公式。
    //     ⚠️ uDeepShadowColor 必须是**色相归一后的乘法因子**（≈0.42），
    //        不能直接拿背景暗部像素值（线性 ≈0.01），否则乘完就是纯黑；
    //  ③ 三级高度衰减：头 0.40 / 腰 0.20 / 脚底 0.05。
    //     用**绝对目标亮度**而不是"再乘一次系数"，避免 0.32 × 0.4 叠乘过头。
    //
    // 为什么之前"死灰一片"：只有 ②① 一个固定系数 0.32，全身一个暗度 → 没有层次；
    // 而且暗部色相来自"按亮度归一"的 chroma，乘上去只改比例不改"沉入感"。
    if (uBacklitMode > 0.5) {
        // 三级高度（本工程 uv.y = 1 是**画面底部**）
        float yT = clamp(uv.y, 0.0, 1.0);
        float tier = mix(0.40, 0.20, smoothstep(0.28, 0.60, yT));   // 上 → 腰
        tier = mix(tier, 0.05, smoothstep(0.78, 0.96, yT));         // 腰 → 脚底（死黑）

        // 环境色乘叠：0.3×基准暗度 + 0.7×环境吸收色（逐通道，保色相）。
        // uBacklitChroma 就是那个 0.7 的混合比（默认 0.70）—— 别让它变成"传了不生效"的死参数。
        vec3 absorb = mix(vec3(uBacklitDarken), uDeepShadowColor, clamp(uBacklitChroma, 0.0, 1.0));
        vec3 target = absorb * tier;

        // 迎光轮廓保留金边（uBacklitGlobal），其余按 target 沉下去
        c *= mix(vec3(uBacklitGlobal), target, shadowW);
    }

    // ===== ⑥b 环境光桥梁（v0.30，规格 3）=====
    // 交界带里"纯光"和"纯暗"都缺中间色，最容易看出断层。叠一层背景代表色
    // （SoftLight，非加性）把两侧接起来，让过渡有"呼吸感"。
    // 权重用钟形 4·l·(1-l)：两端为 0（纯光/纯暗处不该再加色），中点最强。
    float bridge = 4.0 * litRatio * (1.0 - litRatio);
    if (bridge > 0.001) {
        c = mix(c, softLight(c, uAmbientColor), clamp(bridge * 0.35, 0.0, 0.6));
    }

    // ===== ⑦ 高光保护：眼睛/鼻尖在逆光下也要亮，但只提亮一点点 =====
    if (uSpecStrength > 0.001) {
        float specSrc = lumaAt(uv);
        float specMask = smoothstep(uSpecThreshold, min(uSpecThreshold + 0.12, 1.0), specSrc);
        specMask *= smoothstep(-0.2, 0.5, lw);        // 只在迎光侧
        vec3 addSpec = uRimColor * specMask * uSpecStrength;
        c = 1.0 - (1.0 - c) * (1.0 - clamp(addSpec, 0.0, 1.0));           // Screen
    }

    // ===== ⑧ 颜色矩阵：必须最后，且保亮度 =====
    // 矩阵的偏移/降饱和会拉平方差 → 发灰；这里只取它的色相/通道比，亮度还原为矩阵前的值。
    vec3 cm = clamp(uColorMatrix * c, 0.0, 4.0);
    float l0 = dot(c, LUMA);
    float l1 = dot(cm, LUMA);
    cm *= clamp(l0 / max(l1, 1e-4), 0.20, 3.0);
    c = max(mix(c, cm, uMatrixStrength * smoothstep(0.1, 0.6, a)), 0.0);

    // 不做 clamp、不做亮度上限：高光压缩交给最终输出的 Reinhard（见 FRAG_FXAA）
    // alpha 用 aOut（原始 Mask），与光效强度完全解耦
    fragColor = vec4(c, aOut);
}