# P2 曝光体系：总预算 / 软膝 / 黑场 / 伪法线

> 版本：v0.20.0（versionCode 21）
> 目标：根治「人物整体泛白」与「内部明暗发平」。
> 全部改动集中在 `core-cv/.../gles/GlesLayerPipeline.kt` 的 `FRAG_P2`（+ `FRAG_BLOOM_APPLY`）。

## 0. 问题的机制（先定性，再改公式）

在 P2 内部，**提亮与压暗并不是对称的**：

| 步骤 | 类型 | 对明暗的影响 |
| --- | --- | --- |
| ② S 曲线 | 乘性（比值） | 拉开 |
| ②b 逆光渐变 | 乘性 | 压暗 |
| ③ 伪光 | 乘性压暗 + **加性**提亮 | 抵消 |
| ③b 伪法线 | 乘性（±） | 拉开/压缩 |
| ④ 四色 SoftLight | 混合（**非**加性） | 轻微 |
| ④b 冷暖分离 | 乘性冷 + **加性**暖 | 部分抵消 |
| ⑤ 边缘光 | **加性** | 只加 |
| ⑤b 高光保护 | **加性**（Screen） | 只加 |
| ⑥ 颜色矩阵 | 乘性但**保亮度** | 零 |
| ⑦ 亮度上限 | 硬 clamp | **削平** |

加性项有 4 处、外加泛光的 Screen 一次 —— **没有任何一处对总量负责**。
中间调被反复抬亮后超过 1.0，末尾 `clamp` 只负责把它们统统变成纯白：
这就是「泛白」；而纯白区域内部没有任何差异：这就是「发平」。同一个根因，两种描述。

## 1. 加法总预算（Add Budget）

所有加性项不再就地写回 `c`，而是累积到 `addAcc`：

```glsl
vec3 acBase = c;        // 受光前的反照率（Screen→加性 的换算基准）
vec3 addAcc = vec3(0.0);

addAcc += clamp(add, 0.0, 1.0) * (1.0 - acBase);   // Screen 展开成加性贡献
addAcc += warm * (1.0 - acBase);
addAcc += rimColor * ...;                          // Add 本身就是加性
```

统一结算：

```glsl
float baseL = max(dot(c, LUMA), 1e-4);             // 加法前的实际亮度
float addL  = dot(addAcc, LUMA);
float allowed = uAddBudget * max(baseL, uAddFloor); // 1.15 倍；暗部按 0.30 计基准
float kAdd = clamp((allowed - baseL) / addL, 0.0, 1.0);
c += addAcc * kAdd;
```

三个设计要点：

1. **按亮度统一压缩，通道比例不变** —— 逐层 clamp 会偏色，逐层缩放不会。
2. **`uAddFloor = 0.30`**：纯相对预算在暗部趋近 0（`1.15 × 0.02 = 0.023`），
   逆光剪影上的金色轮廓光会被算成 0 直接抹掉。暗部按 0.30 计基准。
3. **轮廓带单独预算** `uAddBudgetBand = 2.2`：
   边缘光在物理上就是强加性高光，按 1.15 倍压到只剩 15% 就彻底看不见了；
   而泛白发生在内部大片区域，不在 2~4px 的轮廓上 —— 两者分开才算对。

```glsl
c += addAcc * mix(kAdd, max(kAdd, kBand), band);
```

泛光（`FRAG_BLOOM_APPLY`）也加了余量衰减，避免"已经很亮的白衬衫再被推一层"：

```glsl
float room = 1.0 - smoothstep(0.72, 1.0, baseLum);
vec3 b = bloom * uStrength * mix(0.2, 1.0, room);
```

## 2. 软膝（Soft Knee）取代硬 clamp

旧写法 `min(c, vec3(cap))` 让所有 > cap 的像素**等于** cap：

```
0.82 0.88 0.93 1.10  →  0.80 0.80 0.80 0.80
```

