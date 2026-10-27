# 奶蛙相机 · 开发路线图与验收

每阶段独立可编译可运行。**上一个阶段没跑通，不进入下一个阶段。**

---

## P0 · 骨架与最小闭环（✅ 已全部完成并本机验证）

**验证结果**（2026-10-16，本机 Gradle 8.9 + JDK 17 + Android SDK 35）：
`BUILD SUCCESSFUL`（186 tasks）；25 个单元测试全绿；APK 12.65 MB，权限仅 CAMERA + WRITE_EXTERNAL_STORAGE(≤28)，**无 INTERNET**。

### P0-1 构建骨架（✅）

已生成：顶层与 `settings.gradle.kts`、版本目录 `gradle/libs.versions.toml`（**Plan A：AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 / compileSdk 35**）、
**8 个模块**的 `build.gradle.kts`（`:core-pipeline` 为 v0.2 新增的编排层）、`app` 的 Manifest（无 INTERNET 权限）+ 骨架代码、
各模块 consumer 混淆规则、素材 `manifest.json`（license 门控）与模型目录说明、`core-common` 的领域契约代码。

**首次构建自检（按顺序做，任一步失败先解决再往下）**

1. 环境：**Android Studio Koala（2024.1）或更新即可**（不必是最新版）；SDK Manager 装 **Android SDK Platform 35**、**Build-Tools 34.0.0**（AGP 8.7 默认值）、**NDK（可选，OpenCV 阶段才需要）**；Gradle JDK 用 **17**（AS 内置 JBR 17 即可）。
2. 生成 wrapper（仓库未含 `gradle-wrapper.jar`）：在项目根目录执行
   ```bash
   gradle wrapper --gradle-version 8.9
   ```
   或直接用 Android Studio 打开项目，按提示让它生成并下载 Gradle 8.9。
3. 首次同步：
   ```bash
   ./gradlew --version          # 确认 Gradle 8.9 + JVM 17
   ./gradlew :app:assembleDebug # 首次会下载全部依赖，约数分钟
   ./gradlew :app:installDebug  # 装到已连接的设备
   ```
   预期结果：桌面出现「奶蛙相机」图标，打开是列出 P0-3~P0-6 入口的占位页。
4. 跑一次已有单测（素材授权门控是合规红线，必须先绿）：
   ```bash
   ./gradlew :data:testDebugUnitTest --tests "com.naiwa.data.frog.FrogManifestTest"
   ```

**常见首次同步报错对照**

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `Android Gradle plugin requires Java 17` | 用了 JDK 8/11 | AS 设置里把 Gradle JDK 改成 17 |
| `Failed to find target with hash string 'android-35'` | 没装 API 35 | SDK Manager 安装；装不上就走 Plan B（架构文档 §2），Kotlin 与代码不动 |
| `Could not find org.opencv:opencv` | 仓库缺 `mavenCentral()` | 确认 `settings.gradle.kts` 两个仓库都在（本项目已配好；P0 未启用 OpenCV 时不会触发） |
| `Could not resolve ksp ...` | KSP 与 Kotlin 版本不匹配 | 两者必须同为 `2.3.21` 前缀，改 `libs.versions.toml` 的 `ksp` |
| Hilt 相关 KSP 报错 | AGP 9 与 Hilt 组合问题 | 见下方「Hilt 移除路径」 |

**Hilt 移除路径（最坏情况的兜底，非默认方案）**
若 Hilt 2.60.1 在你的 AGP 9.4 / KSP 组合下无法通过注解处理：
1. 各模块 `build.gradle.kts` 去掉 `alias(libs.plugins.ksp)`、`alias(libs.plugins.hilt)`、`ksp(libs.hilt.compiler)` 与 `hilt-android`、`androidx-hilt-navigation-compose` 依赖；
2. 顶层 `build.gradle.kts` 去掉 ksp/hilt 两行；
3. `NaiwaApp` 去掉 `@HiltAndroidApp`，`MainActivity` 去掉 `@AndroidEntryPoint`；
4. 用 `:app` 里一个 ~40 行的 `AppContainer` + `ViewModelProvider.Factory` 替代注入。
（该兜底不会改变任何业务代码结构，因为所有依赖都是构造注入。）

