# 逆光暗部压暗 与 阴影边缘漏光熔断（v0.27）

> 对应 `FRAG_P2` 的 ⑥、`FRAG_EMIT` 的 ②b、`FRAG_LIGHTWRAP` 的 ⑤b、`FRAG_P2B` 的熔断，
> 以及规则层 `core-common/model/BacklitShade.kt`。
> 离线对照图：`docs/backlit-cutoff-sim.png`（`tools/sim_backlit_cutoff.py` 生成）。

---

## 一、逆光暗部不够暗

### 1.1 规格给的判据直接用会画错

规格要求「提取背光面（`dot(N, L) < 0` 的区域）压暗」。问题在于：**二次元平涂人物没有表面法向**。

alpha 梯度只在**轮廓**上有值，人物内部恒为 0。所以能拿到的"法向"只有两种：

| 候选 | 在哪有值 | 用它做逆光压暗的后果 |
| --- | --- | --- |
| alpha 梯度法向 | 只有轮廓 2~3px | 内部完全没有法向 → 整块平涂不参与压暗 |
| 径向伪法向（`uv - center`） | 全图 | 等值线是**径向**的 → 画面上被切出一条生硬的斜分界线 |

第二种是 v0.19 用过的写法。这次先用它实现了一版，离线对照图里立刻暴露出问题：
人物被"左上暗、右下亮"的斜线切开，完全不像剪影。

### 1.2 正确的物理判断

逆光的本质是**人物面朝相机、背着光源**。所以：

> **人物整个正面都是背光面，只有迎光侧的窄轮廓会被勾出金边。**

据此，判据不该是"法向朝哪"，而是"**这个像素在不在迎光轮廓上**"：

```glsl
float bGx = aAt(uv + vec2(uTexel.x, 0.0)) - aAt(uv - vec2(uTexel.x, 0.0));
float bGy = aAt(uv + vec2(0.0, uTexel.y)) - aAt(uv - vec2(0.0, uTexel.y));
float bLen = length(vec2(bGx, bGy));
float gAmp = smoothstep(0.0, 0.30, bLen);                    // 轮廓带 → 1，平涂内部 → 0
vec2 bN = bLen > 1e-4 ? -vec2(bGx, bGy) / bLen : vec2(0.0);  // 外法向（梯度指内，取负）
float faceEdge = ll > 0.15 ? dot(bN, l2 / ll) : 0.0;
float edgeFront = gAmp * smoothstep(0.0, 0.35, faceEdge);     // 迎光侧轮廓

// 内部恒为背光；叠 25% 的径向变化只为留一点体积，避免完全平掉的剪影
float backW = clamp((1.0 - edgeFront) * (0.75 + 0.25 * (1.0 - smoothstep(-0.2, 0.2, lw))),
                    0.0, 1.0);
c *= uBacklitGlobal + (uBacklitDarken - uBacklitGlobal) * backW;
```

- `backW = 1`（平涂内部 / 背光侧）→ 系数 **0.32**（规格"亮度强制乘以 0.3"）
- `backW = 0`（迎光轮廓）→ 系数 **0.82**，金黄轮廓光得以保留
- 那 25% 的径向分量只是给剪影留一点点体积，不承担主判据

### 1.3 暗部不能是纯黑死灰

```glsl
vec3 tinted = c * shadowChroma;                        // shadowChroma = 背景暗部色 / 其亮度
float lT = dot(tinted, LUMA);
tinted *= clamp(dot(c, LUMA) / max(lT, 1e-4), 0.5, 2.0);   // ★ 亮度还原
float tintW = backW * (1.0 - smoothstep(0.0, 0.35, dot(c, LUMA))) * uBacklitChroma;
c = mix(c, tinted, clamp(tintW, 0.0, 1.0));
```

两个关键点：

1. **必须做亮度还原**。直接 `c * chroma` 会顺手改变亮度 —— 染色越猛，暗部越亮，
   最后得到一片"发灰的蓝"。还原后 `tinted` 与 `c` 的 luma 相等，染色只动色相。
   离线实测：色度 +0.0284，**亮度只变 +0.0001**。
2. **只染 `luma < 0.35` 的像素**。中间调也染会让整个人物像隔着一层蓝滤镜。

`shadowChroma` 来自 `uShadowColor`（背景暗部中位色），所以夜景偏蓝紫、黄昏偏暖褐，
跟着背景走而不是硬编码。

