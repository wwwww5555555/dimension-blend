# 第三方组件与许可

本项目代码以 **Apache-2.0** 发布（见 [LICENSE](LICENSE)）。下面列出的第三方组件按其自身许可证分发，**不由本项目的 Apache-2.0 授权覆盖**。

原则：只使用 **Apache-2.0 / MIT / MPL-2.0 / CC0 / 公有领域** 的依赖与权重；不引入无许可证、非商业限制或来源不明的资产。
改变这一点必须在 PR 里明说并更新本文件。

---

## 一、随 APK 分发的运行时依赖

| 组件 | 版本 | 许可证 | 用途 | 来源 |
| --- | --- | --- | --- | --- |
| OpenCV | 4.10.0 | Apache-2.0 | 传统 CV：融合、羽化、形态学 | `org.opencv:opencv`（Maven Central） |
| TensorFlow Lite / LiteRT | 2.16.1 | Apache-2.0 | 端侧推理运行时（CPU / GPU delegate） | `org.tensorflow:tensorflow-lite`, `tensorflow-lite-gpu` |
| android-gpuimage-plus | 3.2.0-min | **MIT** | `core-render` 的 DAG 滤镜节点（`GpuImagePlusNode`） | `org.wysaid:gpuimage-plus` / [wysaid/android-gpuimage-plus](https://github.com/wysaid/android-gpuimage-plus) |
| AndroidX（core-ktx / lifecycle / activity / navigation / datastore / exifinterface） | 见 `gradle/libs.versions.toml` | Apache-2.0 | 基础框架、导航、偏好存储、EXIF 读取 | Google Maven |
| Jetpack Compose（BOM 2024.10.01：ui / material3 / icons） | 1.7.x | Apache-2.0 | UI | Google Maven |
| Dagger / Hilt（含 `androidx.hilt:hilt-navigation-compose`） | 2.52 / 1.2.0 | Apache-2.0 | 依赖注入 | Google Maven / Maven Central |
| Kotlin 标准库 / kotlinx.coroutines | 2.0.21 / 1.9.0 | Apache-2.0 | 语言运行时与协程 | Maven Central |

> MediaPipe（`com.google.mediapipe:tasks-vision`，Apache-2.0）在 `libs.versions.toml` 中**已声明但未启用**：
> 它的交互式分割 AAR 会把 `INTERNET` 权限合并进 manifest，与本应用"系统层面无联网能力"的硬约束冲突。
> 未启用的依赖不会进入 APK，不构成本项目的分发内容。

## 二、仅用于构建与测试（不进入 APK）

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| JUnit 4 | EPL-1.0 | 单元测试框架 |
| org.json:json | Public Domain | 单测里替代 Android 的 `org.json` 桩 |
| AndroidX Test / Espresso / Compose UI Test | Apache-2.0 | 仪器化测试 |
| Gradle / Android Gradle Plugin / Kotlin 编译器 | Apache-2.0 | 构建工具链 |

## 三、预训练模型权重（重要）

### `core-ai/src/main/assets/models/pct_net_vit.tflite`

| 项 | 内容 |
| --- | --- |
| 用途 | 跨域图像和谐化（预测逐像素仿射参数），高端机可选，8s 超时回退纯 Kotlin 的 `MklHarmonizer` |
| 上游 | [rakutentech/PCT-Net-Image-Harmonization](https://github.com/rakutentech/PCT-Net-Image-Harmonization)（PCT-Net，CVPR 2023） |
| 许可证 | **Mozilla Public License 2.0（MPL-2.0）** |
| 本仓库内的形态 | 由官方 `PCTNet_ViT.pth` **转换**而来（低分辨率分支 → TFLite，float32，18.47MB） |
| 转换工具 | [`tools/convert_pctnet.py`](tools/convert_pctnet.py)（含对拍验证：torch vs tflite 最大绝对误差 1e-6） |

**MPL-2.0 义务已满足的方式**：
① 许可证随仓库提供（MPL-2.0 全文）；② 该文件是"修改形式"（格式转换），**修改的源码形式即为本仓库内的转换脚本与模型定义**，
按 MPL-2.0 §3.1 随二进制一同提供；③ 未对该文件附加任何额外限制。

> ⚠️ **更正**：`core-ai/src/main/assets/models/README.md` 曾写"许可：非商业"。经核对上游 `LICENSE` 原文，
> PCT-Net 采用 **MPL-2.0**（OSI 认可的开源许可证，**含商业使用许可**），不是非商业许可。已修正。
> MPL-2.0 是**文件级** copyleft：它只约束 `pct_net_vit.tflite` 及其修改，不会传染本项目的其他代码。

### 素材

本项目**不内置任何图片素材**（`core-data/src/main/assets/frogs/manifest.json` 的 `assets` 为空数组）。
若将来内置，`license` 字段只允许 `CC0` / `self-drawn`，由 `FrogManifestTest` 兜底拦截。**禁止抓取未授权网络图片。**

## 四、如果要再分发

- 直接分发本项目的 APK / AAB：遵守上述全部许可证，附带本文件与 [LICENSE](LICENSE)、[NOTICE](NOTICE)。
- 修改 `pct_net_vit.tflite` 或以其他格式重新打包它：该文件仍受 MPL-2.0 约束，需提供其源码形式（转换脚本 + 上游权重）。
- 只复用本项目**代码**：按 Apache-2.0，保留版权与 NOTICE 声明即可，无需开源你的衍生作品。
