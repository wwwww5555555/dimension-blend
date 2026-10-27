# 光影三节点：光包裹 / 阴影 / 镜头光晕（v0.8.0）

> 运行时以 `core-cv/src/main/java/com/naiwa/core/cv/gles/GlesLayerPipeline.kt` 中的 raw string 为准；
> `docs/shaders/*.glsl` 是从该文件**程序化抽取**的评审副本（避免手抄漂移）。

## 图层树与执行顺序

```
P1     背景组       底图 → 全局 CC → 镜头光晕(Screen) → 辉光(Screen)
P2-c   阴影/背光    Mask → 射线模糊(逆光锐化 / 非逆光软投影) → Multiply 因子
P2     主人像       色矩阵(×0.6) → 边缘光(Add，法向·光向 + 内部熔断) → Alpha over
P2-w   光包裹       背景模糊光 × 环绕带 × 迎光 → Screen      ← 本轮新增
P2-b   立体反光     Mask + Bokeh + 受光面筛选 → Screen
Final  统一合成     4 层 + 简化 FXAA + sRGB
```

## 一、光包裹（`FRAG_P2W`，独立 pass）

**为什么必须独立 pass**：光源是**背景模糊光**（1/4 域 `uBgBlur`），而 P2 主 pass 只绑了人物纹理。
大白边的本质是边缘 RGB 仍是背景色，环境光包裹就是把它染回场景色 —— 这是防白边的主手段。

| 熔断门 | 表达式 | 作用 |
| --- | --- | --- |
| 实心内部 | `1 - smoothstep(0.90, 0.95, a)` | 脸/衣服强度归零 |
| 背光面 | `max(0, dot(nOut, lightDir))` | 背光侧不发光 |
| 远离轮廓 | `smoothstep(0.02, 0.55, reach)`（8 向 R 像素取最大 alpha） | 只贴在剪影附近 |
| 限亮 | `bgLight *= min(1, uMaxLuma / luma)` | 0.35，防整片过曝 |

⚠️ **外法向 = `-∇alpha`**：alpha 梯度指向内部，不取负会让一整侧边缘光熄灭（RimLight 踩过）。

## 二、阴影（`FRAG_P2C`）

```glsl
vec2 radial = normalize(uv - uLightPos + 1e-5);              // 从光心向外发散
vec2 dir    = normalize(mix(uShadowDir, radial, uBacklit));  // uShadowDir = 背离光源
float reach = uReachPx * mix(uSoftness, 0.55, uBacklit);     // 逆光更集中
```

- **逆光**：沿射线聚合 + `smoothstep(0.35,0.65)` 锐化 → 剪影后的硬暗部；
- **非逆光**：沿 `uShadowDir` 8 步平均 → 地面软投影；
- 一律 `× (1 - a)` 只落在人物外侧，避免脸部被压脏；
- ⚠️ **符号铁律**：`uShadowDir = -lightDir`。沿光方向偏移等于把影子画到光源那侧（本轮修掉的 bug）。

## 三、镜头光晕（`FRAG_P1` 内联）

定位到 NMS 光源 `(uLightPos)`，四层结构：

| 层 | 公式 |
| --- | --- |
| 高光核 | `exp(-d² · 26)` |
| 大光晕 | `exp(-d² · 3.2) × 0.26` |
| 横向拉丝 | `exp(-|dv.y|·55) · exp(-|dv.x|·2.6) × 0.30`（变形镜头特征） |
| 沿轴鬼影 | 3 个 `exp(-gd²·70)/i`，位置 `lightPos + (center-lightPos)·0.55i` |

**为什么不单开 pass**：纯 Screen 叠加满足结合律，内联省一张全分辨率纹理（2048² = 16MB）+ 一次全图读写；
功能上仍是独立节点（位置/半径/强度全走 uniform）。

## 角度 → uniform（`LightSkeletonBridge`，纯函数，10 单测）

```
angle(来向方位角) → 3D 指向光源向量:
  dirX = cos(θ)·cos(elev)
  dirY = sin(θ)·cos(elev)
  dirZ = sin(elev)              elev 默认 35°，越高水平分量越短（物理事实，非臆造）
阴影方向 = normalize(-dirX, -dirY)
```

坐标约定：x 向右、y 向下；0°=从右来，90°=从下来，180°=从左来，270°=从上来。

## 尺寸安全（仍生效）

- `GL_MAX_TEXTURE_SIZE` 与 2048 双限；所有 `texture()` 的 uv 均被 `clamp`；
- 光包裹外扩半径、阴影投影长度均按 `h / 1080` 等比缩放（4K 下不会糊成一团）。