### 1.4 关于 `pow(backlitColor, vec3(1.5))`

规格提到「使用 `pow(color, 1.5)` 或乘以 0.3」。本实现选了**乘系数**，原因：

- 本管线在**线性空间**工作。`pow(x, 1.5)` 对 0.1 的像素只压到 0.032，对 0.8 的压到 0.72
  —— 它是**改 gamma（拉对比）**，不是"整体压暗"，会把中间调压塌、暗部反而更黑；
- 而"背光面压到 0.3"是一个**线性缩放**，乘 0.32 就是它。

两者量纲不同，不能混用。真要 `pow` 也应该在 sRGB 空间做（那是它的直觉来源）。

---

## 二、阴影边缘漏光

### 2.1 根因：伪法线在阴影处不可信

漏光不是因为门控写错了，而是**门控的输入不可信**：

投影与人物轮廓交界处，alpha 梯度几乎为 0（都是实心或被遮罩外的背景），
`N·L` 于是落在 0 附近 → `smoothstep(0.0, 0.35, N·L)` 给一个小的正值 →
"略微迎光" → 光包裹漏出一圈。

**任何基于法向的门控在这里都不可靠。** 必须补一条与法向无关的判据。

### 2.2 双判据取最大

```glsl
float backW = 1.0 - smoothstep(-0.2, 0.2, facing);            // 看 N·L
float shadowAmt = clamp(1.0 - texture(uShadowMask, uv).luma, 0.0, 1.0);  // 看投影遮罩
float occ = max(backW, shadowAmt);                             // 任意一条成立就熔断
float keep = 1.0 - occ * clamp(uShadowCut, 0.0, 1.0);
```

`uShadowMask` 是 P2-c 的输出（1 = 无遮挡），它**完全不含法向信息**，
因此不会跟着伪法线一起错。这就是"终极阻断"的实现。

### 2.3 熔断后保留环境散射地板

```glsl
float occl = mix(uAmbientFloor, 1.0, keep);   // 默认 0.15
```

规格写的是「包裹光直接熔断归零」。这里让**主光归零**，但保留 15% 的环境散射，
理由是物理上背光侧确实存在环境漫反射；给 0 会让暗部变成死黑，
与"人物融进背景"没有区别。

想要绝对熔断：`wrapAmbientFloor = 0`（`Params` 里一个字段，无需改 shader）。

### 2.4 三个漏光点全部切断

| Pass | 熔断位置 | 效果 |
| --- | --- | --- |
| `FRAG_EMIT` | `lit *= 1.0 - shadowAmt * uShadowCut` | 阴影里**不产生发光源**（连泛光都不会有） |
| `FRAG_LIGHTWRAP` | `occl = mix(floor, 1, keep)` | 向内包裹在阴影处主光归零 |
| `FRAG_P2B` | `facing *= 1.0 - shadowAmt * uShadowCut` | 立体反光同样不出现 |

`FRAG_EMIT` 那一条尤其重要：泛光是**从发光源扩散出去的**，如果源没掐掉，
高斯金字塔会把阴影里的光晕推到几十像素外，看起来像"影子在发光"。

### 2.5 附带修的三个 bug（P2-b）

为了让它能读遮罩，检查这段时发现三个叠加的错误：

```glsl
// 修复前
vec2 n = normalize(vec2(aR - aL, aD - aU) + vec2(1e-5));
float facing = max(0.0, dot(vec3(n, 0.0), uLightDir));

// 修复后
vec2 g = vec2(aR - aL, aD - aU);
float gl = length(g);
vec2 nOut = gl > 1e-4 ? -g / max(gl, 1e-6) : vec2(0.0);
vec3 L = normalize(uLightDir + vec3(1e-5));
float facing = max(0.0, dot(vec3(nOut, 0.0), L));
```

1. **法向没取负**：alpha 梯度指向内部，`+grad` 是内法向 → `dot(n, L) > 0` 意味着"背光"。
   所以从 v0.20 起，立体反光一直只在**背光侧**出现，与设计完全相反
   （与 v0.6 记录过的 RimLight 法向取反是同一个 bug，换了个地方又犯）。
2. **`+ vec2(1e-5)` 伪造 45° 法线**：零梯度处得到固定的 `(0.707, 0.707)`，
   让平涂区的 facing 变成常数。P2 里早就修过，P2-b 漏了。
3. **`uLightDir` 没 normalize**：estimate 出来的向量不保证单位长度，点积结果不可控。

