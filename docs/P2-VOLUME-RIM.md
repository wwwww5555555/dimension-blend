# P2 体积光：伪法线 + 迎光金边 + 高光保护 + 冷暖分离（v0.19.0）

对照参考图（黄昏逆光公路）的四个特征：**人物整体压暗、右发丝金色边缘光、眼睛/鼻尖仍闪、暗部偏冷青**。

## 1. 伪法线（Sobel → lambert）

二次元是平涂，没有真实法线，只能用**自身亮度梯度**反推表面朝向：

```glsl
vec2 sobelLuma(vec2 uv) {
    // 3×3 亮度，Sobel 核
    float dx = (tr + 2.0*mr + br) - (tl + 2.0*ml + bl);
    float dy = (bl + 2.0*b2 + br) - (tl + 2.0*t2 + tr);
    return vec2(dx, dy);
}

vec2 gN = sobelLuma(uv) * uNormalStrength;
vec3 nPseudo = normalize(vec3(-gN, 1.0));          // z=1：平涂区的基准朝向
float lambertN = dot(nPseudo, normalize(uLightDir));
```

**两个细节决定成败**：

| 细节 | 原因 |
| --- | --- |
| 梯度**取负** `-gN` | 亮度增大方向指向"表面内侧"，法线要朝外。不取负会让明暗整体反过来 |
| z 分量固定 1.0 | 平涂区 lambert 稳定在中值；只有结构转折（鼻尖/脸颊/衣褶）梯度变大才拉开明暗 |

应用方式是**相对调制**，不是绝对曝光：
```glsl
float vol = mix(0.74, 1.20, smoothstep(0.30, 0.85, lambertN));
c *= mix(1.0, vol, smoothstep(0.20, 0.60, a));   // 只在人物内部
```
若写成 `c *= lambertN`，会和 ② 的 S 曲线打架（等于乘两次对比度），暗部会被压死。

## 2. 迎光金边（Rim）

```glsl
vec2 rw = vec2(uRimWidthPx) * uTexel;      // 采样半径 = 宽度（2~4px）
float aL = aAt(uv - vec2(rw.x, 0)); ...    // 4 向 → 外法向
float facing = max(0.0, dot(vec3(nOut, 0.0), normalize(uLightDir)));
float band  = smoothstep(0.30, 0.70, a) * (1.0 - smoothstep(0.80, 0.95, a));
c += uRimColor * pow(facing, 1.5) * band * uRimStrength * uLightIntensity * uRimGain;
```
- 颜色 `uRimColor` = 背景高光中位色（`bgHighlightColor`）—— 参考图里就是夕阳的暖白黄；
- `uRimGain` 默认 **2.6**（原来是硬编码 2.0）：逆光金边必须比顺光更亮才压得住背景；
- `uRimWidthPx` 同时决定**环带宽度**与**法向平滑度**：2px 硬朗、4px 柔化。

## 3. 高光保护（Specular）

```glsl
float specSrc  = lumaAt(uv);                                    // 取**原图** sRGB 亮度
float specMask = smoothstep(uSpecThreshold, uSpecThreshold + 0.12, specSrc);   // 0.85 起
specMask *= smoothstep(-0.2, 0.5, lw);                          // 只在迎光侧
c = screen(c, uRimColor * specMask * uSpecStrength);
```
两个关键点：
1. 用**原图 sRGB 亮度**取阈值（0.85 这个数在 sRGB 空间才有直觉意义；线性空间 0.85 ≈ sRGB 0.93，几乎取不到）；
2. 乘迎光侧权重 —— 否则背光侧的眼睛反光也会闪，直接破坏剪影感。

## 4. 环境色分离（冷暗 / 暖亮）

```glsl
float split = smoothstep(0.30, 0.75, lambertN);       // 0 暗部 → 1 亮部

// 暗部：Multiply 冷暗（背景暗部色相 × 压低）
vec3 cool = mix(vec3(1.0), shadowChroma, 0.85) * mix(1.0, 0.82, uEnvSplit);
c *= mix(vec3(1.0), cool, (1.0 - split) * uEnvSplit);

// 亮部：Screen 暖亮（夕阳高光色）
vec3 warm = uRimColor * split * uEnvSplit * 0.45;
c = 1.0 - (1.0 - c) * (1.0 - clamp(warm, 0.0, 1.0));
```
`shadowChroma` = 背景暗部色**按亮度归一**后的色相（`uShadowColor / dot(uShadowColor, LUMA)`）。
直接乘线性暗色（≈0.01）等于纯黑 —— 这个坑在前面几版踩过两次。

## P2 完整执行顺序

```
① 原始(sRGB→线性)
② S 曲线 + Unsharp
②b 逆光方向渐变（smoothstep 距离场）
②c 脚底垂直渐变
③ 伪立体光照（屏幕空间方向）
③b 伪法线体积明暗（Sobel → lambert）   ← 新增
④ 四色环境光 SoftLight
④b 环境色分离（冷暗 Multiply / 暖亮 Screen）  ← 新增
⑤ 边缘光（2~4px 环带 × facing）
⑤b 高光保护（原图 luma>0.85 × 迎光侧）  ← 新增
⑥ 颜色矩阵（最后，保亮度）
⑦ 内部亮度上限
```

## 对照参考图

| 参考图特征 | 对应实现 |
| --- | --- |
| 人物整体暗于天空 | ②b 逆光方向渐变（暗端 0.55） |
| 右发丝金色细边 | ⑤ 边缘光（facing × band，暖色，gain 2.6） |
| 眼睛/鼻尖仍亮 | ⑤b 高光保护（阈值 0.85、仅迎光侧） |
| 暗部偏冷青 | ④b 冷暗 Multiply（背景暗部色相） |
| 脸/肩有结构明暗 | ③b 伪法线 lambert |
| 天空暖白、地面暖黄 | P1 光晕/辉光（不在 P2） |

## 代价

Sobel 9 次纹理采样 + 高光 1 次 = 每像素多 10 次采样；P2 总采样约 30 次。
只在「一键融合」全分辨率路径执行，拖动预览走 Compose ColorMatrix，不跑 shader。
