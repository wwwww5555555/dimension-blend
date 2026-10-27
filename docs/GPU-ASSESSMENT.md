# GPU 渲染引入评估（2026-10-17）

回应的问题：当前 GPU 调用情况如何？是否存在空闲？要不要引入 GPU 渲染管线？

## 一、当前 GPU 调用情况（基于代码事实）

| 环节 | 现状 | 是否用 GPU |
| --- | --- | --- |
| AI 推理（PCT-Net / 光照模型） | `RuntimeProvider` 探测链 QNN→NPU→GPU(OpenCL)→CPU(XNNPACK 4 线程)，自动命中 | ✅ 高端机走 NPU/GPU |
| 相机预览 | CameraX `PreviewView`（COMPATIBLE 模式，TextureView） | ✅ 系统合成走 GPU |
| UI 渲染 | Compose + HWUI | ✅ |
| **像素管线（ColorTransfer / 阴影 / Glow / Wrap / 拉普拉斯 / boxBlur / 高频重注入）** | 纯 Kotlin `IntArray`/`FloatArray` 循环 | ❌ **全 CPU，GPU 空闲** |
| **泊松融合** | OpenCV `seamlessClone`（CPU 实现） | ❌ CPU |
| DRAG 直贴 | `SimpleBlender`：`Canvas(bitmap)` 是**软件光栅化**（CPU 画），仅最终显示由 HWUI 上传纹理 | ❌ 生成在 CPU |

**结论：GPU 空闲是真实的**——耗时大头（像素管线 + 泊松）全在 CPU；GPU 只承担了推理（部分设备）和 UI 合成。但"空闲"不等于"该用"，见第三节。

## 二、引入 GPU 的预期收益（逐项拆解）

一键融图预算 5s（1080p），各步骤 GPU 化的现实收益：

| 步骤 | CPU 现状（估） | GPU 化收益 | 可行性 |
| --- | --- | --- | --- |
| 泊松求解（最大头） | 1.5~3.0s | **几乎不可行**：OpenCV Android 无 CUDA 模块；自研 GPU 泊松（多尺度/PCG）成本极高 | ✗ |
| PCT-Net 推理 | 已走 NPU/GPU delegate | 已覆盖 | — |
| Lab 色彩协调 + 色温 + rim + glow + wrap | 合计约 0.5~1.5s | 合并成**单 pass fragment shader** 可压到 ~30ms | ✓ 但有前提* |
| 3D LUT 采样 | 几十 ms（`GlesLutSampler` 已论证"不是当前瓶颈"） | 小 | △ |
| 阴影 / boxBlur | 数百 ms | 中 | ✓ |
| Bitmap↔纹理往返 | — | 1080p 一次上传+回读约 20~50ms | ⚠ 反向开销 |

\* **前提是整段合并**：若管线被切成"CPU 步骤与 GPU 步骤交替"，每步都要上传/回读纹理，往返开销会吃掉全部收益。只有把连续的逐像素步骤融合进一个 shader（数据不落地）才划算——这是一个完整的 GLSL 重写工程，不是"加个开关"。

## 三、结论与建议

**不建议现在引入 GLES 渲染管线。理由按权重排序：**

1. **瓶颈不在可 GPU 化的部分**。一键融图 5s 预算里，泊松求解（GPU 化几乎不可行）和 AI 推理（已有 NPU/GPU）占大头；可 GPU 化的逐像素步骤合计约 1s，全部优化完也只省其中大半——用户体感从 5s 到 4s，收益/成本比差。
2. **交互模式已变**。实时预览已改为"拖动纯贴图 + 显式一键融图"，用户对单步耗时的敏感度大幅下降，不再需要"每帧 30ms"的 GPU 级性能。
3. **验证成本是硬约束**。EGL 上下文、纹理格式、各家 GPU 驱动精度差异——本项目至今没有真机/模拟器，交付一段没跑过的 GPU 代码违反项目纪律（ARCHITECTURE-CHANGES 已记录过同样结论）。
4. **兼容性风险**。`seamlessClone` 无 GPU 替代；shader 精度（mediump vs highp）在部分 GPU 上会产生色带；float16 纹理支持参差（PCT-Net float16 的 BATCH_MATMUL 教训仍在）。

**同时明确两件已经在做对的事（保持）：**
- 推理侧的 QNN→NPU→GPU delegate 链就是对 GPU/NPU 的正确利用，PCT-Net 已覆盖。
- UI/预览的 GPU 合成由系统承担，无优化空间也无必要。

## 四、如果未来要引入（触发条件 + 路径）

**触发条件（数据驱动，先测再动）**：真机跑一次一键融图，看 `stageTimings`——只有当"可 GPU 化步骤合计 > 1.5s 且成为用户抱怨的瓶颈"时才启动。

**推荐路径（按成本升序）**：
1. **AGSL（Android 13+，API 33+）`RuntimeShader`**：零 EGL 成本、Compose 原生集成、无需手写 GL 胶水。minSdk 26 → 做成"33+ 走 AGSL / 以下走 CPU"双轨。这是当前 Android 生态里性价比最高的 GPU 方案。
2. 整段合并 shader：Lab 协调 + 色温 + rim + glow + wrap 单 pass，CPU 结果做 A/B 对照（误差 <1/255 才启用）。
3. 不做：泊松 GPU 化、分帧流式渲染。

**验证前置**：无论哪条路径，必须真机验证正确性后才能替换 CPU 路径；CPU 路径永久保留为兜底。
