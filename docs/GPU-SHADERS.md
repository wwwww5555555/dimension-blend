# GPU Shader 代码沉淀（规格 8：专业级光影融合）

本文件的 GLSL 片段与 Kotlin 侧纹理逻辑是**规格 8 的 GPU 迁移蓝本**。当前产品路径是
**纯 Kotlin CPU 实现**（`BlendModes.kt` / `LightWrap.kt` / `EdgeHaloFix.kt` / `DirectionalShadow.kt`，
已单测钉住）——本文件的 shader 必须与 CPU 版**逐位对拍（误差 < 1/255）**后才能替换，
启用前置条件与成本评估见 `GPU-ASSESSMENT.md`（无真机验证前不启用）。

对拍基准：`BlendModesTest` / `LightWrapTest` 的断言就是 shader 输出应满足的数值契约。

---

## 一、Fragment Shader：混合模式（规格 8.1）

```glsl
precision highp float;

uniform sampler2D uBase;      // A：底图（合成结果）
uniform sampler2D uOverlay;   // B：叠加层（溢出光 / 阴影场 / 环境色场）
uniform int   uMode;          // 0=Screen 1=Multiply 2=SoftLight
uniform float uOpacity;       // 图层不透明度

vec3 screenMode(vec3 a, vec3 b)      { return 1.0 - (1.0 - a) * (1.0 - b); }
vec3 multiplyMode(vec3 a, vec3 b)    { return a * b; }
vec3 softLightMode(vec3 a, vec3 b)   { return (1.0 - 2.0*b)*a*a + 2.0*b*a; }  // pegtop 近似

void main() {
    vec3 a = texture2D(uBase, vTexCoord).rgb;
    vec3 b = texture2D(uOverlay, vTexCoord).rgb;
    vec3 r = (uMode == 0) ? screenMode(a, b)
           : (uMode == 1) ? multiplyMode(a, b)
                          : softLightMode(a, b);
    // 图层不透明度（Photoshop Opacity 语义）：与底图线性插值
    gl_FragColor = vec4(mix(a, r, uOpacity), 1.0);
}
```

CPU 对应：`BlendModes.screen / multiply / multiplyWithOpacity / softLight`（单测钉住数值）。

## 二、Fragment Shader：光包裹 Light Wrap（规格 8.2）

三个纹理输入：模糊背景（预计算）、环绕遮罩（预计算）、合成结果。

```glsl
precision highp float;

uniform sampler2D uComposite;   // 融合回贴后的合成结果
uniform sampler2D uBlurredBg;   // 步骤1：σ≈22 的高斯模糊背景（1/4 域 blur，CPU/降采样预计算）
uniform sampler2D uWrapMask;    // 步骤2：环绕带遮罩（R 通道，0~1）
uniform float uOpacity;         // 默认 0.4，滑杆 lightWrapStrength 等比驱动

void main() {
    vec3 comp  = texture2D(uComposite, vTexCoord).rgb;
    vec3 ambient = texture2D(uBlurredBg, vTexCoord).rgb;
    float m  = texture2D(uWrapMask, vTexCoord).r;

    vec3 spill = ambient * m;                        // 步骤3：溢出光
    vec3 r = 1.0 - (1.0 - comp) * (1.0 - spill * uOpacity);  // 步骤4：Screen
    gl_FragColor = vec4(r, 1.0);
}
```

**Wrap Mask 的构造**（对应 CPU `LightWrap.apply` 步骤 2，可用两个 pass 完成）：

```glsl
// pass1：双距离场 → 环绕带权重（innerDist/outerDist 由 Chamfer 距离场纹理给出；
//        CPU 版用 DistanceField.compute 跑两次：前景 bin 与背景 bin）
float innerDist = texture2D(uInnerDist, vTexCoord).r;   // mask 内到边界，px
float outerDist = texture2D(uOuterDist, vTexCoord).r;   // mask 外到边界，px
float inner = (isFg && innerDist < 3.0) ? 1.0 - innerDist/3.0 : 0.0;
float outer = (!isFg && outerDist < 4.0) ? 1.0 - outerDist/4.0 : 0.0;
gl_FragColor = vec4(vec3(max(inner, outer)), 1.0);
// pass2：对 pass1 输出做 σ=3 高斯模糊（两趟可分离盒式即可）
```

## 三、Fragment Shader：方向性渐变蒙版（规格 8.3）