---

## 三、Pass 顺序调整（必须）

EMIT 与 P2-b 现在都要读 P2-c 的遮挡，所以 **P2-c 必须最先画**：

```
改前：P2 → EMIT+金字塔 → P2-b → P2-c → LIGHTWRAP → COMPOSITE → GLOW
改后：P2 → P2-c → EMIT+金字塔 → P2-b → LIGHTWRAP → COMPOSITE → GLOW
```

顺序错了**不会编译失败**，只会读到上一帧或空白纹理 —— 表现是"熔断看起来没生效"。
`ShaderShadowCutoffContractTest` 用源码索引位置把这条钉死。

---

## 四、规格伪代码里的 UB

规格给的是：

```glsl
float shadowFactor = smoothstep(0.2, -0.2, dot(N, L));
```

**`edge0 >= edge1` 在 GLSL 里是未定义行为**（规范原文：results are undefined if
`edge0 >= edge1`）。常见后果是返回 0 —— 于是压暗和熔断**整段静默失效**，
而代码看起来完全正常。

本项目的等价合法写法（也是唯一允许的写法）：

```glsl
float shadowFactor = 1.0 - smoothstep(-0.2, 0.2, dot(N, L));
```

在 `x = 0` 处两种写法都应是 0.5，这是 `BacklitShadeTest` 的断言之一。
另外 `ShaderShadowCutoffContractTest` 会**扫描全部 shader**，
把任何 `smoothstep(字面量, 字面量, ...)` 里 `e0 >= e1` 的情况报出来
（这是本项目第三次踩这个坑，前两次是 v0.20 与 v0.26）。

---

## 五、实测数据（`tools/sim_backlit_cutoff.py`）

人物实心区（`alpha > 0.9`）平均**线性**亮度：

| 组合 | 平均亮度 | 说明 |
| --- | --- | --- |
| 旧（无压暗、无熔断） | 0.4828 | 正面发灰、轮廓一圈白边 |
| 只压暗 | 0.2497 | 暗了，但阴影边缘仍漏光 |
| 只熔断 | 0.4713 | 漏光没了，但正面依然亮 |
| **新版（两者）** | **0.2330** | 压暗比 **0.482**，漏光消除 |

染色的独立贡献：色度 **+0.0284**，亮度 **+0.0001**（证明"只改色相不改亮度"）。

对照图六格：mask+投影 / 阴影因子 / 旧版 / 只压暗 / 只熔断 / 新版。
第 5 格（只熔断）最能说明问题——轮廓干净了，但人物还是亮的，
证明两件事**彼此独立、都必须做**。

---

## 六、调参

| 想要 | 改什么 | 位置 |
| --- | --- | --- |
| 暗部更暗 | `backlitDarken` 0.32 → 0.22 | `BacklitShade.DARKEN_BACK` |
| 迎光轮廓弱一点 | `backlitGlobalDarken` 0.82 → 0.70 | `BacklitShade.DARKEN_GLOBAL` |
| 暗部更冷/更暖 | 无需改这里，换背景暗部色即可 | `uShadowColor`（P2-c / 场景统计） |
| 染色更明显 | `backlitChroma` 0.65 → 0.85 | `BacklitShade.CHROMA_WEIGHT` |
| 熔断绝对化 | `wrapAmbientFloor` 0.15 → 0 | `BacklitShade.AMBIENT_FLOOR` |
| 熔断只切一半 | `wrapShadowCut` 1.0 → 0.6 | `BacklitShade.CUT_STRENGTH` |

`backlit = false` 时压暗段整段不执行（`uBacklitMode = 0`），**零成本**。

---

## 七、已知局限

1. **迎光轮廓的判定依赖抠图边缘质量**。发丝/半透明区域 `gAmp` 偏小，会被当成内部压暗，
   结果是"发丝一起变黑"。这在剪影里通常正确（发丝本来就是剪影），
   但如果素材是透光纱质，需要靠 `edgeGlowStrength` 把轮廓光补回来。
2. **`uShadowMask` 只有 P2-c 一个来源**。如果投影本身算错（比如光源高度角给错），
   熔断会跟着错——它是"遮罩的下游"，不负责修遮罩。
3. **`backW` 里那 25% 径向分量**是为了留体积。人物严重偏离画面中心时会略微偏斜；
   想绝对均匀（纯剪影）把它降到 0，代价是完全平的暗部。