### P0-2 导航 / DI / 权限骨架 + 管线接线
- **交付**：`NaiwaNavHost`（路由：`home` → `camera` / `scene-picker` → `editor/{sceneUri}`）；`AppModule` 提供 `@IoDispatcher`、`@CvDispatcher`、`@InferenceDispatcher` 三个调度器限定符；`:core-pipeline` 里的 `DefaultImagePipeline` 通过 Hilt `@Binds` 绑定到 `ImagePipeline` 接口（P0 只实现 `SimpleBlender` 直贴路径）。
- **完成标准**：三个页面可互相跳转，返回栈正确；feature 模块能在**不 import 任何 `core-cv`/`core-ai` 类**的前提下拿到 `ImagePipeline` 实例；`assembleDebug` 通过。
- **验证**：`./gradlew :app:assembleDebug` + `./gradlew :feature-editor:dependencies --configuration debugCompileClasspath`（确认输出里没有 core-cv/core-ai）。

### P0-3 CameraX 拍照（`feature-camera`）
- **交付**：`CameraScreen`（PreviewView 预览 + 快门按钮 + 前后摄切换 + 无权限引导）；`CameraViewModel` 用 `ImageCapture` 拍照存到 `cacheDir/scene_<ts>.jpg`，并用 `ExifInterface` 校正方向后回传编辑页。
- **完成标准**：真机拍照后回到编辑页能看到正立图片；横拍/竖拍都不倒。
- **验证**：真机手测 + `adb logcat -s NaiwaCamera`。
- **降级**：设备无相机 → 隐藏快门，只显示「从相册选」。

### P0-4 相册选场景图
- **交付**：`PhotoPickerContract`（`ActivityResultContracts.PickVisualMedia`）—— **不需要任何存储权限**，API 26~29 由系统自动回退到 `ACTION_GET_CONTENT`。
- **完成标准**：Android 8 与 Android 14 上都能选到图并进入编辑页；`READ_EXTERNAL_STORAGE` 未出现在任何运行时权限请求中。

### P0-5 奶蛙素材库 + 导入 + 基础抠图
- **交付**：`tools/gen_placeholder_frogs.py` 生成 6 张**自绘**占位透明 PNG → 放入 `data/src/main/assets/frogs/`，并**同步写入 `manifest.json`**；`BuiltInFrogRepository` 读 `manifest.json`，**license 非 `CC0`/`self-drawn` 的条目一律不加载并 Log.w**；`FrogPickerSheet` 横向缩略图 + 「从相册导入」；`ColorKeyMatte`（四角背景色 + HSV 距离阈值 + 开闭运算 + 最大连通域 + 孔洞填充，纯 `IntArray` 实现，不引 OpenCV）为无 alpha 的导入图生成掩膜，并提供**一键预览掩膜**供人工确认。
- **完成标准**：内置素材可选中（6 条全部有 license）；手工把 `manifest.json` 里某条的 `license` 删掉后重启，该素材**从列表消失**且 logcat 出现 warning；导入一张「绿幕/白底」奶蛙图能抠出主体。
- **验证**：`./gradlew :data:testDebugUnitTest`（`FrogManifestTest` 6 个用例）+ 手动导入 3 张不同背景的真实照片。

### P0-6 编辑页：变换 + 叠加 + 撤销重做 + 保存 + 分享
- **交付**：`EditorScreen`（预览画布 + 底部工具区）、`TransformGestures`（拖动/双指缩放旋转 → `Placement`，走 `RenderMode.DRAG` 的 720p 直贴预览）、`EditHistoryController`（`UndoRedoStack<EditorSnapshot>`，深度 20；拖动在**手指抬起**时记录、滑杆在**值稳定 300ms** 后记录）、`OverlayCanvas`（`Matrix` 变换绘制奶蛙）、`ExportController`（`MediaStoreSaver` 写入 `Pictures/奶蛙相机/`，JPEG q=95，**统一 sRGB**；`ShareHelper` 分享）。
- **完成标准（P0 验收）**：飞行模式下完成 拍照/选图 → 选奶蛙 → 拖动缩放旋转 → **撤销/重做各 3 次后状态正确** → 保存 → 在系统相册看到成品；全流程无网络请求（开飞行模式验证）。
- **验证**：`./gradlew :app:installDebug` + 真机走查；保存后用 `adb shell content query --uri content://media/external/images/media` 确认条目。
- **已知限制（P0 明确不做）**：无光照协调、无阴影、无泊松融合（奶蛙是「贴上去」而非「融进去」），这是 P1/P2 的目标。
- **降级**：`:core-pipeline` 在没启用 OpenCV 时自动选用 `SimpleBlender`，接口与调用方不变。

### P0-7 测试与验收
- **交付**：`core-common` 的 `UndoRedoStack` / `Placement` / `LightParams` 单测；`data` 的 `FrogManifestTest`（已完成）；`feature-editor` 的手势仪器测试与保存流程仪器测试。
- **命令**：
  ```bash
  ./gradlew testDebugUnitTest              # 全部模块单测
  ./gradlew :core-common:testDebugUnitTest # 只测模型与栈
  ./gradlew :data:testDebugUnitTest        # 只测素材门控
  ./gradlew connectedDebugAndroidTest      # 需连接真机
  ```

