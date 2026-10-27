# 奶蛙相机 · 架构设计说明（v0.2）

> 目标：**完全本地运行、离线优先**。自动识别照片场景光源 → 把用户选定的奶蛙无缝融合进场景。
> 硬约束：不调用任何云 API、不上传图片、不依赖私有服务器；飞行模式下全功能可用。

**v0.2 相对 v0.1 的修正点见 `docs/ARCHITECTURE-CHANGES.md`（逐条对应 + 关键 diff）。**

---

## 1. 需求 → 实现路径对照

| 验收项 | 实现路径 | 阶段 |
| --- | --- | --- |
| 飞行模式完成 拍照/选图 → 选奶蛙 → 分析光源 → 融合 → 保存 | 全链路无网络调用；Manifest **不申请 INTERNET 权限**，`allowBackup=false`（连系统云备份都关掉） | P0→P2 |
| 色温/亮度与场景一致、有方向性阴影、无明显光晕 | Reinhard Lab 迁移（力度用 lerp，见 §6.3）+ 方向性阴影 + 泊松融合 + 边缘三段式（腐蚀/羽化/α 回贴） | P1→P2 |
| 1080p < 5s、内存 < 600MB、4K 长边 4096 导出 < 12s | 三档融合预算 + 自适应降级（§7.2），预览走低清链路；内存预算见 §8 | P0→P3 |
| 本地 Android Studio 可构建、不依赖云服务 | **Plan A 已知可用组合**（§2），全部依赖来自 Google Maven / Maven Central，版本集中在 `gradle/libs.versions.toml` | P0 |

---

## 2. 版本矩阵

**策略：默认 Plan A。** 不赌新版——Plan A 是「官方兼容性表已闭合 + 被大规模项目验证过」的组合；只有在你确认 IDE 支持时才升到 Plan C。

### Plan A · 默认（已写进 `libs.versions.toml`）

| 组件 | 版本 | 依据（已核对） |
| --- | --- | --- |
| Android Gradle Plugin | **8.7.3** | 官方：AGP 8.7 最高支持 **API 35**，最低 **Gradle 8.9**，SDK Build Tools 默认 **34.0.0**，**JDK 17** |
| Gradle | **8.9** | AGP 8.7 的最低要求版；在 Kotlin 2.0.21 支持的 Gradle 范围（7.5–8.10）内，无兼容警告 |
| JDK | **17** | AGP 8.7 起强制 |
| Kotlin / Compose 编译器插件 | **2.0.21** | Kotlin 2.0 要求 AGP ≥ 8.5 ✓；Compose 编译器与 Kotlin 同版本发布 |
| KSP | **2.0.21-1.0.27** | 与 Kotlin 2.0.21 严格同前缀（Hilt 注解处理依赖） |
| compileSdk / targetSdk / minSdk | **35 / 34 / 26** | compileSdk 35 = AGP 8.7 支持上限；targetSdk 34 按需求锁定 Android 14；minSdk 26 = Android 8.0 |
| Compose BOM | **2024.10.01** | Compose 1.7 / Material3 1.3.0，要求 compileSdk ≥ 34 ✓ |
| Hilt + androidx.hilt | **2.52 / 1.2.0** | 与 Kotlin 2.0.21 + KSP 组合经过大规模验证 |
| CameraX | **1.4.0** | Stability 稳定线，要求 compileSdk ≥ 34 |
| AndroidX 基础 | core-ktx **1.13.1**、lifecycle **2.8.6**、activity-compose **1.9.3**、navigation-compose **2.8.3** | 同期稳定版，compileSdk 34/35 兼容 |
| DataStore / ExifInterface | **1.1.1 / 1.3.7** | — |
| Coroutines | **1.9.0** | — |
| OpenCV（P1 起） | **4.10.0** | `org.opencv:opencv`，**仅在 Maven Central** |
| 端侧推理（P1 起） | **TensorFlow Lite 2.16.1** | LiteRT 是 TFLite 更名后的同一运行时。Plan A 采用同期验证最充分的坐标 `org.tensorflow:tensorflow-lite`，**API 与 `.tflite` 文件格式完全相同**，将来迁移到 `com.google.ai.edge.litert:litert` 只需改依赖坐标一行 |
| MediaPipe Tasks Vision（P2 起） | **0.10.18** | `com.google.mediapipe:tasks-vision` |
| 测试 | junit 4.13.2 / androidx.test.ext:junit 1.2.1 / espresso 3.6.1 | — |

### Plan B · 回退（IDE 较旧、拉不到 API 35 或 AGP 8.7 时）

只降三处，Kotlin 保持 2.0.21 不动，业务代码零改动：

| 组件 | 值 |
| --- | --- |
| AGP / Gradle | 8.5.2 / 8.7（AGP 8.5 的最低 Gradle 版本） |
| compileSdk | 34 |
| Compose BOM | 2024.06.00 |
| CameraX / Hilt / navigation / lifecycle | 1.3.4 / 2.51.1 / 2.7.7 / 2.8.0 |

启用条件：Android Studio Koala（2024.1）或更新，但环境拉不到 API 35。

### Plan C · 升级（仅在确认 IDE 支持后启用，非默认）

AGP 9.4.0 + Gradle 9.6.0 + Kotlin 2.3.21 + compileSdk 37 + Compose BOM 2026.09.00 + CameraX 1.6.1 + Hilt 2.60.1。
这组数字我已在官方发布页核实为 2026-10 的当期稳定版（**不是臆造**），但它们同时要求 **Android Studio 2026.2.1+ / API 37 / AGP ≥ 9.2**，且 AGP 9 有 DSL 破坏性变更——**因此不作为默认**，只在确认 IDE 支持时整体切换（一次替换 `libs.versions.toml`）。

---

## 3. 分层架构

```
┌────────────────────────────────────────────────────────────────┐
│  :app          单 Activity + NavHost + Hilt 聚合 + 权限          │
└──────────────┬───────────────────────────────┬─────────────────┘
               │                               │
     ┌─────────▼─────────┐         ┌───────────▼───────────┐
     │ :feature-camera   │         │ :feature-editor       │
     │ CameraX 拍照       │         │ 素材/抠图/变换/预览/导出 │
     └─────────┬─────────┘         └───────────┬───────────┘
               │ 只依赖 ImagePipeline 接口 + RenderRequest/RenderResult
               └───────────┬───────────────────┘
                           │
              ┌────────────▼────────────┐
              │ :core-pipeline          │ ★ ImagePipeline 实现（编排层）
              │ 唯一同时引用 core-cv+ai  │
              └──────┬────────────┬─────┘
                     │            │
        ┌────────────▼──┐   ┌─────▼──────────┐
        │ :core-cv      │   │ :core-ai       │  ← 两者之间【无任何依赖】
        │ 颜色/阴影/融合  │   │ 光照估计/抠图    │
        │ KelvinRgb     │   │ 传统CV兜底(纯Kotlin)│
        └───────┬───────┘   └───────┬────────┘
                └──────────┬────────┘
                           │
                 ┌─────────▼─────────┐
                 │ :core-common      │ 领域模型 + 管线接口 + 图像工具
                 │ （零原生、零 UI）   │
                 └───────────────────┘

   :data  素材库(manifest.json)/MediaStore/DataStore
          —— 只依赖 :core-common，不依赖 :core-cv / :core-ai
```

### 依赖纪律（逐条可静态检查）

1. `:core-common` 谁都能依赖它，它**不依赖任何模块**（仅 kotlinx-coroutines）。
2. **`:core-cv` 与 `:core-ai` 禁止互相依赖**。传统 CV 兜底估计器（灰度世界、直方图、亮度梯度）用**纯 Kotlin 数组**实现，放 `:core-ai`；需要 OpenCV 的算法（颜色迁移、阴影、融合、`KelvinRgb`）放 `:core-cv`；两边共享的数据只用 `:core-common` 的模型交换。
3. `:core-pipeline` 是**唯一**同时依赖 `:core-cv` 和 `:core-ai` 的模块，负责编排 `ImagePipeline`。
4. **feature 模块只依赖 `ImagePipeline` 接口 + `RenderRequest`/`RenderResult` 模型**（均来自 `:core-common`），**不允许**直接引用 `LightEstimator`、`PoissonBlender`、`ColorTransfer` 等实现类。实现通过 Hilt 注入，feature 在编译期不需要知道实现在哪个模块。
5. `:data` 只做「数据来源」：素材文件、MediaStore、DataStore，**不含图像计算**。
6. feature 之间禁止互相依赖；只有 `:app` 知道导航图。
7. 任何模块不得引入 okhttp / retrofit / firebase / 统计 SDK。

