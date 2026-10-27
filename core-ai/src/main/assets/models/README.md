# 端侧模型目录（assets/models）

模型文件放这里，构建时会被合并进 APK。所有推理均在设备端完成，**不访问任何网络**。

## 必需（P1）
| 文件 | 用途 | 输入 | 输出 |
| --- | --- | --- | --- |
| `light_estimator_int8.tflite` | 场景光源估计（色温/亮度/对比度/主光方向） | 1×224×224×3 float32（或 int8，按模型签名） | 8 维回归向量，见 `LightParams` 字段顺序 |

- 要求：MobileNetV2 骨干 + 回归头，int8 全整型量化，单帧 CPU 推理 < 80 ms（中端机）。
- 运行时：Plan A 用 `org.tensorflow:tensorflow-lite:2.16.1`（Maven Central）；迁移到 LiteRT 新坐标
  `com.google.ai.edge.litert:litert` 只需改 `libs.versions.toml` 一行，`.tflite` 文件与 API 都不变。
- 加速器：`GpuDelegate` → CPU/XNNPACK(4 线程)。**NNAPI 自 Android 15 起已废弃，本项目不再使用 `NnApiDelegate`**。
- 首次启动由 `ModelAssetInstaller` 从 assets 复制到 `filesDir/models/`，之后直接 mmap；
  **注意 `build.gradle.kts` 里的 `androidResources.noCompress` 必须包含 `tflite`**，否则无法 mmap。
- **训练建议**：合成数据优先 —— 用 3D 渲染/图像重打光工具生成「已知光照」的成对样本，标签直接来自渲染参数，可避免人工标注成本；真实照片只用于最后 10% 的微调与验证。

## 可选（P2）
| 文件 | 用途 | 来源 |
| --- | --- | --- |
| `selfie_segmenter.tflite` | 通用前景分割兜底（奶蛙为玩具/手办类主体时效果一般） | MediaPipe 官方模型卡 |
| `interactive_segmentation.task` | **推荐**：交互式分割 —— 用户点一下奶蛙，直接得到该物体的掩膜 | MediaPipe Interactive Image Segmenter |

## 可选（规格 8.2：跨域融合 AI 和谐化）
| 文件 | 用途 | 来源 |
| --- | --- | --- |
| `pct_net_vit.tflite` | 图像和谐化（低分辨率预测逐像素颜色变换参数，应用到全分辨率前景；高端机可选，8s 超时回退 MKL） | PCT-Net（CVPR 2023，`rakutentech/PCT-Net-Image-Harmonization`，权重免申请直接下载） |

> **已内置**：`pct_net_vit.tflite`（float32，18.47MB，由 `tools/convert_pctnet.py` 从官方 `PCTNet_ViT.pth` 转换）。
> 转换链已对拍验证：torch vs tflite 最大绝对误差 1e-6（逐点一致）。
> I/O 契约：输入 `[1,256,256,4]`（RGB+mask，float32），输出 `[1,256,256,12]`（逐像素 12 参数仿射）。
> 后半段（bicubic 上采样 + 仿射应用）在端侧 `core-common/color/PctNetTransform.kt` 完成。
> 权重缺失时 `PctNetHarmonizer` 如实回退 MKL（纯 Kotlin 闭式解），**不崩溃**。
> 许可：**MPL-2.0**（上游 `rakutentech/PCT-Net-Image-Harmonization` 的 LICENSE 原文，**含商业使用许可**；文件级 copyleft，只约束本权重文件本身）。
> 详见仓库根目录 `THIRD-PARTY-LICENSES.md`。⚠️ 本行此前误写为"非商业"，已更正。

> ⚠️ float16 版本（9.3MB）不可用：Transformer 的 `BATCH_MATMUL` 算子不支持 float16 输入，故选用 float32。

> 交互式分割比自动分割更契合本项目：奶蛙是任意物体，模型无法预先认识「奶蛙」这一类别；
> 「点击 → 分割该物体」+ 手动涂抹修正的组合，比强行训一个奶蛙专用分割模型更稳、更省。

## 授权
仅使用 Apache-2.0 / MIT / MPL-2.0 / CC0 / 你自己训练的模型，并在提交信息中注明来源与许可证。
新增或替换模型必须同步更新仓库根目录的 `THIRD-PARTY-LICENSES.md`。**不接受无许可证、非商业限制或来源不明的权重。**
