package com.naiwa.core.cv.gles

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.naiwa.core.common.model.DeepShadowTint
import com.naiwa.core.common.render.GlState
import com.naiwa.core.common.model.EmissiveColor
import com.naiwa.core.common.model.GlowPyramid
import com.naiwa.core.cv.shadow.ShadowShear

/**
 * 多通道图层渲染管线（P1/P2/P2-b/P2-c 图层树，规格阶段二/三）。
 *
 * 【图层树（从底到顶）】
 * ```
 *  P1   背景组：底图 → 镜头光晕(Screen, 定位到光源) → 辉光(Screen) → 全局 CC
 *  P2   主人像层：色矩阵(×0.6) → 四色环境光 → 高光保护（**不再有边缘光叠加**）
 *  EMIT 发光源：边缘环带(形态学梯度) × 主光色(已去白) × 迎光门控 → 1/2 域
 *  PYRA 高斯金字塔：5-tap 可分离高斯 × N 级降采样 → tent 上采样累加（多尺度光晕）
 *  P2-b 立体反光层：Mask + Bokeh 圆盘采样 + 受光面筛选 → Screen
 *  P2-c 阴影/背光层：人像 Mask 填黑 → 逆光射线模糊 / 沿光投影 → Multiply
 *  WRAP 光包裹：背景模糊光 → **人物内侧 5~10px**（向内渗透，不向外描边）
 *  Final：P1 × P2c → over P2 → bleed → screen WRAP → screen P2b → **GLOW(Screen)** → FXAA → sRGB
 * ```
 *
 * 【FBO 生命周期（防 OOM，规格输出要求 2）】
 *  - 所有纹理/FBO 由 [FboPool] 统一登记，**无论成败都在 finally 里 releaseAll()**；
 *  - 任一创建失败立即返回 null（调用方降级 CPU），绝不带着半套资源继续渲染；
 *  - 背景模糊、人像低频用 1/4 分辨率（内存 1/16），全分辨率纹理控制在 6 张以内。
 *
 * 【与旧 [GlesFusionRenderer] 的关系】本类是新图层架构；旧类保留作 A/B 对照与降级。
 */
object GlesLayerPipeline {

    private const val TAG = "NaiwaCamera"
    private const val MAX_TEXTURE_SIDE = 2048

    /** 阴影锚点：画面底部中心（人物脚底的经验位置）。 */
    private const val SHADOW_ANCHOR_X = 0.5f
    private const val SHADOW_ANCHOR_Y = 0.9f

    // ================================================================ Shaders

    private const val VERT = """
#version 300 es
out vec2 vTexCoord;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vTexCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

    /** 色键（复用旧管线的算法，输出非预乘 RGBA）。 */
    private const val FRAG_KEY = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform float uHasGreenScreen;
uniform float uKeyCb, uKeyCr, uKeyT1, uKeyT2;
vec3 rgb2ycbcr(vec3 c) {
    return vec3(0.299*c.r+0.587*c.g+0.114*c.b,
               -0.169*c.r-0.331*c.g+0.5*c.b+0.5,
                0.5*c.r-0.419*c.g-0.081*c.b+0.5);
}
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec4 c = texture(uSrc, uv);
    if (uHasGreenScreen < 0.5) { fragColor = vec4(c.rgb, c.a); return; }
    vec3 ycc = rgb2ycbcr(c.rgb);
    float d = length(vec2(ycc.y - uKeyCb, ycc.z - uKeyCr));
    float alpha = smoothstep(uKeyT1, uKeyT2, d);
    vec3 rgb = c.rgb;
    float spill = 1.0 - alpha;
    rgb.g = mix(rgb.g, min(rgb.g, max(rgb.r, rgb.b)), spill * 0.8);
    fragColor = vec4(rgb, alpha);
}"""

    /** 13-tap 边缘感知模糊（背景光/人像低频）。 */
    private const val FRAG_BLUR = """
#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uScale;
uniform float uAlphaWeighted;
void main() {
    vec3 sum = vec3(0.0); float wsum = 0.0; float aw = 0.0;
    #define TAP(dx, dy, w) { vec4 t = texture(uTex, clamp(vTexCoord + uTexel * vec2(dx,dy) * uScale, 0.0, 1.0)); \
        sum += t.rgb * w; wsum += w; aw += (uAlphaWeighted > 0.5 ? t.a : 1.0) * w; }
    TAP(0.0,0.0,0.15)
    TAP(2.5,0.0,0.10) TAP(-2.5,0.0,0.10) TAP(0.0,2.5,0.10) TAP(0.0,-2.5,0.10)
    TAP(5.0,0.0,0.05) TAP(-5.0,0.0,0.05) TAP(0.0,5.0,0.05) TAP(0.0,-5.0,0.05)
    TAP(2.5,2.5,0.0625) TAP(-2.5,2.5,0.0625) TAP(2.5,-2.5,0.0625) TAP(-2.5,-2.5,0.0625)
    #undef TAP
    fragColor = vec4(sum / max(aw, 1e-4), 1.0);
}"""

