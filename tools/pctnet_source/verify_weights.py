#!/usr/bin/env python3
"""验证 PCTNet_ViT.pth 权重能正确加载，并确认低分辨率分支的输入输出形状。"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import torch

from pct_net import PCTNet

CKPT = sys.argv[1] if len(sys.argv) > 1 else "PCTNet_ViT.pth"

model = PCTNet(backbone_type='ViT', transform_type='linear', affine=True, dim=3)
state = torch.load(CKPT, map_location='cpu', weights_only=True)

# 检查键名是否完全匹配
model_keys = set(model.state_dict().keys())
state_keys = set(state.keys())
missing = model_keys - state_keys
unexpected = state_keys - model_keys
print(f"模型层数={len(model_keys)}  权重层数={len(state_keys)}")
print(f"缺失(missing)={len(missing)}  多余(unexpected)={len(unexpected)}")
if missing:
    print("  missing 示例:", list(missing)[:5])
if unexpected:
    print("  unexpected 示例:", list(unexpected)[:5])

model.load_state_dict(state)
model.eval()
print("load_state_dict 成功 ✓")

# 确认 get_params 输出通道 = out_dim
print(f"out_dim = {model.out_dim}")
print(f"get_params.weight 形状 = {tuple(model.get_params.weight.shape)}")

# 跑一次低分辨率分支，确认输出形状 [1,12,256,256]
img_lr = torch.randn(1, 3, 256, 256)
mask_lr = torch.randn(1, 1, 256, 256)
with torch.no_grad():
    x = torch.cat((img_lr, mask_lr), dim=1)             # [1,4,256,256]
    intermediates = model.encoder(x, None)               # [1,32,256,256]
    latent, _ = model.decoder(intermediates, img_lr, mask_lr)
    params = model.get_params(latent)                    # [1,12,256,256]
print(f"低分辨率分支输出 params 形状 = {tuple(params.shape)}")
assert params.shape == (1, 12, 256, 256), "输出形状不符合预期 [1,12,256,256]"
print("形状验证通过 ✓")
