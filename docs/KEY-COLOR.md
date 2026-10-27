# 主光锁定与环境去荧光（v0.31）

> 涉及 `FRAG_EMIT` ③、`FRAG_LIGHTWRAP` ④、`LocalLightSample`、`AmbientSaturation`。
> 离线对照：`docs/key-color-sim.png`（`tools/sim_key_color.py` 生成）。

---

## 一、故障：暖光在蓝背景里变成了蓝光

现象是"光包裹发出蓝色荧光"。直觉判断是"环境光权重太高"，但把权重整体调低并不能修好 ——
因为在迎光面**环境光根本没有归零**：

```glsl
// 修复前
vec3 col = mix(uAmbientLight * 0.45, uKeyLight, smoothstep(-0.1, 0.55, facing));
```

`facing` 在轮廓侧面/半影区落在 0 附近，`smoothstep(-0.1, 0.55, 0) = 0.13` ——
**87% 是环境色**。而 EMIT 的环带恰恰大量分布在 `facing ∈ [0, 0.5]` 这段区间，
所以整条边缘都泛蓝。

三个独立的污染源（都修了）：

| # | 位置 | 问题 |
| --- | --- | --- |
| 1 | `FRAG_EMIT` ③ | 迎光面环境色占 87% |
| 2 | `FRAG_LIGHTWRAP` ④ | `mix(bg, duo, 0.75)` 固定混 25% **蓝色背景模糊** |
| 3 | `LocalLightSample` | 取邻域**整圈平均**，暖光被紧邻的冷背景稀释成"蓝白" |

---

## 二、修法一：权重曲线在迎光面严格归零

```glsl
// core-common/model/LightMix.kt 与 shader 内同一套分段
float ambientWeight(float l) {
    float mid  = 0.30 * (1.0 - smoothstep(0.35, 1.0, l));   // 迎光 → 过渡：0 → 0.30
    float deep = 1.0 - smoothstep(0.0, 0.35, l);            // 过渡 → 背光：0.30 → 1.0
    return clamp(max(mid, deep), 0.0, 1.0);
}
```

| litRatio | 环境权重 | 场景 |
| --- | --- | --- |
| 1.0 | **0** | 主光照射区，100% 主光色 |
| 0.35 | 0.30 | 过渡中段（上限） |
| 0 | 1.0 | 纯背光，环境漫反射主导 |

两段在 0.35 处都等于 0.30，所以连续；`max` 保证过渡段不被第二段拉低。
颜色改为单向插值：

```glsl
vec3 col = mix(uKeyLight, uAmbientLight, aW);   // 迎光面 aW=0 → 纯主光
```

> ⚠️ 顺序很重要：`mix(uKeyLight, uAmbientLight, aW)` 而不是反过来。
> 反写（`mix(ambient, key, keyW)`）在数学上等价，但改代码时极易把 `aW` 当成 `keyW` 用错。

---

## 三、修法二：环境色限饱和

蓝背景的暗部可能是 `(32, 80, 224)`（sat 0.86），乘到人物暗部上像**自发光的荧光灯管**。
真实的环境漫反射在物体之间反复反弹、被材质吸收，色相还在、纯度早掉了。

### 3.1 不要用"反复混灰"

直觉是"向灰度混 30%"，但迭代法收敛极慢。因为 `sat = (max-min)/max`，
混灰时 **max 下降得快、min 上升得慢**，比值几乎不动：

```
纯蓝 (0,0,255) 降到 sat 0.55 需要 k=0.92 或 10 轮迭代
实测 6 轮后仍停在 0.65
```

### 3.2 以亮度为轴缩放色度（闭式解）

```kotlin
c' = luma + (c - luma) · s
```

三个通道用**同一个** `s` 缩放 → 加权和不变 → **亮度严格不变**；
色相由 `(c - luma)` 的方向决定 → 也不变。`s` 有闭式解：

```
s = T·luma / [(max - min) - T·(max - luma)]
```

一次运算精确落在 `T = 0.55`。之后再向 luma 混一次 30% 灰（规格 2 的柔化），
同样以 luma 为轴，明度依然不变。

实测：`(0.10, 0.30, 0.95) → (0.256, 0.303, 0.457)`，饱和 0.89 → 0.44，
色相仍是蓝、亮度不变 —— 从"荧光"变成"夜色里的冷灰蓝"。

应用点（`DefaultImagePipeline`）：