一片同色平顶，高光层次归零。改成指数软膝：

```glsl
if (cL > uLumaCap) {
    float kneeK = max(uLumaCeil - uLumaCap, 0.02);      // 0.8 → 1.0，膝宽 0.2
    float newL  = uLumaCap + kneeK * (1.0 - exp(-(cL - uLumaCap) / kneeK));
    c *= newL / cL;                                      // 等比缩，不偏色
    c  = mix(c_old, c, 1.0 - band);                      // 轮廓带保留镜面溢出
}
```

性质：

- `cL ≤ 0.8`：完全不动（恒等）；
- `cL = 0.8` 处一阶连续（导数 = 1），不会出现亮度台阶；
- `cL → ∞`：渐近 `uLumaCeil = 1.0`，永远不超纯白。

同一组数值：`0.82 → 0.8199`、`0.88 → 0.876`、`0.93 → 0.912`、`1.10 → 0.958` —— 单调、有梯度、不过曝。

## 3. 黑场：S 曲线比值下限 0.20 → 0.08

```glsl
c *= clamp(target / max(l, 1e-4), 0.08, 3.0);   // 旧值 0.20
```

`0.20` 等于给黑场设了 20% 的地板：最深的暗部压不下去，观感就是"阴影发灰、蒙了一层薄雾"。
`0.08` 只用于防止低亮度除法放大噪声，不再充当亮度下限。

## 4. 伪法线：真归一化 + 平涂基准

### 旧版为什么等于没做

```glsl
// 旧
vec2 gN = sobelLuma(uv) * 0.55;        // 全分辨率梯度
vec3 n  = normalize(vec3(-gN, 1.0));
float lambertN = dot(n, normalize(lightDir));
float vol = mix(0.74, 1.20, smoothstep(0.30, 0.85, lambertN));
```

- 全分辨率梯度在二次元图上是**线稿描边**：只在 1~2px 处爆一下，平涂区 ≈ 0；
- 平涂区 `lambertN = lightDir.z ≈ 0.57`，`smoothstep(0.30,0.85,0.57) ≈ 0.55`，`vol ≈ 0.99`；
- 于是全图乘了一个 ≈1.0 的常数 —— 体积感为零，却额外付出 9 次采样。

### 新版

```glsl
vec2 gN = sobelLumaLow(uv) * uNormalStrength;   // 低频层（1/4 域模糊图）的梯度
vec3 nPseudo = normalize(vec3(-gN, 1.0));       // 真归一化：长度=1，z 随梯度收缩
float lambertN = dot(nPseudo, Lunit);
float dLambert = lambertN - Lunit.z;            // 平涂基准：平涂区 = 0
float vol = clamp(1.0 + dLambert * uNormalGain, 0.45, 1.90);   // 8.0
c *= mix(1.0, vol, smoothstep(0.20, 0.60, a));
```

三处关键：

1. **梯度取自低频层**。真正表达"脸颊/鼻梁转折"的是低频明暗结构；
   全分辨率梯度只能得到描边噪点（看起来是脏边，不是体积）。
2. **真归一化，强度乘在梯度上**。
   若按"`z = 1/strength`"的写法，倾角 `tanθ = |g|·s²` —— 调大强度反而更平，是反的；
   强度乘在梯度上，倾角与强度成正比（`tanθ = |g|·s`）。
3. **相对平涂基准调制** `dLambert = lambert - lightDir.z`。
   旧版围绕 0.55 居中，等于给全图加了固定偏置；新版平涂区严格 = 0（恒等），
   只有结构处正负分离 —— 不会和 ② 的 S 曲线重复乘对比度。

## 5. Debug 面板（人物区域直方图）

### 用法

点「✨ 一键融合」后在画布左下角出现一行绿色等宽文本（同时写入 logcat，tag `NaiwaCamera`）：

```
人物 p1=3 p50=96 p95=232 p99=238 Δ95→99=6 过曝1% 亮部OK 黑场OK
```

