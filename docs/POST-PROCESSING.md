# 全局后处理管线（v0.9.0）

插在**四层合成之后、FXAA 之前**，顺序固定、不可交换：

```
合成(线性) → ① 景深 → ② 全局调色 → ③ Looks 漏光 → ④ 暗角 → ⑤ FXAA(sRGB 编码)
```

## 为什么必须先把 Final 拆开

v0.8 的 `FRAG_FINAL` 把"图层合成 + FXAA + sRGB 编码"写在一个 pass 里。后处理要插进去，
就必须先拆成 `FRAG_COMPOSITE`（线性空间，不做任何编码）和 `FRAG_FXAA`（最后一步才转 sRGB）。
**中间所有 pass 都在线性光空间里算**——这是"不发灰"的前提：在 sRGB 空间做模糊/调色会压暗中间调。

## 显存：5 个 pass 只占 2 张纹理

```kotlin
var curTex = texComp; var altTex = texA
fun postPass(name: String, setup: () -> Unit) {
    draw(programs[name], altFbo) { bind("uSrc", curTex, 0); setup() }
    swap(cur, alt)            // 刚写好的成为下一次的输入
}
```
2048² RGBA8 一张 16MB。若每个 pass 各占一张，5 个 pass 会多吃 48MB，中端机直接 OOM。

## ① 景深（DOF）

**收-集（gather）而不是"先模糊再混合"**：给每个采样点算它自己的弥散圆 `coc`，
只让同样处于背景的邻域参与。先整体模糊再按 coc 混合会让背景色渗进人物边缘（糊边）。

```glsl
float coc(float m) { return 1.0 - smoothstep(uFocusFar, uFocusNear, m); }  // 0.12 / 0.9
for (int i = 0; i < 16; i++) {
    float ang = float(i) * 2.39996;                       // 黄金角，避免规则条纹
    float rad = uRadiusPx * sqrt((float(i) + 1.0) / 16.0); // 圆盘内均匀分布
    vec2 sp = clamp(uv + vec2(cos(ang), sin(ang)) * rad * uTexel, 0.0, 1.0);
    float w = coc(texture(uMask, sp).a);
    acc += texture(uSrc, sp).rgb * w; wsum += w;            // 人物内部权重 = 0
}
fragColor = vec4(mix(sharp, acc / wsum, c0), 1.0);
```
半径按 `h/1080` 等比缩放；低端机（RAM<4GB 或 ≤4 核）直接传 0 → 整个 pass 不跑。

## ② 全局调色（COLOR_GRADE）

```
c = M3x3 · c → (c - 0.18) * contrast + 0.18 → mix(luma, c, saturation)
```
以**线性空间 18% 灰**为支点（不是 sRGB 的 128），否则暗部会被压死。
默认 1.05 / 1.06 的轻微胶片化；改回 1.0 且矩阵为单位阵时**整段跳过**。

## ③ Looks 漏光（LOOKS）

程序化生成，**不依赖任何贴图素材**（本仓库 assets 为空是既定策略）：
双高斯核 + 低频正弦扰动，Screen 叠加。`posX/posY/phase` 走 params，可接滑杆做"微调"。

## ④ 暗角（VIGNETTE）

⚠️ 规格给的 `smoothstep(radius, radius - softness, dist)` 里 `edge0 > edge1`，**GLSL 规范中是未定义行为**
（有的驱动返回 0、有的反向）。等价且合法的写法：
```glsl
float v = 1.0 - smoothstep(uRadius - uSoftness, uRadius, dist);
fragColor = vec4(c * mix(1.0, v, uStrength), 1.0);
```

## DAG 节点

`ProjectPresets.fusionProject` 在 `MIX_REFLECT` 与 `OUT_FXAA` 之间插入四个节点：

```
MIX_REFLECT → Post_Dof → Post_ColorGrade → Post_Looks → Post_Vignette → OUT_FXAA
                   ↑ 第二输入 = P2_Main（Mask 源）
```

`PostChainTest` 用拓扑序把顺序钉死（顺序错了不会崩，只会"画面看着不对"）。
关掉某节点（strength=0 / radius=0）**只跳过执行、不改变图结构**，保证参数来回切换时缓存不失效。