```kotlin
wrapAmbientColorRgb = AmbientSaturation.cap(stats.ambientRgb),  // 环境漫反射
ambientColorRgb = AmbientSaturation.cap(ambientColor),           // 桥梁色/环境色罩
```

---

## 四、修法三：主光色彩提纯

### 4.1 亮度补偿（只加在迎光侧）

```glsl
col *= 1.0 + uKeyBoost * lit;   // 默认 0.35
```

冷背景里暖光要靠**增益**才能压住蓝调。但只加在迎光侧 —— 背光侧本来就该是环境色，
给主光加亮会把暗部一起点亮（那正是 v0.29 刚修掉的"暗部压不黑"）。

### 4.2 迎光面不再混背景色

```glsl
// 修复前：固定 0.75 → 25% 的蓝色背景被插进暖色包裹
vec3 tinted = mix(bg, duo, clamp(uColorMix, 0, 1));

// 修复后：迎光 → 1（纯主光色），背光才让背景参与
float colorMixW = mix(uColorMix, 1.0, smoothstep(0.35, 1.0, facing));
vec3 tinted = mix(bg, duo, colorMixW);
```

### 4.3 光源取色只取亮部

`LocalLightSample` 从"邻域整圈平均"改成"**只取亮度前 30%** 再平均"：

```kotlin
val sorted = buf.copyOf(n); sorted.sort()
val keep = max(12, (n * BRIGHT_KEEP_RATIO).toInt())   // 0.30
for (i in (n - keep) until n) { /* luma² 加权平均 */ }
```

光源周围往往紧挨着冷色背景（蓝天、夜街招牌），整圈平均会把暖光稀释成"蓝白"。
只统计光源核心与它的光晕，背景色自然被排除。

> Bloom 的高光色也走这条路：`FRAG_GLOW` 用的是从 `FRAG_EMIT` 扩散出来的金字塔，
> 而 EMIT 的颜色来自 `uKeyLight` —— 所以"辉光取主光源色"是这条链的自然结果，
> 不需要单独再取一次。

---

## 五、离线对照

`tools/sim_key_color.py` → `docs/key-color-sim.png`：蓝色背景 + 橙色主光，左右两版全图，
底部是**光晕本身**的平均 `R−B`（正=偏暖 / 负=偏蓝）。

| 版本 | 光晕平均 R−B |
| --- | --- |
| 旧（`mix(ambient*0.45, key, smoothstep)` + 混 25% bg） | +0.081 |
| 新（迎光环境权重 0 + 增益 + 不混 bg） | **+0.103** |

数值差距看着不大，因为合成图里还有人物底色与蓝色背景稀释；
**直接看光晕本身**（脚本统计的就是 glow，不含合成）才准确。
视觉上：旧版轮廓外侧偏冷白，新版是明确的暖金。

> sim 里的中文标注因缺少字体显示为方块，不影响图像本身。

---

## 六、契约测试

`ShaderKeyColorContractTest`（7 条，全部做过反向验证）：

1. 每个光色 pass 必须有 `ambientWeight`，且两段参数与 `LightMix` 一致；
2. **EMIT 必须是 `mix(uKeyLight, uAmbientLight, aW)`**，禁止退回 `mix(uAmbientLight * ...)`；
3. EMIT 的增益必须 `* lit` 加权（背光侧不加亮）；
4. **LIGHTWRAP 必须有 `colorMixW`**，禁止把 `uColorMix` 直接 clamp 塞进 `mix(bg, ...)`；
5. LIGHTWRAP 的双色插值方向必须是主光→环境；
6. `DefaultImagePipeline` 必须对两个环境色调用 `AmbientSaturation.cap`；
7. `LocalLightSample` 必须有 `BRIGHT_KEEP_RATIO` 与 `sorted.sort()`。

`LightMixTest`（8 条）：迎光权重严格 0、过渡 ≤0.30、背光 1.0、单调无跳变、
互补性、增益只在迎光侧。

`AmbientSaturationTest`（8 条）：高饱和蓝被压到上限内、色相不变（B 仍最大）、
**明度变化 < 6/255**、低饱和原样返回、纯原色能收敛、黑白安全。

---

## 七、调参

| 想要 | 改什么 | 位置 |
| --- | --- | --- |
| 暖光更强势 | `LightMix.KEY_BOOST` 0.35 → 0.5 | 规则层 |
| 过渡区环境更少 | `AMBIENT_MID_CAP` 0.30 → 0.20 | 同上 |
| 环境色更灰 | `AmbientSaturation.MAX_SAT` 0.55 → 0.40 | 同上 |
| 迎光面完全不混背景 | 无需改，`colorMixW` 在 `facing=1` 已是 1.0 | — |
| 光源取色更纯（只要核心） | `BRIGHT_KEEP_RATIO` 0.30 → 0.15 | `LocalLightSample` |