    /**
     * 低频 alpha（1/4 域）：**过渡带法向的基线**。
     *
     * ## 为什么需要它
     * 明暗交界"像刀切"的根因不是缺 smoothstep，而是**空间尺度太小**：
     * 法向由 1 texel 差分求出，而抠图边缘只有 2~3px —— 在这 2~3px 里法向就从
     * "朝外"翻到"朝内"，`N·L` 从 +1 跌到 -1。smoothstep 只是把这条线变成
     * "斜率为 0 的 2px"，视觉上仍是一条线。
     *
     * 把 alpha 先在 1/4 域模糊，轮廓就变成一个 ~8~16px 宽的斜坡；在它上面做差分，
     * 得到的是**宽基线法向**，过渡带自然就宽了。代价只是一张 1/4 域纹理。
     *
     * 注意：不能复用 `uPersonLow` —— 它输出的是模糊后的 **RGB**（alpha 恒 1），
     * 那是给 Unsharp 用的；alpha 必须单独模糊一次。
     */
    private const val FRAG_ALPHA_LOW = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2  uTexel;      // 源（全分辨率）texel
uniform float uScale;      // 模糊半径（像素）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    float s = 0.0;
    float w = 0.0;
    #define T(dx, dy, ww) { s += texture(uTex, clamp(uv + uTexel * vec2(dx,dy) * uScale, 0.0, 1.0)).a * ww; w += ww; }
    T(0.0, 0.0, 0.20)
    T(1.0, 0.0, 0.12) T(-1.0, 0.0, 0.12) T(0.0, 1.0, 0.12) T(0.0, -1.0, 0.12)
    T(2.0, 2.0, 0.06) T(-2.0, 2.0, 0.06) T(2.0, -2.0, 0.06) T(-2.0, -2.0, 0.06)
    #undef T
    fragColor = vec4(vec3(s / max(w, 1e-4)), 1.0);
}"""

    /**
     * P1 背景组：底图 → 全局 CC → 镜头光晕(Screen) → 辉光(Screen)。输出线性空间。
     *
     * 【镜头光晕为什么不单开一个 pass】
     * 它是纯 Screen 叠加，与 P1 的其余步骤满足结合律；内联在本 pass 省掉一张全分辨率纹理
     * （2048² RGBA8 = 16MB）和一次全图读写。功能上仍是独立节点：定位/半径/强度都是 uniform。
     */
    private const val FRAG_P1 = """
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
}"""

    /**
     * P2 主人像层：**色调映射 → 局部对比度 → 颜色矩阵 → 边缘光**。
     *
     * 【为什么人物会"平"】原来只有"乘色温 + 乘矩阵"，两者都是全局线性变换，
     * 既不改亮度分布，也不产生任何层次。三处补强：
     * 1. **S 曲线**：`smoothstep(0,1,luma)` 在端点不变、中间拉出对比 ——
     *    0.2 → 0.104（暗部更暗）、0.8 → 0.896（亮部更亮），正是规格要的形状；
     *    按 `target / luma` 等比缩放而不是逐通道 smoothstep，**保色相**（否则会串色）。
     * 2. **Unsharp Mask**：减去 1/4 域低频频得到高频，再按权重加回 —— 二次元的线稿/结构
     *    立刻立起来。只在人物内部生效（`smoothstep(0.1,0.5,a)`），不锐化半透明边缘。
     * 3. 顺序：**先色调映射，再乘颜色矩阵**（规格要求）。反过来的话矩阵会把曲线压平。
     */
    /**
     * P2 主人像层（v0.21：克制版）。
     *
     * 【为什么回退】
     * v0.19 起用 Sobel 伪法线 + 多重加性叠光做体积感，对二次元平涂图会**同时**出现两个问题：
     *  ① 加性项（伪光/暖色/边缘光/高光）逐层叠加，中间调被反复抬亮，末尾 clamp 全部变纯白 → 泛白；
     *  ② 描边处的梯度爆点被当成"结构"，只在描边产生脏边、平涂区毫无变化 → 明暗仍然丢失。
     * 本版的取舍：**只做乘性明暗 + 极弱的加性点缀**，把高光压缩交给最终输出的 Reinhard。
     *
     * 【顺序】① 线性化 → ② S 曲线 → ②b Unsharp → ②c 径向渐变 → ②d 脚底 →
     *         ③ 微弱点光 → ④ 四色 SoftLight → ⑤ 环境色（暗 Multiply / 亮 Screen 0.15）→
     *         ⑥ 边缘光 → ⑦ 高光保护 → ⑧ 颜色矩阵（保亮度）
     *
     * 加性项只有三处，且强度上限都在 0.2 以内：伪光 0.15、边缘光 ≈0.13、高光保护 0.12。
     */
    private const val FRAG_P2 = """
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
        // ★ 核心区保护（v0.36）：mask ≥ 0.97 的像素**不许被腐蚀**。
        //   腐蚀的本意是"不画最外圈那 1~2px 被污染的背景色像素"，但它同时把
        //   实心内部也削薄了 —— 在暗部/底部，轮廓因此被"吃掉" 1~2px，肉眼看就是变细、
        //   变虚。这里用 mask 自己的高段把它顶回去：内部永远是实心，腐蚀只在过渡带生效。
        a = max(a, smoothstep(0.95, 0.99, p.a));
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
}"""

    /**
     * 步骤 ① 提取真实发光源：**边缘环带 × 主光色 × 迎光门控**。
     *
     * ## 为什么必须废弃旧写法
     * 旧版边缘光是 `uRimColor * pow(facing,1.5) * band * strength` 直接加进人物 RGB：
     * - `band` 来自 `smoothstep(0.01,0.15,a) * (1-smoothstep(0.85,0.99,a))` —— 这是
     *   **固定的 alpha 过渡区**，在二次元线稿上就是一条等宽的带 → 观感必然是一圈描边；
     * - 颜色是 `uRimColor`（背景高光中位色，经常接近纯白）→ 描边还是白的；
     * - 加完之后直接 clamp，没有任何扩散 → 光"贴着边"，不可能有体积。
     *
     * ## 现在的做法
     * 本 pass **不产生最终画面**，只产生一张"发光源图"（emissive map），交给后面的
     * 高斯金字塔去扩散。三个关键点：
     *
     * 1. **环带用形态学梯度，不用 alpha 过渡区**
     *    `band = smoothstep(0.08, 0.55, aMax - aMin)`：邻域里 alpha 跨越了 0→1 才有值。
     *    好处是平涂内部和纯背景都是 0，只有真正贴着轮廓的一圈非零 ——
     *    而且在**半透明发丝**上同样有效（a 从 0 到 0.3 也算跨越）。
     * 2. **迎光门控是硬的**：`lit = smoothstep(0.0, 0.35, facing)`，facing ≤ 0 严格归零。
     *    规格要求"背光面直接剔除"，所以这里不能像旧版那样取 `max(0, dot)` 就完事
     *    （那样背光面会在 dot≈0 处留一圈微光，看起来仍然全周发光）。
     * 3. **不乘 alpha**。乘了 alpha 光就锁在人物内部，只能变亮、不能"溢出去"。
     *    真实的发光是**向外辐射**的，所以外侧保留 0.62 的权重（内侧 1.0），
     *    让光晕自然越过轮廓渗进背景空气里 —— 这正是"发光"与"描边"的分水岭。
     *
     * 光源色 `uKeyLight` 由 CPU 侧的 [com.naiwa.core.common.model.EmissiveColor]
     * 整形过（压亮到 235 以下 + 补足彩度），所以这里**不可能是纯白**。
     *
     * 输出在半分辨率域（1/2）：环带本身只有 2~3px 宽，视觉上不需要全分辨率；
     * 省下 75% 的填充率，且天然是一次抗锯齿下采样。
     */
    private const val FRAG_EMIT = """
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
uniform int   uDebug;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float aAt(vec2 uv) { return texture(uPerson, clamp(uv, 0.0, 1.0)).a; }

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

    // ---------- ③ 颜色：迎光主光色 / 侧光环境色（v0.30 原样）----------
    vec3 col = mix(uAmbientLight * 0.45, uKeyLight, smoothstep(-0.1, 0.55, facing));

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
}"""

    /**
     * 步骤 ② 可分离高斯（单方向）。
     *
     * 5 次采样等效 9 抽头（GPU Gems 3, ch.40 的线性采样技巧）：
     * 权重 `[0.2270, 0.3162, 0.0703]` 配偏移 `[0, 1.3846, 3.2308] × σ`，
     * 权重和恰为 1.0（0.2270 + 2×0.3162 + 2×0.0703 = 1.0000），所以**不需要再除权重和**。
     *
     * ⚠️ 这个核在 σ≈2（texel 单位）时最准；本管线固定 σ=1 而把"大半径"交给降采样，
     * 是刻意的取舍：偏移量按 σ 线性缩放时，核的实际形状会略低于真正的高斯，
     * 但视觉差异在"多级叠加 + Screen"之后完全不可见，换来的是每像素只有 5 次采样。
     */
    private const val FRAG_GAUSS = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2  uTexel;      // 源纹理 texel
uniform vec2  uDir;        // (1,0) 水平 / (0,1) 垂直
uniform float uSigma;      // 以源 texel 为单位的 σ
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec2 d = uDir * uTexel * max(uSigma, 0.5);
    vec3 c = texture(uTex, uv).rgb * 0.2270270270;
    c += (texture(uTex, clamp(uv + d * 1.3846153846, 0.0, 1.0)).rgb
        + texture(uTex, clamp(uv - d * 1.3846153846, 0.0, 1.0)).rgb) * 0.3162162162;
    c += (texture(uTex, clamp(uv + d * 3.2307692308, 0.0, 1.0)).rgb
        + texture(uTex, clamp(uv - d * 3.2307692308, 0.0, 1.0)).rgb) * 0.0702702703;
    fragColor = vec4(max(c, 0.0), 1.0);
}"""

    /**
     * 步骤 ② 上采样累加（金字塔的"回程"）。
     *
     * 从最粗一级往回流：`out = 本级 + tent(更粗一级) × weight`。
     * 这样最终得到的是**所有尺度光晕的加权和** —— 小尺度的锐利核心 + 大尺度的柔和外晕
     * 同时存在，这才是真实光源的衰减曲线（单一尺度再怎么调都是"一个模糊的圆"）。
     *
     * 用 3×3 tent 而不是直接双线性：直接双线性在 1/8 → 1/4 这种跨 2 倍上采样时，
     * 会留下可见的方形台阶（"光晕是马赛克"），tent 核把边界抹开。
     * 9 次采样看着多，但都在低分辨率域，总填充率不到一张全图的 1/4。
     */
    private const val FRAG_BLOOM_UP = """
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
}"""

    /**
     * 步骤 ③ 泛光合成：把金字塔结果 **Screen** 到已完成人物合成的画面上。
     *
     * ## 为什么放在 composite 之后（而不是 Screen 进 P2）
     * 放在 P2 里的话，泛光只能画在**人物自己的像素**上（alpha=0 的背景区写不进去），
     * 光晕越过轮廓就断了 —— 观感仍然是"内部亮了一圈"，不是发光。
     * 放在合成之后，光晕可以同时落在人物内侧和背景空气里，这才是体积光。
     *
     * 但背景不能给满：`w = mix(uAirRatio, 1.0, smoothstep(0.05,0.55,a))`，
     * 空气里只给 35%（"微微渗透"），人物上给 100%。
     *
     * 混合用 Screen 而不是 Add：Screen 对已经很亮的像素几乎不再抬升（`1-(1-b)(1-s)`），
     * 所以白衬衫/高光瞳孔不会被推成死白；Add 在叠加多层时会直接烧成一片。
     */
    private const val FRAG_GLOW = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uBase;      // composite 输出（线性）