---

## P1 · 光照估计与颜色协调（✅ 已完成，54 个单测全绿）

| 任务 | 关键文件 | 完成标准 |
| --- | --- | --- |
| P1-1 启用 OpenCV（`org.opencv:opencv:4.10.0`）并在 `NaiwaApp` 做 `initLocal()` 探测 | `core-cv/build.gradle.kts`、`NaiwaApp` | 原生库加载成功；失败时全链路自动走纯 Kotlin 实现 |
| P1-2 传统 CV 光照估计（**纯 Kotlin，位于 `:core-ai`**：灰度世界/白点法、直方图分位、亮度梯度估主光方向） | `light/SceneStats.kt`、`light/CvLightEstimator.kt` | 合成色温图误差 < 800 K；方位角误差 < 15°；**永不抛异常** |
| P1-3 `KelvinRgb` 互逆函数对（`kelvinToRgbGain` / `rgbGainToKelvin`） | `color/KelvinRgb.kt` | 2000~12000 K 每 250 K 采样，往返误差 < 50 K；自动估计与手动滑杆**共用**这两个函数 |
| P1-4 TFLite 接入 + 模型安装器 + 加速器探测（**GpuDelegate → XNNPACK CPU**，不使用已废弃的 NNAPI） | `RuntimeProvider.kt`、`ModelAssetInstaller.kt`、`TfliteLightEstimator.kt` | GPU 不可用时自动降 CPU；模型缺失时静默回退 CV；`confidence < 0.45` 时改用 CV 结果 |
| P1-5 颜色/亮度协调（Reinhard Lab **+ lerp 力度** + L 通道分位映射 + `KelvinRgb` 色温增益） | `color/ColorTransfer.kt` | `strength=0` 与原图逐像素相等；`strength=1` 与手算 Reinhard 一致；奶蛙与场景平均色温差 < 600 K |
| P1-6 把 P1 结果接入编辑页预览（松手后 300 ms 内切中间质量预览） | `EditorViewModel`、`OverlayCanvas` | `RenderMode.SETTLE` 总耗时 ≤ 250 ms |

---

## P2 · 方向性阴影 + 融合 + 边缘优化（✅ 已完成；点选抠图未交付，原因见 README）

| 任务 | 关键文件 | 完成标准 |
| --- | --- | --- |
| P2-1 方向性阴影 —— **实现方式已升级，不再是仿射近似**：按光源方向对掩膜做**带衰减的扫掠 + 高斯模糊 + multiply**，阴影轮廓**跟随奶蛙形状** | `shadow/DirectionalShadow.kt` | 方向与 `keyAzimuthDeg` 一致（目视 ±15°）；软硬随 `shadowSoftness` 单调变化；`elevation < 10°` 时阴影长度按「物体高度 × 4」截断 |
| P2-2 边缘光 | `rim/RimLight.kt` | 逆光场景出现自然轮廓光；正光场景几乎不可见 |
| P2-3 泊松融合（**档 1 = 720p 域，首选**；档 2 = 540p 域 + 上采样）**+ α 加权回贴三段式** | `blend/PoissonBlender.kt`、`blend/EdgeHaloFix.kt` | 档 1 耗时 1.5~3.0 s（合计约 3.7 s，验收线 5 s）；档 2 耗时 0.6~1.3 s；`feather_px = clamp(0.02×min(w,h), 6, 24)`；草地/木纹等高频背景无可见糊块、无白边光晕 |
| P2-4 拉普拉斯多频段融合（**档 3 = 备选**） | `blend/LaplacianBlender.kt` | 耗时 0.25~0.6 s；低端机/超时时自动被选中，可通过设置项手动切换 A/B 对比 |
| P2-5 点选分割 + 手动涂抹 | `seg/InteractiveSegmenter.kt`、`panel/MattingSheet.kt` | 点击 2~3 次可得到可用掩膜；涂抹可修任意局部；掩膜后处理含「未知区收缩 2px + 高斯羽化」 |

**P2 出口验收**：奶蛙色温/亮度与场景基本一致，有方向性阴影，边缘 100% 放大无明显光晕（架构文档 §6.6 三件套生效）。

---

## P3 · GPU 加速 + 性能优化 + 手动调节（✅ 手动调节/档位自适应/分块融合/档位选择器已交付；GLES 未交付，原因见 README）