---

## 4. 完整文件树（✅=已生成；阶段标注＝该文件实现所属阶段）

```
naiwa-camera/
├─ settings.gradle.kts                        ✅ 模块清单 + 仓库
├─ build.gradle.kts                           ✅ 顶层插件声明
├─ gradle.properties                          ✅
├─ gradle/
│  ├─ libs.versions.toml                      ✅ 唯一版本来源（Plan A）
│  └─ wrapper/gradle-wrapper.properties       ✅ Gradle 8.9
├─ docs/
│  ├─ ARCHITECTURE.md                         ✅ 本文
│  ├─ ARCHITECTURE-CHANGES.md                 ✅ v0.1→v0.2 修正说明与关键 diff
│  └─ ROADMAP.md                              ✅ 阶段任务与验收
├─ README.md                                  ✅
├─ tools/
│  └─ gen_placeholder_frogs.py                P0-5 生成自绘占位奶蛙 PNG + 同步写 manifest.json
│
├─ app/
│  ├─ build.gradle.kts / proguard-rules.pro   ✅
│  └─ src/main/
│     ├─ AndroidManifest.xml                  ✅ 无 INTERNET 权限 / allowBackup=false
│     └─ java/com/naiwa/camera/
│        ├─ NaiwaApp.kt / MainActivity.kt     ✅
│        ├─ ui/NaiwaRoot.kt                   ✅（P0-2 换成 NavHost）
│        ├─ ui/theme/Theme.kt                 ✅
│        ├─ ui/nav/NaiwaNavHost.kt            P0-2
│        └─ di/AppModule.kt                   P0-2 调度器限定符绑定
│
├─ core-common/                               零原生、零 UI
│  └─ src/main/java/com/naiwa/core/common/
│     ├─ model/LightParams.kt                 ✅ ★ 光照参数契约（§6.1）
│     ├─ model/LightOverrides.kt              ✅ 手动滑杆覆盖值（可空=不覆盖）
│     ├─ model/Placement.kt                   ✅ 归一化 平移/缩放/旋转
│     ├─ model/EditorSnapshot.kt              ✅ 撤销/重做快照（Placement+Overrides）
│     ├─ model/FrogAsset.kt                   ✅ 素材元信息（含 license）
│     ├─ model/RenderRequest.kt               ✅ 渲染请求（含 QualityTier）
│     ├─ model/RenderResult.kt                ✅ 结果 + 分阶段耗时
│     ├─ pipeline/ImagePipeline.kt            ✅ ★ 管线接口（feature 只认它）
│     ├─ util/UndoRedoStack.kt                ✅ 深度 20 的内存快照栈
│     ├─ util/MathUtils.kt                    P0-2 变换矩阵 / 角度归一化 / 钳制
│     ├─ image/BitmapDecoder.kt               P0-2 解码 + inPreferredColorSpace=SRGB + EXIF
│     ├─ image/BitmapPool.kt                  P2   大图复用
│     └─ image/AlphaUtils.kt                  P0-2 premultiply / 边缘 alpha
│
├─ core-pipeline/                             ★ 新增：编排层
│  ├─ build.gradle.kts / AndroidManifest.xml  ✅
│  └─ src/main/java/com/naiwa/core/pipeline/
│     ├─ DefaultImagePipeline.kt              P0   P0 版：直贴 + 编码
│     ├─ SimpleBlender.kt                     P0   Canvas 直贴（无 OpenCV 时的实现）
│     ├─ OpenCvPipeline.kt                    P1/P2 完整实现（颜色→阴影→融合→边缘）
│     └─ di/PipelineModule.kt                 P0   @Binds ImagePipeline → 实现
│
├─ core-cv/
│  └─ src/main/java/com/naiwa/core/cv/
│     ├─ color/KelvinRgb.kt                   P1   ★ kelvinToRgbGain / rgbGainToKelvin 互逆对
│     ├─ color/ColorTransfer.kt               P1   Reinhard Lab + lerp 力度（§6.3）
│     ├─ light/SceneStats.kt                  P1   直方图分位数
│     ├─ matte/ColorKeyMatte.kt               P0   色键+形态学+连通域
│     ├─ shadow/DirectionalShadow.kt          P2   shear+压扁+模糊（已标注近似）
│     ├─ shadow/DistanceFieldShadow.kt        P3   距离变换 + 1D 卷积（形状跟随）
│     ├─ blend/PoissonBlender.kt              P2   seamlessClone 封装（三档）
│     ├─ blend/LaplacianBlender.kt            P2   多频段融合（保底档）
│     ├─ blend/EdgeHaloFix.kt                 P2   腐蚀/羽化/α 加权回贴
│     ├─ blend/TiledBlender.kt                P3   分块（25% 重叠 + 重叠区加权）
│     └─ gles/GlesCompositor.kt               P3   EGL + GLSL
│
├─ core-ai/                                   端侧推理（不依赖 :core-cv）
│  └─ src/main/java/com/naiwa/core/ai/
│     ├─ ModelAssetInstaller.kt               P1   assets → filesDir
│     ├─ RuntimeProvider.kt                   P1   加速器探测 GPU→CPU（不使用 NNAPI）
│     ├─ light/LightEstimator.kt              P1   接口
│     ├─ light/TfliteLightEstimator.kt        P1   模型回归
│     ├─ light/CvLightEstimator.kt            P1   ★ 纯 Kotlin 兜底，永不抛异常
│     └─ seg/InteractiveSegmenter.kt          P2   点选分割
│
├─ data/
│  ├─ src/main/assets/frogs/
│  │  ├─ manifest.json                        ✅ ★ license 登记（无 license 不加载）
│  │  ├─ README.md                            ✅ 登记规范
│  │  └─ *.png                                P0-5 素材本体
│  └─ src/main/java/com/naiwa/data/
│     ├─ frog/FrogManifest.kt                 ✅ manifest.json 数据模型 + 解析
│     ├─ frog/BuiltInFrogRepository.kt        P0   扫描 manifest → 只加载 license 合法的
│     ├─ frog/ImportedFrogRepository.kt       P0   用户导入（filesDir/frogs/imported）
│     ├─ media/MediaStoreSaver.kt             P0   保存到 Pictures/奶蛙相机（统一 sRGB）
│     ├─ media/ShareHelper.kt                 P0   分享
│     ├─ source/SceneSourceLoader.kt          P0   Uri → 校正方向/sRGB 的 Bitmap
│     └─ settings/SettingsRepository.kt       P0   DataStore（默认档位、上次选择）
│
├─ feature-camera/                            P0-3
│  └─ src/main/java/com/naiwa/feature/camera/
│     └─ CameraRoute.kt / CameraScreen.kt / CameraViewModel.kt
│
└─ feature-editor/
   └─ src/main/java/com/naiwa/feature/editor/
      ├─ EditorRoute.kt / EditorScreen.kt / EditorViewModel.kt
      ├─ panel/FrogPickerSheet.kt              P0-5
      ├─ panel/MattingSheet.kt                 P2
      ├─ panel/LightSlidersPanel.kt            P3
      ├─ canvas/TransformGestures.kt           P0
      ├─ canvas/OverlayCanvas.kt               P0
      ├─ export/ExportController.kt            P0
      └─ history/EditHistoryController.kt      P0  撤销/重做（深度 20）
```

---

## 5. Gradle 配置中的关键决策

