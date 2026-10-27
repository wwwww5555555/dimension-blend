# 明暗交界过渡带（Penumbra，v0.30）

> 涉及 `FRAG_P2` 的 ②e/⑥b、`FRAG_EMIT`、`FRAG_LIGHTWRAP`、`FRAG_P2B`、`FRAG_COMPOSITE`，
> 新增 `FRAG_ALPHA_LOW` 与 `core-common/model/Penumbra.kt`。
> 离线对照：`docs/penumbra-sim.png`（`tools/sim_penumbra.py` 生成）。

---

## 一、先说一个反直觉的结论

代码里**早就在用 `smoothstep`**，而且没有 `step()`、没有 `if (dot > 0)` 的硬分支。
按"排查硬切分"的思路找，会一无所获。

真正的病根是**空间尺度**：

```
法向由 1 texel 的 alpha 差分求出
  → 抠图边缘只有 2~3px 宽
  → 在这 2~3px 里，法向从"朝外"翻到"朝内"
  → N·L 从 +1 跌到 -1
smoothstep 只把这 2px 变成"斜率为 0 的 2px"
  → 视觉上仍然是一条线
```

离线实测（`tools/sim_penumbra.py`）：旧写法的 `litRatio` 从 1 降到 0 只用了 **1px**。
这就是"刀切"的量化解释。

**真实的半影来自光源有面积**：宽度 ≈ (光源尺寸 / 距离) × 距离。2D 合成里拿不到这些量，
能做的是把过渡带的**空间宽度**拉到 8~14px，并在带内混入环境色。

---

## 二、修法一：宽基线法向

### 2.1 P2：用 1/4 域低频 alpha

新增 `FRAG_ALPHA_LOW`（1/4 域，13-tap 模糊 alpha），轮廓被抹成 8~16px 的斜坡，
在它上面做 ±`uPenumbraPx` 的差分，法向就"慢"下来了：

```glsl
vec2 pbUv = vec2(uPenumbraPx) * uTexel;
float loL = texture(uPersonLoA, clamp(uv - vec2(pbUv.x, 0.0), 0.0, 1.0)).r;
float loR = texture(uPersonLoA, clamp(uv + vec2(pbUv.x, 0.0), 0.0, 1.0)).r;
float loU = texture(uPersonLoA, clamp(uv - vec2(0.0, pbUv.y), 0.0, 1.0)).r;
float loD = texture(uPersonLoA, clamp(uv + vec2(0.0, pbUv.y), 0.0, 1.0)).r;
vec2 gWide = vec2(loR - loL, loD - loU);
float eAmp = smoothstep(0.02, 0.30, length(gWide));
vec2 eN = length(gWide) > 1e-4 ? -normalize(gWide) : vec2(0.0);
```

> ⚠️ **不能复用 `uPersonLow`**：它输出的是模糊后的 **RGB**（alpha 恒 1），那是给 Unsharp
> 用的。alpha 必须单独模糊一次 —— 这就是新增 `FRAG_ALPHA_LOW` 的唯一原因。
> 成本：一张 1/4 域纹理（1MB）+ 一个 1/4 域 pass（≈3.4M 采样），可忽略。

### 2.2 其余 pass：基线换成各自的半径

| Pass | 基线 | 理由 |
| --- | --- | --- |
| `FRAG_EMIT` | `uBandPx`（环带半径） | 环带本身就是这个宽度 |
| `FRAG_LIGHTWRAP` | `uWrapPx`（渗透深度） | 与包裹带同宽 |
| `FRAG_P2B` | `3 texel` | 反光精度要求低，3 倍即可 |
| `FRAG_COMPOSITE` | `3 texel` | 方向性羽化的判定 |

**采样次数没有增加**（都是 4 次），只是偏移量变大。

---

## 三、修法二：`litRatio` 与光/影的平滑混合

```glsl
// 规格 1：区间跨过 0（-0.2 ~ 0.3），背光侧也有一点蔓延
float litRatio = smoothstep(-0.20, 0.30, dot(eN, lDir)) * eAmp;
// 规格 2：阴影权重与迎光比例严格互补
float shadowW  = 1.0 - litRatio;
```

**为什么必须乘 `eAmp`**：平涂人物内部没有法向，`N·L` 恒为 0。
不乘的话 `smoothstep(-0.2, 0.3, 0) = 0.35`，会给内部一个"35% 受光"的判定 ——
人物就压不黑了。内部必须强制按背光处理。

用处：

| 位置 | 用法 |
| --- | --- |
| P2 ⑥ 暗部压暗 | `c *= mix(vec3(uBacklitGlobal), target, shadowW)` |
| P2 ③⑤ 的 Screen 项 | `litGate = uBacklitMode > 0.5 ? litRatio : 1.0` |
| `FRAG_EMIT` | `lit = smoothstep(-0.20, 0.30, facing)` |
| `FRAG_LIGHTWRAP` | `facing = smoothstep(-0.20, 0.30, facing)` |

