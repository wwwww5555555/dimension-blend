# 边缘"融化"修复：Alpha 锁定 + 纯加性光

> 版本：v0.25.0（versionCode 26）
> 改动：`FRAG_P2`（alpha 锁定）、`FRAG_COMPOSITE`（羽化收窄）、`FRAG_P2W`（环境色提亮）
> 新增：`ShaderAlphaContractTest`（3 个静态契约断言）

## 0. 真凶不是"光包裹的混合公式"

先把结论说清楚：**P2-w 从来没有 `mix(baseColor, wrapColor, weight)`**。
它是一个**独立的光缓冲**：

```glsl
fragColor = vec4(spill, 1.0);          // 只有光，alpha 恒为 1
// 合成阶段：Screen 叠加，只影响 RGB
c = 1.0 - (1.0 - c) * (1.0 - clamp(p2w, 0.0, 1.0));
```

所以"光效减弱导致人物透明"不是从这里来的。真正的两条原因是：

### 原因①：Shrink Mask 把腐蚀后的 alpha 写进了输出（v0.22 引入）

```glsl
// 旧代码（错）
float a = p.a;
if (uShrinkPx > 0.05) {
    float aE = min(min(aAt(uv+ex), aAt(uv-ex)), min(aAt(uv+ey), aAt(uv-ey)));
    a = min(a, aE);                     // ← 最外圈 1.5px 的 alpha 被削成 0
}
...
fragColor = vec4(c, a);                 // ← 输出 alpha 也用了腐蚀值
```

去白边的意图是"不画最外圈那 1.5px 的污染像素"，但代价是**人物轮廓真的向内缩了 1.5px**。
光包裹强的时候，附加光把这圈补上了看不出来；光包裹一弱，缺掉的那圈就露出来 ——
视觉上就是"边缘变透明、人物在融化"。

### 原因②：方向性羽化给到 0.45，等于 alpha 全范围混合

```glsl
float width = mix(0.45, 0.09, lit);                    // 背光侧 0.45
float blend = smoothstep(0.5 - width, 0.5 + width, p2.a);   // 0.05 ~ 0.95
```

背光侧从 alpha=0.05 一路混到 0.95，意味着**边缘 2~3px 大面积透出背景**。
这原本是为了"背光面融入环境"，但和原因①叠加后就是明显的融边。

## 1. Alpha 锁定

```glsl
// ★ aOut 是输出 alpha：严格等于原 Mask（绿幕抠图结果）
float aOut = p.a;

// 腐蚀只用于"光效门控"：rim / wrap / 环境色的作用范围
float a = p.a;
if (uShrinkPx > 0.05) {
    float aE = min(min(aAt(uv + ex), aAt(uv - ex)), min(aAt(uv + ey), aAt(uv - ey)));
    a = min(a, aE);        // 只影响门控，不出现在输出里
}
...
fragColor = vec4(c, aOut);
```

这样：
- **光效变弱 → 只有附加光消失，人物本体的不透明度一点不变**；
- 去白边的"不画污染像素"由 **Defringe 换色**承担（RGB 向内部色收缩，alpha 保留），
  而不是靠剪掉 alpha。

## 2. 合成羽化收窄

```glsl
float width = mix(0.18, 0.07, lit);     // 原 0.45 / 0.09
```

迎光 0.07（硬边）、背光 0.18（仍比迎光软，但不再透底）。方向性保留，融化消失。

## 3. 纯加性：Sum/Screen，不做颜色替换

架构上本来就是加性的，这里把等式写清楚（便于以后核对）：

```
人物层 P2      : color = base（线性化 + 明暗 + 矩阵），alpha = mask
光包裹 P2-w    : spill = lightColor × mask × height × occlusion × strength   (alpha=1)
最终合成        : out = screen(out, spill)        // 只加光，不动 alpha
```

对应的 shader 片段（合成阶段）：

```glsl
c = mix(c, p2.rgb, blend);                                  // 人物本体（按真的 alpha）
c = 1.0 - (1.0 - c) * (1.0 - clamp(p2w, 0.0, 1.0));         // 光：Screen 叠加
```

`wrapWeight → 0` 时 `screen(c, 0) = c`，严格等于原色 —— 不会变浅、不会变透。

> 光包裹内部**唯一的 `mix`** 是 `mix(bgLight, wrapColor, uColorMix)`：
> 那是"两个光源之间"调色，不是"光与人物本体"混合，不涉及 alpha。
> `uColorMix = 0` 时退回纯背景光，`= 1` 时纯双色光。

## 4. 环境色提亮（去脏）

背景暗部均值常常接近纯黑，直接叠在人物侧面会像糊了一层灰泥。加一道**保色相的提亮**：

```glsl
vec3 ambient = uAmbientColor;
float al = max(dot(ambient, LUMA), 1e-3);
// 按亮度归一，抬到不低于 uAmbientLift(0.25)；高于它则保持原亮度
ambient *= max(al, uAmbientLift) / al;
// 轻微提饱和，避免"抬亮之后变成灰白"
ambient = mix(vec3(dot(ambient, LUMA)), ambient, 1.15);
```

效果：亮度足够低仍是"暗部光"，但**有色彩、有饱和度**，不是 0.02 的灰泥。

## 5. 防回归

`ShaderAlphaContractTest`（3 个用例，静态解析 shader 字符串）：

1. `FRAG_P2` 必须 `float aOut = p.a;` 且输出 `vec4(c, aOut)`，
   **断言 `fragColor = vec4(c, a);` 不存在** —— 直接钉住这次的事故；
2. `FRAG_P2W` 输出 alpha 必须是 `1.0`（光缓冲不承载人物）；
3. `FRAG_COMPOSITE` / `FRAG_FXAA` 输出 alpha 必须是 `1.0`。

## 6. 真机验收

1. 把「光包裹强度」滑杆从 0.5 拉到 0.05，观察边缘：**轮廓形状不应变化**，只是那层光消失。
2. 逆光场景看背光侧下摆：不再有"半透明融入背景"的雾状带。
3. 底部（脚踝附近）：高度衰减到 0 时，人物该有的实边还在。
4. 若轮廓仍偏细（比原图窄 1px）：把 `edgeShrinkPx` 从 1.5 降到 0.8，或设 0 完全关闭腐蚀。