---

## 八、已知局限

1. **`ambientWeight` 在 shader 里复制了一份**（EMIT / LIGHTWRAP 各一个函数）。GLSL 没有共享库，
   只能靠契约测试保证与 `LightMix` 的分段参数一致（已断言 `0.30 * (1.0 - smoothstep(0.35, 1.0, l))`
   与 `1.0 - smoothstep(0.0, 0.35, l)` 两行字面量）。
2. **限饱和只做在 CPU**。如果将来有 pass 在 shader 内动态生成环境色（比如按像素采样背景），
   需要把闭式解也搬进 GLSL —— 它只有 6 行，可以照搬。
3. **主光色仍可能被 `EmissiveColor` 用环境色相补彩度**（当主光几乎纯白时）。
   本轮的"只取亮部"已经让主光色更纯，实际触发概率很低；若真遇到中性光源被补成蓝，
   把 `EmissiveColor.sanitize` 的 `ambientArgb` 参数改成 `KEY_NEUTRAL` 即可。

---

## 九、v0.33 补丁：荧光蓝的最后两个来源

v0.31 修掉了「迎光面环境权重没归零」，但真机上暖光**仍然**发蓝。用
`tools/sim_ambient_pollution.py`（蓝天空 + 薄雾暖夕阳，逐步复刻 Kotlin/shader 的公式）
量出来的结果是：**问题不在饱和度，也不在主光取色**。

| 位置 | 实测 | 结论 |
| --- | --- | --- |
| 光源邻域取到的 key | `#F2E1CE` → sanitize `#EBDAC8`（R−B **+35**） | 主光本身是暖的，取色没问题 |
| EMIT 在 lit=1.0（强迎光） | R−B **+34.7** | 完全正确 |
| EMIT 在 lit≈0.4（**明暗交界带**） | R−B **+0.2** | ← 中性 = 灰蓝，而这里占可见轮廓面积最大 |
| EMIT 在 lit≈0.1（背光侧） | R−B **−58.9** | 明显偏蓝 |

### ① 丢掉的 0.45：过渡带的环境光涨了 3 倍

v0.30 的内层是 `uAmbientLight * 0.45`；v0.31 重写颜色分层时把它写成了裸的
`uAmbientLight`。于是同样在 lit≈0.4：

```
v0.30：环境权重 0.23 × 0.45 = 0.10
v0.31：环境权重 0.30 × 1.00 = 0.30      ← 3 倍
```

蓝背景就从这条缝里沿着整条轮廓灌进来。它不是"饱和度太高"，而是**用量错了**：
环境光是漫反射的残余，物理上永远比主光弱一档，把它当等权颜色用，任何背景色都会被
放大成"自发光"。

修法（EMIT）：`vec3 ambLit = uAmbientLight * 0.45; vec3 col = mix(uKeyLight, ambLit, aW);`
修后 lit≈0.4 回到 **+13.6**，lit≈0.1 回到 **−22.7**（比 v0.30 的 −39.8 还更不刺眼，
因为下面这条也生效了）。

### ② 冷环境色不该给光源补彩度

`EmissiveColor.sanitize` 在主光接近中性白（chroma < 20）时，会"沿环境色色相"补彩度。
蓝背景下这条规则会把一颗过曝的白太阳补成 `(161,173,207)` 的**蓝白** ——
送进 shader 之后无论怎么调环境权重都是荧光蓝，因为**主光自己已经变蓝了**。

规则改成：环境色偏冷（`dir.blue > dir.red`）时，改用 `warmDirection()` 兜底；
**暖环境色仍然照旧参与补色**（否则等于废掉"跟着场景走"的设计，有单测保护这条）。

### ③ 环境色去毒：混 40% 灰 + 闭式限幅

规格要求混 40% 灰。实现分两步，顺序不能反：

```
① desaturate：c ← 0.6·c + 0.4·luma        // 柔化，去"发光"的锐利感
② 以亮度为轴缩放色度：c ← luma + (c-luma)·s,  s = T·luma/[(max-min) - T·(max-luma)]
```

