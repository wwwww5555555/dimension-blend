#!/usr/bin/env python3
"""
PCT-Net 权重转换脚本（路线 B：ai-edge-torch 直转，兜底 onnx2tf）。

把官方 PCTNet_ViT.pth 的低分辨率分支转成端侧 .tflite：
  输入 [1,256,256,4]（RGB + mask 拼接）→ 输出 [1,256,256,12]（逐像素仿射参数）。
后半段（bicubic 上采样 + 仿射应用）在端侧 core-common/color/PctNetTransform.kt 完成。

用法（在项目根目录）：
    tools/.convert-venv/Scripts/python.exe tools/convert_pctnet.py \
        --ckpt /path/to/PCTNet_ViT.pth \
        --out core-ai/src/main/assets/models/pct_net_vit.tflite

依赖（仅转换时用，不进入 APK）：
    pip install torch torchvision einops ai-edge-torch
"""
import argparse
import os
import sys

import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, 'pctnet_source'))
from pct_net import PCTNet


class LowResBranch(nn.Module):
    """PCT-Net 的低分辨率分支：4 通道输入（RGB+mask 已拼接）→ params[1,12,256,256]。

    输入直接是 [1,4,256,256]（与端侧 PctNetHarmonizer 的单张量输入一致，
    避免端侧/转换侧对"cat 还是双输入"的歧义）。
    """

    def __init__(self, net: PCTNet):
        super().__init__()
        self.encoder = net.encoder
        self.get_params = net.get_params

    def forward(self, x):
        # x: [1,4,256,256]（RGB 3 通道 + mask 1 通道）
        latent = self.encoder(x, None)               # [1,32,256,256]
        return self.get_params(latent)               # [1,12,256,256]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ckpt', required=True)
    ap.add_argument('--out', default='pct_net_vit.tflite')
    ap.add_argument('--fallback-onnx2tf', action='store_true',
                    help='ai-edge-torch 失败时兜底 onnx2tf')
    args = ap.parse_args()

    net = PCTNet(backbone_type='ViT', transform_type='linear', affine=True, dim=3)
    state = torch.load(args.ckpt, map_location='cpu', weights_only=True)
    net.load_state_dict(state)
    net.eval()
    branch = LowResBranch(net).eval()

    # 校验输入输出形状：单输入 4 通道
    x = torch.randn(1, 4, 256, 256)
    with torch.no_grad():
        out = branch(x)
    assert out.shape == (1, 12, 256, 256), f"输出形状异常：{out.shape}"
    print(f"[验证] 低分辨率分支输出 {tuple(out.shape)} ✓")

    # 导出 ONNX（NCHW），onnx2tf 会转成 TFLite 的 NHWC
    onnx_path = args.out.replace('.tflite', '.onnx')
    torch.onnx.export(
        branch, (x,), onnx_path,
        input_names=['input'], output_names=['params'],
        opset_version=17, do_constant_folding=True,
    )
    print(f"[1/2] ONNX 已导出：{onnx_path}")

    # 路线：onnx2tf 转 tflite
    try:
        import onnx2tf
        onnx2tf.convert(
            input_onnx_file_path=onnx_path,
            output_folder_path=os.path.dirname(os.path.abspath(args.out)) or '.',
            copy_onnx_input_output_names_to_tflite=True,
            non_verbose=True,
        )
        produced = os.path.join(os.path.dirname(os.path.abspath(args.out)), 'params_float32.tflite')
        if os.path.exists(produced):
            os.replace(produced, args.out)
            print(f"[成功] onnx2tf 转换完成：{args.out}")
        else:
            # onnx2tf 可能产出别的名字
            import glob
            candidates = glob.glob(os.path.join(os.path.dirname(os.path.abspath(args.out)), '*.tflite'))
            print(f"[提示] 产出文件：{candidates}")
            for c in candidates:
                if c != args.out and 'params' in c:
                    os.replace(c, args.out)
                    print(f"[成功] 重命名 {c} → {args.out}")
    except Exception as e:
        print(f"[onnx2tf 失败] {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