1. **版本目录是唯一版本来源**：`gradle/libs.versions.toml`，模块内不写死版本号。
2. **JDK 17 + `jvmTarget = 17`**：用 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`，不使用已废弃的 `kotlinOptions`。
3. **ABI 只打 ARM**：`arm64-v8a` + `armeabi-v7a`。用模拟器调试时临时加 `x86_64`，否则 OpenCV/TFLite 原生库在模拟器上找不到 `.so`。
4. **`androidResources.noCompress += "tflite"/"task"`**：模型必须不压缩存放才能被 mmap 直接加载；被压缩会导致运行时加载失败，且报错信息具有误导性。
5. **`packaging.jniLibs.pickFirsts`**：多模块携带同一 OpenCV `.so` 时只保留一份。
6. **release 开 `isMinifyEnabled + isShrinkResources`**：OpenCV/LiteRT/MediaPipe 的 JNI 反射类必须保留，规则写在各模块 `consumer-rules.pro`（随 AAR 传给 app）。
7. **隐私即构建约束**：不申请 INTERNET 权限 + `allowBackup=false`，让「上传图片」在系统层不可能发生。
8. `org.gradle.configuration-cache=false`：首次跑通后再开，避免把构建配置问题与业务问题混在一起排查。
9. **Build Tools 用 AGP 8.7 的默认 34.0.0**，无需额外安装高版本。

---

## 6. 核心模型与算法契约

### 6.1 `LightParams` —— 全项目共享的光照参数（★核心契约）

| 字段 | 类型/范围 | 含义 | 来源 |
| --- | --- | --- | --- |
| `colorTempK` | Float 2000~12000 | 场景色温（K），6500 = 中性 | 白平衡统计 / 模型回归 |
| `tint` | Float -1~1 | 绿-品红偏移 | 灰点法残差 |
| `exposureEv` | Float -3~3 | 相对正常曝光档位 | 中位亮度 vs 目标 0.45 |
| `brightness` | Float 0.5~1.5 | 亮度增益（UI 直接用） | 由 exposureEv 换算 |
| `contrast` | Float 0.5~1.5 | 对比度系数 | p1/p99 分位跨度 |
| `saturation` | Float 0.5~1.5 | 饱和度系数 | Lab 平均 chroma |
| `keyAzimuthDeg` | Float 0~360 | 主光方位角：0°=光源在画面正右，90°=画面下方（x 右 y 下） | 亮度梯度场 + 阴影质心交叉验证 |
| `keyElevationDeg` | Float 0~90 | 主光高度角：0=平齐地面，90=正上方 | 高光/阴影面积比 + 模型回归 |
| `keyIntensity` | Float 0~1 | 方向光相对环境光的占比 | 高频梯度能量比 |
| `ambientIntensity` | Float 0~1 | 环境光强度 | 阴影区/受光区亮度比 |
| `shadowStrength` | Float 0~1 | 阴影浓度 | 直方图双峰分离度 |
| `shadowSoftness` | Float 0~1 | 阴影软硬 | 亮度过渡带宽度 |
| `confidence` | Float 0~1 | 估计可信度，**< 0.45 时自动改用传统 CV 结果** | 模型熵 / CV 一致性 |

### 6.2 光照估计与色温反解

```
输入场景 Bitmap
  ├─ 缩到 256×256（letterbox）
  ├─ ① TFLite/LiteRT 回归 → LightParams(confidence)
  │     confidence < 0.45 或模型异常 ↓
  └─ ② 纯 Kotlin 传统 CV 兜底（位于 :core-ai，不依赖 OpenCV）：
        · 白平衡：灰度世界（全图均值）与白点法（p99 亮度像素）加权折中
          —— 纯灰度世界在「大面积单色场景」（草地）下会严重偏色
        · 色温：由 RGB 增益反解 Kelvin
        · 亮度/对比度：直方图 p1/p50/p99
        · 主光方向：灰度图 → 强高斯模糊(σ≈长边/12) → Scharr 梯度场 → 能量加权平均
          得方位角；高度角由「亮部质心偏离画面中心距离」估计
        · 交叉验证：暗部(p10 以下)质心相对中心的偏移应与梯度方向大致相反，
          夹角 > 120° 判为不可靠，取两者负方向平均并降低 confidence
  └─ ③ 兜底中的兜底：任何一步失败都返回中性参数
        （6500 K / EV 0 / 方位角 135° / 高度角 45°），永不抛异常
```

**色温必须走同一套函数**（`core-cv/color/KelvinRgb.kt`，P1 起）：

```kotlin
object KelvinRgb {
    /** Kelvin → 归一化 RGB 增益（6500 K 恒为 [1,1,1]） */
    fun kelvinToRgbGain(kelvin: Int): FloatArray
    /** RGB 增益 → Kelvin：对前向函数做单调二分搜索（1500~15000 K），
     *  保证与 kelvinToRgbGain 严格互逆，误差 < 50 K */
    fun rgbGainToKelvin(gain: FloatArray): Int
}
```
**自动估计出的色温**与**手动色温滑杆**都必须调用这一对函数，否则「自动」和「手动」会出现两套不一致的色偏。

### 6.3 颜色与亮度协调（Reinhard 公式修正）

- **只在奶蛙 alpha 掩膜内做统计**。透明区经 premultiply 后接近黑，会把均值/方差拉偏——最容易被忽略、也最影响效果的一步。
- **力度不乘在 σ 上**。标准 Reinhard 迁移照原式做，力度用线性插值施加：

```
// 每个通道 c ∈ {L, a, b}
c_ref = (c_frog - μ_frog_c) * (σ_scene_c / σ_frog_c) + μ_scene_c   // 标准 Reinhard
c_out = lerp(c_frog, c_ref, strength)                              // strength ∈ [0,1]
```
v0.1 把 strength 乘在 `σ_scene` 上会让「力度 = 0」仍产生均值平移（不是恒等变换），且力度变化时对比度与均值耦合抖动。改成 lerp 后：`strength=0` 严格等于原图，`strength=1` 严格等于完整迁移，中间连续可调。

- 亮度（L 通道）另做**分位数映射**（p5/p50/p95 三点折线映射）而非单纯均值匹配，防止奶蛙高光溢出成死白。
- 色温偏移统一由 §6.2 的 `KelvinRgb` 施加，与自动估计走同一路径。

### 6.4 抠图

优先级从高到低，任何一步都能人工接管：

1. **P0 · 色键 + 形态学 + 连通域**：四角/边缘采样估计背景色 → HSV 距离阈值 → 开闭运算去噪 → 连通域取最大前景 → 孔洞填充。
2. **P2 · 点选分割**（MediaPipe Interactive Image Segmenter）：点一下奶蛙 → 得到该物体的掩膜。**不用自动分割**：奶蛙是任意物体，通用分割模型不认识这个类别；「点哪分哪」不依赖类别先验。
3. **P2 · 手动涂抹**：前景/背景双色笔刷改掩膜，3 档笔刷半径。
4. **掩膜后处理（所有路径都必须做）**：最大连通域保留 → 孔洞填充 → **未知区收缩 2 px** → 高斯羽化。

### 6.5 方向性阴影

```
mask ──► 沿光方向 shear ──► 垂直方向按 |sin(elevation)| 压扁
     ──► 高斯模糊 σ = base × softness × 物体特征尺寸
     ──► 随「距物体投影原点距离」线性衰减
     ──► multiply 叠加到场景，α 上限 = shadowStrength
