# 落地细节三修：接触阴影 / 反弹光 / 白边熔断

> 版本：v0.22.0（versionCode 23）
> 改动文件：`GlesLayerPipeline.kt`（`FRAG_P2` / `FRAG_P2C` / `FRAG_P2W`）、`GlesFusionRenderer.Params`、`DefaultImagePipeline`
> DAG 节点架构未动。

## 一、底部：接触阴影 + 环境反弹光

### 1.1 接触阴影（`FRAG_P2C` 第 ⑤ 段）

```glsl
float cAcc = 0.0;
for (int i = -1; i <= 1; i++) {
    cAcc += aAt(uv + vec2(float(i) * uTexel.x, uContactPx * uTexel.y));  // 正下方 3.5px
}
// 阈值收窄到 0.55→0.80 = 极硬的一条（原来 0.45→0.85 太软）
float contact = smoothstep(0.55, 0.80, cAcc / 3.0);
// 高度加权：脚底/裙摆最强，保留 35% 基础量（构图偏上时也不至于完全悬空）
contact *= mix(0.35, 1.0, smoothstep(0.45, 0.85, uv.y));
contact *= (1.0 - smoothstep(0.85, 0.95, a)) * uContactStrength;   // 0.9
```

接触色 `uContactColor` 由 CPU 传入 `deepenShadow(shadowTint)`（环境暗色再压深），**不是纯黑** ——
纯黑 Multiply 会把地面纹理整块抹掉。

与投影阴影的关系是**逐通道连乘**（`1.0` 是 Multiply 的恒等元）：

```glsl
vec3 f = mix(vec3(1.0), uShadowColor, shadowMask)
       * mix(vec3(1.0), uContactColor, contact);
```

### 1.2 底部反弹光（`FRAG_P2` 第 ⑤b 段）

```glsl
float lower = smoothstep(0.55, 1.0, uv.y);
vec3 bounce = uQuadBottom * (uBounceLight * lower) * smoothstep(0.30, 0.80, a);
c = 1.0 - (1.0 - c) * (1.0 - clamp(bounce, 0.0, 1.0));   // Screen
```

- 颜色直接复用四区色板的**下区中位色** `uQuadBottom`（已经在 CPU 侧取好并转线性），
  不额外做一次降采样 —— 雪地/亮马路的白、草地的绿都会自然带上来。
- 强度 0.12（规格 0.10~0.15）。
- `smoothstep(0.30, 0.80, a)` 门控：只作用在人物上，透明区不加。

## 二、阴影层次：近深远浅 + 近实远虚

`FRAG_P2C` 从"单层阴影"改成**两层 max 合成**：

```glsl
float falloffK = clamp(distK * uFalloff, 0.0, 1.0);           // 0.80

// 软层：覆盖整段投影，随距离线性消隐 → 远浅、远虚
float softLayer = smoothstep(0.10, 0.60, occ) * mix(1.0, 0.0, falloffK);

// 硬核：只在根部 30% 出现，阈值更陡 → 近黑、近实
float coreLayer = smoothstep(0.45, 0.90, occ) * (1.0 - smoothstep(0.0, 0.30, distK));

float shadowMask = max(softLayer, coreLayer * 0.85) * uStrength;
```

沿轴一维模糊（不换高斯）：

```glsl
float blurPx = uBlurBasePx + distK * uBlurGrowPx * uShear;   // 2.5 → +26px
float d = clamp(along + k * blurPx, 0.0, reach);
vec2 offPx = dir * d;  offPx.y = max(0.0, offPx.y);          // 安全垫：永不向上
```

- 采样次数固定 12，**步长随距离增长** —— 这就是"近实远虚"，比换大核高斯便宜得多。
- `uFalloff` 0.65 → 0.80：末端几乎消失，层次拉开。

## 三、白边熔断（`FRAG_P2` + `FRAG_P2W`）

三层防护，从粗到细：

### 3.1 Shrink Mask（腐蚀）

```glsl
float aE = min(
    min(aAt(uv + vec2(er.x, 0.0)), aAt(uv - vec2(er.x, 0.0))),
    min(aAt(uv + vec2(0.0, er.y)), aAt(uv - vec2(0.0, er.y)))
);
a = min(a, aE);      // er = uShrinkPx(1.5) × texel
```

最外圈 1~2px 的 RGB 必然是背景色（白/绿），alpha 也在 0~0.5 —— **直接不画这一圈**。

### 3.2 Defringe（换色）

```glsl
// 4 邻域各取 2.5px 内侧，保留 alpha 最大者的颜色作为"内部色"
if (s0.a > bestA) { bestA = s0.a; inner = s0.rgb; }  // ×4
float w = uDefringe * (1.0 - smoothstep(0.55, 0.95, a));
c = mix(c, pow(max(inner, 0.0), vec3(2.2)) * uTint, w);
```

腐蚀会把轮廓削细，Defringe 补回来：**保留 alpha，只换颜色**。a≈1 的内部权重为 0。

### 3.3 严格边缘带（规格原式）

```glsl
float edgeBand(float m) {
    return smoothstep(0.01, 0.15, m) * (1.0 - smoothstep(0.85, 0.99, m));
}
```

- `FRAG_P2` 边缘光：`float band = edgeBand(a);` —— 只在 alpha 的过渡区发光。
- `FRAG_P2W` 光包裹：**必须用 dilation 后的 `wrapMask` 求值**，不能用原始 `a`：

```glsl
float insideCut = 1.0 - smoothstep(0.85, 0.99, a);
float outerRise = smoothstep(0.01, 0.15, wrapMask);   // wrapMask 已 dilate
wrapMask *= outerRise * insideCut;
```

> 用原始 `a` 求 `smoothstep(0.01,0.15,a)`，轮廓外侧 a≈0 → 权重 0 → 光包裹永远不会溢出到背景，
> 等于把这个节点取消了。这是规格公式在"wrap"场景下必须换变量的原因。

## 四、参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `edgeShrinkPx` | 1.5 | 腐蚀像素；调到 2.5 可去更重的白边，但极细发丝会消失 |
| `defringeStrength` | 0.6 | 边缘换色强度（0 = 关） |
| `bounceLightStrength` | 0.12 | 底部反弹光（规格 0.10~0.15） |
| `shadowFalloff` | 0.80 | 投影距离衰减（越大末端越淡） |
| `shadowBlurGrowPx` | 26 | 远端模糊增长（越大越"远虚"） |
| `contactShadowPx` | 3.5 | 接触阴影向下偏移 |
| `contactShadowStrength` | 0.9 | 接触阴影浓度 |

## 五、真机检查

1. **踩实**：脚底有一条 1~2px 的深色硬边；把 `contactShadowStrength` 调到 0 对比，应明显"飘起来"。
2. **反弹光**：雪地/亮地面时，人物小腿与裙摆下缘略微透出地面色；暗背景下应几乎不可见。
3. **阴影层次**：根部深且边缘清晰，向外逐渐变淡变虚，末端消失（不是一层均匀的灰）。
4. **白边**：发丝外侧不再有 1px 白圈；若仍有，先加 `edgeShrinkPx` 到 2.0，再提 `defringeStrength` 到 0.8。
5. **副作用**：`edgeShrinkPx` 过大时**细发丝会断**，这时退回 1.0~1.2 并靠 Defringe。
