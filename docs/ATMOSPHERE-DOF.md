# 景深修正 + 大气透视（v0.12.0）

## 1. 景深：从"整张背景糊死"改成"环形焦外"

**上一版为什么糊成色块**：`coc = 1 - smoothstep(far, near, mask)` 让整个背景（mask≈0）都拿到满半径，
9px 半径 × 16 tap 把纹理、建筑轮廓全抹平，只剩色块。

**现在的模型**：

```glsl
// ① 环形带：外扩 20px 内有人物，且自己不是实心主体
float near = max over 8 dirs of aAt(uv + dir * uRingPx);   // dilate 检测
float ring = smoothstep(0.02, 0.50, near) * (1.0 - smoothstep(0.55, 0.92, a));

// 远处背景 & 人物内部：零采样、零模糊（细节 100% 保留）
if (ring < 0.01) { fragColor = vec4(sharp, 1.0); return; }

float r = uMaxRadiusPx * ring;   // 半径随 ring 渐变，上限 5px
```

| 区域 | 行为 |
| --- | --- |
| 人物内部（a > 0.92） | 绝对清晰，`ring = 0` |
| 轮廓外 0~20px | 半径 1~5px 轻微散焦（焦外感） |
| 20px 以外 | **完全不处理**，纹理/轮廓保持原样 |

这同时兑现了规格的"替代方案"：环形区本来就是高频突变处，等价于只模糊边界高频。
代价也比原来低 —— 大部分像素走 early-return，不做 12 次采样。

## 2. 大气透视（合成节点内）

### 2.1 颜色渗透（Light Bleed）

```glsl
float bleedPx = mix(uBleedPxMax, uBleedPxMin, lit);   // 迎光 10px / 背光 20px
float minA = min over 8 dirs of aAtP2(uv + dir * bleedPx);
float innerProx = 1.0 - minA;                          // 贴轮廓=1，内部=0
float inside = smoothstep(0.55, 0.95, p2.a);           // 只在人物本体
float bleedW = innerProx * innerProx * inside;         // 平方衰减
c = screen(c, p1 * uBleedStrength * bleedW);
```
- `p1` 就是**人物背后那层背景的颜色**（含光晕/辉光），所以渗进来的正是环境固有色；
- **平方衰减**是关键：线性衰减会让整个人物罩上一层雾、发白；平方只在最外一圈生效。
- 宽度用 `lit` 插值 —— 迎光面窄（锐利）、背光面宽（消散进环境），和前一步的方向性羽化同一套 `LightDir`。

### 2.2 亮度/权重渐变（方向性羽化）

```glsl
float width = mix(0.45, 0.09, lit);
float blend = smoothstep(0.5 - width, 0.5 + width, p2.a);
c = mix(c, p2.rgb, blend);
```
靠近边缘的过渡带里背景权重上升、人物权重下降，视觉上"人物长在空气里"而不是切在背景上。

### 2.3 环境色罩

```glsl
c = mix(c, uAmbientColor, uAmbientTint * smoothstep(0.2, 0.9, p2.a));
```
`uAmbientColor` = 背景**均值色**（草地绿 / 夕阳橙），`uAmbientTint = 0.05`。
只罩人物区域 —— 背景本来就是这个色，再罩一次没有意义。

## 3. 参数默认值

| 参数 | 值 | 备注 |
| --- | --- | --- |
| `dofRadiusPx` | 4 | 硬上限 5（shader 内 `coerceIn(1,5)`） |
| `dofRingPx` | 20 | 交界带宽 |
| `bleedPxMin` / `bleedPxMax` | 10 / 20 | 迎光 / 背光 |
| `bleedStrength` | 0.35 | 0 = 关闭（省 8 次采样） |
| `ambientTint` | 0.05 | 0 = 关闭 |

## 4. 两条管线同步

DAG 侧的 `SHADER_DOF` 也换成了环形版本（`ringPx` 节点参数），
`PostChainTest` 新增断言：**景深半径 ≤5px 且必须带 ringPx** —— 防止再次回归成"整张背景糊死"。
