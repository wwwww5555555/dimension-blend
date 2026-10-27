#!/usr/bin/env python3
"""
「暖光在冷背景里被染蓝」的离线对照（**只用于验证，不是产品代码**）。

场景：蓝色背景 + 橙色主光（右上）。
  OLD = `mix(ambient*0.45, key, smoothstep(-0.1,0.55,facing))` + 固定混 25% 背景色
  NEW = ambientWeight 分层（迎光环境权重 0）+ 主光增益 + 迎光不混背景

输出 docs/key-color-sim.png（2×2）：两版全图、局部放大、色相定量条。

运行：python tools/sim_key_color.py
"""
import io
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
from sim_rim_glow import (  # noqa: E402
    W, H, bilinear, downsample, gauss_pyramid, grid, label, make_person,
    plan_levels, screen, smoothstep,
)

# 冷背景 / 暖主光（与真实故障场景一致）
KEY = np.array([1.00, 0.78, 0.42], np.float32)        # 暖橙
AMBIENT_RAW = np.array([0.10, 0.30, 0.95], np.float32)  # 高饱和蓝（未限饱和）
DEEP_LEVEL = 0.42


def cap_saturation(rgb):
    """与 AmbientSaturation.cap 的浮点等价：以 luma 为轴缩色度到 sat<=0.55，再混 30% 灰。"""
    r, g, b = [float(v) for v in rgb]
    luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
    mx, mn = max(r, g, b), min(r, g, b)
    if mx <= 1e-6 or mx - mn <= 1e-6:
        return np.array([r, g, b], np.float32)
    sat = (mx - mn) / mx
    T = 0.55
    if sat > T:
        denom = (mx - mn) - T * (mx - luma)
        s = min(max(T * luma / denom, 0.0), 1.0) if denom > 1e-6 else 0.0
        r, g, b = [luma + (v - luma) * s for v in (r, g, b)]
    k = 0.30
    return np.array([v * (1 - k) + luma * k for v in (r, g, b)], np.float32)


def ambient_weight(lit):
    """与 LightMix.ambientWeight 一致。"""
    mid = 0.30 * (1.0 - smoothstep(0.35, 1.0, lit))
    deep = 1.0 - smoothstep(0.0, 0.35, lit)
    return np.maximum(mid, deep)


def emit(a, lit, use_new, ambient):
    """简化 FRAG_EMIT：环带 × 迎光门 × 颜色。"""
    X, Y = grid(W, H)
    band_px = 2.2
    a_min = np.ones_like(a)
    a_max = np.zeros_like(a)
    for i in range(8):
        ang = i * math.pi / 4
        s = bilinear(a, X + math.cos(ang) * band_px, Y + math.sin(ang) * band_px)
        a_min = np.minimum(a_min, s)
        a_max = np.maximum(a_max, s)
    band = smoothstep(0.08, 0.55, a_max - a_min) * smoothstep(0.02, 0.25, a_max)

    if use_new:
        aW = ambient_weight(lit)[..., None]
        col = KEY * (1 - aW) + ambient * aW
        col = col * (1.0 + 0.35 * lit)[..., None]
    else:
        # 旧：environment 在 facing=0 处占 87%
        w = smoothstep(-0.1, 0.55, lit)[..., None]
        col = (ambient * 0.45) * (1 - w) + KEY * w

    outer = 1.0 - smoothstep(0.05, 0.5, a)
    side_w = (1.0 - outer) + 0.62 * outer
    return np.clip(col * (band * lit * 0.9 * side_w)[..., None], 0, 4)


