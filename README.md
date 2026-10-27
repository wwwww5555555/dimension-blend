# 次元溶图（Dimension Blend）

[![CI](https://github.com/wwwww5555555/dimension-blend/actions/workflows/ci.yml/badge.svg)](https://github.com/wwwww5555555/dimension-blend/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

完全本地运行、离线优先的 Android 图像合成应用：从相册选一张背景、导入一张人像素材，一键把它溶进场景的光影里。

- **不联网**：Manifest 不申请 `INTERNET` 权限，`allowBackup=false` —— 应用在系统层面没有上传图片的能力。
- **全端侧**：光照估计、抠图、融合全部在本机推理与计算完成，飞行模式下可用。
- **无私有服务器**：所有依赖来自 Google Maven / Maven Central。

技术栈：Kotlin · Jetpack Compose · Hilt · DataStore · OpenCV · LiteRT(TFLite) · MediaPipe · GLES 3.0

---

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构、模块划分、文件树、依赖版本矩阵（Plan A/B/C）、算法契约、性能预算、降级矩阵、测试策略 |
| [docs/ARCHITECTURE-CHANGES.md](docs/ARCHITECTURE-CHANGES.md) | v0.1 → v0.2 → v0.3 逐条修正说明与关键 diff；末尾含「取消内置素材 + UI 暖黄 + 性能口径放宽」的本轮记录 |
| [docs/ADVANCED-FUSION-2026.md](docs/ADVANCED-FUSION-2026.md) | 2026 融图新趋势的端侧可行性评估：SHINE / MobileFusion / UHD-MFF / DiffusionLight-Turbo 的逐项判定与已落地机制 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | P0~P3 任务分解、完成标准、验收清单 |
| [docs/RIM-GLOW.md](docs/RIM-GLOW.md) | **边缘光与泛光的完整实现**：为什么旧版是白描边、发光源提取、高斯金字塔（含「为什么不用单 pass 多次高斯」的成本推导）、向内渗透的光包裹、Alpha 锁死、调参与 Debug 判读、离线验证工具 |
| [docs/BACKLIT-CUTOFF.md](docs/BACKLIT-CUTOFF.md) | **逆光剪影压暗 + 阴影漏光熔断**：为什么径向伪法线会画出斜切、内部=背光面的判据、双判据取最大熔断、规格伪代码的 UB 问题、实测亮度对比 |
| [docs/DYNAMIC-LIGHT.md](docs/DYNAMIC-LIGHT.md) | **动态光影跟随**：为什么「方向是常数」会让包裹指错边、像素空间 `normalize(uLightPos-uv)`、顶光偏置、光源邻域取色、三位置对称验证 |
| [docs/SHADOW-DEPTH.md](docs/SHADOW-DEPTH.md) | **暗部层次与底部投影**：三级高度阶梯、环境色乘叠、接触阴影「向下采样」的方向 bug、强度被当暗度乘的问题、节点存活链路验证 |
| [docs/PENUMBRA.md](docs/PENUMBRA.md) | **明暗交界过渡带**：为什么「用了 smoothstep 还是刀切」（空间尺度只有 1px）、宽基线法向、litRatio 互补混合、环境光钟形桥梁、投影末端柔化 |
| [docs/KEY-COLOR.md](docs/KEY-COLOR.md) | **主光锁定与环境去荧光**：为什么暖光在蓝背景里变蓝（迎光面环境权重没归零）、ambientWeight 三段曲线、限饱和的闭式解、主光提纯与亮部取色 |
| [docs/ORIENTATION-PIPELINE.md](docs/ORIENTATION-PIPELINE.md) | **素材翻转 + 管线中断**：三个渲染器回读方向不一致（双翻即颠倒）、统一回读 + 首次调用实测方向、DAG 逐节点 try-catch 与直通降级、每 pass 强制关闭深度/剔除 |
| [docs/EDGE-SOLIDITY.md](docs/EDGE-SOLIDITY.md) | **边缘变半透明（融化）**：不是 alpha 被污染，而是大气透视把背景 Screen 进人物内侧 20px；alpha 过渡带限制、核心区锁定、腐蚀回填 |
| [docs/UI-POLISH.md](docs/UI-POLISH.md) | **启动图标与素材清空**：自适应图标的青橙双环设计、`#AARRGGBB` 坑、安全区实测；清空素材的四步时序、为什么不能立即 recycle、管线无取消点的竞态兜底 |
| [docs/RELEASE.md](docs/RELEASE.md) | **发布指南**：素材列表删除（为什么 MaterialItem 不放 Bitmap、删除后自动接续、回收要等管线空闲）、日志用 R8 `-assumenosideeffects` 而非 `BuildConfig.DEBUG` 的理由与实测、R8 规则、keystore 与签名、体积构成（dex/so） |
| [CHANGELOG.md](CHANGELOG.md) | v0.1 → v0.7 与渲染迭代 v0.19~v0.26 的变更与验证结果 |

## 环境要求（Plan A）

| 项 | 版本 |
| --- | --- |
| Android Studio | Koala（2024.1）或更新即可 |
| JDK | 17 |
| Gradle | 8.9（由 wrapper 指定） |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / KSP | 2.0.21 / 2.0.21-1.0.27 |
| compileSdk / targetSdk / minSdk | 35 / 34 / 26 |
| Compose BOM | 2024.10.01 |
| Android SDK Platform | 35；Build-Tools 34.0.0（AGP 8.7 默认值） |
| 最低运行版本 | Android 8.0（API 26，arm64-v8a / armeabi-v7a） |

装不上 API 35 时按 [docs/ARCHITECTURE.md §2](docs/ARCHITECTURE.md) 的 Plan B 改 4 个值即可，Kotlin 与业务代码不动。

## 快速开始

> **仓库里没有 APK、模型权重和 `dist/` 归档**（见 `.gitignore`）：这三类合计 2.8GB+，
> 提交进来会让 clone 变成几分钟的事。想直接装包用 [Releases](../../releases)，或按下述方式自行构建。

### 方式一：命令行构建（无需 Android Studio）

```bash
bash tools/build-local.sh :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
bash tools/build-local.sh testDebugUnitTest         # 423 个单元测试
bash tools/build-local.sh :app:assembleRelease      # R8 + 资源裁剪，需在 local.properties 配签名
bash tools/build-local.sh clean
```

`tools/build-local.sh` 里的 JDK / SDK 路径是本机绝对路径（见文件头）。换机器时改那两行，
或直接用 Android Studio 打开（方式二）。

### 方式二：Android Studio

用 Android Studio 打开本目录即可（`local.properties` 指向本机 SDK，换 SDK 就改这一行）。
首次同步会下载 Gradle 8.9 与全部依赖。仓库不含 `gradle-wrapper.jar`，如要用 wrapper：

```bash
gradle wrapper --gradle-version 8.9   # 只需执行一次
./gradlew :app:assembleDebug
```

### 构建前须知：模型文件

| 文件 | 在仓库里？ | 缺失时的影响 |
| --- | --- | --- |
| `assets/models/pct_net_vit.tflite`（18.5MB） | ✅ **Git LFS 纳管** | 见下方「没装 git-lfs」 |
| `assets/models/light_estimator_int8.tflite` | ❌ 未训练 | 光照估计走传统 CV（Plan B），方向/色温精度略降 |

**clone 前先装 git-lfs**，否则模型会是一个 132 字节的指针文件（不会崩，但 PCT-Net 静默降级）：

```bash
git lfs install            # 每台机器一次
git clone git@github.com:wwwww5555555/dimension-blend.git
```

已经 clone 过、发现模型是 132 字节？补一句 `git lfs pull` 即可。

模型的规格与获取方式见
[core-ai/src/main/assets/models/README.md](core-ai/src/main/assets/models/README.md)，
把文件放回该目录重新构建即生效，**代码无需改动**（缺模型一律降级，不崩）。

### 已验证的产物事实（`aapt dump permissions/badging` 实测）

| 项 | 值 |
| --- | --- |
| 包名 / 版本 | `com.naiwa.camera` / 1.0.0（versionCode 100） |
| minSdk / targetSdk / compileSdk | 26 / 34 / 35 |
| 权限 | `WRITE_EXTERNAL_STORAGE`(maxSdk 28) —— **无 INTERNET、无 CAMERA、无 READ_MEDIA_IMAGES** |
| 原生 ABI | arm64-v8a、armeabi-v7a |
| 体积 | debug 75.9 MB / release 66.0 MB（其中 `.so` 45.7 MB，占 69%） |
| 单元测试 | 423 个用例 / 60 个测试类全绿 |

## 模块结构

```
:app              单 Activity + 导航 + Hilt 聚合（不直接依赖 core-cv / core-ai）
:core-common      领域模型 / 管线接口 / 图像工具（零原生、零 UI）
:core-pipeline    管线编排层 —— 唯一同时引用 core-cv 与 core-ai 的模块
:core-cv          OpenCV 与像素算法：颜色迁移、KelvinRgb、方向性阴影、融合、边缘优化
:core-ai          端侧推理：光照估计、抠图（传统 CV 兜底为纯 Kotlin，不依赖 OpenCV）
:core-data        奶蛙素材库（manifest.json + license 门控）、MediaStore 保存、DataStore 设置
:feature-camera   CameraX 拍照
:feature-editor   素材选择 / 抠图涂抹 / 变换 / 预览 / 保存分享（只依赖 ImagePipeline 接口）
```

**依赖纪律**：`:core-cv` 与 `:core-ai` 互相不依赖；feature 之间不互相依赖；feature 只认 `:core-common` 里的
`ImagePipeline` 接口与 `RenderRequest`/`RenderResult` 模型，不直接引用任何具体算法类。

> 模块名说明：数据层模块叫 `:core-data` 而不是 `:data`。改名是因为排查构建问题时发现同名目录存在状态异常，
> 统一成 `core-` 前缀后与其它能力层模块命名一致，也顺手消除了这一类风险。

## 当前进度

- [x] **P0**：骨架、相机拍照、相册选图、素材库（授权门控）、色键抠图、拖动缩放旋转、撤销/重做、保存到相册、分享
- [x] **P1**：光照估计（传统 CV + LiteRT 双实现，模型缺失自动降级）、色温互逆函数对、
      颜色/亮度协调（Reinhard + lerp 力度 + 分位数亮度匹配 + 色温增益）、轮廓光、OpenCV 接入
- [x] **P2**：方向性阴影（沿光扫掠，轮廓跟随形状）、泊松融合三档（**720p 首选** / 540p 保底 / 拉普拉斯备选）、
      边缘三段式 α 加权回贴、掩膜腐蚀/羽化
- [x] **P3**：手动调节面板（8 项滑杆，可逐项"恢复自动"）、融合档位运行时自适应（超时降档且不回升）、
      **档位选择器**（自动 / 720p 高质量 / 540p 验收线 / 多频段保底 / 全分辨率分块，选择持久化并真正参与渲染）、
      分块融合（25% 重叠 + 汉宁窗，已接入 `FusionTier.TILED`，仅高端机原样执行）
- [x] **本轮调整**：取消内置素材（改为用户导入）、UI 改暖黄配色、性能口径放宽（5s / 600MB / 4K 12s）、
      低端机判定改为 RAM < 4GB 或核数 ≤ 4、降级工作分辨率 1280 → 1600、解码后实测 sRGB 兜底
- [x] **2026 融图趋势落地**（详见 [docs/ADVANCED-FUSION-2026.md](docs/ADVANCED-FUSION-2026.md)）：
      **双轨制**（低端经典 / 高端细节保真，设备档位 + 用户开关 + 如实上报）、
      **结构重参数化**（MobileFusion 机制：多分支 → 折叠单路径，52 参数、折叠等价性 5.2e-08）、
      **一维 LUT 替代超越函数**（UHD-MFF 思路：实测 2.81× 加速、ΔE76 0.0006）
- [x] **v0.5 边缘预处理（规格 6）**：反预乘 → 去白（只改 RGB）→ alpha 柔化（只改 alpha）→ 重新预乘，
      专治"透明玻璃白边"；白边验收 **35.9 → 0.8**（验收线 <15），`FrogEdgeQualityTest` 把"放大目测"变成断言
- [x] **v0.6 高级光影融合（规格 7 + 7.1a）**：四色渐变环境光、边缘打光（距离变换梯度法向 + 3D 光源方向 + 点积^1.5）、
      辉光、阴影动态投位；规则下沉为纯函数 `LightFxRules` / `ShadowPlacement`（可 JVM 单测），
      UI 与管线共用同一套，杜绝"面板显示 0.3 实际跑 0"
- [x] **v0.7 跨域融合（规格 8）**：**3D LUT 颜色分级**（4 套内置 LUT，`tools/gen_luts.py` 纯标准库生成）、
      **MKL 最优传输和谐化**（纯 Kotlin 闭式解，12 维仿射，无需权重、永不失败）、
      **PCT-Net 端侧和谐化**（CVPR 2023，权重免申请，仅高端机 + 8s 超时回退 MKL，结构就位）、
      **NPU 探测链**（QNN→NPU→GPU→CPU，反射探测、结果缓存、禁止 NNAPI）

### 未交付项与原因（不含糊）

| 项 | 原因 | 如何补齐 |
| --- | --- | --- |
| MediaPipe 点选分割 | 没接进 UI（引进来就是 30MB 死重量）；其 AAR 会把 INTERNET 权限合并进 manifest，破坏硬约束；无真机可验证原生推理路径 | `core-ai/src/main/assets/models/README.md` 保留了模型下载 URL 与启用步骤，接入点是 `ImagePipeline.prepareFrog` |
| GLES 加速 | EGL/GLSL 无法在本环境验证，而"渲染对不对"只能看真机；宁可交付已验证的 CPU 路径 | 建议先真机上把"颜色增益 + α 合成"搬到 GPU，与 CPU 路径 A/B 对照 |
| PCT-Net 权重 | **已内置**（`pct_net_vit.tflite`，float32 18.47MB，已对拍验证 torch vs tflite 误差 1e-6）；仅高端机启用，8s 超时回退 MKL | 转换脚本 `tools/convert_pctnet.py` 可复现 |
| 真机验证 | 本机无设备，NPU 探测、光效观感、融合耗时、Android 15+ GpuDelegate→XNNPACK 回退链均未实测 | `docs/DEVICE-VERIFICATION.md` 已预留全部开关与 logcat 判定标准 |

### 已知限制（P1/P2/P3 及之后）

| 限制 | 现状 |
| --- | --- |
| 光照模型 | `light_estimator_int8.tflite` 未训练，运行时自动走传统 CV（功能完整，精度略降）——这是设计如此 |
| 融合耗时 | 档 1（720p 泊松）实测需真机验证；档位会按实测耗时自动降级（档 1 → 档 2 → 档 3），并记住"超时的档位"不再回升 |
| 广色域输入 | 解码即请求 sRGB，**并已加"解码后实测 `colorSpace.isSrgb`，不是则重画转换"的兜底**；真机色偏仍需实测确认 |
| 性能指标 | 1080p < 5s / 4K 4096 < 12s / 内存 < 600MB / 拖动 ≥ 30fps 均为**目标值**，本机无真机，**未实测** |

## 性能目标（2026-10 口径）

| 指标 | 目标 |
| --- | --- |
| 1080p 导出端到端 | < 5 s（档 1 预算约 3.7 s） |
| 4K 长边 4096 导出 | < 12 s |
| 峰值内存 | < 600 MB |
| 预览拖动 | ≥ 30 fps |
| 松手后中间质量预览 | ≤ 300 ms |

融合四档：**档 1（720p 域泊松，首选）→ 档 2（540p 域 + 上采样，保底）→ 档 3（拉普拉斯多频段，备选）→ 档 4（全分辨率分块，用户显式选择）**。
超时自动降档且**不回升**（避免在边缘设备上来回震荡）。档位阈值：升级 3000ms / 降级 6000ms。
档 4 不做全局降采样（因此没有"上采样导致过渡带发糊"），代价是耗时随 tile 数线性增长，**不参与自适应升降档**。

编辑页底部可**显式选择档位**（自动 / 720p 高质量 / 540p 验收线 / 多频段保底 / 全分辨率分块），选择会持久化。
设备能力可能否决用户的选择（例如中端机选"分块"）—— 此时界面会明确提示改用了哪一档，**不默默替换**。

融合**后端**（双轨制）：**经典（泊松 + α 回贴）** / **细节保真（重参数化）**。
低端机（RAM < 4GB 或核数 ≤ 4）无条件走经典路径；中/高端机默认开启细节保真，可在编辑页关闭。
编辑页状态行会显示**实际使用的后端**，不猜。

## 素材与模型授权

**本应用不内置任何奶蛙素材**：`core-data/src/main/assets/frogs/manifest.json` 的 `assets` 是空数组，
界面会提示用户「从相册导入」。因此首次启动素材条为空是**预期行为**。

若将来要重新内置素材，必须同时满足：① 文件放进 `assets/frogs/`；
② 在 `manifest.json` 的 `assets` 数组登记；③ `license` 字段只能是 `CC0` 或 `self-drawn`。
缺失或非法的条目会被仓库层丢弃并打 warning，不会进入素材列表（有 `FrogManifestTest` 单测兜住）。
端侧模型清单位于 `core-ai/src/main/assets/models/README.md`。**禁止抓取未授权网络图片。**

内置的预训练权重 `pct_net_vit.tflite`（PCT-Net，图像和谐化）来自
[rakutentech/PCT-Net-Image-Harmonization](https://github.com/rakutentech/PCT-Net-Image-Harmonization)，
由其官方 `.pth` 经 `tools/convert_pctnet.py` 转换而来，许可为 **MPL-2.0**（允许商业使用）。
逐项许可与再分发义务见 [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)。

## 界面配色

主题为**暖黄（琥珀/蜂蜜）**基调，亮暗两套色板都偏黄（不跟随系统动态取色，避免壁纸把主色带成绿/蓝）：
主色 `#F2B12B`（亮）/ `#FFD479`（暗），背景 `#FFFBF2`（暖奶油）/ `#17130C`（暖褐黑）。
配色定义集中在 `app/src/main/java/com/naiwa/camera/ui/theme/Theme.kt`；
`app/src/main/res/values/themes.xml` 的 `windowBackground` 与之对齐，避免冷启动首帧闪白。

## 许可证

本项目代码以 **Apache-2.0** 发布：全文见 [LICENSE](LICENSE)，版权与第三方声明见 [NOTICE](NOTICE)，
依赖与预训练权重的逐项许可见 [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)。

不属于 Apache-2.0 的两处（**再分发前必读**）：

| 内容 | 许可证 | 影响 |
| --- | --- | --- |
| `core-ai/src/main/assets/models/pct_net_vit.tflite`（PCT-Net 预训练权重，由官方 `.pth` 转换） | **MPL-2.0** | 文件级 copyleft，**只约束该权重文件本身**，不传染其余代码；**允许商业使用**；再分发需提供其源码形式（`tools/convert_pctnet.py` + 上游权重） |
| `org.wysaid:gpuimage-plus` | **MIT** | 保留版权与许可声明 |

其余依赖（OpenCV、LiteRT / TensorFlow Lite、AndroidX、Compose、Hilt、Kotlin）均为 Apache-2.0。