```
- `elevation` 越小（光越平）→ 阴影越长越实；→90° 时收缩到物体正下方一小片。
- 阴影颜色取**场景暗部的中位色**再压暗，不用纯黑，避免暖色场景里出现发灰的脏影子。
- **极端情况处理**：`elevation < 10°` 时阴影长度趋于无限，实际按「阴影长度 ≤ 物体高度 × 4」截断，避免生成一整片灰雾。

> **已知近似（必须记录）**：shear + 压扁是**仿射近似**。阴影轮廓只是奶蛙掩膜的仿射变换结果，**不会跟随奶蛙的局部形状**（跷起的腿、耳朵等），在形体复杂或地面有明显纹理时会偏「贴纸感」。
> **P3 可选升级**：`DistanceFieldShadow.kt` —— 对掩膜做**沿光方向的距离变换**，再沿光方向做 **1D 高斯卷积**（核长 ∝ 1/sin(elevation)），让阴影轮廓跟随奶蛙形状并自然过渡到地面。新旧实现在同一接口下 A/B 对比。

### 6.6 融合与边缘（三件套，按顺序执行）

1. **mask 腐蚀 2~4 px**：泊松融合的色偏主要来自边缘一圈背景残留。
2. **泊松融合** `seamlessClone(MIXED_CLONE)`，档位见 §7.2。
3. **α 回贴（过渡带取加权和，不是纯直贴）**：

```
feather_px = clamp(0.015 * 工作域短边, 4, 20) * 手动倍率     // 下限 2px
```
**注意：不要再乘一次 `workShortSide / 1080`。** `0.015 × min(w,h)` 本身已随分辨率线性变化，
再乘一次会变成随分辨率的**平方**响应。

> **数值变更记录**：早期规格给的是 `clamp(0.02 × min(w,h), 6, 24)`，
> 本轮的边缘修复规格 6.5 改为 `clamp(0.015 × min(w,h), 4, 20)`，已按后者执行。
> 过渡带变窄 ⇒ 泊松结果占比变小、直贴背景占比变大，白边与糊块的可见度都降低；
> 代价是梯度域解在边缘的影响范围变窄，更依赖 §6.11 的去白把颜色本身修对。
> 三个常量集中在 `EdgeHaloFix`，要回退只改它们。

| 工作域短边 | 基准 feather | 说明 |
| --- | --- | --- |
| 1080 | 16 px | 0.015 × 1080 = 16.2 |
| 720 | 10 px | 线性缩小 |
| 540 | 8 px | 同上 |
| 64 | 4 px | 触发下限 |

- 中心区（腐蚀后 mask 内缩 8 px）：`α = 1`，取**泊松融合结果**
- 过渡带（腐蚀后 mask 外扩 `feather_px`）：权重 `w` 由 smoothstep 从 0 平滑升到 1
  `out = (1 - w) * 羽化直贴结果 + w * 泊松融合结果`
  —— 纯羽化直贴会在过渡带留下色温断层，纯泊松结果会在高频背景上糊一块，**两者加权**才能同时消掉这两个问题
- 过渡带之外：`α = 0`，完全保留场景原像素

> **关于规格 6.5 写的"过渡带 = 0.5 × 泊松 + 0.5 × 羽化直贴"**：这里保留 smoothstep 渐变的 `w` 而不用固定 0.5。
> 固定 0.5 会在两侧各形成一个台阶（中心 1.0 → 过渡带 0.5 → 外部 0.0），
> 而 `w` 在过渡带中点恰好等于 0.5 —— 也就是说 smoothstep **已经覆盖了规格想要的那个 0.5，且没有台阶**。

### 6.11 奶蛙边缘预处理（★2026-10 新增，规格 6.1~6.4）

**它解决的问题**：成品最显眼的缺陷是**边缘灰白/玻璃白边**。
本质不是"过渡不够软"，而是**半透明边缘像素的 RGB 仍是被抠掉的背景色**：
一只原本在白底上的奶蛙，边缘 alpha=0.3 但 RGB 仍是 (246,246,246)，
叠到深色场景上就是一层被稀释的白。**加宽羽化只会把这层白摊得更开** ——
所以必须先修颜色、再柔化 alpha，顺序不能颠倒。

```
抠图（若需要）
  ↓
6.1 预乘检查 → 反预乘        PremultipliedAlpha
  ↓
6.2 边缘去白（只改 RGB）      EdgeDefringe
  ↓
6.3 Alpha 边缘柔化（只改 alpha）  AlphaEdgeSoften
  ↓
6.4 方向性边缘柔化（只改 alpha）  同上
  ↓
6.5 重新预乘
  ↓
（进入颜色协调 → 阴影 → 融合）
```

| 步骤 | 关键点 |
| --- | --- |
| 6.1 | Android 默认按 **premultiplied** 解码，`getPixels()` 拿到的 RGB 已被 alpha 乘过。判定用硬性不变式 **通道 ≤ alpha**（违规率 < 2% 即预乘）；**判定必须在处理之前** —— 把非预乘当预乘去反解会把边缘放大到过亮，正是白边的来源之一 |
| 6.2 | 邻域内 alpha ≥ 0.95 的实心前景做距离加权平均，替换边缘 RGB。**半径会扩张**（2 → 4 → 6）：细结构（触角、脚趾、1px 边）在小半径内找不到参考，否则会静默留下局部白点。连最大半径都找不到时计入 `unresolvedPixels` **如实回报** |
| 6.3 | 半径 `clamp(0.5 × (1 - softness), 0.3, 1.5)` px，**只在 alpha 0.01~0.99 生效**：实心区与完全透明区保持不变，主体不会变瘦 |
| 6.4 | 用 alpha 梯度定出法线，沿**切线**做 1D 高斯。切向平滑能磨掉轮廓锯齿，同时不模糊跨边界的梯度 —— 跨边界模糊才是把轮廓抹平的原因 |
| 6.5 | 内部全程用非预乘运算，**交还给 Android 之前必须回到预乘**：`Bitmap.createBitmap(ARGB_8888)` + `setPixels` 与 `Canvas.drawBitmap` 都按预乘解释，这条边界守不住边缘就会因为"多乘一次 alpha"而发暗 |

**两处有意偏离规格，已在此记录**：

1. **6.2 的 alpha 下界**：规格写 0.2，实现取 **0.02**。
   低 alpha 区同样会出白边（alpha=0.08 的白叠在深色背景上仍可见），而其贡献正比于 alpha、并不为零；
   上界保持 0.95（再高就已经不被稀释，替换是多余改动）。
2. **6.4 的模糊方向**：规格同时写了"沿切线方向"与"水平边用垂直模糊"，后者其实描述的是**法线方向**，与前者相反。
   规格自己给的理由是"更保边、避免轮廓被抹平" —— 只有**切向**满足这个理由，故按切向实现。

**幂等性**（重要）：alpha 柔化**不幂等**，反复执行会把边缘越磨越虚。
因此 `prepareFrog` 的产物会记录版本号（导入素材写进 `index.json` 的 `edgePrepVersion`），
选中素材时先查版本号，已处理过就直接加载；内置素材用会话内集合兜住。
**早期实现在"原图已带 alpha"时直接 return —— 那正是白边的高发场景，等于完全没修。**

**验收方式（规格 6.7 已自动化）**：`FrogEdgeQualityTest` 把手工目测变成断言 ——
把奶蛙合成到 `#1a1a1a` 上，对 alpha ∈ [0.2, 0.8] 的边缘像素比较
"实际合成值"与"用本体色按同样 alpha 合成的理想值"，**平均通道差 < 15** 即通过。

### 6.7 色域管理（P0 起必须处理）

- `BitmapFactory.Options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)`（API 26+）：让解码器**直接输出 sRGB**，一步规避 Display P3 输入带来的色偏。
- 自己 `createBitmap` 时显式传 `ColorSpace.get(SRGB)`。
- 解码后仍以 `bitmap.colorSpace` 兜底判断；若不是 sRGB（例如经 `BitmapRegionDecoder`、`ImageDecoder` 或第三方库得到），做**显式转换**（新建 sRGB 位图 + `Canvas.drawBitmap` 重画，Skia 在目标色域内做转换），并且**必须在光照估计之前转换**——否则色温估计会基于错误的 RGB 值。
  注意 `inPreferredColorSpace` 只是**建议**：部分解码器（尤其 HEIC 与厂商私有路径）会忽略它，所以"实测兜底"这一步不能省。转换失败时保住原图（色偏好过丢图）。
- **保存统一 sRGB**：最终位图与编码参数都按 sRGB 处理，避免不同设备导出色偏。

### 6.8 双轨制：设备档位 → 融合后端（★2026-10 新增）

**问题**：细节保真这类高配路径只适合一部分设备。如果让管线自己判断，就会出现
"UI 显示可用、管线拒绝执行"这类自相矛盾的状态；如果让每处各读一次 `ActivityManager`，
只要有一处口径不同就会静默分裂。

**设计**：把"能力判定"与"执行"彻底分开，判定**只发生在一个地方**。

```
编辑页（唯一选路点）
  DeviceCapabilityProbe.detect(context)   ← 全项目唯一读 ActivityManager 的文件
        │  totalRamBytes / cores / lowRamFlag
        ▼
  FusionTrack.tierFor(...)                ← 纯函数，与 Android 无关，可直接单测
        │  LOW_END / MID_RANGE / HIGH_END
        ▼
  FusionTrack.backendFor(tier, userOptIn) ← 纯函数
        │  CLASSICAL / REP_DETAIL
        ▼
  RenderRequest.requestedBackend
        ▼
  管线：只执行、不判断；RenderResult.backend 如实回报
```

