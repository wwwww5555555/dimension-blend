# 逆光剪影：S 型方向渐变（v0.18.0）

## 从"整体乘法"到"渐变场"

**旧做法**（v0.15~0.17）：
```glsl
c *= mix(1.0, uBacklitDarken, uBacklit);   // 全人物同一个倍率
```
问题：整体只有一个亮度值 —— 既"压得不够黑"，也**没有任何渐变**，人物像一块均匀的灰板。

**新做法**：以光源为原点的距离场 → `smoothstep` → 明暗交界线。

## 1. 距离场 + S 曲线

```glsl
// 宽高比校正：否则宽画幅会把圆形渐变压成椭圆
float distToLight = length((uv - uLightPos) * vec2(uAspect.x, 1.0));

// lightFactor: 1 = 贴近光源（亮），0 = 远离（暗）
// 注意是 1 - smoothstep：逆光时光在背后，**越靠近光源越亮**
float lightFactor = 1.0 - smoothstep(uGradMin, uGradMax, distToLight);
```

| `distToLight` | `lightFactor` |
| --- | --- |
| ≤ `uGradMin` (0.22) | 1.0（肩膀/头顶，最亮） |
| 中间 | S 型过渡（不是线性！） |
| ≥ `uGradMax` (0.78) | 0.0（腿部/正面，最暗） |

**为什么必须 smoothstep**：线性 `mix` 的过渡带在视觉上是一条"平"的斜坡，
大面积中灰会让人觉得"蒙了层灰"；S 曲线把中间压缩、两端拉开，才会出现**明暗交界线**。

## 2. 暗端 / 亮端混合

```glsl
vec3 darkColor   = c * uBacklitDarken * mix(vec3(1.0), shadowChroma, 0.65);
vec3 brightColor = c * 1.2;
c = mix(c, mix(darkColor, brightColor, lightFactor), uGradStrength * mix(0.45, 1.0, uBacklit));
```

⚠️ **不能写 `c * uShadowColor`**：`uShadowColor` 是线性空间的暗色（sRGB 26 ≈ 线性 0.01），
直接乘等于纯黑。正确做法是先按亮度归一取出**色相**：
```glsl
vec3 shadowChroma = clamp(uShadowColor / max(dot(uShadowColor, LUMA), 1e-4), 0.4, 2.5);
```
然后 `× uBacklitDarken` 控制压暗幅度（0.55 = 暗端保留 55% 亮度），× `mix(1, chroma, 0.65)`
把暗部稍微染上背景的冷暖倾向 —— 暗而不死。

`uGradStrength × mix(0.45, 1.0, uBacklit)`：逆光满强度；顺光保留 45%，
让侧光人物也有一点方向性明暗（完全关掉反而显得板）。

## 3. 脚底垂直渐变

```glsl
float feetK = smoothstep(uFeetStart, 1.0, uv.y);            // 0.78 → 1.0
c *= 1.0 - uFeetStrength * feetK * mix(0.5, 1.0, uBacklit);
```
与 P2-c 的接触 AO 分工不同：
- **这里（P2）**：把人物自身下半身压暗，模拟"腿部陷进环境"；
- **P2-c**：在背景上画接触阴影，让人"踩实"。
两者独立，可以分别调。

## 4. 参数（都已做成 uniform，可直接接滑杆）

| 参数 | uniform | 默认 | 说明 |
| --- | --- | --- | --- |
| `gradientMinDist` | `uGradMin` | 0.22 | 渐变内径（越小亮区越小） |
| `gradientMaxDist` | `uGradMax` | 0.78 | 渐变外径（越大暗区越早开始） |
| `gradientStrength` | `uGradStrength` | 0.9 | 0 = 关闭 |
| `backlitDarken` | `uBacklitDarken` | 0.55 | 暗端倍率 |
| `feetFadeStart` | `uFeetStart` | 0.78 | 脚底起始 Y |
| `feetFadeStrength` | `uFeetStrength` | 0.35 | 0 = 关闭 |

**调带宽窄**：只改 `uGradMin/uGradMax` 的差 —— 差值小 = 硬交界（剪影感强），
差值大 = 柔和过渡（雾气感强）。

## 5. P2 完整顺序（未变）

```
① 原始 → ② S曲线+Unsharp → ②b 方向渐变 → ②c 脚底渐变
→ ③ 伪立体光照 → ④ 四色 SoftLight → ⑤ 边缘光 → ⑥ 颜色矩阵 → ⑦ 内部亮度上限
```

## 已知限制

- 距离场用的是**画面空间直线距离**，不是真实三维距离；人物离光源很远时会整体偏暗
  （这符合直觉，但不是物理正确）。
- 渐变只作用于人物（P2），背景的明暗由 P1 的光晕/辉光负责。
