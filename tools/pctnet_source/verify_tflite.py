#!/usr/bin/env python3
"""对拍验证：torch 低分辨率分支 vs 转换后的 tflite，输出应逐点一致（float16 允许小误差）。"""
import sys, os
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))  # tools/pctnet_source
sys.path.insert(0, HERE)                            # 加载 pct_net.py
sys.path.insert(0, os.path.dirname(HERE))           # 加载 tools/convert_pctnet.py

import torch
from pct_net import PCTNet
from convert_pctnet import LowResBranch

TOOLS = os.path.dirname(HERE)  # tools 目录
CKPT = os.path.join(TOOLS, 'weights', 'PCTNet_ViT.pth')
TFLITE = os.path.join(TOOLS, 'weights', 'pct_net_vit.tflite')

# 1) torch 参考输出
net = PCTNet(backbone_type='ViT', transform_type='linear', affine=True, dim=3)
net.load_state_dict(torch.load(CKPT, map_location='cpu', weights_only=True))
net.eval()
branch = LowResBranch(net).eval()

np.random.seed(0)
x = np.random.randn(1, 4, 256, 256).astype(np.float32)
with torch.no_grad():
    ref = branch(torch.from_numpy(x)).numpy()  # [1,12,256,256]

# 2) tflite 输出
import ai_edge_litert.interpreter as tflite
interp = tflite.Interpreter(model_path=TFLITE)
interp.allocate_tensors()
inp = interp.get_input_details()
out = interp.get_output_details()
print("=== tflite 输入 ===")
for d in inp:
    print(f"  {d['name']}: shape={d['shape']} dtype={d['dtype']}")
print("=== tflite 输出 ===")
for d in out:
    print(f"  {d['name']}: shape={d['shape']} dtype={d['dtype']}")

# 转 NHWC（tflite 输入通常是 NHWC）
x_nhwc = x.transpose(0, 2, 3, 1)  # [1,256,256,4]
interp.set_tensor(inp[0]['index'], x_nhwc)
interp.invoke()
got = interp.get_tensor(out[0]['index'])  # 可能是 [1,256,256,12] 或 [1,12,256,256]

# 转回 NCHW 对比
if got.shape[-1] == 12 and got.ndim == 4:
    got_nchw = got.transpose(0, 3, 1, 2)  # NHWC -> NCHW
else:
    got_nchw = got

ref_flat = ref.reshape(-1)
got_flat = got_nchw.reshape(-1)
diff = np.abs(ref_flat - got_flat)
print(f"\n参考范围: [{ref_flat.min():.4f}, {ref_flat.max():.4f}]")
print(f"最大绝对误差: {diff.max():.6f}")
print(f"平均绝对误差: {diff.mean():.6f}")
print(f"相对误差(归一化): {diff.mean() / (np.abs(ref_flat).mean() + 1e-6):.6f}")

# float16 量化误差容忍度：参数是 tanh 输出的 [-1,1] 范围，float16 误差 ~1e-3
if diff.max() < 5e-3:
    print("\n✅ 对拍通过：torch 与 tflite 输出一致（float16 误差可接受）")
else:
    print("\n⚠️ 对拍偏差较大，建议改用 float32 版本")
