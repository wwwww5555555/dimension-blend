# 光包裹三维分层 + 冷暖双色（Teal & Orange）

> 版本：v0.23.0（versionCode 24）
> 改动：`FRAG_P2W` 重写、`FRAG_P2C` 提前到 P2-w 之前、`Params` 新增 5 个字段、
> 新增 `core-pipeline/SceneLightColors.kt`（纯函数 + 6 单测）。DAG 架构未动。

## 0. 旧版为什么像"描边发光圈"

| 缺失的维度 | 结果 |
| --- | --- |
| 高度 | 整圈轮廓同强度，脚底也在发光 → 人物像浮着 |
| 遮挡 | 不管投影落在哪都照发 → 阴影里的下摆反而最亮 |
| 颜色 | 只有一个高光色 → 整圈暖白，没有冷暖对比 |

## 1. Kotlin：双色提取（`SceneLightColors`）

```kotlin
// 主光色：从 255 往下累计到 5% 处的亮度，取 luma >= cut 的均值
val cutKey = cutFromTop(hist, max(1, round(n * 0.05)))
// 环境色：从 0 往上累计到 50% 处的亮度，取 luma < cut 的均值（严格小于）
val cutAmb = cutFromBottom(hist, max(1, round(n * 0.50)))
```

**为什么不直接用 `p95 / p50`**：边界上会堆积大量同亮度像素。
若画面正好 5% 是亮部、其余是中调，升序累计到 95% 分位时 `luma >= p95`
会把**整块中调**也算进主光，颜色立刻稀释成灰。
按数量从两端累计找截断亮度，同亮度的像素同色，多算几个不影响色调。

`luma < cutAmb` 用**严格小于**，把中调排除在环境色之外；整张图同一亮度时
取不到像素，再退回"包含截断亮度"。

输入是长边 480 的缩略图（复用 `sceneStats` 的那一份像素），整段 < 1ms。

| 场景 | 主光色 | 环境色 |
| --- | --- | --- |
| 夕阳暖天 + 冷阴影 | (255,190,110) 暖橙 | (30,45,80) 冷蓝 |
| 纯灰 | 灰 | 灰（不凭空造冷暖） |
| 5% 纯白 + 95% 黑 | 接近纯白 | 接近纯黑 |

## 2. Shader：三维衰减

### 2.1 高度

```glsl
// 本工程 uv.y = 1 是**画面底部**（与 P2 脚底渐变同一约定）
float heightWeight = 1.0 - smoothstep(uHeightStart, uHeightEnd, uv.y);   // 0.70 / 1.0
```

> **规格给的 `smoothstep(0.0, 0.3, vUV.y)` 在本工程约定下会把「顶部」归零**，
> 与"让人物底部归零"正好相反。这里按语义改写为 `1 - smoothstep(0.70, 1.0, uv.y)`：
> uv.y=0.85 时约 0.5，到画面最底部归零。

### 2.2 阴影遮挡

P2-w 原来画在 P2-c **之前**，拿不到阴影贴图 —— 已把 P2-c 整块提前。

```glsl
vec3 sh = texture(uShadowMask, uv).rgb;                    // P2-c 的 Multiply 因子
float shadowAmt = clamp(1.0 - dot(sh, LUMA), 0.0, 1.0);    // 1 = 强阴影
float occlusion = 1.0 - shadowAmt * uShadowOccl;           // 0.8 → 只剩 20%
```

P2-c 输出的是 `mix(vec3(1), shadowColor, mask)`，所以"1 − 亮度"就是遮挡强度，
不需要额外一张 mask 纹理。

### 2.3 冷暖分离

```glsl
float NdotL = dot(N, L);                                   // 2D 抠图无深度，nz 如实取 0
float keyWeight     = smoothstep(0.0, 0.8, NdotL);          // 迎光：主光色（暖）
float ambientWeight = 1.0 - smoothstep(-0.5, 0.2, NdotL);   // 背光/侧面：环境色（冷）

// ★ 归一化：两段 smoothstep 在 NdotL≈0.2 处同时接近 0，
//   直接相加会让 wrapColor 变成接近黑色，轮廓上留一圈暗缝
float wsum = max(keyWeight + ambientWeight, 1e-3);
vec3 wrapColor = (uKeyLightColor * keyWeight + uAmbientColor * ambientWeight) / wsum;
```

### 2.4 合成

```glsl
// 亮度跟随背景模糊光（保留局部明暗），色调由双色决定
vec3 tinted = mix(bgLight, wrapColor * (0.55 + 0.45 * luma(bgLight)), uColorMix);  // 0.75
vec3 spill = tinted * wrapMask * heightWeight * occlusion * uWrapStrength * 2.0;
```

`uColorMix = 0` 时退回"纯背景光"（旧行为），方便 A/B 对比。

## 3. 参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `keyLightColorRgb` | 0xFFFFD8A8 | 背景亮部前 5% 加权均值 |
| `wrapAmbientColorRgb` | 0xFF8FA8C8 | 背景暗部（≤中位）均值 |
| `wrapColorMix` | 0.75 | 双色替换背景光色的比例 |
| `wrapHeightStart` / `End` | 0.70 / 1.0 | 高度衰减区间（1.0 = 画面底部归零） |
| `wrapShadowOcclusion` | 0.8 | 遮挡处强度只剩 20% |

## 4. 真机检查

1. **冷暖分离**：夕阳背景下，迎光侧轮廓偏金橙、背光侧偏冷青；不再是整圈同一个白。
2. **高度**：脚踝以下基本没有包裹光；人物不再"浮"在地面上。
3. **遮挡**：投影压到裙摆的位置，包裹光明显变弱（对比 `wrapShadowOcclusion=0` 应能看出差别）。
4. **暗缝检查**：绕轮廓一圈看是否有"断点"（若有，说明权重归一化被改掉了）。
5. **过曝检查**：`wrapColorMix` 过高 + 亮背景时，若边缘发白就降到 0.6。
