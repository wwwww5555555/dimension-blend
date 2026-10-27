#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「人物边缘半透明 / 快融化」的离线复现与验收。

复刻 `FRAG_COMPOSITE` 里决定"背景能不能透出来"的两项（公式与 GLSL 一致）：

  ① blend   —— 方向性羽化：`smoothstep(0.5-w, 0.5+w, maskAlpha)`
  ② bleed   —— 大气透视：把背景以 `uBleedStrength` Screen 进人物**内侧**

关键发现：`innerProx` 的探针半径是 bleedPx（背光侧 20px），在那个半径内它恒为 1，
于是背景被灌进人物内侧整整 20px —— 而背光侧没有光包裹去盖住它，看起来就是"融化"。
羽化只影响 alpha 过渡带那 1~2px，不是主因。

输出：`docs/alpha-lock-sim.png` 三联图 + 数值。
用法：python tools/sim_alpha_lock.py
"""

import os

import numpy as np
from PIL import Image, ImageDraw

W, H = 240, 200
EDGE_Y = 132          # 人物下边缘（y 越大越靠下）
RAMP = 4              # 抠图自带的抗锯齿斜坡宽度（px）
BLEED_STRENGTH = 0.35  # DefaultImagePipeline: bleedStrength
BLEED_PX_MIN, BLEED_PX_MAX = 10.0, 20.0
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "alpha-lock-sim.png")


def smoothstep(e0, e1, x):
    """支持标量/数组混合的边界（width 逐像素变化）。"""
    e0 = np.asarray(e0, dtype=np.float64)
    e1 = np.asarray(e1, dtype=np.float64)
    denom = np.where(e1 - e0 == 0.0, 1.0, e1 - e0)
    t = np.clip((np.asarray(x, dtype=np.float64) - e0) / denom, 0.0, 1.0)
    out = t * t * (3 - 2 * t)
    return np.where(e1 - e0 == 0.0, 0.0, out)


def make_mask():
    """上半实心、下边缘带 4px 抗锯齿斜坡的人物 mask。"""
    yy = np.mgrid[0:H, 0:W][0].astype(np.float64)
    # y < EDGE_Y-RAMP → 1；y > EDGE_Y → 0；中间线性斜坡
    a = np.clip((EDGE_Y - yy) / RAMP, 0.0, 1.0)
    a = smoothstep(0.0, 1.0, a)          # 斜坡本身也过一遍 smoothstep，接近真实抠图
    return a


def compose_terms(a, lit, fix):
    """返回 (blend, bleedW)。fix=True 时施加 v0.36 的两道保护。"""
    yy = np.mgrid[0:H, 0:W][0].astype(np.float64)
    ynorm = yy / H                        # uv.y：1 = 画面底部

    bottom_solid = smoothstep(0.86, 0.96, ynorm) if fix else 0.0
    width = (0.07 + (0.18 - 0.07) * (1.0 - lit)) * (1.0 - 0.75 * bottom_solid)
    blend = smoothstep(0.5 - width, 0.5 + width, a)
    if fix:
        blend = np.maximum(blend, smoothstep(0.90, 0.97, a))

    # 8 方向 alpha 探针 → 到轮廓的"内部距离"
    bleed_px = BLEED_PX_MAX + (BLEED_PX_MIN - BLEED_PX_MAX) * lit
    min_a = np.ones((H, W))
    for i in range(8):
        ang = i * 0.7853982
        dx = int(round(np.cos(ang) * bleed_px))
        dy = int(round(np.sin(ang) * bleed_px))
        shifted = np.roll(np.roll(a, -dy, axis=0), -dx, axis=1)
        min_a = np.minimum(min_a, shifted)
    inner_prox = 1.0 - min_a
    inside = smoothstep(0.55, 0.95, a)
    bleed_w = inner_prox * inner_prox * inside
    if fix:
        edge_only = 1.0 - smoothstep(0.90, 0.99, a)
        bleed_w = bleed_w * edge_only * (1.0 - bottom_solid)
    return blend, bleed_w


def purity(blend, bleed_w):
    """人物纯度：1 = 完全是人物像素，0 = 完全被背景占据。"""
    return blend * (1.0 - bleed_w * BLEED_STRENGTH)


def to_img(g):
    return Image.fromarray((np.clip(g, 0, 1) * 255).astype(np.uint8))


def main():
    a = make_mask()
    lit_backlit = 0.0          # 背光侧（问题最明显的一侧）
    old_blend, old_bleed = compose_terms(a, lit_backlit, fix=False)
    new_blend, new_bleed = compose_terms(a, lit_backlit, fix=True)

    p_old = purity(old_blend, old_bleed)
    p_new = purity(new_blend, new_bleed)

    # 统计列：人物中轴，从边缘往里 0~24px
    x = W // 2
    print("背光侧 · 从下边缘往里逐像素（距边 px / 旧纯度 / 新纯度）")
    for d in range(0, 25, 2):
        y = EDGE_Y - d
        print("  %2d   %.3f   %.3f" % (d, p_old[y, x], p_new[y, x]))

    band_old = float(np.mean(p_old[EDGE_Y - 20:EDGE_Y - 2, x]))
    band_new = float(np.mean(p_new[EDGE_Y - 20:EDGE_Y - 2, x]))
    print("\n内侧 2~20px 平均纯度：旧 %.3f → 新 %.3f" % (band_old, band_new))

    core = a >= 0.99
    print("实心区（mask≥0.99）最低纯度：旧 %.3f → 新 %.3f"
          % (float(p_old[core].min()), float(p_new[core].min())))

    assert band_new > band_old + 0.2, "新写法必须显著提高内侧纯度"
    assert float(p_new[core].min()) > 0.999, "实心区必须完全不透明（纯度=1）"

    # ---- 三联图：mask / 旧纯度 / 新纯度 ----
    panels = [
        ("mask alpha", a),
        ("v0.30 purity (backlit) %.2f" % band_old, p_old),
        ("v0.36 purity (backlit) %.2f" % band_new, p_new),
    ]
    imgs = []
    for title, g in panels:
        im = to_img(g).convert("RGB")
        d = ImageDraw.Draw(im)
        d.rectangle([0, 0, im.width, 20], fill=(18, 18, 20))
        d.text((6, 5), title, fill=(235, 235, 235))
        imgs.append(im)

    pad = 8
    canvas = Image.new("RGB", (W * 3 + pad * 4, H + pad * 2), (10, 10, 12))
    for i, im in enumerate(imgs):
        canvas.paste(im, (pad + i * (W + pad), pad))
    canvas.save(OUT)
    print("\nwrote", os.path.relpath(OUT))


if __name__ == "__main__":
    main()
