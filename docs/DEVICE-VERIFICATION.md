# 真机验证指南（规格 8 跨域融合 + NPU 探测）

本指南对应 `core-common/.../debug/DebugConfig.kt` 预留的验证入口。**当前阶段无真机**，
以下步骤已全部在代码里预留好开关与日志，拿到真机后无需改代码即可逐项验证。

## 一、权重文件接入（PCT-Net）

PCT-Net 官方权重（`rakutentech/PCT-Net-Image-Harmonization`）是 **PyTorch `.pth` 格式**，不能直接端侧部署。
需先转 LiteRT。**转换脚本已就绪**（`tools/convert_pctnet.py`），权重下载后一键转换。

### 转换流程（PyTorch → LiteRT）
```bash
# 1. 克隆仓库并下载权重（免申请，直接下载）
git clone https://github.com/rakutentech/PCT-Net-Image-Harmonization.git
# 权重文件：pretrained_models/PCTNet_ViT.pth（libcom 分发版为 PCTNet.pth）

# 2. 安装转换依赖（仅转换时用，不进入 APK）
pip install torch torchvision onnx onnx2tf

# 3. 一键转换（脚本内完成 PyTorch → ONNX → TFLite）
python tools/convert_pctnet.py --ckpt pretrained_models/PCTNet.pth --out pct_net_vit.tflite
```
> 产物契约：输入 `[1,256,256,4]`（RGB + mask 拼接，256×256 低分辨率），
> 输出 `[1,256,256,12]`（逐像素 12 参数仿射：3×3 + 3 平移）。
> 后半段（bicubic 上采样 + 仿射应用 + `out*mask+image*(1-mask)`）在端侧 `PctNetTransform.kt` 完成，
> 因此 TFLite 图不含动态 H/W 的 interpolate，转换简单。

### 权重接入（转换完成后）
```bash
# 方式 A：外部推送（推荐，免重新打包）
adb push pct_net_vit.tflite \
  /sdcard/Android/data/com.naiwa.camera/files/models/pct_net_vit.tflite

# 方式 B：随包分发（放 core-ai/src/main/assets/models/ 重新打包）
```

## 二、调试开关（`DebugConfig`）

| 开关 | 默认 | 用途 |
| --- | --- | --- |
| `diagnosticLogging` | false | 打开后管线打印「生效路径：LUT=… 和谐化=…」 |
| `forcePctNet` | false | **绕过高端机门槛**，中端机也能测 PCT-Net 的 NPU 推理 + 超时回退 |

## 三、验证清单

### 8.1 3D LUT
- 二次元奶蛙 + 写实暖光室内背景 → 奶蛙偏暖（`warm_indoor` 命中）
- 冷光夜景 → 偏冷蓝（`cool_night` 命中）
- 逆光 + 强主光 → `backlit` 命中，边缘光朝光源
- 判定：`adb logcat -s NaiwaCamera | grep -E "LUT|光效"`

### 8.2 MKL 和谐化
- 前景/背景色调差异大时，MKL 让奶蛙色度向背景靠拢
- 判定：肉眼对比"开/关 8.2"的色温融合度；`adb logcat | grep MKL`

### 8.2 PCT-Net（高端机 / forcePctNet=true）
- 转换后权重到位 → 日志「PCT-Net 和谐化（QNN/NPU/GPU）」
- 权重缺失 → 日志「回退 MKL」，不崩溃
- 推理超 8s → 自动回退 MKL

### 8.3 NPU 探测链
- `adb logcat -s NaiwaCamera | grep "推理运行时"` → 确认命中 QNN/NPU/GPU/CPU 哪一级
- 高通机型应命中 QNN；其他依次降级，最终 CPU+XNNPACK 兜底

## 四、已排除的模型

- **FLUX.2-klein-4B**：需约 13GB VRAM，不满足端侧内存约束，**不使用**。
- **DreamLite**：权重需邮件申请（gated）+ 依赖 Qwen3-VL 文本编码器，流程繁琐，已换 PCT-Net。

## 五、CPU 数值正确性（已在单测钉住）

- `CubeLut3dTest`：.cube 解析 + 三线性采样 + 恒等 LUT 不变性
- `MklHarmonizerTest`：相同分布恒等 / 均值平移 / 方差缩放 / 退化对角缩放 / 像素范围
- `LutChoiceTest`：色温分档 + 过渡带插值 + 逆光优先
- `PctNetTransformTest`：恒等参数不变 / 平移 / 缩放 / 背景与透明像素不受影响 / clamp / 非法输入（7 用例，纯函数）

## 六、GLES 3D LUT（后续优化，未启用）

当前用 CPU 三线性采样（正确性已测）。GLES 方案见 `core-cv/.../color/GlesLutSampler.kt` 的 KDoc：
3D 纹理 `texture3D()` + `GL_LINEAR`，输出与 CPU 对拍逐位一致才启用。
