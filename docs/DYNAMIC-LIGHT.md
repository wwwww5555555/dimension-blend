# 动态光影跟随：让包裹光"指哪打哪"（v0.28）

> 涉及 `FRAG_P2` / `FRAG_EMIT` / `FRAG_LIGHTWRAP` / `FRAG_P2B` / `FRAG_COMPOSITE` 的迎光判定，
> 新增 `core-common/model/LocalLightSample.kt`。
> 离线对照：`docs/dynamic-light-sim.png`（`tools/sim_dynamic_light.py` 生成）。

---

## 一、根因：点积的第二个操作数是常数

用户报的现象是"光源移到左/右/正中，包裹区域不跟随"。检查后发现**法向没错、点积也没错**，
错的是它乘的那个方向：

```glsl
// 修复前（5 个 pass 全部如此）
vec2 l2 = uLightDir.xy;                 // ← CV 方位角直方图给的**一个常数**
float facing = dot(nOut, l2 / ll);      // 整张图共用同一个方向
```

问题在于 `uLightDir` 与 `uLightPos` 是**两套独立统计**：

| 量 | 来源 | 特点 |
| --- | --- | --- |
| `uLightPos`（光源坐标） | NMS 找最亮连通区 → 加权质心 | 精确到像素 |
| `uLightDir`（光照方向） | 亮部方位直方图 → 主峰角度 | 一个标量角度，全图共用 |

单光源、人物居中的画面里两者大致一致，所以**平时看不出来**；
一旦多光源（窗 + 灯）、光源偏心、或人物被拖到画面一角，方向就与位置脱节——
这就是"光在左边、包裹却在右边"。

而且它属于**静默错误**：不崩溃、不报错、参数都在传，只是指错了边。

---

## 二、修法：逐像素算，不用全局方向

```glsl
/** 像素空间：当前像素 → 光源的单位向量。1/uTexel = (w, h)，宽高比已修正。 */
vec2 lightDirPx(vec2 uv) {
    vec2 d = (uLightPos - uv) / uTexel;
    float l = length(d);
    // 光源与像素重合时 normalize 会得到 NaN → 退化为"正上方"
    return l > 1.0 ? d / l : vec2(0.0, -1.0);
}
```

**为什么必须除以 `uTexel`**：`uv` 是 0~1 的归一化坐标，长宽比不是 1 时，
`uLightPos - uv` 的方向不是真实屏幕方向。例如 16:9 画面上"左上 45°"在 uv 空间是
`(-0.7, -0.7)`，但屏幕上是 `(-0.7×1920, -0.7×1080)` → 实际角度 60° 而非 45°。
除以 `uTexel`（即乘 `(w, h)`）之后，x/y 分量单位一致，45° 就是 45°。

同理，**法向也必须在像素空间算**：

```glsl
// uv 空间的差分每 texel 在 x/y 上对应的长度不同 → 直接归一化会偏角
vec2 gPx = vec2(aR - aL, aD - aU) / (2.0 * uTexel);
float gLen = length(gPx);
vec2 nPx = gLen > 1e-6 ? -gPx / gLen : vec2(0.0);   // 梯度指内 → 取负为外法向
```

点积仍然是那一行，但两个操作数现在**都随像素位置变化**：

```glsl
float NdotL = dot(nPx, lightDirPx(uv));
float lit   = smoothstep(0.0, 0.35, NdotL);   // 背光严格归零
```

效果就是用户要的：光源在右 → 只有 `N.x > 0` 的右缘亮；光源在左 → 只有 `N.x < 0` 的左缘亮。

> `uLightDir` 并没有完全废弃：它的 **z 分量**仍然有用（`cos(elevation)` 用来衰减
> 水平方向性），只是不再拿 `.xy` 当方向。见下面的 `wXY`。

---

## 三、顶光：两点同时趋 0 的失效

光源在画面正中间时，`uLightPos - uv` 对左右两侧边缘分别指向右和左，
两侧的 `N·L` 分别是 `+1` 和 `-1` —— 其实是**正确**的不对称。

真正会失效的是**光源在人物正上方**（`uLightPos ≈ 人物中心`）：所有边缘的
`lightDirPx` 都指向内部，`N·L` 全为负 → 整圈全灭。反过来若光源在人物背后正中心，
退化分支给出的"正上方"会让左右对称地亮 → **对称大白边**。

处理：

```glsl
// uv.y 向下，所以"法向朝上"是 nPx.y < 0
float topBias   = 1.0 - smoothstep(0.06, 0.20, abs(uLightPos.x - 0.5));
float upperEdge = smoothstep(0.0, 0.6, -nPx.y);
float facing    = mix(dirDot, upperEdge, topBias * 0.8);
```

`topBias` 在光源水平居中时 → 1（渐入带 0.06~0.20，避免位置微抖导致跳变），
把 80% 的权重压到"上边缘"，保留 20% 的 `N·L` 让它还有一点点方向感。
结果：顶光时包裹光集中在**头顶与肩线**，两侧显著减弱，不再是对称大白边。

为什么是 0.8 而不是 1.0：完全替掉会让顶光失去所有水平变化，人物像被贴了一圈顶灯；
留 20% 能保住"光源在略偏左/右"时的可辨识差异。

---

## 四、光源色也跟随：邻域取色

