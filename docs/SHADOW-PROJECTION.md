# 逆光阴影投影：CPU 轴 + 定向模糊 + 距离衰减（v0.17.0）

## 1. CPU 端：`calculateShadowAxis` + uniform 打包

```kotlin
object ShadowShear {
    const val MIN_DOWN = 0.25f      // 影子必须朝画面前方
    const val SHEAR_GAIN = 0.6f
    const val SHEAR_BASE = 0.25f

    fun calculateShadowAxis(anchorX, anchorY, lightX, lightY): Axis {
        var dx = anchorX - lightX
        var dy = anchorY - lightY
        if (dy < MIN_DOWN) dy = MIN_DOWN          // 光源低于锚点 → 强制朝下
        val len = sqrt(dx*dx + dy*dy)
        val ux = dx / len; val uy = dy / len
        return Axis(ux, uy, shear = SHEAR_BASE + abs(ux) * SHEAR_GAIN, lengthY = dy)
    }
}
```

**uniform 打包**（`Axis.pack(...)` → `ShadowUniforms`）：阴影有 9 个 uniform，散着手写
`uni2f/uni1f` 极易错位（把 `shear` 传给 `falloff` 编译器不会报，只会"看起来怪"）。
集中成一个结构体后，单测可以直接断言打包结果与钳制行为。

| uniform | 来源 |
| --- | --- |
| `uShadowDir` | `dirX/dirY`（单位向量，dirY>0） |
| `uAnchor` | 脚底锚点 `(0.5, 0.9)` —— 距离衰减与模糊的原点 |
| `uShear` | `0.25 + |dirX|*0.6` |
| `uReachPx` | `shadowOffsetPx * h/1080` |
| `uFalloff` | 0.65 |
| `uBlurBasePx` / `uBlurGrowPx` | 2.5 / 18 |

⚠️ 锚点必须与算轴时用**同一个点**，否则距离衰减会整体错位。

## 2. Shader：push 模型 + 沿轴一维模糊

```glsl
vec2 dir = uShadowDir;
dir.y = max(dir.y, 0.0);                    // 方向本身也不许向上
dir = normalize(dir + vec2(0.0, 1e-4));     // 退化时默认朝下
vec2 perp = vec2(-dir.y, dir.x);

// 当前像素沿轴距锚点的距离（像素）
vec2 deltaPx = (uv - uAnchor) / uTexel;
float along = max(0.0, dot(deltaPx, dir));
float distK = clamp(along / max(uReachPx, 1.0), 0.0, 1.0);

float reach  = uReachPx * mix(uSoftness, 1.0, uBacklit);
float blurPx = uBlurBasePx + distK * uBlurGrowPx * uShear;   // 越远越糊

for (int i = 0; i < 12; i++) {
    float k = (float(i) + 0.5) / 12.0 - 0.5;                 // -0.46..0.46
    float d = clamp(along + k * blurPx, 0.0, reach);          // 沿轴位移（像素）

    vec2 offPx = dir * d;
    offPx.y = max(0.0, offPx.y);                              // ★ 安全垫

    float lat = k * blurPx * uShear * 2.0;                    // 横向展宽（错切）
    vec2 sp = clamp(uv - offPx * uTexel + perp * lat * uTexel, 0.0, 1.0);
    acc += aAt(sp);
}
float shadowMask = acc / 12.0;
shadowMask *= 1.0 - uFalloff * distK;      // 根部重、末端淡
shadowMask = smoothstep(0.10, 0.60, shadowMask) * uStrength;
shadowMask *= 1.0 - smoothstep(0.55, 0.90, a);   // 人物自身不压暗
```

**三个要点**

1. **push 模型**：像素 p 落在影子里 ⟺ "p 沿轴回退 d 处有人物"。所以采样 `uv - offPx`。
   这比"往光源方向 gather"更直观，而且**位移量显式可控** —— 才能加安全垫。
2. **定向 ≠ 高斯**：抖动只发生在 `dir` 方向（`k*blurPx`），横向仅随 `shear` 轻微展宽。
   如果用二维高斯，影子会变成一团没有方向感的糊斑。
3. **距离越远步长越大**：`blurPx = base + distK * grow * shear` —— 近处是实边，
   远处是大范围柔化，这正是真实投影的"近实远虚"。

## 3. 安全垫（防向上偏移）

三处，逐级兜底：

```glsl
dir.y  = max(dir.y, 0.0);          // ① 方向向量
dir    = normalize(dir + vec2(0.0, 1e-4));   // ② 退化兜底
offPx.y = max(0.0, offPx.y);       // ③ 每个采样的实际位移
```
即使 CPU 传来脏数据（光源在锚点下方）或出现浮点误差，影子也不会向上偏一像素。

## 4. 接触 AO（逆光也踩实）

```glsl
float contact = smoothstep(0.45, 0.85, cAcc / 3.0);   // 向下 3px 的极窄带
contact *= (1.0 - smoothstep(0.85, 0.95, a)) * uContactStrength;
```
与投影两个 Multiply 场逐通道相乘，互不污染。

## 5. 阴影只作用 P1（结构已验证）

```glsl
vec3 c = p1 * p2c;              // ① 只乘背景
c = mix(c, p2.rgb, blend);      // ② 人像随后覆盖
c = screen(c, p2w);             // ③ 光包裹在其后
```

## 已知限制

- 锚点固定 `(0.5, 0.9)`，未做 Mask 底边检测；人物明显偏左右时方向会有偏差。
- `shadowFalloff / blurBase / blurGrow` 仍是 `Params` 常量，无 UI 滑杆。
