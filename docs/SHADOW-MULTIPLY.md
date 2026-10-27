# Multiply 物理化：环境暗色 + 接触阴影 + 全局压暗（v0.10.0）

## 1. 严格 Multiply，不用 Alpha 覆盖

```glsl
// 合成（FRAG_COMPOSITE）：阴影是**乘**上去的，不是盖上来的
vec3 c = p1 * p2c;                          // ← 逐通道相乘，底图细节全部保留
c = mix(c, p2.rgb, smoothstep(0.0, 1.0, p2.a));
```
`base * blend` 的意义：blend 里的**明暗结构**（模糊后的阴影渐变）会作为"乘法遮罩"作用在底图上，
所以地面的纹理、砖缝、噪点都还在。若换成 `mix(base, shadowColor, alpha)`，这些细节会被一次性抹平 —— 这就是"塑料感"的来源。

`1.0` 是 Multiply 的恒等元，因此多层阴影可以**直接连乘**而互不污染：
```glsl
vec3 f = mix(vec3(1.0), uShadowColor, soft)        // 环境软阴影
       * mix(vec3(1.0), uContactColor, contact);    // 接触阴影
```

## 2. 阴影颜色：背景暗部中位色（严禁纯黑）

`core-cv/shadow/ShadowTint.kt`：
```
按 Rec.709 亮度排序 → 取最暗 12% → 逐通道取中位色 → 钳制到 [10, 92]
```
- **下界 10**：低于它的颜色在 Multiply 里等同于纯黑（`base * 0.04` 基本没信息）；
- **上界 92**：超过它就不再压暗，说明这张背景没有暗部（全白底），此时宁可退化成中性暗灰；
- 12% 而不是 5%：5% 容易只抓到单个暗角像素，中位色抖动大。

取色在 **480px 缩略图**上算（亮度分布是低频，中位色差异 < 3/255），不额外付全图排序的代价。
接触阴影色 = 环境暗色 × 0.55，每通道保底 6 —— 更深，但仍不是 0。

## 3. 接触阴影（Contact Shadow）

```glsl
// 沿 +y 下移 3.5px（按 h/1080 缩放）采样 Mask，3 tap 取均值后**硬化**
float contact = smoothstep(0.45, 0.85, cAcc / 3.0);
contact *= (1.0 - smoothstep(0.85, 0.95, a)) * uContactStrength;   // 只落人物外侧
```
- 偏移只有 2~5px、阈值区间窄（0.45→0.85）→ 得到"极窄极硬"的一条暗边；
- 强度 0.9 接近 1：这一层的作用不是"投影"，而是**让人物与背景在交界处咬合**；
- 与软阴影叠乘后：脚下最深，向外迅速过渡到环境阴影 → 立刻"站住"，不再"飘"。

## 4. 全局压暗（Global Multiply）

```glsl
float darkMask = 1.0 - smoothstep(0.02, 0.30, lum);   // 纯黑=1，中高调=0
c *= mix(1.0, uGlobalMultiply, darkMask);              // 默认 0.85
```
**只在暗部生效**。全图无差别 ×0.85 等于降曝光（高光一起灰掉，画面更平）；
电影感来自"暗部压下去、高光保住"的对比扩张。四周的压暗仍交给 Vignette 节点——两件事分开，参数才能单独调。

## 5. P1 镜头光晕：中心 Add、外围 Multiply

```glsl
c += flareCore;                                            // ① 中心核：相加（能超过 1.0）
c = 1.0 - (1.0 - c) * (1.0 - clamp(flareHalo, 0.0, 1.0));  // ② 光晕/拉丝/鬼影：Screen
c *= 1.0 - 0.22 * ring * uFlareStrength;                   // ③ 外圈：Multiply 压暗
```
- Screen 的上界被卡在 1.0，**永远做不出"过曝的白心"**，只有 Add 能推过 1；
- 外圈只加不减会让整块画面发白发飘（veiling glare 反而降低外围对比），必须补一层 Multiply。

## 默认值

| 项 | 值 | 说明 |
| --- | --- | --- |
| shadowColor | 背景暗部中位色 | 无暗部时退化为 `0xFF1A1C22` |
| contactShadowStrength | 0.9 | 0 = 关闭 |
| contactShadowPx | 3.5 | 2~5px，按 h/1080 缩放 |
| globalMultiply | 0.85 | ≥0.999 时调色 pass 整段跳过 |
| flare 外圈压暗 | 0.22 × strength | — |