交界处 `litRatio` 从 0.9 到 0.1 跨越 5px（离线实测），光和影在此区间内按比例分配，
不会出现"两边都空"的断层。

---

## 四、修法三：环境光桥梁

交界带里「纯光」和「纯暗」都缺中间色，这是断层最显眼的位置。用背景代表色补一层：

```glsl
float bridge = 4.0 * litRatio * (1.0 - litRatio);       // 钟形：两端 0，中点 1
if (bridge > 0.001) {
    c = mix(c, softLight(c, uAmbientColor), clamp(bridge * 0.35, 0.0, 0.6));
}
```

**为什么是钟形而不是线性**：线性会在纯光端和纯暗端各留一个台阶 —— 那正是用户说的"断层"。
`4l(1-l)` 在两端导数为 0，自然融入。

用 **SoftLight** 而不是 Screen/Add：它是非加性的（s=0.5 恒等），亮部不会过曝、
暗部不会提亮，只在中间调起作用 —— 这正是"桥梁"该有的行为。

强度 0.35 是上限：再高会让交界带发灰、丢失对比。

---

## 五、修法四：投影末端柔化

```glsl
// 旧：线性衰减，distK=1 处导数不为 0 → 末端一条硬边
depth *= mix(0.30, 1.0, 1.0 - distK);

// 新：smoothstep 两端导数为 0，自然消散
float tail = 1.0 - smoothstep(0.35, 1.0, clamp(distK, 0.0, 1.0));
depth *= mix(0.05, 1.0, tail);
```

接触阴影同理：`smoothstep(0.40, 0.70)` → `smoothstep(0.28, 0.75)`（在 4px 内完成太陡），
外圈权重 0.40 → 0.55。

---

## 六、离线实测

`tools/sim_penumbra.py` → `docs/penumbra-sim.png`（上排全图、下排 3× 局部放大）。

沿 y=0.28 一条横线，`litRatio` 每 2px 采样：

| 写法 | 采样（x=318→340） | 过渡宽度 |
| --- | --- | --- |
| 旧（1 texel + `smoothstep(0, 0.35)`） | `0.06, 0, 0, 0, 0, 0 …` | **1 px** |
| 新（8px 宽基线 + `smoothstep(-0.2, 0.3)`） | `1.0, 1.0, 1.0, 1.0, 0.51, 0.24, 0.04, 0 …` | **5 px** |

512 图上的 5px 对应 1080p 的 ~10.5px，符合"半影"的视觉尺度。

视觉上：左下（旧）的迎光侧是一条锐利细线，右下（新）是一条从暗到亮渐变的宽带。

> sim 里用 8px 基线是为了在 512 图上看得清；产品 1080p 默认 `penumbraPx = 7`。

---

## 七、调参

| 想要 | 改什么 | 位置 |
| --- | --- | --- |
| 更柔/更硬 | `penumbraPx` 7 → 10 / 4 | `Params.penumbraPx` |
| 交界带更宽（法线方向） | `Penumbra.NDL_LO/HI` -0.2/0.3 → -0.35/0.45 | 规则层 |
| 桥梁更强 | `BRIDGE_STRENGTH` 0.35 → 0.45 | `Penumbra` |
| 不要桥梁 | 设 0（那一段就跳过） | 同上 |

**注意**：`penumbraPx` 同时控制三处（低频 alpha 模糊半径、差分基线、EMIT/LIGHTWRAP 的基线），
所以它调的是"整体软硬"，不能单独调某一处。

---

## 八、契约测试

`ShaderPenumbraContractTest` 级别的断言（分散在两个既有测试类）：

1. `litRatio = smoothstep(-0.20, 0.30, ...)` 必须存在（区间跨 0）；
2. `shadowW = 1.0 - litRatio` —— 严格互补，不允许各算各的；
3. 桥梁 `4.0 * litRatio * (1.0 - litRatio)` —— 钟形；
4. 法向基线**不能是 1 texel**（必须命中 `uPersonLoA` / `uBandPx` / `uWrapPx` / `3.0 * uTexel` 之一）；
5. 投影末端必须 `1.0 - smoothstep(0.35, 1.0, ...)`。

`PenumbraTest`（12 条）：两端 0/1、中点 0.5、`N·L=0` 处 >0.25（证明跨过 0）、
内部强制背光、单调无跳变（跳变 < 0.035，即 smoothstep 的理论斜率上限）、
互补性、桥梁钟形与两端归零。

---

## 九、已知局限

1. **过渡带宽度不随人物尺度自适应**。`penumbraPx` 是绝对像素，特写时偏窄、远景时偏宽。
   要自适应得按人物 mask 的高度算。
2. **宽基线在锐角/凹角处会"抹过头"**：两条边距离小于 `penumbraPx` 时（比如手指之间），
   法向会指向中间而不是各自向外。表现是指缝间出现一块多余的光。这是基线的固有限制，
   要靠距离场才能彻底解决。
3. **桥梁色是全局背景均值**（`uAmbientColor`），不是"交界处局部环境色"。
   局部取色需要按像素位置采样背景，成本与收益不成正比。