| 规则 | 取值 | 理由 |
| --- | --- | --- |
| 低端机 | 厂商标记 ∨ 总 RAM < 4GB ∨ 核数 ≤ 4 | 泊松求解在 4 核上明显吃力；宁可慢不可崩 |
| 高端机 | 总 RAM ≥ 8GB ∧ 核数 ≥ 8 | 给实验路径留出余量 |
| **内存取不到（=0）** | **不判为高端** | "未知"必须按能力不足处理，不能在没有依据时启用实验特性 |
| 低端机的后端 | **无条件 `CLASSICAL`** | 用户开关不能绕过设备档位（UI 也不渲染那个按钮） |
| 其余设备的默认 | 开（"能上就上"） | 真正的门槛是设备能力，而不是用户的默认设置 |

**如实上报**：细节保真是增强步骤，可能因内存或参数原因未执行。此时
`EdgeHaloFix.DetailReport` 会把"没做成"带回来，管线据此把 `RenderResult.backend`
从 `REP_DETAIL` **改回 `CLASSICAL`** 并打 warning —— 绝不允许"功能没生效却照旧上报已启用"。

### 6.9 细节保真：结构重参数化的落地（★2026-10 新增）

**它修什么**：§6.6 的 α 加权回贴解决了"糊的那部分占多少权重"，但没找回**丢掉的高频**。
`RepDetailRefiner` 把原始背景的高频补回过渡带：

```
hp   = 多尺度高通(sceneLuma)              // DC 增益严格为 0
band = smoothstep(core) × (1 - frogAlpha) // ★两个因子都不能少，见下
out  = blended + strength × band × hp
```

**band 为什么是这个式子**（这一步踩过坑，测试抓回来的）：

| 因子 | 含义 | 漏掉会怎样 |
| --- | --- | --- |
| `smoothstep(core)` | 就是混合式里的 `w` —— **融合结果在多大程度上取代了羽化直贴**。丢掉的高频量正比于 `w` | 若误用 `1 - core`，会在 `w≈0` 的外圈（细节本来就在）重复补偿 → **过冲**。实测把边缘背景误差从 **11.59 放大到 26.68** |
| `1 - frogAlpha` | 补偿只对**背景像素**成立 | 奶蛙本体（alpha=1）上补背景细节＝污染奶蛙 |

两者相乘天然满足两条硬性质：**奶蛙本体（alpha=1）= 0**、**远离掩膜（w=0）= 0**，不需要额外门控。

**为什么写成多分支再折叠**：`hp` 是线性算子，正好可以走
`RepConv`（异构分支 5×5 / 3×3 / 1×3 / 3×1 / 1×1 + concat + 1×1 投影）→ `FoldedConv`（单路径）的流程。
**折叠是数学恒等变换**，所以可以用单测逐点证明"折叠前后一致"（实测最大差 5.2e-08）。
权重目前是**闭式设计**的（不是训练值），接入训练权重时只改权重数组，`fold()` 与调用方一行都不用改。

**三条安全性质**（都有单测）：

| 性质 | 值 / 断言 | 意义 |
| --- | --- | --- |
| DC 增益为 0 | 实测 7.3e-08 | 不改变整体亮度（否则等于偷偷调曝光） |
| 中心区与掩膜外不动 | `band = 1 - core`，两处均为 0 | 不破坏 §6.6 已调好的行为 |
| 失败即退回 | 返回 `null` → 退回未修正结果 + 如实回报 | 增强步骤不能把预览/导出打挂 |

**内存**：只按 `band > 0` 的包围盒分配浮点数组（区域局部坐标），
整图 4096² 的 `FloatArray` 要 67MB，而真正非零的只有过渡带那一圈。

### 6.10 一维 LUT 替代超越函数（★2026-10 新增）

`rgbToLab` 每次要 3 次 `cbrt`，`labToRgb` 每次要 3 次 `pow(1/2.4)`。
一张 1024² 的奶蛙两个方向各走一遍 ≈ 600 万次超越函数调用，是颜色协调循环的最大单笔开销。

`Lut1d` 把这两个函数换成 8192 / 4096 段的查表 + 线性插值（实测 **2.81× 加速**，
sRGB→Lab 最大 ΔE76 **0.0006**，Lab→sRGB 最大误差 **0.0034/255**）。
表内容由 `LabColor` 的解析公式生成，**只有一份数学实现**。

> ⚠️ **已实测否定的方案**：用 33³ **三维**查找表直接替掉整条 `sRGB ↔ Lab` 点变换。
> 实测反而慢 **0.8×**（8 角点 × 3 通道的读/乘加 > 3 次 cbrt），
> 且 Lab→sRGB 往返误差达 **32.8/255** —— 均匀网格跨不过 `gamma()` 的色域钳制面，是结构性误差。
> 结论：**点变换不该用三维 LUT，超越函数才该用一维 LUT**。不要再走回头路。

### 6.11 高级光影融合（★2026-10 新增，规格 7）

在边缘优化（6.1~6.7）之后、泊松融合之前，对奶蛙施加四项光效。**规则全部下沉为纯函数**（`LightFxRules` / `ShadowPlacementRule`，可 JVM 单测），`core-cv` 只做像素循环，UI 面板与管线共用同一套规则 —— 杜绝"面板显示 0.3、实际跑 0"的状态错位。

| 步骤 | 算子 | 关键规则 |
| --- | --- | --- |
| ① 四色渐变环境光 | `LightWrapGradient` | 上/下/左/右四区中位色（排除过曝与死黑）→ 垂直+水平+四角双线性三路加权合成 → Soft Light 混合。方向权重由**实测亮度差**决定，不是拍脑袋的 1/3:1/3:1/3 |
| ② 边缘打光 | `RimLight` | 逆光判定（方位角与相机侧夹角 > 120° 且主光 > 0.5）；环带 `alpha ∈ [0.3, 0.9]`；颜色取**场景高光区中位色**；`out = clamp(c + rimColor × facing × ring × s)`（Add） || ③ 辉光 | `Glow` | 高亮掩膜（Luma > 204）→ 高斯（σ 随尺寸缩放，1080p 基准 12px）→ Screen 混合叠回。强度上限 0.3（超过即"发光贴纸"） |
| ④ 阴影动态投位 | `DirectionalShadow` + `ShadowPlacementRule` | 逆光→投向前景（下方）长度×0.6 浓度×0.5；侧光→沿光方向；顺光→只剩脚下一小片接触阴影（≤ 12% 高度） |

**执行顺序（规格 7.5）**：颜色/亮度协调 → 四色渐变 → 边缘打光 → 辉光 → 泊松融合 → 边缘回贴 → 阴影合成。

**性能与降级（规格 7.6）**：辉光与四色渐变**只在导出且非低端机**时执行，预览只做边缘打光（便宜的那一步）；
三个光效滑杆拖到 0 = 关掉该步骤（管线整段跳过，不只是强度归零）。

> ⚠️ **本轮实测修掉的算法 bug**：`RimLight` 的边缘法向最初取 `∇soft`（指向物体**内部**），
> 导致 `facing = 外法向 · 光源方向` 在朝向光源的一侧恒为负、被 `facing <= 0.02` 直接跳过
> —— 边缘光**一整侧完全不亮**（受控复现实测 `bottomLit=0`）。正确外法向 = `-∇soft`。
> 另：早期还叠加过一个 `band = a - soft` 的"贴近轮廓"拉普拉斯过滤，它在平滑边缘上恒趋近 0、
> 误杀了本应打亮的轮廓；`ring`（alpha 环带）本身已是"边缘"的完整定义，该过滤已删除。

### 6.11a 精细化边缘提亮（★2026-10 新增，规格 7.1a）

规格 7.1a 是对 `RimLight` 的**物理正确性**升级，三点改动：

1. **法向改用距离变换梯度**（`core-cv/util/DistanceField.kt`，Chamfer 两遍扫描，纯 Kotlin 可单测）——
   不再依赖"模糊 alpha 的中心差分"，对锯齿轮廓更稳健，也不因羽化半径不同而漂移。
