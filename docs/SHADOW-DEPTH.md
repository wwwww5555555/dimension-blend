# 暗部层次与底部投影（v0.29）

> 对应 `FRAG_P2` 的 ②e/⑥、`FRAG_P2C` 的 ④⑤，新增 `core-common/model/DeepShadowTint.kt`。
> 离线对照：`docs/shadow-deep-sim.png`（`tools/sim_shadow_deep.py` 生成）。

---

## 一、暗部死灰：两个独立原因

### 1.1 全身一个固定暗度 → 没有层次

v0.27 的压暗是 `c *= mix(0.82, 0.32, backW)`：全身一个 0.32，从上到下一样暗。
观感就是"一块平整的灰蓝"。

现在换成**三级高度阶梯**（本工程 `uv.y = 1` 是画面底部）：

```glsl
float yT = clamp(uv.y, 0.0, 1.0);
float tier = mix(0.40, 0.20, smoothstep(0.28, 0.60, yT));   // 头 → 腰
tier = mix(tier, 0.05, smoothstep(0.78, 0.96, yT));         // 腰 → 脚底（死黑）
```

| 部位 | 目标亮度 | 实测（离线） |
| --- | --- | --- |
| 头/上 | 0.40 | 0.076 |
| 腰/中 | 0.20 | 0.045 |
| 脚底 | 0.05 | 0.025 |

（实测值 = 人物基础色 × 目标系数后的绝对亮度，比值 1 : 0.60 : 0.33）

**用"绝对目标亮度"而不是"再乘一次系数"** 很关键：`0.32 × 0.4` 会叠乘成 0.128，
再乘基础色就是一片死黑 —— 层次反而丢了。

### 1.2 暗部没有"吸收"环境色

原来的染色是 `c * shadowChroma` 再按 luma 归一 —— 只改通道比例，**不改"沉入感"**。

现在用规格给的公式，逐通道乘：

```glsl
vec3 absorb = mix(vec3(uBacklitDarken), uDeepShadowColor, clamp(uBacklitChroma, 0.0, 1.0));
vec3 target = absorb * tier;
c *= mix(vec3(uBacklitGlobal), target, backW);
```

`uDeepShadowColor` 是 `DeepShadowTint.factor(背景最暗部中位色)`：

```
factor = 色相归一 × 0.42
```

深蓝背景 `(0.16,0.19,0.34)` → `(0.33,0.39,0.70)`：暗部沉进蓝调而**不是纯黑**。

> ⚠️ **不能直接拿背景最暗部的像素值**。那是 sRGB `(26,30,48)`，线性 ≈ 0.012，
> 乘上去等于 `color × 0.012` = 纯黑。必须先做色相归一。
>
> ⚠️ **必须用 `uni3f` 直传**，不能走 `uniSrgbLinear` —— 后者会把 0.42 转成线性 0.14，
> 暗部直接塌掉。它是**乘法因子**，不是 sRGB 颜色。

### 1.3 第三个原因：压完又被 Screen 提回去

这是"怎么调都压不黑"最隐蔽的一条。P2 里有四处 Screen/Add：

| 项 | 处理 |
| --- | --- |
| ③ 伪立体光照 | `* litGate`（暗部归零） |
| ⑤ 环境亮部 Screen | `* litGate` |
| ⑤b 底部反弹光 | `* mix(0.25, 1.0, litGate)`（保留 25%） |
| ⑦ 高光保护 | 已有 `smoothstep(-0.2, 0.5, lw)` 门控 |

```glsl
float litGate = uBacklitMode > 0.5 ? (1.0 - backW) : 1.0;
```

反弹光为什么保留 25%：地面反射确实会打到下摆，物理上存在。清零会让脚部"断掉"，
看起来像被裁掉了。

**`backW` 必须是 1.0（不是 0.75）**：`mix(0.82, target, backW)` 里 0.82 远大于
`target`（0.01~0.13），`backW = 0.75` 时 0.82 会漏进 25%，把"脚底 0.05"抬到 0.22 ——
三级层次直接被压平（实测比值从 1:0.6:0.33 退化成 1:0.76:0.59）。

---

## 二、底部投影消失：三个原因，都不是 Alpha

