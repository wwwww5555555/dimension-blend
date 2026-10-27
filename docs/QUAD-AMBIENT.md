# 四色渐变环境光 + 伪立体打光（v0.13.0）

## 1. Kotlin：四区平均色（复用 `ScenePaletteExtractor`）

不新写一套取色逻辑——`ScenePaletteExtractor` 已经在做"分区 + 排除过曝/死黑 + 中位色"，
并且被光影/阴影/边缘光共用。同一张图必须只有一套"区域主色"定义，否则会出现
"边缘光偏白、环境光偏蓝"这种自相矛盾。

```kotlin
// 一次降采样，同时产出三样东西（骨架测光也复用这份像素，不再各自降采样）
private fun sceneStats(scene: Bitmap): SceneStats {
    val (px, w, h) = downscalePixels(scene, SKELETON_LONG_SIDE)   // 长边 480
    val palette = ScenePaletteExtractor.extract(px, w, h)          // 上/下/左/右 + 高光/暗部
    val shadow  = ShadowTint.fromBackground(px, w, h)              // 最暗 12% 中位色
    val mean    = meanOf(px)                                       // 全图均值
    return SceneStats(px, w, h, palette, shadow, mean)
}
```

`ScenePalette` 的四区定义（`ScenePaletteExtractor.extract` 内部）：

| 区 | 范围 |
| --- | --- |
| top | `y ∈ [0, h/3)`，全宽 |
| bottom | `y ∈ [h - h/3, h)`，全宽 |
| left | `x ∈ [0, w/3)`，全高 |
| right | `x ∈ [w - w/3, w)`，全高 |

**为什么要排除过曝与死黑**：天空的 255 白和阴影的 0 黑不代表这一区的环境色，
算进去会让四色渐变退化成"上白下黑"，比不加还难看。

## 2. Shader：双线性插值 + SoftLight + 方向/径向加权

```glsl
// 双线性：水平与垂直两路插值后取均值 → 平滑二维渐变场
vec3 quadH = mix(uQuadLeft, uQuadRight, uv.x);
vec3 quadV = mix(uQuadTop, uQuadBottom, uv.y);
vec3 quad  = mix(quadH, quadV, 0.5);

// 径向方向 · 光向 → 迎光侧更亮、背光侧压暗
vec2 rel = uv - 0.5;
vec2 nuv = normalize(rel);
float lw = dot(nuv, normalize(uLightDir.xy));       // +1 迎光 / -1 背光
float litK = smoothstep(-0.6, 0.8, lw);

// 距离衰减：中心保留原本色彩，边缘更多受环境光影响
float r = length(rel * uAspect) / 0.7071;
float edgeW = smoothstep(0.20, 0.95, r);

float k = uQuadStrength * mix(0.55, 1.15, litK) * mix(0.30, 1.0, edgeW);
c = mix(c, softLight(c, quad), k * smoothstep(0.15, 0.60, a));
```

**SoftLight 为什么能出立体感**（Pegtop 公式）：

```glsl
float softLightCh(float b, float s) { return (1.0 - 2.0*s) * b*b + 2.0*s*b; }
```

- `s = 0.5` → 结果恒等于 `b`（恒等元）；
- `s < 0.5` → 相当于 `b` 被压暗，**暗部保留更多层次**（不是简单乘法，暗部不会直接归零）；
- `s > 0.5` → 提亮，但高光按 `2b - b²` 收敛，不会烧白。

所以它比 Multiply 更适合"上色"：环境色的**明度信息**被压成轻微的明暗调制，只保留色相倾向。

**`dot(normalizedUV, lightDir)` 的作用**：`normalizedUV` 是像素相对画面中心的径向方向，
相当于把"一个球面"投影到 2D。与光向点乘得到 ±1 的受光系数：
迎光一侧渐变光乘以 1.15、背光一侧乘以 0.55 —— 人物的左侧和右侧因此拿到**不同的环境光强度**，
而不是整张脸均匀罩一层色（那正是"平涂感"的来源）。

## 3. 伪立体打光

```glsl
float luma = dot(c, LUMA);
// 迎光：只提亮"本来就亮"的区域（保留二次元的固有色层次），并出高光点
float hiMask = smoothstep(0.45, 0.95, luma);
c += vec3(1.0, 0.97, 0.90) * pow(max(lw, 0.0), 2.0) * hiMask * uFakeLight * 1.2;

// 背光：压暗"本来就暗"的区域，用背景暗部的**色相**着色
float darkMask = 1.0 - smoothstep(0.08, 0.50, luma);
float back = max(-lw, 0.0) * darkMask;
vec3 tint = clamp(uShadowColor / max(dot(uShadowColor, LUMA), 1e-4), 0.4, 2.5);
c *= mix(vec3(1.0), tint, back * 0.45 * uFakeLight);
c *= 1.0 - back * 0.35 * uFakeLight;
```

⚠️ **不能用 `c *= uShadowColor` 着色**：`uShadowColor` 是线性空间的暗色（sRGB 26 ≈ 线性 0.01），
直接乘等于纯黑，会把暗部整块抹掉。正确做法是**先按亮度归一化取出色相**再乘，
暗部只会偏冷/偏暖，不会消失。

## 4. 参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `quadTop/Bottom/Left/RightRgb` | 背景四区中位色 | 每帧实时取 |
| `quadStrength` | 0.25 | 0 = 关闭（省 4 个 uniform + SoftLight） |
| `fakeLightStrength` | 0.3 | 0 = 关闭 |

## 5. 性能

四色均匀色不需纹理，`ScenePaletteExtractor` 在 480px 缩略图上跑（区域采样上限 2048 点/区），
实测与骨架测光共用同一份降采样像素，**没有新增全图扫描**。