2. **点积方向升级为 3D**——上一版只用 `keyAzimuthDeg` 的 2D 方向、完全忽略 `keyElevationDeg`。
   现在光源方向是 3D 单位向量 `L = (cos(elev)·cos(az), cos(elev)·sin(az), sin(elev))`；
   边缘像素 `nz≈0`，点积退化为 `n2d · L_xy`，**elevation 通过 `cos(elev)` 衰减光源水平分量**：
   正顶光（elevation→90°）方向性消失、亮边趋向均匀；低角度光（elevation≈0°）方向性最强。
   这是 elevation 的真实物理作用，而非臆造一个 z 法向。
3. **权重 = 点积^1.5**——半影区（点积略大于 0）被压得更暗，亮边集中在真正朝向光源的窄带内。

> 2D/3D 的诚实边界：奶蛙是 2D 抠图，边缘像素的真实 3D 法向 z 分量**不可恢复**，本实现如实记为 `nz≈0`；
> 不引入无法验证的伪参数。**无动态光带动画**——纯静态逐像素光效，输出静态图。

### 6.12 跨域融合升级（★2026-10 新增，规格 8）

针对"二次元前景 + 写实背景"这类**跨域**场景，纯几何/颜色算法的天花板不足，引入两层升级（执行顺序见 §8.4）：

**8.1 3D LUT 颜色映射（基础层）** —— `core-cv/color/CubeLut3d.kt` + `LutApply.kt` + `LutRepository.kt`：
- 解析 `.cube`（Adobe/Resolve 格式），CPU 三线性插值采样（GLES 加速为后续优化项，正确性优先）。
- 4 套内置 LUT（`assets/luts/`，由 `tools/gen_luts.py` 生成）：暖光室内 / 冷光夜景 / 阴天户外 / 逆光。
- 选 LUT 规则（`core-common/model/LutChoice.kt`，纯函数）：按 `colorTempK` 分档 + 过渡带插值；逆光且主光强时优先 backlit。
- 作用域：**仅奶蛙前景 RGB**，背景不碰。

**8.2 AI 和谐化（可选）** —— 三轨制契约 `core-common/model/Harmonizer.kt`：
- **MKL**（`MklHarmonizer.kt`，纯 Kotlin 闭式解）：Monge-Kantorovich Linear 最优传输滤波，
  用源/目标颜色分布的均值+协方差算 12 维仿射变换（3×3 A + 3 维 S）。**无需训练权重、永不失败**，
  是所有设备的默认增强；相比 Reinhard 额外匹配通道间协方差（压"高饱和贴纸感"）。
- **PCT-Net**（`core-ai/harmonize/PctNetHarmonizer.kt`，高端机可选）：图像和谐化模型
  （CVPR 2023，`rakutentech/PCT-Net-Image-Harmonization`），低分辨率预测逐像素颜色变换参数、
  应用到全分辨率前景，计算量小。**仅高端机（RAM≥8GB 且核数≥8）启用，8s 超时回退 MKL**。
  ⚠️ 官方权重是 PyTorch `.pth` 格式（`pretrained_models/PCTNet_ViT.pth`，**免申请直接下载**），
  端侧需先转 LiteRT；**转换脚本已就绪**（`tools/convert_pctnet.py`），本仓库**不提供转换后的权重**
  （结构就位、权重原位替换）。I/O 契约：TFLite 输入 `[1,256,256,4]`（RGB+mask）→ 输出
  `[1,256,256,12]`（逐像素 12 参数仿射）；后半段 bicubic 上采样 + 仿射应用在端侧
  `core-common/color/PctNetTransform.kt` 完成（纯函数、有 7 用例单测）。

**8.3 NPU 部署规范** —— `RuntimeProvider` 探测链升级为 **QNN → NPU → GPU → CPU(XNNPACK)**，
结果缓存；QNN/NPU delegate 用反射探测（当前 Maven Central 依赖无这些类，加 Google Maven litert-* 依赖后反射即命中，**代码零改动**）。**禁止新增 NNAPI 路径**。

**8.4 执行顺序（更新版）**：
```
Alpha 预乘检查 → 去白 → Alpha 模糊 → 方向性边缘模糊
→ 颜色/亮度协调 → 3D LUT 颜色映射 → (可选)AI 和谐化(MKL/PCT-Net)
→ 四色渐变环境光 → 边缘打光 → 辉光
→ 泊松融合 → 边缘回贴 → 动态阴影合成
```

**8.5 降级**：低端机（RAM<4GB 或 NPU 不可用）自动跳过 PCT-Net，只走 MKL + 3D LUT + 传统颜色迁移。

> ⚠️ **已排除的模型**：FLUX.2-klein-4B（4B 参数，需约 13GB VRAM，不满足端侧内存约束）；
> DreamLite（权重需邮件申请 gated + 依赖 Qwen3-VL 文本编码器，流程繁琐，已换 PCT-Net）。
> **关于"离线"与"体积"的澄清**：PCT-Net 是纯视觉参数预测模型，计算量小（低分辨率预测、上采样应用），
> 模型随包分发、推理全程无网络。离线约束 = "不联网推理"，与模型体积无关。

---

## 7. 渲染管线

### 7.1 预览三段式（拖动 → 松手 → 静止）

| 触发 | 工作分辨率 | 计算内容 | 目标耗时 |
| --- | --- | --- | --- |
| **拖动中** | 长边 ≤ 720 | 变换合成 + α 直贴（跳过阴影与融合） | ≤ 33 ms/帧 → 拖动 **≥ 30fps** |
| **松手后 ≤ 300 ms 内** | 长边 ≤ 1080 | + 颜色/亮度协调 + 阴影（1/4 分辨率生成后上采样），**不含泊松融合** | ≤ 250 ms |
| **静止 1.2 s 后 / 点「保存」前** | 长边 ≤ 1080 | 接近成品：含融合（档 1/2） | ≤ 1.5 s |

第二档是关键：松手后立即切到「带阴影的中间质量预览」，用户在保存之前就能判断效果，不必反复保存—回退。

### 7.2 融合性能预算 · 分档

`seamlessClone` 的泊松求解在中端 ARM CPU 上远比直觉慢，必须分档并明确验收线。
**2026-10 起性能标准放宽**（1080p < 5s、4K 4096 < 12s、内存 < 600MB），因此档位优先级整体上移：

| 档位 | 实现 | 中端 ARM CPU 预期 | 定位 |
| --- | --- | --- | --- |
| **档 1** | 720p 域 `seamlessClone` | **1.5 ~ 3.0 s** | ✅ **首选档（默认）**：质量最好；加上其余阶段合计约 3.5 s，仍在 5 s 验收线内 | 
| **档 2** | **540p 域 `seamlessClone` + 上采样回贴** | **0.6 ~ 1.3 s** | ✅ **保底档**：档 1 实测超过 4 s 时自动降到这一档 |
| **档 3** | **拉普拉斯多频段融合**（工作域全分辨率，纯卷积/金字塔） | **0.25 ~ 0.6 s** | ✅ **备选档**：低端机（RAM < 4GB 或核数 ≤ 4）、超时或内存吃紧时自动切换，质量略降但绝不超线 |
| **档 4** | **全分辨率分块**（`TiledBlender`）：2048 tile、25% 重叠、每块 540p 域求解后汉宁窗加权合并 | **随 tile 数线性增长**（4096² 约 9 块） | ⚙️ **用户显式选择的档位**：不做全局降采样，因此没有"上采样导致过渡带发糊"；**永不进入自动升降档** |

**自适应策略**：首跑按设备能力选档（低端机直接档 3，其余档 1），之后按**实测融合耗时**调整 ——
- 档 1 实测 ≥ 4 s → 降到档 2；档 2 实测 ≥ 4 s → 降到档 3；
- 档 2 实测 ≤ 2.5 s → 下次升到档 1。
**关键约束（三个"必须有"，否则会出真问题）**：
1. **只改"下一次"的默认档，不改本次结果** —— 保证同一张图重复导出得到同样的输出；
2. **降过的档不自动升回**（超时档位进黑名单）。否则会震荡：档 1 跑 6 s → 降档 2 → 档 2 只跑 1 s → 又升回档 1 → 又跑 6 s。
   而"这台设备跑档 1 会超时"是设备固有属性，记住一次就够。
3. **档 4 完全不参与自适应**。它的耗时随图片尺寸线性增长，被"上次跑得快"自动升上来会直接失控；
   它只能在用户从编辑页的档位选择器里显式点选时执行。