迎光面过渡窄硬、背光面宽软。`w` 是各向同性过渡权重（CPU 版 `EdgeHaloFix.blend` 的
`smoothStep(core)`），方向修正项：

```glsl
uniform vec2  uLightDir;     // 光源来向 (cos az, sin az)，图像坐标 y 向下
uniform sampler2D uNormal;   // 外法向纹理（RG 通道，来自距离场梯度）
uniform float uW;            // 各向同性过渡权重（smoothStep(core)）——作为 base 传入

float facing = dot(normalize(n.xy), uLightDir);          // 迎光 +1，背光 -1
float w = clamp(uW + 0.35 * uW * (1.0 - uW) * facing, 0.0, 1.0);
// w=0/1 处修正项自然为零，与 CPU 版一致
```

CPU 对应：`EdgeHaloFix.blend(lightAzimuthDeg = ...)`，`DistanceField.outwardNormal` 求外法向。

## 四、Fragment Shader：Multiply 阴影（规格 8.4）

```glsl
uniform sampler2D uScene;
uniform sampler2D uShadowField;  // R：阴影强度场（DirectionalShadow.buildShadowField 的 GPU 等价）
uniform vec3  uShadowColor;      // 背景暗部中位色（ScenePalette.shadowColor / 255.0）
uniform float uOpacity;          // 阴影浓度

void main() {
    vec3 scene = texture2D(uScene, vTexCoord).rgb;
    float amount = texture2D(uShadowField, vTexCoord).r * uOpacity;
    // Multiply + 图层不透明度：亮处压得多、暗处几乎不动，带环境色倾向不死黑
    vec3 factor = 1.0 - amount + amount * uShadowColor;
    gl_FragColor = vec4(scene * factor, 1.0);
}
```

CPU 对应：`DirectionalShadow.composite` 的 `r * (1 - amount + amount * sr/255)`。

## 五、Kotlin 侧：EGL 上下文与纹理准备（启用时才写，当前为蓝本）

```kotlin
/**
 * 离屏 GLES 渲染器（启用 GPU 路径时才实现本类）。
 * 前置：真机验证 EGL 上下文、纹理格式、驱动精度差异（见 GPU-ASSESSMENT.md）。
 */
class OffscreenGlesRenderer(width: Int, height: Int) {
    // 1) EGL pbuffer 上下文（不依赖任何 View，后台线程安全）
    //    eglChooseConfig(EGL_RGBA_8, EGL_SURFACE_TYPE=PBUFFER) → eglCreatePbufferSurface
    // 2) 纹理上传：Bitmap → GL_TEXTURE_2D（GL_LINEAR / CLAMP_TO_EDGE）
    //    GLUtils.texImage2D(GLES11.GL_TEXTURE_2D, 0, bitmap, 0)  // 注意 premultiplied 语义
    // 3) FBO 离屏渲染：attach 纹理 → 绘制全屏 quad → glReadPixels 回读 ARGB
    //    回读是最大开销（1080p 一次 ~20-50ms），所以管线必须整段 shader 化，
    //    禁止 CPU/GPU 步骤交替（见 GPU-ASSESSMENT.md 二节）
    // 4) 3D LUT 用 GL_TEXTURE_3D + texture3D()（见 GlesLutSampler.kt 的既有 KDoc）
}
```

**Kotlin 侧 Mask/纹理的职责划分**（与当前 CPU 管线一致，只是执行位置不同）：

| 数据 | 生成方（保持 CPU） | 上传为纹理 |
| --- | --- | --- |
| 前景掩膜 sceneMask | 掩膜腐蚀/羽化管线 | `uWrapMask`/距离场的输入 |
| 距离场（内/外） | `DistanceField.compute` ×2 | `uInnerDist` / `uOuterDist` |
| 光照参数 | `LightParams`（估计一次） | uniform |
| PCT-Net 调色 | TFLite（QNN/NPU/GPU delegate） | 参数图纹理或 uniform 矩阵 |
| 溢出光的模糊背景 | `LightWrap` 步骤 1（或 GPU 降采样 blur） | `uBlurredBg` |

## 六、启用检查单

- [ ] 真机上 EGL pbuffer 上下文创建成功（各家驱动）
- [ ] shader 输出与 CPU 版逐位对拍（A/B 开关 + 误差 < 1/255），重点：mediump 色带
- [ ] 1080p 单 pass 耗时 + 上传/回读往返实测 < 对应 CPU 步骤
- [ ] `DebugConfig` 增加 `forceGles` 开关，可随时回退 CPU 路径