uniform sampler2D uBloom;     // 金字塔顶（1/2 域加权和）
uniform sampler2D uMask;      // 人物 alpha（texP2）
uniform float uStrength;
uniform float uAirRatio;
uniform float uClamp;         // 单次可注入的最大光量（防止极端参数烧白）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 base = texture(uBase, uv).rgb;
    vec3 gl = texture(uBloom, uv).rgb;
    float a = texture(uMask, uv).a;
    float w = mix(uAirRatio, 1.0, smoothstep(0.05, 0.55, a));
    vec3 add = clamp(gl * uStrength * w, 0.0, uClamp);
    vec3 c = 1.0 - (1.0 - clamp(base, 0.0, 1.0)) * (1.0 - add);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""

    /** P2-b 立体反光层：Mask + Bokeh 圆盘 + 受光面筛选 → 预乘输出（供 Screen）。 */
    private const val FRAG_P2B = """
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
    // 基线取 3 texel（不是 1）：与其它光效 pass 的过渡带宽度对齐
    float aL = texture(uPerson, clamp(uv - vec2(3.0 * uTexel.x, 0.0), 0.0, 1.0)).a;
    float aR = texture(uPerson, clamp(uv + vec2(3.0 * uTexel.x, 0.0), 0.0, 1.0)).a;
    float aU = texture(uPerson, clamp(uv - vec2(0.0, 3.0 * uTexel.y), 0.0, 1.0)).a;
    float aD = texture(uPerson, clamp(uv + vec2(0.0, 3.0 * uTexel.y), 0.0, 1.0)).a;
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
}"""

    /**
     * P2-c 阴影节点：**全局投影轴 + 沿轴一维模糊 + 距离衰减 + 接触 AO**。
     *
     * 【模型：把 mask 沿 dir 推出去】
     * 像素 p 落在影子里，等价于"在 p - dir*d 处有人物"，d 是该像素沿轴距锚点的距离。
     * 所以在 p 处回溯采样 `uv - offPx` 即可 —— 这是 push 模型，天然有正确的透视缩短，
     * 且位移量**显式可控**（这点很重要：能在位移处加 `max(0, y)` 安全垫）。
     *
     * 【为什么不用 Radial Blur】
     * 径向模糊是"从光心向四周发散"（体积光/辉光）；投影是平行光在承接面上的延伸，
     * 方向必须全场一致。原来的 `normalize(uv - uLightPos)` 让上半个身体和下半个身体
     * 朝不同方向发，影子呈放射状"炸开"。
     *
     * 【模糊与衰减】
     *  - 一维模糊：只沿 dir 抖动 —— 横向不糊，这正是"定向模糊"的定义；
     *  - 步长随 `distK` 增大 → 越远越糊（`uBlurBasePx + distK * uBlurGrowPx * shear`）；
     *  - `1 - uFalloff * distK` → 根部最重、末端渐隐。
     *
     * 【安全垫】
     * `offPx.y = max(0.0, offPx.y)`：浮点误差、脏数据（光源低于锚点）都不会让影子向上偏一像素。
     */
    private const val FRAG_P2C = """
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
}"""

    /**
     * 步骤 ③ 光包裹（Light Wrap）：**向内渗透**，不是向外描边。
     *
     * ## 语义的根本改变
     * 旧版 `dilate(mask, 5px)` 把包裹蒙版**外扩**到人物之外，再乘一个"遮罩上升沿"——
     * 效果必然是在轮廓外侧画一圈等宽的光环。那就是"白边描边"的来源。
     *
     * 真正的光包裹方向是**反的**：背景的光打在人物边缘，穿透皮肤/衣料最外层
     * 5~10px 的**次表面**，越往里越弱。所以本 pass 只在 `a > 0.45` 的**人物内部**
     * 叠加，`a` 越低权重越低 → 一旦越过轮廓立刻归零，绝不落到背景上。
     *
     * ## 向内距离怎么测（这是唯一老实又便宜的办法）
     * 2D 抠图没有深度，也没有 SDF。这里用 **8 方向 × 3 半径的 alpha 探针**：
     * ```
     * prox = min over (dir, r) of alpha(uv + dir*r)      // r = wrapPx/3, 2wrapPx/3, wrapPx
     * depth = 1 - prox
     * ```
     * 贴着轮廓时，至少有一个方向在 wrapPx 内就离开了人物 → min 很小 → depth≈1；
     * 深入内部时三个半径都还在人物里 → min≈1 → depth≈0。
     * 这个估计在直角/凹角处偏大（探针会斜穿出去），所以最后用 `pow(depth, 1.8)`
     * 把权重往最外层压，凹角的误差就在衰减里被吃掉了。
     *
     * ## 颜色
     * 亮度取自 **P1 的背景模糊光**（`uBgBlur`，1/4 域），色相按 `N·L` 在
     * 主光色 / 环境色之间插值 —— 迎光侧是夕阳的暖橙，背光侧是天空的冷青，
     * 这不是"给轮廓染个色"，而是两块不同的光确实来自不同方向。
     *
     * 遮挡：读 P2-c 的 Multiply 因子，被投影盖住的地方包裹光同步衰减
     * （否则阴影里的下摆反而最亮）。
     *
     * 输出只承载"光"（alpha 恒 1），在 composite 里 Screen 上去。
     */
    private const val FRAG_LIGHTWRAP = """
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

    // ---------- ④ 颜色：背景模糊光的亮度 × 冷暖双色（v0.30 原样）----------
    vec3 bg = pow(clamp(texture(uBgBlur, uv).rgb, 0.0, 1.0), vec3(2.2));
    float bgL = max(dot(bg, LUMA), 1e-3);
    bg *= min(2.0, uMaxLuma / bgL);                 // 暗背景也能提亮，亮背景不过曝

    // 环境色提亮：暗部均值可能接近纯黑，直接叠会糊成灰泥
    vec3 amb = uAmbientLight;
    float al = max(dot(amb, LUMA), 1e-3);
    amb *= max(al, uAmbientLift) / al;
    amb = mix(vec3(dot(amb, LUMA)), amb, 1.15);     // 轻微提饱和，保留"有光感"

    float keyW = smoothstep(-0.1, 0.55, facing);
    vec3 duo = mix(amb, uKeyLight, keyW);
    vec3 tinted = mix(
        bg,
        duo * (0.55 + 0.45 * clamp(dot(bg, LUMA), 0.0, 1.0)),
        clamp(uColorMix, 0.0, 1.0)
    );

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
}"""

    /**
     * 图层合成：P1 × P2c → over P2 → **大气透视** → Screen 光包裹（向内） → Screen P2b → **环境色罩**。
     * 输出线性空间，不含 FXAA。
     *
     * 【v0.26 的职责变化】本 pass **不再承担"边缘光"**。
     * 发光（rim light）已经拆成三步走到别处：`FRAG_EMIT` 提取发光源 →
     * 高斯金字塔扩散 → `FRAG_GLOW` 在**合成之后** Screen 上去。
     * 这里只保留唯一一个"贴边"的光：光包裹的**向内渗透**（`uWrapIn`），
     * 它严格限制在人物内部（a>0.45），所以不会画出一圈白边。
     *
     * 【① 方向性羽化】**不再用各向同性的 `smoothstep(0,1,a)`**：
     * ```
     * facing = 外法向 · 光向        → +1 迎光 / -1 背光
     * width  = mix(0.45, 0.09, lit)  → 背光过渡宽、迎光窄
     * blend  = smoothstep(0.5-width, 0.5+width, a)
     * ```
     * 物理依据：迎光侧被强光"咬"住 → 硬边；背光侧没有轮廓光，只能靠环境光过渡 → 天然发虚。
     *
     * 【② 大气透视（Light Bleed）】把**背后那层背景的颜色**往人物内部渗 10~20px：
     * ```
     * innerProx = 1 - min(8 方向上 bleedPx 处的 alpha)   // 贴着轮廓 = 1，深入内部 = 0
     * bleed     = p1 × strength × innerProx² × inside
     * ```
     * 平方衰减很关键：线性衰减会让整个人物发白（像罩了层雾），平方只在最外一圈生效。
     * `bleedPx` 按 `lit` 在 20 ↔ 10 px 间插值：迎光面窄（锐利）、背光面宽（消散进环境）。
     *
     * 【③ 环境色罩】用背景代表色以 0.05 罩在人物区域，让二次元与写实背景色温同频。
     * 只在人物区域内混合（背景已经就是这个色，再罩一次没有意义）。
     */
    private const val FRAG_COMPOSITE = """
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
    // 宽基线（3 texel）：与 P2 / EMIT / LIGHTWRAP 的过渡带宽度对齐。
    // 1 texel 基线会让"方向性羽化"的迎光/背光判定在 2~3px 内翻转 → 边缘上有一条硬边。
    float aL = aAtP2(uv - vec2(3.0 * uTexel.x, 0.0));
    float aR = aAtP2(uv + vec2(3.0 * uTexel.x, 0.0));
    float aU = aAtP2(uv - vec2(0.0, 3.0 * uTexel.y));
    float aD = aAtP2(uv + vec2(0.0, 3.0 * uTexel.y));
    // 像素空间外法向（零梯度必须显式归零，`+vec2(1e-5)` 会伪造 45° 法线）
    vec2 gPx = vec2(aR - aL, aD - aU) / (2.0 * uTexel);
    float gLen = length(gPx);
    vec2 nPx = gLen > 1e-6 ? -gPx / gLen : vec2(0.0);

    // v0.28：羽化宽度也必须跟着光源走，否则"迎光侧硬边"会留在旧方位
    float facing = dot(nPx, lightDirPx(uv));
    float lit = clamp(facing * 0.5 + 0.5, 0.0, 1.0);          // 1 迎光 / 0 背光
    // 背光侧过去给到 0.45：那是 0.05~0.95 的 alpha 全范围混合，
    // 等于让边缘 2~3px 大面积透出背景 —— 观感就是"融化"。收窄到 0.18/0.07。
    // ★ 底部实心保护（v0.36）：本工程 uv.y = 1 是画面**底部**，那里是人物与地面相接的位置，
    //   羽化在这里最刺眼（脚"陷进"背景 = 融化）。底部把羽化宽度收到 25%。
    float bottomSolid = smoothstep(0.86, 0.96, uv.y);
    float width = mix(0.18, 0.07, lit) * (1.0 - 0.75 * bottomSolid);
    float blend = smoothstep(0.5 - width, 0.5 + width, p2.a);

    // ★ Alpha 锁定（v0.36，规格 1/2）：mask ≥ 0.97 的像素必须是 **100% 人物**。
    //   羽化只该发生在 alpha 过渡带上（0.32~0.68），一旦它伸进实心区，背景就从那里透出来。
    //   这一行是"人物不可能变透明"的硬保证：与光效强度、法线、光源位置全部无关。
    blend = max(blend, smoothstep(0.90, 0.97, p2.a));

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
        // ★ 只允许在 alpha 过渡带内渗背景（v0.36）。
        //   `innerProx` 的探针半径是 bleedPx（背光侧 20px），在那个半径内它**恒为 1** ——
        //   于是背景以 uBleedStrength(0.35) 的强度被 Screen 进人物内侧整整 20px。
        //   背光侧没有光包裹去盖住它，观感就是"边缘半透明、快融化了"。
        //   真正的"大气透视"只发生在轮廓外 2~3px，深度信息必须由 alpha 自己给：
        //   a < 0.99 才允许渗，a ≥ 0.99（实心）完全禁止；底部再额外关掉。
        float edgeOnly = 1.0 - smoothstep(0.90, 0.99, p2.a);
        float bleedW = innerProx * innerProx * inside * edgeOnly * (1.0 - bottomSolid);
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
}"""

    /**
     * 后处理 ①：景深（**环形焦外**，不是整张背景糊死）。
     *
     * 【为什么改成环形】
     * 原来的做法是"背景全模糊、人物清晰"：背景 Luma 一旦低于阈值就以 9px 半径整片模糊，
     * 结果背景纹理/建筑轮廓全被抹成色块 —— 这是"糊死"的直接原因。
     * 真实镜头的焦外是**渐进的**：焦点平面附近依然锐利，只有远离焦平面的部分才开始散焦；
     * 而在这种"人物+背景"的合成里，唯一必须虚化的其实是**人物与背景的交界**（否则边界像贴纸）。
     *
     * 现在的模型：
     *   ring = (轮廓外 uRingPx 之内) × (不是人物实心内部)
     *   半径 = uMaxRadiusPx(≤5) × ring
     *   ring ≈ 0 时**直接返回原像素**（背景远处与人物内部零采样、零模糊，细节 100% 保留）
     *
     * 兼顾规格里的"替代方案"：环形区本身就是"高频突变处"，等价于只模糊边界高频，
     * 背景的轮廓与纹理不动。
     */
    private const val FRAG_DOF = """
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
}"""

    /**
     * 后处理 ②：全局调色（3×3 矩阵 + 对比度 + 饱和度 + **全局 Multiply 压暗**）。
     *
     * 「四周和暗部乘 0.85」的实现要点：**只在暗部生效**。
     * 全图无差别乘 0.85 等于降低曝光（高光会一起灰掉，反而更平）；
     * 真实电影感来自"暗部压下去、高光保住"的对比度扩张，所以用 `darkMask` 做选择性压暗：
     * ```
     * darkMask = 1 - smoothstep(0.02, 0.30, luma)   // 纯黑=1，中高调=0
     * c *= mix(1.0, uGlobalMultiply, darkMask)
     * ```
     * 四周的压暗交给 Vignette 节点（两件事分开做，参数才好单独调）。
     */
    private const val FRAG_GRADE = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform mat3  uMatrix;          // 3D LUT 退化为 3×3（端侧 CPU 侧已烘进矩阵）