**产品入口（用户意图 vs 实际执行必须分开）**：编辑页底部有档位选择器（自动 / 720p / 540p / 多频段 / 全分辨率分块）。
用户的选择是**意图**（`EditorUiState.tierPreference`），设备能力可能否决它 —— 规则只在纯函数
`FusionTierChoice.resolve` 里写一次，被降级时 UI 会明确提示"你选的分块在当前设备风险过高，已改用 720p"。
档 4（分块）只有高端机原样执行：中端降到档 1、低端降到档 2。

| 偏好 | 低端 | 中端 | 高端 |
| --- | --- | --- | --- |
| 自动 | 自适应 | 自适应 | 自适应 |
| 720p 泊松 | →540p（降级） | 720p | 720p |
| 540p 泊松 / 多频段 | 原样 | 原样 | 原样 |
| 全分辨率分块 | →540p（降级） | →720p（降级） | 分块 |

实际档位写入 `RenderResult.actualTier` 供回归分析，顶部状态行如实显示。注意**用户选保守档不降级**：
用户主动选一个比自动更省时的档，那是明确意图，不是可优化的对象。

**总耗时预算（1080p 输入，档 1）**

| 阶段 | 预算 |
| --- | --- |
| 解码 + sRGB/EXIF 校正 | 120 ms |
| 光照估计（256×256 推理 / 纯 Kotlin 兜底 < 40 ms） | 150 ms |
| 奶蛙预处理 + 颜色亮度协调 | 100 ms |
| 方向性阴影（1/4 分辨率） | 250 ms |
| **融合（档 1）** | **2500 ms**（1.5~3.0 s 区间取中值） |
| 边缘三段式 + JPEG 编码入相册 | 600 ms |
| **合计** | **≈ 3.7 s**（验收线 5 s，余量约 1.3 s） |

档 2 合计约 1.9–2.6 s；档 3 合计约 1.5–1.9 s。4K 长边 4096 导出：融合阶段仍在档位工作域
（720/540）求解后上采样回贴，因此增量主要来自解码、阴影与最终编码，预算 **< 12 s**。

### 7.3 大图保护（P0–P2 不分块）

- 工作副本长边 > 4096 → **先整图缩到 4096**（4K 长边的验收口径）
- 长边 > 2048 → **整图降采样到档位工作域（720 / 540）做融合，再上采样回贴**
  （一次缩放，不产生拼接痕迹；实现位置：`PoissonBlender` 把「场景 + 已摆放奶蛙」一起缩放后求解）
- **P0–P2 不引入分块**。分块仅在 P3 作为**可选**优化引入，且必须 **25% 重叠 + 重叠区加权融合**（汉宁窗权重），否则拼接缝在高频区清晰可见。
- 兜底：融合前若可用堆内存不足，自动降到档 3 并把工作分辨率上限压到 **1600**（见 §9）。

---

## 8. 线程与内存模型

| 任务 | 调度器 | 原因 |
| --- | --- | --- |
| UI 状态 | `Dispatchers.Main.immediate` | Compose 重组 |
| 解码/编码/MediaStore | `Dispatchers.IO` | 磁盘 IO |
| CV 算法 | `Dispatchers.Default`（并发度 = 核数-1，至少 1） | CPU 密集，留一核给 UI |
| 推理 | **专用单线程调度器** | 多 Interpreter 并发抢内存带宽且非线程安全 |
| OpenCV 原生调用 | **独占串行调度器** | 部分函数非线程安全并缓存内部缓冲 |

**内存预算（1080×1920 场景）**

| 项 | 大小 |
| --- | --- |
| 场景位图 ARGB_8888 | ≈ 8.3 MB |
| 工作副本（长边 1600） | ≈ 5.8 MB |
| 融合临时（720p × 3 份，档 1） | ≈ 11.1 MB |
| 奶蛙（1024² 含 alpha） | 4 MB |
| 模型 + arena | ≈ 30 MB |
| Compose/系统/显示缓冲 | ≈ 60 MB |
| 导出路径峰值（4096 长边 + 中间副本） | ≈ 300 MB |
| **峰值** | **≈ 480 MB**（验收线 **600 MB**） |

三条防 OOM 铁律：① 建位图前先查 `BitmapPool`；② 峰值期间全分辨率位图不超过 2 份；③ 捕获 `OutOfMemoryError` 后自动降到 **1600** 工作分辨率 + 档 3 重试一次（降级而非崩溃）。

> 说明：旧标准是"峰值 < 300 MB"，那是按 1280 工作分辨率 + 540p 融合域算的。新标准放宽到 600 MB 后，
> 工作分辨率与融合域同步上移（降级线 1280 → **1600**、默认档 2 → **档 1**），
> 换来的是 4K 导出不再需要降到 1280 才能不 OOM。

---

## 9. 降级矩阵

| 触发条件 | 现象 | 降级方案 |
| --- | --- | --- |
| IDE 拉不到 API 35 / AGP 8.7 | Gradle Sync 失败 | 切 **Plan B**（AGP 8.5.2 + Gradle 8.7 + compileSdk 34 + Compose BOM 2024.06.00），Kotlin 与业务代码不动 |
| 已确认 IDE 支持更高版本链 | — | 可选切 **Plan C**（AGP 9.4 系），一次性替换 `libs.versions.toml` |
| TFLite/LiteRT 加载失败、ABI 不匹配 | `UnsatisfiedLinkError` / `Unable to load model` | ① 检查 `noCompress` 是否含 `tflite`；② 直接走 `CvLightEstimator`（纯 Kotlin，功能完整） |
| GPU 加速不可用（含 **Android 15+ NNAPI 废弃**） | 推理慢 3~5 倍 | 加速器链 **GpuDelegate → XNNPACK CPU（4 线程）**，不再依赖 NNAPI；一次性探测并缓存 |
| 低端机（**总 RAM < 4 GB 或核心数 ≤ 4**；厂商标记 `isLowRamDevice` 仍尊重） | 融合 OOM 或卡顿 | 融合直接走 **档 3**、降级工作分辨率上限 **1600**、阴影用 1/8 分辨率生成 |
| 仅 `armeabi-v7a`（32 位） | 原生库缺失 / 寻址受限 | 只保留 CPU 路径，禁用 GLES 管线，导出提示 720p |
| OpenCV 未启用 | 无法 seamlessClone | `:core-pipeline` 自动选用拉普拉斯多频段或 `SimpleBlender`（Canvas 直贴），接口不变 |
| 单次融合耗时 ≥ 4 s | 超时风险 | 自动降档（档 1 → 档 2 → 档 3），并把该档加入本进程黑名单，记录 `RenderResult.actualTier` |
| 广色域输入（Display P3 / HEIC） | 色偏 | 解码即 `inPreferredColorSpace = SRGB`，**并且**解码后实测 `colorSpace.isSrgb`，不是则重画转换（在光照估计之前完成） |

---

## 10. 测试策略

**单元测试（JVM）**
- `UndoRedoStack`：压栈 20 次后 undo/redo 序列正确、超容量丢最旧、新操作清空 redo。
- `FrogManifest` 解析：**license 缺失 / 非法值 → 该条目不加载**并产生 warning；`CC0`/`self-drawn` 正常加载。
- `LightParams`：全字段钳制边界；`confidence < 0.45` 走兜底选择。
- `KelvinRgb`：**互逆性** —— `rgbGainToKelvin(kelvinToRgbGain(k))` 对 2000~12000 K 每 250 K 采样，误差 < 50 K。
- `ColorTransfer`：`strength = 0` 时输出与原图逐像素相等（lerp 修正的回归测试）；`strength = 1` 与手算 Reinhard 结果一致。
- `EdgeHaloFix`：`feather_px` 在 1080p 域落在 [6, 24]，720p 域按比例缩放且不小于 2。
- `ColorKeyMatte`：合成图（纯色背景 + 彩色圆）掩膜 IoU > 0.95。
- `CvLightEstimator`：合成色温图（3000/6500/9000 K）误差 < 800 K；构造梯度图验证方位角误差 < 15°。
- `DirectionalShadow`：σ 随 `elevation` 单调递减、随 `softness` 单调递增。

