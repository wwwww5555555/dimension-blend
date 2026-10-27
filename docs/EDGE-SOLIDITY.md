# 人物边缘变半透明（"融化"）：根因与修复（v0.36）

> 现象：背光面（尤其人物底部边缘）没有光包裹的地方，边缘看起来半透明、露出背景，像要融化。

## 一、先排除三个"看起来很像"的原因

| 怀疑点 | 实测 |
| --- | --- |
| Shader 把光效强度乘进了 alpha | **不是**。`FRAG_P2` 输出 `vec4(c, aOut)`，`aOut = p.a`（原始 mask），腐蚀后的 `a` 只用于门控。 |
| 预乘 alpha 多乘了一次 | **不是**。全程不开 `GL_BLEND`（`GlState.ensure2dPass` 显式 disable），混合都在片元里手算，`glBlendFunc` 根本不参与。`FRAG_KEY` 输出非预乘，`FRAG_P2B` 是预乘但只取 `.rgb` 做 Screen。 |
| 最终 Bitmap 带透明区 | **不是**。逐个 pass 扫过 `fragColor`：后处理链 6 个 pass 全部输出 alpha = 1.0。 |

`gl_FragColor = vec4(finalRGB, originalMaskAlpha)` 这条不变式**本来就是满足的**。
所以"半透明"不是 alpha 变低，而是 **背景被合成进来了**。

## 二、真凶：大气透视把背景 Screen 进人物内侧 20px

`FRAG_COMPOSITE` 的"大气透视"：

```glsl
float bleedPx = mix(uBleedPxMax, uBleedPxMin, lit);   // 背光侧 20px / 迎光侧 10px
float minA = min over 8 方向 of aAtP2(uv + dir * bleedPx * uTexel);
float innerProx = 1.0 - minA;                          // 贴轮廓=1，内部=0
float bleedW = innerProx * innerProx * inside;
vec3 bleed = p1 * uBleedStrength * bleedW;             // uBleedStrength = 0.35
c = 1.0 - (1.0 - c) * (1.0 - clamp(bleed, 0.0, 1.0));  // Screen
```

问题在 `innerProx` 的**空间尺度**：探针半径就是 `bleedPx`，所以在距轮廓 20px 以内的
任何像素上，8 个探针**全部落在轮廓外** → `minA = 0` → `innerProx = 1` → `bleedW = 1`。

也就是说：**人物内侧整整 20px 的一条带，被背景以 35% 强度 Screen 上去。**

背光侧没有光包裹去盖住它，这条带就赤裸地显出来 —— 观感正是"边缘半透明、快融化了"。
底部最明显，因为底部边缘通常没有迎光轮廓光，而且背景（地面）往往更亮。

### 量化

`tools/sim_alpha_lock.py`（背光侧，mask 下边缘 4px 抗锯齿斜坡）把"人物纯度"定义为
`blend × (1 − bleedW × uBleedStrength)`，1 = 完全是人物，0 = 完全被背景占据：

```
距下边缘 2~20px 平均纯度：0.653 → 0.984
实心区（mask ≥ 0.99）最低纯度：0.650 → 1.000
```

`docs/alpha-lock-sim.png` 中排那条灰带就是它。

## 三、修复（三处，都不改光影结构）

### ① 大气透视限制在 alpha 过渡带

```glsl
float edgeOnly = 1.0 - smoothstep(0.90, 0.99, p2.a);
float bleedW = innerProx * innerProx * inside * edgeOnly * (1.0 - bottomSolid);
```

深度信息由 **alpha 自己**提供，而不是由"探针半径"这个代理量提供。
实心区（a ≥ 0.99）彻底禁止渗背景；真正的"大气透视"只剩轮廓外 2~3px。

### ② 核心区 alpha 锁定 + 底部实心

```glsl
float bottomSolid = smoothstep(0.86, 0.96, uv.y);              // uv.y=1 是画面底部
float width = mix(0.18, 0.07, lit) * (1.0 - 0.75 * bottomSolid);
float blend = smoothstep(0.5 - width, 0.5 + width, p2.a);
blend = max(blend, smoothstep(0.90, 0.97, p2.a));              // ← 锁定
```

`blend = max(...)` 是硬保证：**mask ≥ 0.97 的像素 100% 是人物**，与光效强度、法线、
光源位置全都无关。底部再把羽化宽度压到 25%（人物与地面相接处不能软）。

### ③ 腐蚀不许吃掉实心内部

```glsl
a = min(a, aE);                                  // 形态学腐蚀（去最外圈污染像素）
a = max(a, smoothstep(0.95, 0.99, p.a));         // ← 核心区回填
```

腐蚀后的 `a` 只用于**光效门控**（不写进 `fragColor.a`），但它被门控共享：`a` 被削薄后，
暗部/底部的轮廓会被当成"过渡带"，光效位置与强度都跟着内缩，看起来就是轮廓被"吃掉了 1~2px"。
回填之后腐蚀只在过渡带生效。

## 四、为什么不加"底部兜底补光"

需求里提到"底部边缘因为缺光包裹显得单薄，可以补一点环境色"。**没有加**，理由：
底部单薄的直接原因是上面那条 20px 的背景渗透把边缘冲淡了；修掉之后底部自然变实。
再叠一层 Screen 会把边缘重新提亮，与"实心"目标相反，也违背"不改变光影结构"。

## 五、验证

- 契约测试新增 3 条（`ShaderAlphaContractTest`）：
  「合成必须把 mask 核心区锁成完全不透明」「背景渗透必须被 alpha 过渡带限制」「腐蚀不得吃掉 mask 核心区」；
  同时把"后处理链全部不透明"从 2 个 pass 扩到 6 个（DOF/GRADE/LOOKS/VIGNETTE 也查）。
- **反向验证**：删掉 `edgeOnly` 与 `blend = max(...)` → 立即 4 处失败；恢复后全绿。
- 405 用例 / 58 测试类全绿；APK 81.16MB，权限未变（无 INTERNET）。

## 六、相关参数（真机可调）

| 想要 | 改什么 | 位置 |
| --- | --- | --- |
| 边缘再实一点 | `bleedStrength` 0.35 → 0.2 | `DefaultImagePipeline` |
| 底部完全不许羽化 | `bottomSolid` 的 0.86/0.96 → 0.78/0.90 | `FRAG_COMPOSITE` |
| 过渡带更硬 | `width` 的 0.18/0.07 → 0.12/0.05 | 同上（**不要**把 0.18 往上加，那是融化感来源） |
| 关掉大气透视 | 管线 `bleedStrength = 0f` | `DefaultImagePipeline` |
