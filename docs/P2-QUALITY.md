# P2 人像组质感重塑（v0.11.0）

四个节点按顺序执行，**顺序不能换**（色调映射必须在色矩阵之前，泛光必须在色调之后，光包裹必须在泛光之后）。

```
P2_Key(绿幕) → ① 色调映射+Unsharp → ② 色矩阵 → 边缘光
             → ③ 高光泛光(1/4域, Screen 回叠)
             → ④ 光包裹(读 P1 模糊光, Screen)
             → 合成：方向性羽化(mix by LightDir)
```

## ① 色调映射 + 局部对比度（同一 pass）

人物"平"的根因：原来只有"乘色温 + 乘矩阵"，两者都是全局线性变换，不改变亮度分布。

```glsl
// S 曲线：端点不动，中间拉出对比（0.2→0.104，0.8→0.896）
float l = dot(c, LUMA);
float s = smoothstep(0.0, 1.0, l);
float target = mix(l, s, uToneContrast);
c *= clamp(target / max(l, 1e-4), 0.15, 4.0);   // 按亮度比缩放 → 保色相
```
**为什么不是逐通道 smoothstep**：逐通道做会让 R/G/B 各自被压/提，饱和度随亮度漂移，肤色会串。
按 `target/luma` 等比缩放，色相与通道比例不变。

```glsl
// Unsharp Mask：c - lowFreq，只在人物内部加回
vec3 low = pow(max(texture(uPersonLow, uv).rgb, 0.0), vec3(2.2));
c += (c - low) * uUnsharp * smoothstep(0.1, 0.5, a);
```
低频频在 **1/4 域**、且以 `uAlphaWeighted=1` 生成（只统计人物自身像素，透明区残留色不污染）。

## ② 高光泛光（Bloom，三 pass）

| pass | 分辨率 | 作用 |
| --- | --- | --- |
| `bloomExtract` | 1/4 | `c*a` 取高光（乘 alpha 防发丝糊白），阈值 0.8 + 软膝 0.15 |
| `blur` ×2 | 1/4 | 两遍半径（第二遍 ×1.8），单遍 13-tap 撑不到大光晕 |
| `bloomApply` | 全 | `1-(1-base)(1-bloom)` **Screen** 回叠，强度 0.3 |

用 Screen 而不是 Add：Add 会把已经很亮的高光烧成一片白，Screen 保得住层次。

## ③ 光包裹（真正的 Light Wrap）

**修掉三个"没效果"的根因**：

| 根因 | 修法 |
| --- | --- |
| 强度只有 ~0.1，肉眼看不出 | 总增益 ×2.0，`wrapMask` 上限 0.9 |
| 蒙版没外扩，环绕带全落在人物内部被熔断 | 两圈 8 方向 **dilate 5px** + 0.5R 模糊混合 |
| `max(0,dot)` 恒为正的小值 → 全周都发光 | `smoothstep(-0.15, 0.45, facing)`：背光真归 0 |

```glsl
float wrapMask = mix(dil, soft / 8.0, 0.45);
wrapMask *= 1.0 - smoothstep(0.90, 0.95, a);       // 实心内部熔断
vec3 bgLight = pow(texture(uBgBlur, uv).rgb, vec3(2.2));   // ★ P1 的背景模糊纹理
bgLight *= min(2.0, uMaxLuma / max(luma, 1e-3));
vec3 spill = mix(bgLight, uWrapTint, 0.30) * wrapMask * gate * uWrapStrength * 2.0;
```
光源来自 **P1 的模糊背景**（`uBgBlur`）而不是人物自身颜色——这才是"草地/夕阳包裹住轮廓"。

## ④ 方向性羽化（合成节点）

```glsl
float facing = dot(nOut, normalize(uLightDir.xy));   // +1 迎光 / -1 背光
float lit = clamp(facing * 0.5 + 0.5, 0.0, 1.0);
float width = mix(0.45, 0.09, lit);                  // 背光宽、迎光窄
float blend = smoothstep(0.5 - width, 0.5 + width, p2.a);
c = mix(c, p2.rgb, blend);
```
物理依据：迎光侧被强光"咬"住，视觉上是硬边；背光侧没有轮廓光，只能靠环境光过渡，天然发虚。
各向同性羽化（原来 `smoothstep(0,1,a)`）让两侧一样软 → 看起来就是贴纸。

## 关键修正：下游节点改读「已调色的 P2」

原来 P2-b / P2-c / P2-w 全部绑 `texPerson`（**未调色的原始抠图**），所以：
阴影颜色与人物肤色脱节、光包裹打在旧像素上、反光与主体不同色。
现在统一改绑 `texP2`（色调映射 + 泛光后的结果）。Mask 的 alpha 不变，几何不受影响。

## 显存

新增 1 张全分辨率（`texP2Tone`）+ 3 张 1/4 域（人物低频、泛光提取/中间）。
2048² 上限时全分辨率纹理 11 张 ≈ 176MB，仍在 600MB 预算内；泛光关闭时复用色调层，不额外占纹理。