**金标（Golden Image）测试**：固定输入 + 固定 `LightParams` → 与基准图比对，**PSNR > 32 dB 或平均 ΔE(Lab) < 3**（不逐像素相等，跨设备浮点/GPU 差异必然存在）。

**仪器测试**：编辑页手势 → `Placement` 变化断言；保存 → MediaStore 条目存在且尺寸正确；权限拒绝路径的降级 UI。

**设备/系统矩阵（重点）**

| 系统 | 版本 | 重点验证 |
| --- | --- | --- |
| Android 8 (API 26) | minSdk 边界 | 无 scoped storage：MediaStore 写入走 `WRITE_EXTERNAL_STORAGE` 分支；`PickVisualMedia` 回退到 GET_CONTENT |
| Android 12 (API 31) | 主流存量 | 权限与相册流程 |
| Android 14 (API 34) | targetSdk 对应版本 | 部分相册权限、前台服务限制 |
| Android 15 (API 35) | **NNAPI 废弃起点** | **GpuDelegate → XNNPACK 回退链**（NNAPI 不再作为备选） |
| Android 16 (API 36) | 前瞻 | 同上 + 新的内存限制行为 |

**性能基准**：`benchmark` 模块 macrobenchmark 端到端（独立进程，3 次取中位数）；单测级用
`RenderResult.stageTimings` 断言总耗时。**新验收口径（2026-10）**：1080p 端到端 < **5 s**、
4K 长边 4096 导出 < **12 s**、峰值内存 < **600 MB**、拖动 ≥ **30 fps**、松手 ≤ **300 ms** 出中间质量预览
（真机数据为准；模拟器不作数）。

**素材合规**：`FrogManifestTest` 覆盖"缺 license / 非法 license / 非 PNG / 路径穿越 / 重复登记 / 坏 JSON"
六类拒绝路径。**注意**：本应用**不内置素材**（`assets/frogs/manifest.json` 的 `assets` 为空数组），
这些用例构造的是内联 JSON，因此与"包里有没有图"解耦 —— 将来重新内置素材时门控逻辑无需改动。

---

## 11. 风险登记册

| 风险 | 影响 | 概率 | 缓解 |
| --- | --- | --- | --- |
| AGP/Kotlin/KSP 组合不兼容 | 构建失败，阻塞全部工作 | **低**（v0.2 已改为官方兼容表闭合的 Plan A） | Plan B 只降 AGP/Gradle/compileSdk 三处，Kotlin 不动；Hilt 最坏情况切手写 `AppContainer` |
| `seamlessClone` 性能不达标 | 验收项 3 不通过 | 中（标准放宽到 5 s 后，档 1 中值约 3.7 s 已在线内；风险主要落在超低端机） | 三档预算 + 档 1 默认 / 档 2 保底 / 档 3 备选 + 运行时自适应降档（含黑名单防震荡） |
| 档位自适应在边缘设备上震荡 | 同一台设备导出质量时好时坏 | 低 | 超时档位进黑名单只降不升；实测耗时只影响"下一次" |
| 泊松融合在高频背景糊块 | 观感像贴纸 | 高 | §6.6 的 α 加权回贴；保留拉普拉斯多频段实现可切换对比 |
| 阴影为仿射近似，不跟随形状 | 复杂姿态不自然 | 高 | §6.5 已显式标注；P3 用距离变换 + 1D 卷积升级 |
| 自动光照估计在极端场景（夜景/纯色墙/逆光）失准 | 奶蛙格格不入 | 高 | 纯 Kotlin 兜底 + `confidence` 门控 + P3 手动滑杆；不追求全自动一步到位 |
| 素材版权 | 无法发布 | 低 | `manifest.json` 强制 license 字段，非 `CC0`/`self-drawn` 不加载 |
| 32 位/低内存设备崩溃 | 差评 | 中 | §9 降级矩阵 + OOM 自动降级重试 |

---

## 12. 变更记录

- **v0.7（本轮）**：实现**规格 8 跨域融合升级**（§6.12）——3D LUT 颜色映射（`.cube` 解析器 + 4 套内置 LUT + 选 LUT 规则）、
  MKL 最优传输颜色迁移（纯 Kotlin 闭式解，无需权重）、PCT-Net 端侧和谐化（高端机可选，CVPR 2023 参数预测 + 8s 超时回退）、
  RuntimeProvider 探测链升级 QNN→NPU→GPU→CPU。三轨制契约 `Harmonizer.kt`。
  新增 6 个测试类（CubeLut3d / MklHarmonizer / LutChoice 等），共 +17 用例。
  **已排除**：FLUX.2-klein-4B（需约 13GB VRAM，不满足端侧内存约束）。
- **v0.6（本轮）**：实现**规格 7 高级光影融合**（在边缘优化之后）——四色渐变环境光（`LightWrapGradient`）、
  边缘打光（`RimLight` 重写）、辉光（`Glow`）、阴影动态投位（`ShadowPlacementRule`，逆光/侧光/顺光三情形）。
  规则层下沉为纯函数 `LightFxRules`（逆光判定、三光效强度、预览/导出分级），UI 与管线共用同一套；
  三个新滑杆（边缘光/辉光/环境色）拖到 0 即关闭该步骤。新增 6 个测试类共 24 个用例，
  **实测修掉 `RimLight` 法向取反 bug**（详见 §6.11）。
  随后按规格 7.1a 做**精细化边缘提亮**（§6.11a）：法向改用距离变换梯度（`DistanceField`）、
  点积方向升级为 3D（elevation 衰减水平分量）、权重改点积^1.5。详见下方"规格 7 实现记录"。
- **v0.4（本轮）**：评估并落地 2026 融图趋势中**可端侧实现的机制**（详见 `docs/ADVANCED-FUSION-2026.md`）：
  **双轨制**（`FusionBackend` + `DeviceCapabilityProbe`，低端机无条件走经典路径，设备探测收敛到一处）；
  **结构重参数化**（`RepConv` / `FoldedConv`，多分支异构核折叠成单路径，附折叠等价性单测）；
  **细节保真修正**（`RepDetailRefiner`，52 参数的闭式多尺度高通，补回过渡带丢失的背景高频，
  DC 增益严格为 0，`strength=0` 时逐像素恒等）；**一维 LUT 替代超越函数**（`Lut1d`，cbrt / pow 走查表，
  实测 2.81× 加速、ΔE76 0.0006 —— 三维点变换 LUT 经实测否定，原因与数据见该文档 §2b）。
  同时按放宽后的预算调整档位阈值（升级 3000ms / 超时 6000ms）。
  **跳过**：SHINE 式生成式先验、DiffusionLight-Turbo 式生成式光照估计（差距 2 个数量级）。
- **v0.3**：性能口径按新标准放宽并同步到代码（1080p < 5s、4K 4096 < 12s、峰值内存 < 600MB、
  拖动 ≥ 30fps、松手 ≤ 300ms）；融合档位优先级上移为 **档 1 首选 / 档 2 保底 / 档 3 备选**
  并新增"超时档位只降不升"的防震荡黑名单；低端机判定改为 **总 RAM < 4GB 或核数 ≤ 4**；
  降级工作分辨率 1280 → **1600**；**取消内置素材**（`assets/frogs` 清空，素材改由用户从相册导入）；
  UI 改**暖黄配色**（亮暗两套写死色板 + `surfaceContainer*` 显式指定）；解码后新增
  **实测 sRGB 兜底转换**。逐条说明与 diff 见 `docs/ARCHITECTURE-CHANGES.md` 末尾。
- **v0.2**：版本矩阵改为 Plan A/B/C；删除 `:core-ai → :core-cv` 依赖；新增 `:core-pipeline` 编排层并规定 feature 只依赖接口；修正 Reinhard 力度施加方式；标注阴影为已知近似并给出 P3 升级；`feather` 加量纲公式；融合预算改三档并明确验收线/保底；P0–P2 取消分块、分块移入 P3 并要求 25% 重叠；新增 `KelvinRgb` 互逆函数对；预览改三段式；新增色域（sRGB）管理；新增撤销/重做栈；测试矩阵扩到 Android 8/12/14/15/16；素材改为 `manifest.json` + license 门控。
  逐条说明与关键 diff 见 `docs/ARCHITECTURE-CHANGES.md`。
- v0.1：首版架构。
