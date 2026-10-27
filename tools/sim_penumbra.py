#!/usr/bin/env python3
"""
明暗交界过渡带（Penumbra）的离线对照（**只用于验证，不是产品代码**）。

对比两种法向基线的 litRatio 场：
  OLD = 1 texel 差分 + smoothstep(0, 0.35)  → 过渡带约 2~3px（"刀切"）
  NEW = 8px 宽基线 + smoothstep(-0.2, 0.3)  → 过渡带约 10~14px

输出 docs/penumbra-sim.png：上行全图、左下局部 3× 放大、右下 litRatio 剖面曲线。
运行：python tools/sim_penumbra.py
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
from sim_rim_glow import W, H, bilinear, grid, make_person, smoothstep  # noqa: E402

LIGHT_POS = (0.74, 0.16)
CROP = (int(0.30 * W), int(0.10 * H), int(0.72 * W), int(0.52 * H))
ZOOM = 3


def normal_at(a, baseline_px):
    """外法向（像素空间）。baseline_px = 1 是旧写法，8 是宽基线。"""
    X, Y = grid(W, H)
    b = max(baseline_px, 1.0)
    gx = bilinear(a, X + b, Y) - bilinear(a, X - b, Y)
    gy = bilinear(a, X, Y + b) - bilinear(a, X, Y - b)
    glen = np.hypot(gx, gy)
    safe = np.maximum(glen, 1e-6)
    nx = np.where(glen > 1e-6, -gx / safe, 0.0)
    ny = np.where(glen > 1e-6, -gy / safe, 0.0)
    return nx, ny


def light_dir():
    X, Y = grid(W, H)
    dx = LIGHT_POS[0] * W - X
    dy = LIGHT_POS[1] * H - Y
    ln = np.maximum(np.hypot(dx, dy), 1e-6)
    return dx / ln, dy / ln


def low_alpha(a, radius_px):
    """模拟 1/4 域低频 alpha：用大半径近似模糊（等价于 shader 里的 FRAG_ALPHA_LOW）。"""
    X, Y = grid(W, H)
    acc = a.copy()
    n = 1
    for ang_i in range(8):
        ang = ang_i * math.pi / 4
        for k in (0.5, 1.0):
            acc = acc + bilinear(a, X + math.cos(ang) * radius_px * k,
                                 Y + math.sin(ang) * radius_px * k)
            n += 1
    return acc / n


def lit_ratio(a, band_px, lo, hi, wide):
    if wide:
        # 宽基线：先模糊 alpha，再做差分
        a_use = low_alpha(a, band_px * 0.9)
        nx, ny = normal_at(a_use, band_px)
    else:
        nx, ny = normal_at(a, 1.0)
    lx, ly = light_dir()
    ndl = nx * lx + ny * ly
    r = smoothstep(lo, hi, ndl)
    if wide:
        # 轮廓带：宽基线梯度幅度（内部为 0 → 强制背光）
        X, Y = grid(W, H)
        a_use = low_alpha(a, band_px * 0.9)
        gx = bilinear(a_use, X + band_px, Y) - bilinear(a_use, X - band_px, Y)
        gy = bilinear(a_use, X, Y + band_px) - bilinear(a_use, X, Y - band_px)
        amp = smoothstep(0.02, 0.30, np.hypot(gx, gy))
    else:
        X, Y = grid(W, H)
        gx = bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y)
        gy = bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1)
        amp = smoothstep(0.0, 0.30, np.hypot(gx, gy))
    return r * amp


def render(a, lr, ambient):
    """简化 P2：迎光侧亮、背光侧沉入环境色，交界处用钟形桥梁叠环境色。"""
    base = np.array([0.62, 0.58, 0.52], np.float32)      # 迎光侧
    dark = np.array([0.05, 0.06, 0.10], np.float32)      # 环境暗部
    c = dark[None, None, :] + (base - dark)[None, None, :] * lr[..., None]
    bridge = 4.0 * lr * (1.0 - lr)
    c = c * (1 - (bridge * 0.20)[..., None]) + ambient[None, None, :] * (bridge * 0.20)[..., None]
    return np.clip(c, 0, 1)


def crop_zoom(img):
    x0, y0, x1, y1 = CROP
    sub = (np.clip(img[y0:y1, x0:x1], 0, 1) * 255).astype(np.uint8)
    im = Image.fromarray(sub)
    return im.resize((im.width * ZOOM, im.height * ZOOM), Image.NEAREST)


def profile_chart(lr_old, lr_new):
    """沿一条穿过暗/亮交界的横线画 litRatio 曲线，直接看出过渡带宽度。"""
    y = int(0.28 * H)
    xs = np.arange(W)
    h = H // 2
    im = Image.new("RGB", (W, h), (24, 24, 28))
    d = ImageDraw.Draw(im)
    for v in (0.25, 0.5, 0.75):
        d.line([(0, int(h * (1 - v))), (W, int(h * (1 - v)))], fill=(50, 50, 58))
    for series, color in ((lr_old[y], (235, 120, 120)), (lr_new[y], (120, 220, 140))):
        pts = [(int(xs[i]), int(h * (1 - float(series[i])))) for i in range(0, W, 2)]
        d.line(pts, fill=color, width=2)
    d.text((10, 8), "litRatio profile @ y=0.28  RED=old(1texel)  GREEN=new(wide)", fill=(230, 230, 230))
    return im


def main():
    a = make_person()
    ambient = np.array([0.22, 0.26, 0.38], np.float32)

    lr_old = lit_ratio(a, 8.0, 0.0, 0.35, wide=False)
    lr_new = lit_ratio(a, 8.0, -0.20, 0.30, wide=True)

    old_img = render(a, lr_old, ambient)
    new_img = render(a, lr_new, ambient)

    top_old = Image.fromarray((old_img * 255).astype(np.uint8)).resize((W, H))
    top_new = Image.fromarray((new_img * 255).astype(np.uint8)).resize((W, H))
    z_old = crop_zoom(old_img)
    z_new = crop_zoom(new_img)
    z_old = z_old.resize((W, H))
    z_new = z_new.resize((W, H))

    sheet = Image.new("RGB", (2 * W, 2 * H), (16, 16, 16))
    sheet.paste(top_old, (0, 0))
    sheet.paste(top_new, (W, 0))
    sheet.paste(z_old, (0, H))
    sheet.paste(z_new, (W, H))
    d = ImageDraw.Draw(sheet)
    d.rectangle([0, 0, 2 * W, 22], fill=(0, 0, 0))
    d.text((8, 6), "OLD: 1-texel normal + smoothstep(0,0.35)   |   NEW: 8px wide baseline + smoothstep(-0.2,0.3) + ambient bridge",
           fill=(255, 255, 255))
    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs", "penumbra-sim.png"))
    sheet.save(out)
    print("saved", out)

    # 定量：**最长连续段**的宽度（litRatio 在 0.1~0.9 之间跨越多少像素）。
    # 不能用 max-min —— 一行里有多处交界（左右两侧），那样统计的是整体跨度。
    def run_width(series):
        row = series[int(0.28 * H)]
        mask = (row > 0.1) & (row < 0.9)
        best = cur = 0
        for v in mask:
            cur = cur + 1 if v else 0
            best = max(best, cur)
        return best
    print("过渡带宽度（最长连续段，px）：旧", run_width(lr_old), " 新", run_width(lr_new))

    # 密集采样交界处（每 2px），直接看斜率
    y = int(0.28 * H)
    print("交界处 litRatio（每 2px，x=318..340）：")
    for tag, series in (("旧", lr_old), ("新", lr_new)):
        vals = [round(float(series[y, x]), 2) for x in range(318, 341, 2)]
        print(f"  {tag}:", vals)


if __name__ == "__main__":
    main()