def main():
    a = make_person()
    X, Y = grid(W, H)

    # 法向与迎光比例
    bw = 4.0
    gx = (bilinear(a, X + bw, Y) - bilinear(a, X - bw, Y))
    gy = (bilinear(a, X, Y + bw) - bilinear(a, X, Y - bw))
    glen = np.hypot(gx, gy)
    safe = np.maximum(glen, 1e-6)
    nx = np.where(glen > 1e-6, -gx / safe, 0.0)
    ny = np.where(glen > 1e-6, -gy / safe, 0.0)
    lx = 0.74 * W - X
    ly = 0.16 * H - Y
    ln = np.maximum(np.hypot(lx, ly), 1e-6)
    facing = nx * (lx / ln) + ny * (ly / ln)
    lit = smoothstep(-0.20, 0.30, facing)

    ambient_raw = AMBIENT_RAW
    ambient_capped = cap_saturation(AMBIENT_RAW)

    # ⚠️ 旧分支函数内部已经乘了 0.45，这里传原始值，别乘两次
    e_old = emit(a, lit, use_new=False, ambient=ambient_raw)
    e_new = emit(a, lit, use_new=True, ambient=ambient_capped)

    levels = plan_levels(W, H, 26.0 * H / 1080)
    def bloom(e):
        return downsample(gauss_pyramid(downsample(e, W // 2, H // 2), levels), W, H)

    # 背景：冷蓝
    bg = np.array([0.10, 0.16, 0.34], np.float32) * np.ones((H, W, 1), np.float32)
    person = np.array([0.30, 0.32, 0.40], np.float32) * (0.85 + 0.3 * a)[..., None]
    base = bg * (1 - a[..., None]) + person * a[..., None]

    air = 0.35 + 0.65 * smoothstep(0.05, 0.55, a)
    old_img = screen(base, np.clip(bloom(e_old) * 1.5 * air[..., None], 0, 0.85))
    new_img = screen(base, np.clip(bloom(e_new) * 1.5 * air[..., None], 0, 0.85))

    # 定量：**光晕本身**（不是合成结果）的平均 R-B。
    # 合成图里有人物底色和蓝色背景，会把结论稀释；直接量 glow 才是"光包裹是什么颜色"。
    g_old = bloom(e_old)
    g_new = bloom(e_new)
    glow_mask = ((g_old @ np.array([1, 1, 1], np.float32)) > 0.02) & (a > 0.5)
    def rb(img, mask):
        px = img[mask]
        return float(np.mean(px[:, 0] - px[:, 2])) if len(px) else 0.0
    rb_old = rb(g_old, glow_mask)
    rb_new = rb(g_new, glow_mask)

    tiles = [
        label(old_img, "OLD: ambient 87% at facing=0 -> BLUE glow"),
        label(new_img, "NEW: ambient=0 on lit side -> WARM glow"),
    ]
    sheet = Image.new("RGB", (2 * W, H + 120), (16, 16, 16))
    for i, t in enumerate(tiles):
        sheet.paste(t, (i * W, 0))

    # 底部：色相定量条
    d = ImageDraw.Draw(sheet)
    y0 = H + 10
    def bar(x, val, color, name):
        # val ∈ [-0.3, 0.3] → 宽度
        w = int(abs(val) / 0.3 * 400)
        d.rectangle([x + 160, y0, x + 160 + w, y0 + 34], fill=color)
        d.text((x + 6, y0 + 10), f"{name}: R-B={val:+.3f}", fill=(235, 235, 235))
    bar(10, rb_old, (90, 130, 240), "old")
    bar(10 + W, rb_new, (240, 170, 70), "new")
    d.text((10, y0 + 52), "发光区平均 R-B：>0 偏暖 / <0 偏蓝。这是本轮故障的量化指标。",
           fill=(220, 220, 220))
    d.text((10, y0 + 74), f"环境色限饱和：{np.round(AMBIENT_RAW, 2).tolist()} -> {np.round(ambient_capped, 2).tolist()}",
           fill=(200, 200, 200))

    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs", "key-color-sim.png"))
    sheet.save(out)
    print("saved", out)
    print(f"发光区平均 R-B：旧 {rb_old:+.3f} / 新 {rb_new:+.3f}")
    print("环境色限饱和后：", np.round(ambient_capped, 3).tolist())


if __name__ == "__main__":
    main()