完整报告（多行）也会 `Log.i` 出去：

```
[hista] 人物区域直方图（样本 214331，sRGB 0~255）
 p1=3  p5=18  p50=96  p95=232  p99=238
 mean=104  std=71  过曝(≥250)=1.2%
 亮部平顶: 否
 黑场抬升: 否
 动态范围 p5→p95 = 214
```

### 判据

| 指标 | 判据 | 含义 |
| --- | --- | --- |
| 亮部平顶 | `p99 ≥ 200` 且 `p99 - p95 ≤ 2` | 高光被削成一块同色 |
| 黑场抬升 | `p1 ≥ 14`（≈5%） | 阴影发灰 |
| 过曝占比 | `luma ≥ 250` 的比例 | 泛白的直接量化 |
| 动态范围 | `p95 - p5 < 90` 时提示 | 对比不足 |

> `p99 ≥ 200` 这个前置条件是必要的：整张图最亮只有 120 时，`p95 == p99` 是正常的，
> 不能报"平顶"。这一条由 `PersonHistogramTest` 钉住。

### 实现与开销

- `core-cv/debug/PersonHistogram.kt`：mask alpha ≥ 230 的像素才算人物；步长采样上限 30 万点；
  256 桶直方图求分位（不排序，误差 ≤ 1/255）。
- 只在**全量融合**后调用一次；拖动/松手预览**不统计**（那两条路径每帧都跑）。
- 统计失败静默返回 null —— 调试设施不允许影响渲染。
- 开关：`DebugReport.enabled`（`:core-common/debug/DebugReport.kt`），关掉即零开销。

## 6. 参数一览

| 参数 | uniform | 默认 | 说明 |
| --- | --- | --- | --- |
| `addBudget` | `uAddBudget` | 1.15 | 最终亮度 ≤ 1.15 × 基色亮度（轮廓带除外） |
| `addBudgetFloor` | `uAddFloor` | 0.30 | 暗部基准下限，保住剪影轮廓光 |
| `addBudgetBand` | `uAddBudgetBand` | 2.2 | 轮廓带预算放宽倍数 |
| `personLumaCap` | `uLumaCap` | 0.80 | 软膝起点 |
| `personLumaCeil` | `uLumaCeil` | 1.00 | 软膝渐近上限 |
| `normalStrength` | `uNormalStrength` | 1.0 | 低频梯度放大倍数（0 = 关） |
| `normalGain` | `uNormalGain` | 8.0 | `dLambert → 明暗倍率` 的增益 |
| S 曲线比值下限 | — | 0.08 | 硬编码在 shader（防除零，不当地板） |

## 7. 真机验收清单

1. **泛白**：直方图 `过曝(≥250)` 应 < 3%；`p99` 明显高于 `p95`（Δ ≥ 10）。
2. **发平**：`p5→p95` 动态范围应 ≥ 120；脸/衣服上能看出结构的明暗过渡，而不是一整块。
3. **黑场**：`p1 ≤ 8`；阴影里仍能看出颜色倾向（不是纯黑，也不是灰）。
4. **金边**：逆光时右发丝仍有连续金色细边（轮廓带预算 2.2 的作用）。
5. 若整体偏暗：先抬 `personLumaCap`（0.85），再考虑 `addBudget`（1.25）；不要一次改两个。

## 8. 已知限制

- 伪法线的梯度尺度依赖素材分辨率：`uNormalStrength` 以 1080p 为基准，4K 下同样的
  归一化 UV 偏移对应更多像素，低频梯度会更陡 —— 若 4K 上结构对比过强，按 `h/1080` 反向缩放。
- 预算与软膝都作用在**线性空间**，与直方图（sRGB）刻度不同：
  直方图里 250 以上对应线性 ≈0.94 以上，两者不要直接互相套用阈值。
- 泛光仍会跨越 `uAddBudget`：它在 P2 之后单独成 pass，只按 `room` 衰减，不做全局预算。