先排除一项：**P2-c 从来没有"被锁定 alpha"的问题**。它输出的是一张 **Multiply 因子图**
（`fragColor = vec4(f, 1.0)`），在 composite 里做 `c = p1 * p2c`。它不承载人物透明度，
也不受人物 alpha 影响。所以"Alpha 锁定后遗症"这条可以排除。

真正的原因是三个：

### 2.1 接触阴影采样方向反了（**最关键**）

```glsl
// 旧：向下看
cAcc += aAt(uv + vec2(float(i) * uTexel.x, uContactPx * uTexel.y));
```

本工程 `uv.y = 1` 是画面底部。对人物正下方的**背景**像素，再往下 4px 还是背景（`a=0`）
→ `cAcc ≈ 0` → 接触阴影恒为 0。而唯一能采到人物的位置（人物内部）又被
`1 - smoothstep(0.80, 0.95, a)` 门控掉了。

**净效果：接触阴影从未生效过一次。** 离线对照图第 2 格（旧 contact 项）几乎是全白，
只在轮廓内侧留了一条 1px 的黑线 —— 那不是"脚下的影子"，是"给轮廓描了个黑边"。

```glsl
// 新：向上看（朝人物脚底）
cAccNear += aAt(uv + vec2(float(i) * uTexel.x, -uContactPx * uTexel.y));       // 4px 核心
cAccSoft += aAt(uv + vec2(float(i) * uTexel.x, -uContactPx * 3.0 * uTexel.y)); // 12px 过渡
```

双圈设计：内圈 4px 死黑（规格 3~5px），外圈 12px 半透明衰减 —— 只做 4px 会像一条硬线。

### 2.2 强度被当成暗度直接乘

```glsl
// 旧
float shadowMask = max(softLayer, coreLayer) * uStrength;   // uStrength = 0.3
```

`LightParams.shadowStrength` 默认 **0.3**（它是光照模型里的相对权重，不是暗度）。
直接乘上去，根部最多 0.3 的暗度 → 在明亮地面上等于看不见。

```glsl
// 新：先算本体深度，再乘强度
float softLayer = smoothstep(0.10, 0.60, occ) * 0.55;
float coreLayer = smoothstep(0.45, 0.90, occ) * (1.0 - smoothstep(0.0, 0.30, distK)) * 0.95;
float depth = max(softLayer, coreLayer) * mix(0.30, 1.0, nearK);
float shadowMask = clamp(depth * clamp(uStrength, 0.0, 1.0), 0.0, 1.0);
```

配 pipeline 端的重映射（0.3 → 0.685）：

```kotlin
shadowStrength = run {
    val s0 = request.effectiveLight.shadowStrength.coerceIn(0f, 1f)
    if (s0 < 0.01f) 0f else (0.55f + 0.45f * s0).coerceAtMost(1f)
}
```

用户把滑杆拖到 0 仍然能关掉（`s0 < 0.01` 分支）。

### 2.3 投影太短

`shadowOffsetPx = 24f` 在 1080p 下只有 24px，相对人物高度不到 3% —— 视觉上就是"没有"。
改成 **60px**（约 5.5%），配合错切与远端衰减才看得出方向。

远端不再归零（保留 30%）：`depth *= mix(0.30, 1.0, nearK)`。归零会让影子"断成一截"。

---

## 三、节点存活验证

用户担心的"P2-c 被后续 Pass 覆盖"不成立，链路是：

```
P2-c 输出 Multiply 因子（alpha=1，独立于人物 alpha）
  ↓
COMPOSITE：c = p1 * p2c            ← 阴影乘在背景上
  ↓
mix(c, p2.rgb, blend)              ← 只在人物区域覆盖（背景阴影不动）
  ↓
screen(c, p2w)                     ← 光包裹，暗部已被熔断到 15%
  ↓
screen(c, p2b) → ambient tint
  ↓
GLOW（Screen，背景 × 0.35）        ← 唯一会轻微提亮阴影的地方
```

GLOW 对背景只给 35%，实测提亮 ≈10%。如果真机上阴影仍偏淡，把 `glowAirRatio`
从 0.35 降到 0.2 即可，不需要动 P2-c。

顺序上还有一条硬约束（v0.27 起）：**P2-c 必须最先画**，因为 EMIT / P2-b / LIGHTWRAP
都要读它的遮挡。`ShaderShadowCutoffContractTest` 用源码索引位置钉死了。

