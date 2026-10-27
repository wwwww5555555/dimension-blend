# P2 克制版：Reinhard 肩部 + 单一径向渐变

> 版本：v0.21.0（versionCode 22）
> 变更性质：**回退 v0.19 的伪法线与多重叠光**，重建最小可用的光影质感。
> 改动范围：`FRAG_P2`（重写）、`FRAG_FXAA`（加 tonemap）、`Params` / 管线默认值。
> DAG 节点架构**未动**。

## 0. 回退的原因（不是审美问题，是数学问题）

v0.19 起用 Sobel 伪法线 + 四处加性叠光做体积感，对**二次元平涂图**同时踩两个坑：

1. **加性项逐层叠加**：伪光 Screen、暖色 Screen、边缘光 Add、高光 Screen，
   每层各自看起来都很轻（0.1~0.3），但中间调被叠加 4 次后越过 1.0，
   末尾 `clamp` 把它们统一变成纯白 —— 这就是泛白。
2. **伪法线在平涂区恒为常数**：平涂区梯度 ≈ 0 → `lambert = lightDir.z` → `vol ≈ 0.99`，
   等于乘了一个 1.0；而在线稿描边处梯度爆点 → 只在描边产生脏边。
   结果是"该有体积的地方没变化，不该有边的描边上多了噪点"。

结论：对平涂素材，**乘性渐变 > 法线模拟**，**少而弱的加性 > 多而强的加性**。

## 1. 第一步：Reinhard 色调映射（HDR 保护）

### 位置

`FRAG_FXAA` —— 整条管线唯一的最终输出点，此时 P1 + P2 + 后处理全部合成完毕，
且仍在**线性空间**（后面才 `pow(1/2.2)`）。放在别处要么漏掉后处理结果，
要么在 sRGB 空间压高光（那会压暗中间调）。

### 公式

```glsl
float knee = clamp(uToneKnee, 0.0, 0.95);
vec3 over = max(c - knee, 0.0);
vec3 shoulder = (1.0 - knee) * (over / (over + vec3(1.0)));
vec3 toned = c - over + shoulder;      // over = 0 时严格等于 c
c = mix(c, toned, uToneMap);
```

**为什么不是整幅 `c/(c+1)`**：

| 线性输入 | 整幅 Reinhard | sRGB（原 → 压缩后） |
| --- | --- | --- |
| 0.05 | 0.048 | 0.26 → 0.25 |
| 0.36 | 0.264 | 0.63 → 0.55 |
| 0.69 | 0.408 | 0.85 → **0.67** |

整幅 Reinhard 会把整个中高调一起往下拉，观感是**发闷、发灰** —— 与"不破坏原图色彩分布"冲突。

肩部版把 `knee` 以下**严格恒等**，只压缩超出部分；`uToneKnee = 0` 时 shoulder 退化为
`c/(1+c)`，与规格给的公式**逐字相同**（`ToneCurveTest` 钉住这个等价关系）。

数值（knee = 0.6）：

| 线性输入 | 输出 | 说明 |
| --- | --- | --- |
| 0.50 | 0.500 | 恒等 |
| 0.787 | 0.663 | sRGB 0.85 → 0.81（只掉 4 个点） |
| 1.20 | 0.750 | 原本会裁成 1.0 |
| 2.00 | 0.833 | 仍保留层次 |
| ∞ | 1.000 | 渐近，**永不等于 1.0** |

### 加性强度统一压到"点缀级"

| 位置 | 公式 | 峰值增量 |
| --- | --- | --- |
| ③ 伪立体光照 | `uFakeLight(0.15) × 0.6` | ≈0.09 |
| ⑤ 环境亮部 Screen | `uRimColor × brightW × 0.15` | 0.15 |
| ⑥ 边缘光 Add | `rimStrength(0.35) × lightIntensity(~0.5) × uRimGain(1.2)` | ≈0.13 |
| ⑦ 高光保护 Screen | `uRimColor × specMask × 0.12` | 0.12 |

四处合计的**理论峰值 ≈0.49**，且分布在不同区域（伪光在迎光面、边缘光只在轮廓带、
高光保护只在原图 >0.85 处），重叠概率极低。即使重叠，Reinhard 也会平滑压回。

## 2. 第二步：基础径向渐变（替代伪法线）

```glsl
float dist = length((uv - uLightPos) * vec2(uAspect.x, 1.0));
float gradient = 1.0 - smoothstep(uGradMin, uGradMax, dist);   // 0.20 / 0.80
float gradK = mix(0.60, 1.10, gradient);
c *= mix(1.0, gradK, uGradStrength);
```

