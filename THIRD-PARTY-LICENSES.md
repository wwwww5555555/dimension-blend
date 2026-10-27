# 第三方组件与许可

本项目**代码**以 **Apache-2.0** 发布（见根目录 [LICENSE](LICENSE)）。
下面列出的第三方组件按其自身许可证分发，**不由本项目的 Apache-2.0 授权覆盖**。
各许可证全文放在 [`LICENSES/`](LICENSES/) 目录：

| 文件 | 许可证 |
| --- | --- |
| [`LICENSES/MPL-2.0.txt`](LICENSES/MPL-2.0.txt) | Mozilla Public License 2.0（PCT-Net 权重与其衍生的模型定义源码） |
| [`LICENSES/MIT-gpuimage-plus.txt`](LICENSES/MIT-gpuimage-plus.txt) | MIT（android-gpuimage-plus，Copyright (c) 2017-2026 Wang Yang） |
| [`LICENSE`](LICENSE) | Apache-2.0（本项目自身代码） |

原则：只使用 **Apache-2.0 / MIT / MPL-2.0 / CC0 / 公有领域** 的依赖与资产；不引入无许可证、非商业限制或来源不明的素材。
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

> MediaPipe（`com.google.mediapipe:tasks-vision`，Apache-2.0）在 `libs.versions.toml` 中**已声明但未被任何模块引用**：
> 它的交互式分割 AAR 会把 `INTERNET` 权限合并进 manifest，与本应用"系统层面无联网能力"的硬约束冲突。
> 未引用的依赖不会进入 APK，不构成分发内容。

## 二、仅用于构建与测试（不进入 APK）

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| JUnit 4 | EPL-1.0 | 单元测试框架 |
| org.json:json | Public Domain | 单测里替代 Android 的 `org.json` 桩 |
| AndroidX Test / Espresso / Compose UI Test | Apache-2.0 | 仪器化测试 |
| Gradle / Android Gradle Plugin / Kotlin 编译器 | Apache-2.0 | 构建工具链 |

## 三、PCT-Net 权重与模型定义源码（MPL-2.0）

PCT-Net 是本项目中**唯一**受 MPL-2.0 约束的部分，包含两个文件/目录：

| 内容 | 说明 |
| --- | --- |
| `core-ai/src/main/assets/models/pct_net_vit.tflite` | 预训练权重（float32，18.47MB），由官方 `PCTNet_ViT.pth` 转换而来，进入 APK |
| `tools/pctnet_source/{pct_net.py, functions.py}` | 上游模型定义的**改写合并版**，仅转换权重时导入，**不进入 APK** |

| 项 | 内容 |
| --- | --- |
| 上游 | [rakutentech/PCT-Net-Image-Harmonization](https://github.com/rakutentech/PCT-Net-Image-Harmonization)（PCT-Net，CVPR 2023） |
| 许可证 | **Mozilla Public License 2.0**，全文见 [`LICENSES/MPL-2.0.txt`](LICENSES/MPL-2.0.txt) |
| 转换工具 | [`tools/convert_pctnet.py`](tools/convert_pctnet.py)（含对拍验证：torch vs tflite 最大绝对误差 1e-6） |

**MPL-2.0 义务的履行方式**：

1. **许可证全文随仓库提供** —— [`LICENSES/MPL-2.0.txt`](LICENSES/MPL-2.0.txt)（本节此前只写了许可证名称、没有附全文，已修正）。
2. **每个受约束文件都有源码级标识** —— `tools/pctnet_source/*.py` 顶部带 `SPDX-License-Identifier: MPL-2.0` 与修改说明。
3. **修改以源码形式提供** —— 本仓库内的模型定义源码 + 转换脚本即为"修改的源码形式"（MPL-2.0 §3.1）。
4. **不附加额外限制** —— 未对上述文件增加任何使用限制。

> ⚠️ **两处此前的错误，已更正**：
> ① `core-ai/src/main/assets/models/README.md` 曾写"许可：非商业"。核对上游 `LICENSE` 原文为 **MPL-2.0**
> （OSI 认可的开源许可证，**含商业使用许可**），不是非商业许可。
> ② 曾把 MPL 的适用范围写成"只约束该权重文件"。实际还约束 `tools/pctnet_source/*.py`（上游模型定义的衍生物）。

**MPL-2.0 是文件级 copyleft**：它只覆盖上述两处文件及其后续修改，**不会传染**本项目其余 Apache-2.0 代码；
衍生的更大作品仍可按 Apache-2.0 分发。替换/新增模型必须同步更新本文件与 `LICENSES/`。

## 四、素材

本项目**不内置任何图片素材**（`core-data/src/main/assets/frogs/manifest.json` 的 `assets` 为空数组）。
若将来内置，`license` 字段只允许 `CC0` / `self-drawn`，由 `FrogManifestTest` 兜底拦截。**禁止抓取未授权网络图片。**

## 五、如果要再分发

- 直接分发本项目的 APK / AAB：遵守上述全部许可证，附带本文件、[`LICENSE`](LICENSE)、[`NOTICE`](NOTICE) 与 [`LICENSES/`](LICENSES/)。
- 修改 `pct_net_vit.tflite` 或 `tools/pctnet_source/*.py`：这两个对象仍受 MPL-2.0 约束，需提供其源码形式。
- 只复用本项目**其余代码**：按 Apache-2.0，保留版权与 NOTICE 声明即可，无需开源你的衍生作品。