uniform float uContrast;
uniform float uSaturation;
uniform float uGlobalMultiply;  // 1 = 关闭；0.85 = 暗部压暗
void main() {
    vec3 c = texture(uSrc, clamp(vTexCoord, 0.0, 1.0)).rgb;
    c = uMatrix * c;
    c = (c - 0.18) * uContrast + 0.18;                        // 线性空间支点
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l), c, uSaturation);

    // 全局 Multiply：暗部压暗，高光保持（保留细节，不做死黑）
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float darkMask = 1.0 - smoothstep(0.02, 0.30, lum);
    c *= mix(1.0, uGlobalMultiply, darkMask);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""

    /**
     * 后处理 ③：Looks 加光（漏光 / 胶片光晕，Screen）。
     *
     * 本应用**不内置任何素材贴图**（assets 为空是既定策略），所以漏光用程序化生成：
     * 双高斯核 + 低频正弦扰动（避免"一个死圆斑"）。将来接入贴图只需把 `leak` 换成
     * `texture(uLeakTex, uv).r` —— uniform 契约不变。
     */
    private const val FRAG_LOOKS = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2  uLeakPos;         // 漏光中心（归一化，可随滑杆微调）
uniform vec2  uAspect;
uniform vec3  uLeakColor;       // 线性空间
uniform float uStrength;
uniform float uPhase;           // UV 微调相位（滑杆/时间）
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 c = texture(uSrc, uv).rgb;

    vec2 d = (uv - uLeakPos) * uAspect;
    float r = length(d);
    float leak = exp(-r * r * 4.0) * 0.85 + exp(-r * r * 1.2) * 0.25;

    // 低频扰动：真实漏光从来不是标准圆形
    float wobble = 0.85 + 0.15 * sin(uv.x * 9.0 + uPhase) * sin(uv.y * 7.0 - uPhase * 0.7);
    leak = max(leak * wobble, 0.0);

    vec3 add = uLeakColor * leak * uStrength;
    fragColor = vec4(1.0 - (1.0 - c) * (1.0 - clamp(add, 0.0, 1.0)), 1.0);   // Screen
}"""

    /**
     * 后处理 ④：暗角。
     *
     * ⚠️ 规格公式 `smoothstep(radius, radius - softness, dist)` 的 `edge0 > edge1` 在 GLSL 规范里
     * 是**未定义行为**（部分驱动会返回 0 或反向）。这里用严格等价的合法写法：
     * `1.0 - smoothstep(radius - softness, radius, dist)`。
     */
    private const val FRAG_VIGNETTE = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2  uAspect;
uniform float uRadius;          // 开始压暗的归一化距离
uniform float uSoftness;        // 过渡宽度
uniform float uStrength;        // 0 = 关闭
void main() {
    vec2 uv = clamp(vTexCoord, 0.0, 1.0);
    vec3 c = texture(uSrc, uv).rgb;
    float dist = length((uv - 0.5) * uAspect) / 0.7071;              // 归一化：对角 = 1
    float v = 1.0 - smoothstep(uRadius - uSoftness, uRadius, dist);   // 中心 1 → 边缘 0
    fragColor = vec4(c * mix(1.0, v, uStrength), 1.0);
}"""

    /** 最终：FXAA（5 tap 亮度对比检测）+ sRGB 编码。必须是**最后一个** pass。 */
    /**
     * Final：简化 FXAA + **Reinhard 色调映射** + sRGB 编码。
     *
     * 【为什么把 tonemap 放在这里】
     * 它是整条管线唯一的"最终输出"点：P1 + P2 + 后处理全部合成完毕、且仍在**线性空间**。
     * 放在别处要么漏掉后处理的结果，要么在 sRGB 空间压高光（会压暗中间调）。
     *
     * 【为什么是 Reinhard 而不是 clamp】
     * `clamp(c,0,1)` 把 >1 的像素全部压成 1.0 —— 高光区域变成一块同色纯白，这就是"泛白"。
     * Reinhard `c/(1+c)` 让 1.0→0.5、2.0→0.667、∞→1.0，**单调、连续、永不裁切**，
     * 过曝的地方保留层次；且暗部近似恒等（0.05→0.048），不会动整体反差。
     */
    private const val FRAG_FXAA = """
#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uToneMap;          // 1 = Reinhard（0 = 关闭，仅调试用）
uniform float uExposure;         // 线性曝光补偿（1 = 不补偿）
uniform float uToneKnee;         // 膝点：0 = 整幅 Reinhard；0.6 = 只压高光（默认）
uniform int   uDebug;            // >0：调试直通，跳过 tonemap 与 sRGB 编码

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
    // 调试通道：原样输出（不做 tonemap、不做 gamma），保证"值 = 屏幕灰度"
    if (uDebug > 0) {
        fragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);
        return;
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
}"""

    // ================================================================ 入口

    /**
     * 执行多图层融合。任何 EGL/GL 失败返回 null（调用方降级 CPU）。
     *
     * @param background 背景（已是绘制尺寸）
     * @param person 已摆放进场景坐标系的人物图层（非预乘 RGBA，背景透明）
     */
    fun render(
        background: Bitmap,
        person: Bitmap,
        params: GlesFusionRenderer.Params,
    ): Bitmap? {
        if (background.isRecycled || person.isRecycled) return null
        if (background.width <= 0 || background.height <= 0) return null

        val egl = createEgl(background.width, background.height) ?: run {
            Log.i(TAG, "图层管线 EGL 创建失败，降级")
            com.naiwa.core.common.debug.DebugReport.lastPipeline = "legacy"
            return null
        }
        val pool = FboPool()
        return try {
            egl.makeCurrent()
            val hwMax = queryMaxTextureSize().coerceAtLeast(512)
            val limit = minOf(MAX_TEXTURE_SIDE, hwMax)
            val bgSafe = sanitize(background, limit) ?: return null
            val personSafe = sanitize(person, limit) ?: return null
            val w = bgSafe.width
            val h = bgSafe.height
            try {
                renderLayers(bgSafe, personSafe, params, w, h, pool)
                    ?.also { com.naiwa.core.common.debug.DebugReport.lastPipeline = "layer" }
            } finally {
                if (bgSafe !== background) bgSafe.recycle()
                if (personSafe !== person) personSafe.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "图层管线失败，降级 CPU：${t.message}", t)
            com.naiwa.core.common.debug.DebugReport.lastPipeline = "legacy"
            null
        } finally {
            // ★ 无论成败：统一销毁所有 FBO/纹理（防 OOM）
            pool.releaseAll()
            egl.release()
        }
    }

    private fun renderLayers(
        bg: Bitmap,
        person: Bitmap,
        params: GlesFusionRenderer.Params,
        w: Int,
        h: Int,
        pool: FboPool,
    ): Bitmap? {
        val programs = HashMap<String, Int>()
        try {
            listOf(
                "key" to FRAG_KEY,
                "blur" to FRAG_BLUR,
                "alphaLow" to FRAG_ALPHA_LOW,
                "p1" to FRAG_P1,
                "p2" to FRAG_P2,
                "emit" to FRAG_EMIT,
                "gauss" to FRAG_GAUSS,
                "bloomUp" to FRAG_BLOOM_UP,
                "glow" to FRAG_GLOW,
                "p2b" to FRAG_P2B,
                "p2c" to FRAG_P2C,
                "lightwrap" to FRAG_LIGHTWRAP,
                "composite" to FRAG_COMPOSITE,
                "dof" to FRAG_DOF,
                "grade" to FRAG_GRADE,
                "looks" to FRAG_LOOKS,
                "vignette" to FRAG_VIGNETTE,
                "fxaa" to FRAG_FXAA,
            ).forEach { (name, src) ->
                val prog = buildProgram(src) ?: return null
                programs[name] = prog
            }

            val vao = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])

            val texBg = upload(bg, pool) ?: return null
            val texPersonSrc = upload(person, pool) ?: return null

            // ---- ① 色键：绿幕 → 人物 RGBA ----
            val texPerson = pool.texture(w, h) ?: return null
            val fboPerson = pool.fbo(texPerson) ?: return null
            draw(programs["key"]!!, fboPerson, w, h, pool) {
                bind("uSrc", texPersonSrc, 0)
                uni1f("uHasGreenScreen", if (params.hasGreenScreen) 1f else 0f)
                if (params.hasGreenScreen) {
                    val (cb, cr) = sampleKeyChroma(person)
                    uni1f("uKeyCb", cb); uni1f("uKeyCr", cr)
                    uni1f("uKeyT1", 0.08f); uni1f("uKeyT2", 0.20f)
                }
            } ?: return null

            // ---- ② 1/4 域背景模糊（辉光/包裹光源） ----
            val qw = (w / 4).coerceAtLeast(1)
            val qh = (h / 4).coerceAtLeast(1)
            val texBgQ = pool.texture(qw, qh) ?: return null
            val fboBgQ = pool.fbo(texBgQ) ?: return null
            draw(programs["blur"]!!, fboBgQ, qw, qh, pool) {
                bind("uTex", texBg, 0)
                uni2f("uTexel", 1f / w, 1f / h)
                uni1f("uScale", 0.45f)
                uni1f("uAlphaWeighted", 0f)
            } ?: return null

            val texBgBlur = pool.texture(qw, qh) ?: return null
            val fboBgBlur = pool.fbo(texBgBlur) ?: return null
            draw(programs["blur"]!!, fboBgBlur, qw, qh, pool) {
                bind("uTex", texBgQ, 0)
                uni2f("uTexel", 1f / qw, 1f / qh)
                uni1f("uScale", (params.bgBlurSigma / 4f * 1.732f / 2.5f).coerceIn(0.8f, 6f))
                uni1f("uAlphaWeighted", 0f)
            } ?: return null

            // ---- ②' 人物低频（1/4 域）：Unsharp Mask 的低频基准 ----
            //   uAlphaWeighted=1 让模糊只统计人物自身像素，透明区的残留色不污染低频。
            val texPersonLow = pool.texture(qw, qh) ?: return null
            val fboPersonLow = pool.fbo(texPersonLow) ?: return null
            draw(programs["blur"]!!, fboPersonLow, qw, qh, pool) {
                bind("uTex", texPerson, 0)
                uni2f("uTexel", 1f / w, 1f / h)
                uni1f("uScale", 1.1f)
                uni1f("uAlphaWeighted", 1f)
            } ?: return null

            // ---- ②'' 低频 alpha（1/4 域）：过渡带法向的宽基线 ----
            //   P2 的法向若用 1 texel 差分，过渡带只有 2~3px（"刀切"的根源）。
            //   先把 alpha 模糊成 ~8~16px 的斜坡，再在它上面求方向，过渡带自然变宽。
            val texPersonLoA = pool.texture(qw, qh) ?: return null
            val fboPersonLoA = pool.fbo(texPersonLoA) ?: return null
            draw(programs["alphaLow"]!!, fboPersonLoA, qw, qh, pool) {
                bind("uTex", texPerson, 0)
                uni2f("uTexel", 1f / w, 1f / h)
                uni1f("uScale", (params.penumbraPx * h / 1080f).coerceIn(2f, 12f))
            } ?: return null

            // ---- ③ P1：背景组（底图 + 光晕 + 辉光 + CC） ----
            val texP1 = pool.texture(w, h) ?: return null
            val fboP1 = pool.fbo(texP1) ?: return null
            draw(programs["p1"]!!, fboP1, w, h, pool) {
                bind("uBackground", texBg, 0)
                bind("uBgBlur", texBgBlur, 1)
                uni2f("uLightPos", params.lightX, params.lightY)
                uni2f("uAspect", w.toFloat() / h.toFloat(), 1f)
                // 光晕只在有明确光源时出现（否则整屏发白）
                uni1f("uFlareStrength", params.flareStrength.coerceIn(0f, 1f))
                uni1f("uFlareRadius", params.flareRadius.coerceIn(0.2f, 4f))
                uni1f("uGlowStrength", params.glowStrength * 0.8f)
                uniMat3("uColorMatrix", params.globalColorMatrix)
            } ?: return null

            // ---- ④-1 P2 色调层：S 曲线 → Unsharp → 色矩阵 → 边缘光 ----
            val rim = intArrayOf(
                ((params.bgHighlightColor shr 16) and 0xFF),
                ((params.bgHighlightColor shr 8) and 0xFF),
                (params.bgHighlightColor and 0xFF),
            )
            // 环境吸收色：只算一次，P2 内用于逆光暗部（色相归一 × 0.42）
            val deepShadowFactor = DeepShadowTint.factor(params.shadowColorRgb)
            val texP2Tone = pool.texture(w, h) ?: return null
            val fboP2Tone = pool.fbo(texP2Tone) ?: return null
            draw(programs["p2"]!!, fboP2Tone, w, h, pool) {
                bind("uPerson", texPerson, 0)
                bind("uPersonLow", texPersonLow, 1)
                bind("uPersonLoA", texPersonLoA, 2)   // 低频 alpha：过渡带法向用
                uni2f("uTexel", 1f / w, 1f / h)
                uniMat3("uColorMatrix", params.personColorMatrix)
                // 0.5：矩阵是最后一步，权重过高仍会把 ②③ 拉出的层次抹平
                uni1f("uMatrixStrength", 0.5f)
                uni3f("uTint", params.tintR, params.tintG, params.tintB)
                uni3f("uLightDir", params.lightDirX, params.lightDirY, params.lightDirZ)
                uni3f("uRimColor", rim[0] / 255f, rim[1] / 255f, rim[2] / 255f)
                uni1f("uToneContrast", params.toneContrast.coerceIn(0f, 1f))
                uni1f("uSCurveLow", params.toneCurveLow.coerceIn(0f, 0.5f))
                uni1f("uSCurveHigh", params.toneCurveHigh.coerceIn(0.5f, 1f))
                uni1f("uUnsharp", params.unsharp.coerceIn(0f, 1.5f))
                // ---- 四色渐变环境光 + 伪立体打光 ----
                uniSrgbLinear("uQuadTop", params.quadTopRgb)
                uniSrgbLinear("uQuadBottom", params.quadBottomRgb)
                uniSrgbLinear("uQuadLeft", params.quadLeftRgb)
                uniSrgbLinear("uQuadRight", params.quadRightRgb)
                uni2f("uAspect", w.toFloat() / h.toFloat(), 1f)
                uni1f("uQuadStrength", params.quadStrength.coerceIn(0f, 1f))
                uni1f("uFakeLight", params.fakeLightStrength.coerceIn(0f, 1f))
                // 径向渐变场：min/max 是"渐变带内径/外径"（0.2 / 0.8），差值越小交界越硬
                uni2f("uLightPos", params.lightX, params.lightY)
                uni1f("uGradMin", params.gradientMinDist.coerceIn(0.02f, 1.2f))
                uni1f("uGradMax", params.gradientMaxDist.coerceIn(0.05f, 2f))
                uni1f("uGradStrength", params.gradientStrength.coerceIn(0f, 1.5f))
                uni1f("uFeetStart", params.feetFadeStart.coerceIn(0.3f, 0.99f))
                uni1f("uFeetStrength", params.feetFadeStrength.coerceIn(0f, 1f))
                // 高光保护 / 环境色（边缘光已迁到 EMIT + 金字塔，见 FRAG_P2 注释）
                uni1f("uSpecThreshold", params.specThreshold.coerceIn(0.5f, 0.99f))
                uni1f("uSpecStrength", params.specStrength.coerceIn(0f, 1f))
                uni1f("uEnvSplit", params.envSplitStrength.coerceIn(0f, 1f))
                // 白边熔断 + 底部反弹光
                uni1f("uShrinkPx", params.edgeShrinkPx.coerceIn(0f, 3f))
                uni1f("uDefringe", params.defringeStrength.coerceIn(0f, 1f))
                uni1f("uBounceLight", params.bounceLightStrength.coerceIn(0f, 0.3f))
                uniSrgbLinear("uShadowColor", params.shadowColorRgb)
                // ---- 逆光剪影压暗（规格 1）：非逆光时 uBacklitMode = 0，整段不执行 ----
                uni1f("uBacklitMode", if (params.backlit) 1f else 0f)
                uni1f("uBacklitDarken", params.backlitDarken.coerceIn(0.1f, 0.9f))
                uni1f("uBacklitGlobal", params.backlitGlobalDarken.coerceIn(0.4f, 1f))
                uni1f("uBacklitChroma", params.backlitChroma.coerceIn(0f, 1f))
                // 过渡带：半宽（像素）+ 桥梁色（背景代表色，线性）
                uni1f("uPenumbraPx", (params.penumbraPx * h / 1080f).coerceIn(2f, 16f))
                uniSrgbLinear("uAmbientColor", params.ambientColorRgb)
                // ★ 环境吸收色：`shadowColorRgb` 是背景最暗部中位色（很暗，线性≈0.01），
                //   直接相乘会把暗部压成纯黑。DeepShadowTint 只取它的**色相**、把亮度归一到 0.42。
                //   注意用 uni3f 直传：它是乘法因子，不是 sRGB 颜色（走 uniSrgbLinear 会变 0.14）。
                val deep = deepShadowFactor
                uni3f("uDeepShadowColor", deep[0], deep[1], deep[2])
            } ?: return null

            // ★ P2 的最终输出：后续所有光效层（P2-c / EMIT / P2-b / LIGHTWRAP）都以它为输入。
            //   显式命名而不是到处写 texP2Tone，是因为顺序在 v0.27 调整过（P2-c 提前），
            //   一旦引用错位编译器会直接报 Unresolved reference，而不是悄悄画错层。
            val texP2 = texP2Tone

            // ---- ④-2 P2-c：阴影/背光层（**最先算**：EMIT / P2-b / LIGHTWRAP 都要用它的遮挡）----
            val texP2C = pool.texture(w, h) ?: return null
            val fboP2C = pool.fbo(texP2C) ?: return null
            draw(programs["p2c"]!!, fboP2C, w, h, pool) {
                bind("uPerson", texP2, 0)
                uni2f("uTexel", 1f / w, 1f / h)
                // ★ 投影轴：CPU 端一次算好并打包（字段与 GLSL uniform 一一对应，避免手写错位）
                val axis = ShadowShear.calculateShadowAxis(
                    anchorX = SHADOW_ANCHOR_X,
                    anchorY = SHADOW_ANCHOR_Y,
                    lightX = params.lightX,
                    lightY = params.lightY,
                )
                val su = axis.pack(
                    anchorX = SHADOW_ANCHOR_X,
                    anchorY = SHADOW_ANCHOR_Y,
                    reachPx = params.shadowOffsetPx * h / 1080f,
                    falloff = params.shadowFalloff,
                    blurBasePx = params.shadowBlurBasePx,
                    blurGrowPx = params.shadowBlurGrowPx,
                )
                uni2f("uShadowDir", su.dirX, su.dirY)
                uni2f("uAnchor", su.anchorX, su.anchorY)
                uni1f("uShear", su.shear)
                uni1f("uFalloff", su.falloff)
                uni1f("uBlurBasePx", su.blurBasePx)
                uni1f("uBlurGrowPx", su.blurGrowPx)
                uni1f("uBacklit", if (params.backlit) 1f else 0f)
                uni1f("uStrength", params.shadowStrength.coerceIn(0f, 1f))
                // 投影长度按 1080p 基准等比放大（阴影长度必须随画布尺寸一起变，否则 4K 下影子糊成一团）
                uni1f("uReachPx", (params.shadowOffsetPx * h / 1080f).coerceIn(8f, 200f))
                uni1f("uSoftness", params.shadowSoftness.coerceIn(0.2f, 3f))
                // 接触阴影：2~5px 的极窄带（按画布等比缩放）
                uni1f("uContactPx", (params.contactShadowPx * h / 1080f).coerceIn(1.5f, 8f))
                uni1f("uContactStrength", params.contactShadowStrength.coerceIn(0f, 1f))
                uni3f(
                    "uShadowColor",
                    shadowLinear(params.shadowColorRgb)[0],
                    shadowLinear(params.shadowColorRgb)[1],
                    shadowLinear(params.shadowColorRgb)[2],
                )
                uni3f(
                    "uContactColor",
                    shadowLinear(params.contactShadowColorRgb)[0],
                    shadowLinear(params.contactShadowColorRgb)[1],
                    shadowLinear(params.contactShadowColorRgb)[2],
                )
            } ?: return null

            // ---- ④-3 发光源提取 + 高斯金字塔（规格 1 / 2）----
            //   ① EMIT 在 1/2 域提取"边缘环带 × 主光色 × 迎光门控"；
            //   ② 逐级降采样做可分离高斯（大半径靠降分辨率买，不靠加大核）；
            //   ③ 上采样累加成多尺度光晕；最后在 composite **之后** Screen（见 FRAG_GLOW）。
            //
            //   人物层本身**不再内联泛光**：泛光必须落在合成之后的整幅画面上才能渗入背景空气。

            // 发光色：先整形（压亮到 235 以下 + 补足彩度），物理上边缘散射达不到光源白点
            val emitColor = EmissiveColor.sanitize(params.keyLightColorRgb, params.wrapAmbientColorRgb)
            val wantGlow = params.debugWrapMode != 0 || params.edgeGlowStrength > 0.001f
            val glowLevels = if (wantGlow) {
                GlowPyramid.plan(w, h, params.glowSpreadPx.coerceIn(4f, 96f))
            } else {
                emptyList()
            }
            // 规格要求"多次高斯"，至少两级；plan() 已保证，这里再兜一次（防 -X 参数被手改成 0）
            val drawGlow = glowLevels.size >= 2

            var texEmitOut: Int = 0
            var texGlowOut: Int = 0

            // ★ 整条发光链是**可选**的：任何一步失败只关掉"发光"，绝不终止整条管线。
            //   旧写法每一步都 `?: return null`，于是"金字塔某级纹理分配失败/某个 shader 在
            //   某驱动上出 GL 错"的后果是整帧降级 —— 用户看到的是"素材翻转 + 光影全没了"。
            //   抽成局部函数还有一个好处：里面的 `?: return null` 只退出本函数（局部返回），
            //   不会像原来那样穿透 `renderLayers` 直接把整帧扔掉。
            fun buildGlow(): Int? {
                val l0 = glowLevels[0]

                // ---------- ① 提取发光源 ----------
                val texEmit = pool.texture(l0.w, l0.h) ?: return null
                val fboEmit = pool.fbo(texEmit) ?: return null
                draw(programs["emit"]!!, fboEmit, l0.w, l0.h, pool) {
                    bind("uPerson", texP2, 0)
                    bind("uShadowMask", texP2C, 1)      // ★ 阴影熔断：投影覆盖处不产生发光源
                    uni2f("uTexel", 1f / w, 1f / h)
                    uni2f("uLightPos", params.lightX, params.lightY)
                    uni3f("uLightDir", params.lightDirX, params.lightDirY, params.lightDirZ)
                    uniSrgbLinear("uKeyLight", emitColor)
                    uniSrgbLinear("uAmbientLight", params.wrapAmbientColorRgb)
                    // 环带半径：来自 rimWidthPx（滑杆语义保留），按 1080p 基准等比缩放。
                    // 形态学梯度在 ±r 内都能检测到跨越，所以实际环带宽度 ≈ 2r。
                    uni1f("uBandPx", (params.rimWidthPx * h / 1080f).coerceIn(1f, 6f))
                    uni1f("uGain", params.edgeGlowStrength.coerceIn(0f, 2f))
                    uni1f("uShadowCut", params.wrapShadowCut.coerceIn(0f, 1f))
                    uni1i("uDebug", if (params.debugWrapMode == 7) 1 else 0)
                } ?: return null
                texEmitOut = texEmit

                // ---------- ② 逐级：水平模糊（同时降采样） → 垂直模糊 ----------
                //   H 的目标就是下一级尺寸，一次搞定降采样 + 半程高斯，省掉单独的 downsample pass。
                val chain = ArrayList<Int>(glowLevels.size)
                var srcTex = texEmit
                var srcW = l0.w
                var srcH = l0.h
                glowLevels.forEachIndexed { idx, lv ->
                    // 水平：源 texel 采样，写到本级尺寸（idx=0 时是同尺寸 1:1）
                    val texH = pool.texture(lv.w, lv.h) ?: return null
                    val fboH = pool.fbo(texH) ?: return null
                    draw(programs["gauss"]!!, fboH, lv.w, lv.h, pool) {
                        bind("uTex", srcTex, 0)
                        uni2f("uTexel", 1f / srcW, 1f / srcH)
                        uni2f("uDir", 1f, 0f)
                        uni1f("uSigma", lv.sigma)
                    } ?: return null

                    // 垂直：本级 texel
                    val texV = pool.texture(lv.w, lv.h) ?: return null
                    val fboV = pool.fbo(texV) ?: return null
                    draw(programs["gauss"]!!, fboV, lv.w, lv.h, pool) {
                        bind("uTex", texH, 0)
                        uni2f("uTexel", 1f / lv.w, 1f / lv.h)
                        uni2f("uDir", 0f, 1f)
                        uni1f("uSigma", lv.sigma)
                    } ?: return null

                    chain += texV
                    srcTex = texV
                    srcW = lv.w
                    srcH = lv.h
                }
                if (chain.isEmpty()) return null

                // ---------- ③ 上采样累加：从最粗一级往回流 ----------
                //   每级权重：越粗的光晕贡献越低（否则整张图被大范围灰雾抬亮）。
                var accTex = chain.last()
                for (i in chain.size - 2 downTo 0) {
                    val hi = glowLevels[i]
                    val lo = glowLevels[i + 1]
                    val texUp = pool.texture(hi.w, hi.h) ?: return null
                    val fboUp = pool.fbo(texUp) ?: return null
                    draw(programs["bloomUp"]!!, fboUp, hi.w, hi.h, pool) {
                        bind("uLow", accTex, 0)
                        bind("uHigh", chain[i], 1)
                        uni2f("uLowTexel", 1f / lo.w, 1f / lo.h)
                        // 0.85：实测 0.72 时最粗两级贡献过小，光晕"有形状但没亮度"
                        uni1f("uWeight", 0.85f)
                    } ?: return null
                    accTex = texUp
                }
                return accTex
            }

            if (drawGlow) {
                texGlowOut = buildGlow() ?: 0
                if (texGlowOut == 0) Log.w(TAG, "发光链（EMIT/金字塔）失败：本帧关闭发光，其余光效照常")
            }
            if (texEmitOut == 0) texEmitOut = black1x1(pool) ?: return null
            if (texGlowOut == 0) texGlowOut = black1x1(pool) ?: return null

            // ---- ④-4 P2-b：立体反光层（用**已调色**的人物，否则反光与主体肤色脱节）----
            //   ★ 可选层：失败只是"这一层没有"，不能让整帧降级
            val texP2BRaw = pool.texture(w, h)
            var p2bOk = false
            if (texP2BRaw != null) {
                val fboP2B = pool.fbo(texP2BRaw)
                if (fboP2B != null) {
                    p2bOk = draw(programs["p2b"]!!, fboP2B, w, h, pool) {
                bind("uPerson", texP2, 0)
                bind("uShadowMask", texP2C, 1)      // ★ 阴影熔断
                uni2f("uTexel", 1f / w, 1f / h)
                uni2f("uLightPos", params.lightX, params.lightY)
                uni3f("uLightDir", params.lightDirX, params.lightDirY, params.lightDirZ)
                uni1f("uBokehRadius", 6f)
                uni1f("uReflectStrength", params.wrapStrength * 0.8f)
                uni1f("uShadowCut", params.wrapShadowCut.coerceIn(0f, 1f))
                    } != null
                }
            }
            if (!p2bOk) Log.w(TAG, "P2-b 立体反光失败，本帧跳过该层")
            // 失败时必须换成黑纹理：刚分配的纹理内容未定义，直接 Screen 进来会是彩色噪声
            val texP2B = if (p2bOk) texP2BRaw!! else black1x1(pool) ?: return null

            // ---- ⑥ LIGHTWRAP：光包裹「向内渗透」（严格限制在人物内部） ----
            //   强度为 0 时用 1×1 黑纹理占位：Final 的 Screen 对 0 是恒等，省掉一整个全分辨率 pass。
            val texWrapIn: Int
            // 调试模式必须强制跑这一 pass，否则看不到通道 1~6
            if (params.lightWrapStrength > 0.001f || params.debugWrapMode in 1..6) {
                val t = pool.texture(w, h)
                val f = t?.let { pool.fbo(it) }
                // ★ 可选层：失败退化为"没有向内包裹"，而不是整帧降级
                val ok = if (t != null && f != null) draw(programs["lightwrap"]!!, f, w, h, pool) {
                    bind("uPerson", texP2, 0)
                    bind("uBgBlur", texBgBlur, 1)
                    bind("uShadowMask", texP2C, 2)      // 阴影遮挡剔除
                    uni2f("uTexel", 1f / w, 1f / h)
                    uni2f("uLightPos", params.lightX, params.lightY)
                    uni3f("uLightDir", params.lightDirX, params.lightDirY, params.lightDirZ)
                    // 与发光源同一套整形色（保证"迎光金边"和"向内包裹"是同一个光源）
                    uniSrgbLinear("uKeyLight", emitColor)
                    uniSrgbLinear("uAmbientLight", params.wrapAmbientColorRgb)
                    uni1f("uColorMix", params.wrapColorMix.coerceIn(0f, 1f))
                    uni1f("uHeightStart", params.wrapHeightStart.coerceIn(0.3f, 0.99f))
                    uni1f("uHeightEnd", params.wrapHeightEnd.coerceIn(0.5f, 1f))
                    uni1f("uShadowOccl", params.wrapShadowOcclusion.coerceIn(0f, 1f))
                    // ★ 熔断：背光权重 与 投影遮罩 取最大 → 主光归零，只留环境散射地板
                    uni1f("uShadowCut", params.wrapShadowCut.coerceIn(0f, 1f))
                    uni1f("uAmbientFloor", params.wrapAmbientFloor.coerceIn(0f, 0.6f))
                    uni1f("uAmbientLift", params.wrapAmbientLift.coerceIn(0f, 0.6f))
                    uni1f("uWrapStrength", params.lightWrapStrength.coerceIn(0f, 1f))
                    // ★ 向内渗透深度：5~10px（规格），随画布等比缩放
                    uni1f("uWrapPx", (params.wrapRadiusPx * h / 1080f).coerceIn(3f, 22f))
                    uni1f("uMaxLuma", params.wrapMaxLuma.coerceIn(0.1f, 0.6f))
                    uni1i("uDebug", params.debugWrapMode)
                } != null else false
                if (ok) {
                    texWrapIn = t!!
                } else {
                    Log.w(TAG, "LIGHTWRAP 失败，本帧跳过向内包裹")
                    texWrapIn = black1x1(pool) ?: return null
                }
            } else {
                texWrapIn = black1x1(pool) ?: return null
            }


            // ---- ⑦ 图层合成（线性空间，不含 FXAA） ----
            val texComp = pool.texture(w, h) ?: return null
            val fboComp = pool.fbo(texComp) ?: return null
            draw(programs["composite"]!!, fboComp, w, h, pool) {
                bind("uP1", texP1, 0)
                bind("uP2", texP2, 1)
                bind("uP2B", texP2B, 2)
                bind("uP2C", texP2C, 3)
                bind("uWrapIn", texWrapIn, 4)
                bind("uEmitTex", texEmitOut, 5)
                bind("uBloomTex", texGlowOut, 6)
                uni2f("uTexel", 1f / w, 1f / h)
                // 动态光向：光源换位置，方向性羽化跟着换
                uni2f("uLightPos", params.lightX, params.lightY)
                uni3f("uLightDir", params.lightDirX, params.lightDirY, params.lightDirZ)
                uni1f("uBleedPxMin", (params.bleedPxMin * h / 1080f).coerceIn(4f, 24f))
                uni1f("uBleedPxMax", (params.bleedPxMax * h / 1080f).coerceIn(6f, 40f))
                uni1f("uBleedStrength", params.bleedStrength.coerceIn(0f, 1f))
                uniSrgbLinear("uAmbientColor", params.ambientColorRgb)
                uni1f("uAmbientTint", params.ambientTint.coerceIn(0f, 0.3f))
                uni1i("uDebug", params.debugWrapMode)
            } ?: return null

            // ---- ⑦-2 GLOW：把金字塔光晕 Screen 到**已完成的画面**上 ----
            //   必须在 composite 之后：lightWrap 只能写进人物像素，泛光要能渗进背景空气。
            //   debug > 0 时整条链只输出中间量，不能再叠一遍。
            var compTex = texComp
            var compFbo = fboComp
            if (params.debugWrapMode == 0 && params.edgeGlowStrength > 0.001f && drawGlow) {
                val t = pool.texture(w, h)
                val f = t?.let { pool.fbo(it) }
                // ★ 可选：失败保留未加泛光的合成图（光效少一层，不是整帧失败）
                val ok = if (t != null && f != null) draw(programs["glow"]!!, f, w, h, pool) {
                    bind("uBase", texComp, 0)
                    bind("uBloom", texGlowOut, 1)
                    bind("uMask", texP2, 2)
                    uni1f("uStrength", params.glowIntensity.coerceIn(0f, 3f))
                    uni1f("uAirRatio", params.glowAirRatio.coerceIn(0f, 1f))
                    // 单次注入上限 0.85：极端参数下也不会把整片推成死白
                    uni1f("uClamp", 0.85f)
                } != null else false
                if (ok) {
                    compTex = t!!
                    compFbo = f!!
                } else {
                    Log.w(TAG, "GLOW 失败，本帧跳过泛光（保留未泛光的合成图）")
                }
            }

            // ---- ⑧ 全局后处理链：DOF → 调色 → Looks → 暗角 → FXAA ----
            //   ★ 两个全分辨率纹理**乒乓复用**：5 个 pass 只占 2 张纹理（不是 5 张）。
            //     2048² RGBA8 一张 16MB，若每 pass 一张会多吃 48MB，中端机直接 OOM。
            val texA = pool.texture(w, h) ?: return null
            val fboA = pool.fbo(texA) ?: return null
            var curTex = compTex
            var curFbo = compFbo
            var altTex = texA
            var altFbo = fboA

            fun postPass(name: String, setup: () -> Unit) {
                val prog = programs[name] ?: return
                val ok = draw(prog, altFbo, w, h, pool) {
                    bind("uSrc", curTex, 0)
                    setup()
                } != null
                if (!ok) {
                    // ★ 失败时**绝对不能交换**：altTex 里是没写完/未初始化的像素，
                    //   换过去之后后面每个 pass 都在读脏数据，最终图会变成残影或纯色块，
                    //   而且日志里只有一条 GL 错误。保持 curTex 继续跑，最多少一个效果。
                    Log.w(TAG, "后处理 '$name' 失败，跳过该效果并沿用上一 pass 结果")
                    return
                }
                // 交换：刚写好的成为当前输入
                val t = curTex; curTex = altTex; altTex = t
                val f = curFbo; curFbo = altFbo; altFbo = f
            }

            val aspect = floatArrayOf(w.toFloat() / h.toFloat(), 1f)

            // ① 景深：0 = 关闭（不跑 pass，直接让下一节点读上一节点的纹理）
            if (params.debugWrapMode == 0 && params.dofRadiusPx > 0.5f) {
                postPass("dof") {
                    bind("uMask", texP2, 1)
                    uni2f("uTexel", 1f / w, 1f / h)
                    // 半径按画布等比缩放，但上限死守 5px —— 这是"背景糊成色块"的直接原因
                    uni1f("uMaxRadiusPx", (params.dofRadiusPx * h / 1080f).coerceIn(1f, 5f))
                    uni1f("uRingPx", (params.dofRingPx * h / 1080f).coerceIn(8f, 40f))
                }
            }

            // ② 全局调色：单位矩阵 + 默认对比度/饱和度 + 无全局压暗时整段跳过
            val gradeNoop = params.gradeContrast == 1f && params.gradeSaturation == 1f &&
                params.globalMultiply >= 0.999f &&
                params.gradeMatrix.contentEquals(GlesFusionRenderer.Params.UNIT_3X3)
            if (params.debugWrapMode == 0 && !gradeNoop) {
                postPass("grade") {
                    uniMat3("uMatrix", params.gradeMatrix)
                    uni1f("uContrast", params.gradeContrast.coerceIn(0.5f, 2f))
                    uni1f("uSaturation", params.gradeSaturation.coerceIn(0f, 3f))
                    uni1f("uGlobalMultiply", params.globalMultiply.coerceIn(0.5f, 1f))
                }
            }

            // ③ Looks 漏光
            if (params.debugWrapMode == 0 && params.looksStrength > 0.001f) {
                val lc = params.looksColor
                postPass("looks") {
                    uni2f("uLeakPos", params.looksPosX.coerceIn(0f, 1f), params.looksPosY.coerceIn(0f, 1f))
                    uni2f("uAspect", aspect[0], aspect[1])
                    uni3f(
                        "uLeakColor",
                        Math.pow(((lc shr 16) and 0xFF) / 255.0, 2.2).toFloat(),
                        Math.pow(((lc shr 8) and 0xFF) / 255.0, 2.2).toFloat(),
                        Math.pow((lc and 0xFF) / 255.0, 2.2).toFloat(),
                    )
                    uni1f("uStrength", params.looksStrength.coerceIn(0f, 1f))
                    uni1f("uPhase", params.looksPhase)
                }
            }

            // ④ 暗角
            if (params.debugWrapMode == 0 && params.vignetteStrength > 0.001f) {
                postPass("vignette") {
                    uni2f("uAspect", aspect[0], aspect[1])
                    uni1f("uRadius", params.vignetteRadius.coerceIn(0.3f, 1.2f))
                    uni1f("uSoftness", params.vignetteSoftness.coerceIn(0.05f, 0.8f))
                    uni1f("uStrength", params.vignetteStrength.coerceIn(0f, 1f))
                }
            }

            // ⑤ FXAA + sRGB：永远是最后一棒
            postPass("fxaa") {
                uni2f("uTexel", 1f / w, 1f / h)
                uni1f("uToneMap", params.toneMapStrength.coerceIn(0f, 1f))
                uni1f("uExposure", params.toneMapExposure.coerceIn(0.5f, 2.5f))
                uni1f("uToneKnee", params.toneMapKnee.coerceIn(0f, 0.95f))
                uni1i("uDebug", params.debugWrapMode)
            }

            return readPixels(curFbo, w, h)

        } finally {
            programs.values.forEach { runCatching { GLES30.glDeleteProgram(it) } }
        }
    }

    // ================================================================ FBO 池

    /** FBO/纹理统一登记与销毁（防泄漏/OOM）。 */
    private class FboPool {
        private val textures = ArrayList<Int>(10)
        private val fbos = ArrayList<Int>(10)

        /**
         * 已分配纹理的总字节数 / 峰值。
         *
         * 为什么要记账：整条管线同时持有 9 张全分辨率 RGBA8（2048² 时约 150MB），
         * 一旦某张分配失败，旧代码会让整帧降级到 legacy/CPU。没有这个数字，
         * 现场只能看到"效果突然没了"，无法判断是不是显存。
         */
        var bytes: Long = 0L
            private set
        var peakBytes: Long = 0L
            private set

        fun texture(w: Int, h: Int): Int? {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            if (t[0] == 0) return null
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                GLES30.glDeleteTextures(1, t, 0)
                Log.w(TAG, "纹理创建失败 ${w}x$h（可能 OOM），降级")
                return null
            }
            textures += t[0]
            bytes += w.toLong() * h * 4
            if (bytes > peakBytes) peakBytes = bytes
            return t[0]
        }

        fun fbo(tex: Int): Int? {
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            if (f[0] == 0) return null
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0,
            )
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glDeleteFramebuffers(1, f, 0)
                Log.w(TAG, "FBO 不完整，降级")
                return null
            }
            fbos += f[0]
            return f[0]
        }

        fun releaseAll() {
            if (peakBytes > 32L * 1024 * 1024) {
                Log.i(
                    TAG,
                    "FBO 池：峰值 ${peakBytes / 1024 / 1024}MB / ${textures.size} 张（" +
                        "全分辨率 ${peakBytes / (1024 * 1024)}MB 量级说明同时持有多层全图纹理）",
                )
            }
            if (fbos.isNotEmpty()) {
                GLES30.glDeleteFramebuffers(fbos.size, fbos.toIntArray(), 0)
                fbos.clear()
            }
            if (textures.isNotEmpty()) {
                GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
                textures.clear()
            }
            bytes = 0L
        }
    }

    // ================================================================ 工具

    private var currentProgram = 0

    private fun draw(program: Int, fbo: Int, w: Int, h: Int, pool: FboPool, setup: () -> Unit): Unit? {
        currentProgram = program
        GLES30.glUseProgram(program)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        // 每个 pass 前强制 2D 状态基线（无深度/剔除/混合/裁剪）：
        // 全屏 quad 是顺时针，一旦被背面剔除，quad 整体消失且 glGetError 不报错。
        GlState.ensure2dPass()
        setup()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val err = GLES30.glGetError()
        return if (err == GLES30.GL_NO_ERROR) Unit else {
            Log.w(TAG, "GL 错误 0x${Integer.toHexString(err)}，降级")
            null
        }
    }

    private fun bind(name: String, tex: Int, unit: Int) {
        val loc = GLES30.glGetUniformLocation(currentProgram, name)
        if (loc < 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(loc, unit)
    }

    private fun uloc(name: String) = GLES30.glGetUniformLocation(currentProgram, name)
    private fun uni1f(name: String, v: Float) = GLES30.glUniform1f(uloc(name), v)

    /** int uniform：采样器计数/调试通道这类必须用 glUniform1i，用 1f 传会被驱动忽略。 */
    private fun uni1i(name: String, v: Int) = GLES30.glUniform1i(uloc(name), v)
    private fun uni2f(name: String, x: Float, y: Float) = GLES30.glUniform2f(uloc(name), x, y)
    private fun uni3f(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(uloc(name), x, y, z)

    /** sRGB 0xAARRGGBB → 线性空间 vec3 uniform。 */
    private fun uniSrgbLinear(name: String, rgb: Int) {
        fun ch(shift: Int) = Math.pow(((rgb shr shift) and 0xFF) / 255.0, 2.2).toFloat()
        uni3f(name, ch(16), ch(8), ch(0))
    }

    /** 3×3 行优先矩阵 → mat3（GL 按列读取，这里转置传入）。 */
    private fun uniMat3(name: String, m: FloatArray) {
        val loc = uloc(name)
        if (loc < 0) return
        val colMajor = floatArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        )
        GLES30.glUniformMatrix3fv(loc, 1, false, colMajor, 0)
    }

    private fun buildProgram(fragSrc: String): Int? = try {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.w(TAG, "program link 失败：${GLES30.glGetProgramInfoLog(prog)}")
            null
        } else prog
    } catch (t: Throwable) {
        Log.w(TAG, "shader 构建异常：${t.message}")
        null
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader 编译失败：${GLES30.glGetShaderInfoLog(s)}")
        return s
    }

    private fun upload(bitmap: Bitmap, pool: FboPool): Int? {
        val tmp = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        tmp.setPremultiplied(false)
        val tex = pool.texture(bitmap.width, bitmap.height) ?: run { tmp.recycle(); return null }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, tmp, 0)
        val err = GLES30.glGetError()
        tmp.recycle()
        return if (err == GLES30.GL_NO_ERROR) tex else null
    }

    private fun queryMaxTextureSize(): Int = try {
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        v[0]
    } catch (t: Throwable) {
        0
    }

    private fun sanitize(src: Bitmap, limit: Int): Bitmap? {
        if (src.isRecycled) return null
        val longSide = maxOf(src.width, src.height)
        if (longSide <= limit) return src
        val s = limit.toFloat() / longSide
        return try {
            Bitmap.createScaledBitmap(
                src,
                (src.width * s).toInt().coerceAtLeast(1),
                (src.height * s).toInt().coerceAtLeast(1),
                true,
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 1×1 全黑纹理（关闭某 pass 时的占位输入，比留空绑定更安全）。
     * Screen 混合对 0 是恒等，所以 Final 里不需要额外分支。
     */
    private fun black1x1(pool: FboPool): Int? {
        val t = pool.texture(1, 1) ?: return null
        val f = pool.fbo(t) ?: return null
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
        GLES30.glViewport(0, 0, 1, 1)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        return t
    }

    /**
     * 回读 FBO 像素 —— 统一走 [GlReadback]。
     *
     * 本文件曾经自带一份"不翻转"的实现并写了推导注释（推导是对的），但另外两个渲染器
     * （`GlesFusionRenderer` / `RenderGraphExecutor`）各自翻转了一次。三份实现里
     * **只要有一份方向不同，降级切换时画面就会上下颠倒** —— 而"降到哪条路径"是由
     * 一个 `GL` 错误决定的，测试里永远不会同时覆盖。
     * 现在方向只剩一处：`GlReadback` 首次调用时用 2×2 图案实测，并缓存结论。
     *
     * ⚠️ 必须**显式传入** [fbo]：旧版依赖"上一 pass 恰好还绑着正确 FBO"，
     * 而 `postPass` 里的乒乓交换一旦中途跳过，绑定与预期就会错位（读到另一张纹理）。
     */
    private fun readPixels(fbo: Int, w: Int, h: Int): Bitmap? =
        com.naiwa.core.common.render.GlReadback.read(fbo, w, h)

    private fun shadowLinear(rgb: Int): FloatArray = floatArrayOf(
        Math.pow(((rgb shr 16) and 0xFF) / 255.0, 2.2).toFloat(),
        Math.pow(((rgb shr 8) and 0xFF) / 255.0, 2.2).toFloat(),
        Math.pow((rgb and 0xFF) / 255.0, 2.2).toFloat(),
    )

    /** 绿幕参考色度（前景边缘带中位）。 */
    private fun sampleKeyChroma(bitmap: Bitmap): Pair<Float, Float> {
        val total = bitmap.width * bitmap.height
        val step = (total / 1024).coerceAtLeast(1)
        val px = IntArray(total)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val cbs = ArrayList<Float>(1024)
        val crs = ArrayList<Float>(1024)
        var i = 0
        while (i < total) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            if (l > 0.15f) {
                cbs += -0.169f * r - 0.331f * g + 0.5f * b + 0.5f
                crs += 0.5f * r - 0.419f * g - 0.081f * b + 0.5f
            }
            i += step
        }
        if (cbs.isEmpty()) return 0.5f to 0.5f
        return cbs.sorted()[cbs.size / 2] to crs.sorted()[crs.size / 2]
    }

    // ================================================================ EGL

    private class EglEnv(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface,
    ) {
        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }
        }

        fun release() {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private fun createEgl(w: Int, h: Int): EglEnv? {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, 0x0040,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) return null
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            EglEnv(display, context, surface)
        } catch (t: Throwable) {
            Log.w(TAG, "EGL 初始化异常：${t.message}")
            null
        }
    }
}