三个细节：

1. **`1 - smoothstep(min, max, dist)` 与规格 `smoothstep(0.8, 0.2, dist)` 数学等价**，
   但符合 GLSL 规范 —— `edge0 > edge1` 是未定义行为，部分驱动会返回 0 或反向。
2. **宽高比校正**：不乘 `aspect.x` 时，16:9 画面上的"圆"会变成横向椭圆，渐变带在左右两侧
   提前结束，人物的脸会被压暗。
3. **纯乘性，范围 0.60~1.10**：不产生任何 >1.1 的增益，也不会有 <0.6 的死黑。
   与 v0.19 的伪法线相比，这条曲线在平涂区**一定有变化**（因为它只依赖位置）。

## 3. 第三步：环境色融合

```glsl
// 暗部（gradient < 0.3）：只染背景暗部的"色相"，不动明暗层次
float darkW = 1.0 - smoothstep(0.0, 0.30, gradient);
c *= mix(vec3(1.0), shadowChroma, darkW * uEnvSplit);      // uEnvSplit = 0.45

// 亮部（gradient > 0.7）：极少量背景亮色，做环境呼应
float brightW = smoothstep(0.70, 1.0, gradient);
c = screen(c, uRimColor * brightW * 0.15);
```

`shadowChroma` 是 `uShadowColor` **按亮度归一**后的色相（`clamp(·, 0.4, 2.2)`）。
直接乘线性暗色（sRGB 26 ≈ 线性 0.01）等于纯黑，这个坑在 v0.10 踩过一次。

## 4. 最终顺序

```
① sRGB→线性 → ② S 曲线 → ②b Unsharp → ②c 径向渐变 → ②d 脚底
→ ③ 微弱点光 → ④ 四色 SoftLight → ⑤ 环境色（暗 Multiply / 亮 Screen 0.15）
→ ⑥ 边缘光（严格环带 + 迎光门）→ ⑦ 高光保护 → ⑧ 颜色矩阵（保亮度）
→ [图层合成 / 后处理] → FXAA → Reinhard → sRGB
```

P2 内部**不再有** clamp、亮度上限、加法预算 —— 高光统一交给 Reinhard 处理。

## 5. 参数一览

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `toneMapStrength` | 1.0 | Reinhard 总开关 |
| `toneMapKnee` | 0.6 | 膝点；`0` = 整幅 Reinhard（规格原式） |
| `toneMapExposure` | 1.0 | 线性曝光补偿（整体偏暗时 1.2~1.3） |
| `gradientMinDist` / `MaxDist` | 0.20 / 0.80 | 渐变带；差值越小交界越硬 |
| `gradientStrength` | 1.0 | 渐变混合比例 |
| `fakeLightStrength` | 0.15 | 伪立体打光（点缀） |
| `rimGain` | 1.2 | 边缘光增益（点缀） |
| `specStrength` | 0.12 | 高光保护 |
| `envSplitStrength` | 0.45 | 暗部环境色 Multiply |
| `feetFadeStrength` | 0.20 | 脚底压暗 |

### 已删除的参数（回退后不再使用）

`normalStrength`、`normalGain`、`backlitDarken`、`personLumaCap`、`personLumaCeil`、
`addBudget`、`addBudgetFloor`、`addBudgetBand`。字段、uniform、传参、管线默认值**同步删除**，
不留死参数（v0.4 曾因为"参数只写不读"埋过死代码）。

## 6. 调试面板仍然有效

点「一键融合」后画布左下角显示：

```
人物 p1=3 p50=96 p95=232 p99=238 Δ95→99=6 过曝0% 亮部OK 黑场OK
```

Reinhard 生效后应看到：`过曝(≥250)` 明显下降，`Δ95→99` 变大（高光有梯度而不是平顶）。
若 `Δ95→99` 仍是 0~2 且 `过曝` 为 0，说明**画面本来就没有高光**，不是被削平
（判据里 `p99 ≥ 200` 的前提会自动区分）。

## 7. 真机检查清单

1. **泛白**：`过曝(≥250) < 2%`；纯白区域消失，高光呈柔和过渡。
2. **发灰**：`p5→p95 ≥ 120`；若整体偏暗，抬 `toneMapExposure` 到 1.2，**不要**动 P2。
3. **体积感**：靠近光源一侧（头顶/肩）比远端（腿）亮，过渡带落在 0.2~0.8 的距离区间。
4. **暗部**：`p1 ≤ 8`，阴影偏背景暗部色相（深青/深蓝）而不是纯黑。
5. **边缘**：金边只在迎光侧，脸和衣服上没有白光。