**为什么不能只用 ①**：`sat=(max-min)/max` 里 max 降得快、min 升得慢。
纯蓝混 40% 灰后 sat 只从 **1.000 → 0.929**（肉眼几乎看不出）。
蓝天空的 ambient `#436BCC`（sat 0.671）混 40% 灰能到 0.499 —— 这一档够用，
所以 ① 不是没用，只是**不能兜底**；接近纯原色时必须靠 ② 精确落位。
两步都保持 luma 不变（同轴缩放），所以暗部不会因为去毒而变亮或变暗。

改动文件：`AmbientSaturation`（`GRAY_MIX` 0.30→0.40、新增 `desaturate`、`cap` 改两步）、
`EmissiveColor`（冷环境不补色 + 抽出 `warmDirection`）、`FRAG_EMIT`（恢复 0.45）。

验证：432 用例 / 61 类全绿；`ShaderKeyColorContractTest` 新增「环境色必须带 0.45 衰减」，
反向验证（把 `ambLit` 改回裸 `uAmbientLight`）立即 4 处失败。**未改动 DAG 与回读/坐标系。**

---

## 十、v0.34：光包裹硬覆盖主光色（停止调权重）

### 为什么停止"混合权重"这条路

v0.31 分层、v0.33 恢复 0.45 衰减，两轮都在调"环境光占多少"。结论是**这条路没有终点**：
蓝背景的彩度足够高，环境权重哪怕只剩 10%，交界带依然会被染成灰蓝
（实测 v0.31 交界带 R−B 只剩 +0.2；压到 0.10 也只是"淡灰蓝"）。

边界光的物理本质是**主光源的散射**。颜色只能由光源决定，背景只该决定"有多亮"。
既然权重调不出正确结果，就改成**硬覆盖**。

### 修改（只动 `FRAG_LIGHTWRAP` 的颜色段）

```glsl
const vec3 FALLBACK_KEY_SRGB = vec3(1.0, 0.8, 0.5);
vec3 keyColor = uKeyLight;
if (keyColor.b > keyColor.r) keyColor = pow(FALLBACK_KEY_SRGB, vec3(2.2));

float litRatio = smoothstep(-0.10, 0.40, facing);   // 只受法线/光向控制
vec3 tinted = keyColor * litRatio;                  // 无环境色、无背景色
```

删掉的东西：`uAmbientLight` 混色（含 `ambientWeight` 函数）、`uColorMix` 双色插值、
`uBgBlur` 的亮度调制、`uAmbientLift`。uniform 声明保留（调用方还在传），
标成「已废弃」——GLSL 编译器会把未使用的 uniform 优化掉，`glGetUniformLocation` 返回 -1，
传值是安全的空操作。

### 两个容易踩的实现细节

1. **兜底色必须 `pow(2.2)`**。本 pass 全程线性空间，最终由 FXAA 做 sRGB 编码。
   直接把 sRGB 的 `(1.0, 0.8, 0.5)` 当线性值用，输出会变成 sRGB ≈ (1.00, 0.91, 0.73) —— 偏白的暖白，
   不是暖黄。`pow(2.2)` 之后才是 `#FFCC80`。
2. **`litRatio` 要乘在颜色上，不是乘在 mask 上**。`wrapMask`（向内距离）与 `occl`（阴影熔断）
   已经各自负责"哪里有光"和"该不该有光"；`litRatio` 只负责"迎光多少"。三者相乘，
   颜色比例恒定 → 任何亮度下都保持同一个暖色，这正是硬覆盖要的性质。

### 实测（`tools/sim_ambient_pollution.py`）

```
litRatio=1.0  #EBDAC8  R-B=+35
litRatio=0.5  #766D64  R-B=+17
litRatio=0.2  #2F2C28  R-B=+7
litRatio=0.0  #000000  R-B= 0
提取失败（主光被污染成 #A1ADCF）→ 兜底 #FFCC80  R-B=+128
```

### 代价（必须知道）

- **背光侧的光也变成暖色了**。物理上背光侧的包裹光本该偏环境色（冷），现在统一为暖。
  这是"宁可全暖也不要一处荧光"的取舍。
- **包裹光的强度不再跟随背景亮度**（`uBgBlur` 那一项被删）。深色背景与亮色背景上，
  同一 `uWrapStrength` 得到相同的绝对亮度；暗景里若觉得太亮，调 `lightWrapStrength`。
- **`EMIT`（更外圈的光晕）仍保留 45% 环境混色**。它是发光源图，会被高斯金字塔扩散到背景空气里，
  那里本来就该有环境色。若真机上仍觉得外圈发蓝，把 `FRAG_EMIT` 的 `ambLit` 一并改成 `uKeyLight`。