规格第 3 点。原来的 `uKeyLightColor` 是**全图亮度前 5% 的加权均值**，
多光源场景下会把冷暖互相抵消成灰（夕阳 + 蓝天 → 一坨灰粉）。

新增 `LocalLightSample.extract()`：以光源坐标为圆心、半径 = 长边 5%（1080p 下约 54px）
统计加权均值。两个细节：

1. **`luma²` 加权**：光源邻域里既有过曝核心（近白）也有色温明显的边缘。
   算术平均会把它们混成中间灰；`luma²` 让亮的那一圈主导，但不退化成"只取最亮点"。
2. **跳过 `luma < 8` 的像素**：邻域可能跨进阴影（光源在窗边时），那些像素会把颜色压脏。

```kotlin
val localKey = LocalLightSample.extract(stats.pixels, stats.w, stats.h, lightPosX, lightPosY)
val keyLightColor = if (localKey != 0) localKey else stats.keyRgb   // 样本不足则回退
```

半径小是刻意的：取太大反而把周围那圈"有颜色的光晕"稀释掉。

> 半径按**长边**比例算，不是固定 50px。因为这里处理的是长边 480 的缩略图，
> 固定 50px 在 480 图上是 10%、在 4K 全分辨率上是 1.2%，完全不是同一个语义。

---

## 五、改动清单

| 位置 | 改动 |
| --- | --- |
| `FRAG_P2` | ③ 伪立体光照的 `lw` 改为像素空间径向 · 动态光向；⑥ 逆光压暗的迎光轮廓改用 `lightDirPx`；新增 `lightDirPx()` |
| `FRAG_EMIT` | 法向/光向改像素空间 + 动态；新增 `uLightPos`、`lightDirPx()`、顶光偏置 |
| `FRAG_LIGHTWRAP` | 同上 |
| `FRAG_P2B` | 同上（顺带把 v0.27 的法向取负一并纳入像素空间） |
| `FRAG_COMPOSITE` | 方向性羽化的 `facing` 改动态（否则"迎光侧硬边"会留在旧方位） |
| `DefaultImagePipeline` | 新增 `LocalLightSample` 取色；`lightX/lightY` 与方向计算共用同一来源 |
| `Params` | 无新增字段（复用 `lightX/lightY`、`keyLightColorRgb`） |

管线里 4 处新增 `uni2f("uLightPos", params.lightX, params.lightY)`。

---

## 六、离线验证（`tools/sim_dynamic_light.py`）

三列 = 光源在 x = 0.14 / 0.50 / 0.86。上排旧写法，下排新写法。

**光晕质心 X 坐标**（只统计发光源图）：

| 写法 | 光源 0.14 | 0.50 | 0.86 | 结论 |
| --- | --- | --- | --- | --- |
| 旧（全局常数方向） | 0.538 | 0.538 | 0.538 | **完全不动** —— 就是用户看到的现象 |
| 新（逐像素动态） | 0.461 | 0.510 | 0.546 | 跟随光源移动 |

视觉上对照更直接：上排三张图的光晕**永远在人物右上**（因为那个常数方向指向右下），
下排分别是左缘、头顶肩线、右缘。

注意质心移动幅度（0.461~0.546）看着不大，是因为人物本身居中、
且"所有朝左的表面都受光"（包括左臂的内侧边缘）—— 这是正确的物理，不是 bug。

---

## 七、契约测试

`ShaderDynamicLightContractTest`（6 条，全部做过反向验证）：

1. 5 个光效 pass 都必须有 `uLightPos` + `lightDirPx()`，且函数体内必须是 `(uLightPos - uv)`；
2. **禁止任何 `dot(...)` 里出现 `uLightDir`** —— 这条是核心，直接掐死"退回全局方向"；
   同时禁 `vec2 l2 = uLightDir.xy`、`dot(..., uLightDir.xy / len)`、`dot(vec3(n,0), uLightDir)`；
3. 法向必须在像素空间（`/ (2.0 * uTexel)`）且取负；
4. 顶光偏置必须基于 `abs(uLightPos.x - 0.5)` 且用 `-nPx.y`；
5. `lightDirPx` 必须除以 `uTexel`（宽高比修正）——防止有人"简化"掉；
6. CPU 端必须调 `LocalLightSample.extract` 且**有回退**。

反向验证：在 P2 的迎光轮廓处把 `lightDirPx(uv)` 换成 `normalize(uLightDir.xy)`，测试立刻失败。

`LocalLightSampleTest`（9 条）：左红右蓝图分别取左/右得到对应色；纯黑返回 0；
样本 < 12 返回 0；NaN/Inf 不崩；坐标越界钳到边界；`luma²` 让亮侧主导。

---

## 八、已知局限

1. **没有透视**。`lightDirPx` 是屏幕空间方向，等价于"平行光 + 一点径向"的混合近似。
   真实点光源在 3D 里还有深度衰减，2D 合成拿不到深度。
2. **光源在人物轮廓内部**（比如人物挡住了太阳）时，`uLightPos` 落在人物身上，
   该处 `d → 0` 走退化分支。这是几何上的真实歧义，不会崩，但那一小块的方向是猜的。
3. **`LocalLightSample` 依赖缩略图**。缩略图长边 480，5% = 24px；全分辨率下等价 102px
   （1080p），略大于规格说的 50px。要严格 50px 就把 `RADIUS_RATIO` 改成 0.03。