---

## 四、Kotlin 端：`uDeepShadowColor` 与锚点

```kotlin
// core-cv 内部：params.shadowColorRgb 已经是"背景最暗部中位色"（ShadowTint 提取）
val deepShadowFactor = DeepShadowTint.factor(params.shadowColorRgb)
...
uni3f("uDeepShadowColor", deepShadowFactor[0], deepShadowFactor[1], deepShadowFactor[2])
```

```kotlin
// core-pipeline：锚点（脚底经验位置）与投影轴都在 CPU 算好、打包成 uniform
val axis = ShadowShear.calculateShadowAxis(
    anchorX = SHADOW_ANCHOR_X,   // 0.5
    anchorY = SHADOW_ANCHOR_Y,   // 0.9
    lightX = params.lightX,
    lightY = params.lightY,
)
val su = axis.pack(anchorX = ..., anchorY = ..., reachPx = params.shadowOffsetPx * h / 1080f, ...)
uni2f("uAnchor", su.anchorX, su.anchorY)
uni2f("uShadowDir", su.dirX, su.dirY)
```

`calculateShadowAxis` 里有一条硬约束：`dy < MIN_DOWN` 时强制抬到 `MIN_DOWN` ——
光在锚点下方时不会产生朝上的影子（那观感极其错误）。

---

## 五、离线验证

`tools/sim_shadow_deep.py` → `docs/shadow-deep-sim.png` 四格：

| 格 | 内容 | 结论 |
| --- | --- | --- |
| 1 | 旧：固定 0.32 暗度 + 接触向下采样 | 全身一片灰蓝，脚下影子极淡 |
| 2 | 旧 contact 项 | **几乎全白**，只有轮廓内侧 1px 黑线 → 从未在脚底生效 |
| 3 | 新：三级高度 + 环境吸收 | 从上到下递进变暗，脚部墨蓝 |
| 4 | 新 contact 项 | 裙摆下方出现明显弧线，脚下有实感 |

量化：

| 指标 | 旧 | 新 |
| --- | --- | --- |
| 头部平均亮度 | 0.151（全身均值） | 0.076 |
| 腰部 | — | 0.045 |
| 脚底 | — | 0.025 |
| 接触阴影位置 | 轮廓内侧 | 脚下/裙摆下方 |

---

## 六、契约测试（都做过反向验证）

`ShaderShadowCutoffContractTest` 新增 2 条：

1. **接触阴影必须向上采样** —— 必须出现 `-uContactPx`，且**禁止**出现 `+uContactPx`。
   反向验证：把符号改回去 → 立刻失败。
2. **主投影必须有接近死黑的硬核层** —— 断言 `* 0.95`、`* 0.55`、`mix(0.30, 1.0, nearK)`。

另加 2 条：

3. `逆光压暗必须逐通道吸收环境色并做三级高度` —— 断言 `absorb = mix(vec3(uBacklitDarken), uDeepShadowColor`、
   存在 `tier`、与 `uBacklitGlobal` 混合。
4. `所有 Screen 项在暗部都被门控` —— `litGate` 必须乘到伪光与环境亮部 Screen 上，
   反弹光保留 25%。

`DeepShadowTintTest`（12 条）：深蓝 → B 最大且不是纯黑；中性 → 三通道相等；
纯黑输入不除零；三级高度符合 0.40/0.20/0.05；从上到下单调不变亮；
头腰落差 >0.12、腰脚落差 >0.08；脚底 <0.08；吸收比 0/1 的边界行为。

---

## 七、已知局限

1. **高度阈值是经验值**（0.28/0.60/0.78/0.96）。人物只占画面上半部分时，
   "脚底 0.05" 会落在画面中部而非真正脚底 —— 因为这里没有人体关键点，
   只有 `uv.y`。要精确需要接姿态估计（成本远超收益）。
2. **接触带宽度固定 4px + 12px**，不随人物缩放。特写构图下会显得偏宽，
   远景下偏窄。若要自适应，得按人物 mask 的高度算。
3. **`uDeepShadowColor` 来自整幅背景的最暗 12%**。人物脚下是草地、背景远处是夜空时，
   取到的是夜空色 —— 物理上"环境暗部色"本来就是全局的，这是可接受的近似。