| 任务 | 关键文件 | 完成标准 |
| --- | --- | --- |
| P3-1 GLES 融合管线（EGL + GLSL，替代/补充 CPU 路径） | `gles/GlesCompositor.kt` | 1080p 融合耗时下降 ≥ 40%；CPU 路径保留作兜底 |
| P3-2 分块融合（tile 2048、**25% 重叠**、重叠区**汉宁窗加权**）—— **已接入产品**：新增 `FusionTier.TILED`，编辑页档位选择器可选，`TiledBlender.fuse` 逐块 540p 求解后加权合并；**只有高端机原样执行**（中端 → 720p、低端 → 540p，由 `FusionTierChoice` 降级并如实提示），且**永不进入自动升降档** | `blend/TiledBlender.kt`、`core-common/model/FusionTierChoice.kt`、`DefaultImagePipeline.kt` | 4096 长边输入无可见拼接缝；合并后**无未覆盖像素**（实测 4096×2600 / 6 块 → 未覆盖 0）；峰值内存 < 600 MB |
| P3-3 阴影形状跟随（**已提前到 P2 交付**：扫掠实现天然跟随形状；距离变换方案作为备选未启用） | `shadow/DirectionalShadow.kt` | 与 P2 描述一致；跷腿/耳朵等局部形状有对应阴影 |
| P3-4 手动调节面板（光照强度/阴影方向/色温/融合强度/边缘羽化）+ 撤销/重做联动 | `LightOverrides`、`panel/LightSlidersPanel.kt` | 每个滑杆拖动后 300 ms 内预览升级；「重置为自动」恢复模型估计值；色温滑杆与自动估计共用 `KelvinRgb` |
| P3-5 广色域输入处理：解码后**实测** `bitmap.colorSpace.isSrgb`，不是则用 Canvas 重画转换（在光照估计之前） | `core-common/image/BitmapDecoder.kt` | Display P3 / HEIC 输入不再产生整体色偏；导出统一 sRGB |
| P3-6 融合档位自适应策略 + 性能基准回归 | `DefaultImagePipeline.kt`、`benchmark/` | 中端机 1080p 端到端 < **5 s**（3 次中位数）；`RenderResult.actualTier` 记录实际档位；超时档位进黑名单不回升 |
| P3-7 **双轨制**：设备档位（RAM/核数）+ 用户开关 → 融合后端选路；`RenderResult.backend` 如实上报 | `core-common/model/FusionBackend.kt`、`core-common/device/DeviceCapabilityProbe.kt`、`EditorViewModel`、`EditorScreen` | 低端机无条件走经典；中/高端默认细节保真可关闭；**`ActivityManager` 只在 `DeviceCapabilityProbe` 一处出现** |
| P3-8 **结构重参数化**（MobileFusion 机制）：多分支异构核 → 折叠单路径 + 有效核自动收缩 | `core-cv/fusion/RepConv.kt` | 折叠前后逐点一致（实测最大差 1.8e-07）；1×3 / 3×1 分支的折叠位置正确；外圈为零时有效核收缩且结果不变 |
| P3-9 **细节保真修正**：闭式多尺度高通算子，补回过渡带丢失的背景高频 | `core-cv/fusion/RepDetailRefiner.kt`、`blend/EdgeHaloFix.kt` | 52 参数；DC 增益 7.3e-08；`band = smoothstep(core) × (1-alpha)`；边缘背景误差 **8.91 → 4.86（−45%）**；`detailStrength=0` 与旧行为逐像素一致；失败返回 null 并如实回报 |
| P3-10 **一维 LUT 替代超越函数**（UHD-MFF 思路）：cbrt / pow 走查表 | `core-cv/color/Lut1d.kt`、`color/LabColor.kt` | 实测 **3.32×** 加速；sRGB→Lab 最大 ΔE76 0.0006；**端到端产品输出与解析版逐位相同**（最大通道差 0）；建表 4ms |
| P3-11 **集成加固**：增强步骤的失败契约 + 如实回报 + 区域局部内存 | `blend/EdgeHaloFix.kt`（`DetailReport`）、`fusion/RepDetailRefiner.kt`、`DefaultImagePipeline.kt` | 细节保真失败时退回未修正结果且**不抛异常**；`RenderResult.backend` 随之改回经典路径；band 数组按包围盒分配（4096² 下省 67MB/次） |
| P3-12 **高级光影融合（规格 7）**：四色渐变环境光 + 边缘打光 + 辉光 + 阴影动态投位 | `core-common/model/LightFxRules.kt`、`ShadowPlacement.kt`；`core-cv/rim/RimLight.kt`、`glow/Glow.kt`、`wrap/LightWrapGradient.kt`、`scene/ScenePalette.kt`、`shadow/DirectionalShadow.kt` | 逆光判定（夹角>120°且主光>0.5）；阴影三情形（逆光投前方/侧光沿光/顺光接触阴影）；辉光与环境色**仅导出且非低端机**执行；三个新滑杆拖 0 即关。**实测修掉 RimLight 法向取反 bug** |
| P3-13 **边缘预处理（规格 6，白边修复）**：反预乘 → 去白(只改 RGB) → alpha 柔化(只改 alpha) → 重新预乘 | `core-cv/edge/FrogEdgePreparer.kt`、`blend/EdgeHaloFix.kt` | 白边验收 **35.9 → 0.8**（验收线 <15，`FrogEdgeQualityTest` 断言）；`feather_px = clamp(0.015×min(w,h), 4, 20)`；`prepareFrog` 早期"已带 alpha 就 return"的白边高发场景也已修复 |
| P3-14 **精细边缘光（规格 7.1a）**：距离变换梯度法向 + 3D 光源方向 + 点积^1.5 | `core-cv/util/DistanceField.kt`、`rim/RimLight.kt` | 法向用 Chamfer 两遍扫描距离场梯度；`L=(cos(elev)cos(az), cos(elev)sin(az), sin(elev))` 让 elevation 真正衰减水平分量；权重 = 点积^1.5；2D 抠图 nz≈0 如实记录 |

### 规格 8 · 跨域融合升级（✅ 已完成，173 单测全绿；PCT-Net 权重待转换）

针对"二次元前景 + 写实背景"的跨域场景，几何/颜色算法天花板不足，引入三层升级（执行顺序见 ARCHITECTURE §6.12）：

| 任务 | 关键文件 | 完成标准 |
| --- | --- | --- |
| 8.1 **3D LUT 颜色分级**（`.cube` 解析 + 三线性采样 + 4 套内置 LUT + 选 LUT 规则） | `core-cv/color/CubeLut3d.kt`、`LutApply.kt`、`LutRepository.kt`；`core-common/model/LutChoice.kt`；`tools/gen_luts.py` | 恒等 LUT 不变性 / 色温分档 + 过渡带插值 / 逆光优先（`CubeLut3dTest` 5 + `LutChoiceTest` 6 用例）；仅前景 RGB，背景不碰 |
| 8.2 **MKL 最优传输和谐化**（纯 Kotlin 闭式解，12 维仿射） | `core-cv/color/MklHarmonizer.kt`、`core-common/model/Harmonizer.kt` | 相同分布恒等 / 均值平移 / 方差缩放 / 退化对角 / 像素范围（`MklHarmonizerTest` 6 用例）；无需权重、永不失败 |
| 8.2b **PCT-Net 端侧和谐化**（CVPR 2023，权重免申请，仅高端机 + 8s 超时回退 MKL） | `core-ai/harmonize/PctNetHarmonizer.kt` | 结构就位；权重为 PyTorch `.pth`，需转 LiteRT（见 `docs/DEVICE-VERIFICATION.md`），本仓库不提供转换后权重 |
| 8.3 **NPU 部署规范**（QNN→NPU→GPU→CPU，反射探测 + 缓存，禁止 NNAPI） | `core-ai/RuntimeProvider.kt` | 加 Google Maven litert-* 依赖后反射即命中、代码零改动；探测结果按模型路径缓存 |

---

## 全局验收清单（性能口径为 2026-10 放宽后的版本）

- [ ] 飞行模式下：拍照/选图 → 导入奶蛙 → 自动分析光源 → 融合 → 保存，全程可用
- [ ] 奶蛙色温、亮度与场景基本一致；有方向性阴影；无明显边缘光晕
- [ ] 1080p 处理 < **5 s**（**档 1 首选**；超时自动降 **档 2 保底**，再超时降 **档 3 备选**）
- [ ] 4K 长边 4096 导出 < **12 s**
- [ ] 峰值内存 < **600 MB**
- [ ] 预览：拖动 ≥ **30 fps**；松手后 ≤ **300 ms** 切到带阴影的中间质量预览
- [ ] 在本地 Android Studio 构建成功，无任何云服务依赖（Manifest 无 INTERNET 权限）
- [ ] **内置素材为空是预期状态**；若将来重新内置，全部条目必须在 `manifest.json` 登记且 license 合法
- [ ] 设备矩阵：Android 8 / 12 / 14 / 15 / 16，重点验证 Android 15+ 的 **GpuDelegate → XNNPACK** 回退链
      （此项需要真机，本机无设备，**尚未验证**）
