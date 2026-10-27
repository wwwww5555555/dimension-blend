#!/usr/bin/env python3
"""
逆光压暗 / 阴影漏光熔断的 CPU 参考实现（**只用于离线验证，不是产品代码**）。

回答两个只能靠肉眼判断的问题：
  1. 逆光时人物正面到底够不够暗？会不会变成"死黑一块"（没有环境色）？
  2. 投影压到人物边缘时，那一圈还会不会漏光？

逐段对应 `FRAG_P2` 的 ⑥、`FRAG_EMIT` 的 ②b、`FRAG_LIGHTWRAP` 的 ⑤b，
常量与 `BacklitShade` 保持一致。输出 docs/backlit-cutoff-sim.png（2×3）。

运行：python tools/sim_backlit_cutoff.py   （依赖 numpy + pillow）
"""
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from sim_rim_glow import (  # noqa: E402  复用同一套采样器与几何，保证两处口径一致
    AMBIENT, GAUSS_O, GAUSS_W, KEY, W, H,
    bilinear, downsample, gauss_dir, gauss_pyramid, grid, label, make_person,
    plan_levels, screen, smoothstep,
)

# ---- 与 core-common/.../BacklitShade.kt 保持一致 ----
DARKEN_BACK = 0.32
DARKEN_GLOBAL = 0.82
CHROMA_WEIGHT = 0.65
CUT_STRENGTH = 1.0
AMBIENT_FLOOR = 0.15

L = (0.62, -0.48)          # 光来自右上（归一化后用）
LUMA = np.array([0.2126, 0.7152, 0.0722])


def norm2(v):
    n = math.hypot(*v)
    return (v[0] / n, v[1] / n)


def back_weight(n_dot_l):
    """1 - smoothstep(-0.2, 0.2, x)：与 shader 的合法写法完全一致。"""
    return 1.0 - smoothstep(-0.2, 0.2, n_dot_l)


def make_background_backlit():
    """逆光背景：整体很亮（天空过曝），下方偏暖。"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    t = yy / H
    bg = np.zeros((H, W, 3), np.float32)
    bg[..., 0] = 1.00 - 0.20 * t
    bg[..., 1] = 0.88 - 0.30 * t
    bg[..., 2] = 0.72 - 0.34 * t
    d2 = ((xx - W * 0.80) ** 2 + (yy - H * 0.12) ** 2) / (W * 0.26) ** 2
    bg = bg + np.exp(-d2 * 1.6)[..., None] * np.array([0.35, 0.30, 0.20])
    return np.clip(bg, 0, 1)


def make_shadow_factor(a, reach=110.0, blur=22.0):
    """简化版 P2-c：人物沿"向下偏右"投影 + 一维模糊，返回 Multiply 因子（1 = 无遮挡）。"""
    X, Y = grid(W, H)
    dx, dy = norm2((0.42, 0.91))
    off = a
    acc = np.zeros_like(a)
    steps = 14
    for i in range(steps):
        d = reach * (i + 0.5) / steps
        s = bilinear(a, X + dx * d, Y + dy * d)
        acc += s * (1.0 - 0.75 * i / steps)      # 近深远浅
    acc /= steps
    # 一维模糊（沿投影轴）
    for _ in range(3):
        acc = gauss_dir(acc[..., None], dx, dy, sigma=blur / 3.0)[..., 0]
    occ = smoothstep(0.10, 0.55, acc) * (1.0 - smoothstep(0.55, 0.90, a))
    return np.clip(1.0 - 0.85 * occ, 0, 1)


def gradient_normal(a):
    """1 texel 中心差分外法向（alpha 梯度指内，取负）。返回 (facing, gLen)。"""
    X, Y = grid(W, H)
    gx = bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y)
    gy = bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1)
    glen = np.hypot(gx, gy)
    Lh = norm2(L)
    safe = np.maximum(glen, 1e-6)
    nx = np.where(glen > 1e-4, -gx / safe, 0.0)
    ny = np.where(glen > 1e-4, -gy / safe, 0.0)
    return nx * Lh[0] + ny * Lh[1], glen


def radial_n_dot_l():
    """径向伪法向点积（只用来给剪影留 25% 的体积，不参与主判据）。"""
    X, Y = grid(W, H)
    rel = np.stack([X - W / 2, Y - H / 2], axis=-1)
    rl = np.linalg.norm(rel, axis=-1, keepdims=True)
    nuv = np.where(rl > 1e-4, rel / np.maximum(rl, 1e-6), 0.0)
    Lh = norm2(L)
    return nuv[..., 0] * Lh[0] + nuv[..., 1] * Lh[1]


def back_weight_map(a, facing, lw):
    """对应 FRAG_P2 ⑥：内部=背光，只有迎光侧轮廓不压暗。"""
    _, glen = gradient_normal(a)
    g_amp = smoothstep(0.0, 0.30, glen)
    edge_front = g_amp * smoothstep(0.0, 0.35, facing)
    return np.clip((1.0 - edge_front) * (0.75 + 0.25 * (1.0 - smoothstep(-0.2, 0.2, lw))), 0, 1)


def person_shade(a, use_backlit, use_tint=True):
    """对应 FRAG_P2 ⑥：返回 (人物线性 RGB, facing)。"""
    facing, _ = gradient_normal(a)
    base = np.array([0.34, 0.36, 0.44], np.float32) * (0.85 + 0.3 * a)[..., None]
    if not use_backlit:
        return base, facing

    back_w = back_weight_map(a, facing, radial_n_dot_l())
    c = base * (DARKEN_GLOBAL + (DARKEN_BACK - DARKEN_GLOBAL) * back_w)[..., None]

    if use_tint:
        shadow_rgb = np.array([0.16, 0.19, 0.30], np.float32)   # 背景暗部环境色
        chroma = np.clip(shadow_rgb / max(float(np.dot(shadow_rgb, LUMA)), 1e-4), 0.4, 2.2)
        tinted = c * chroma
        l_t = np.maximum(tinted @ LUMA, 1e-4)
        tinted = tinted * np.clip((c @ LUMA) / l_t, 0.5, 2.0)[..., None]
        w = back_w * (1.0 - smoothstep(0.0, 0.35, c @ LUMA)) * CHROMA_WEIGHT
        c = c * (1 - w[..., None]) + tinted * w[..., None]
    return np.clip(c, 0, 1), facing


def emit_pass(a, facing, shadow_factor, use_cut, band_px=2.2, gain=0.9):
    """对应 FRAG_EMIT：环带 × 迎光门 × 阴影熔断。lw 作为伪法向点积（与 P2 同一套）。"""
    X, Y = grid(W, H)
    a_min = np.ones_like(a)
    a_max = np.zeros_like(a)
    for i in range(8):
        ang = i * math.pi / 4
        s = bilinear(a, X + math.cos(ang) * band_px, Y + math.sin(ang) * band_px)
        a_min = np.minimum(a_min, s)
        a_max = np.maximum(a_max, s)
    band = smoothstep(0.08, 0.55, a_max - a_min) * smoothstep(0.02, 0.25, a_max)

    lit = smoothstep(0.0, 0.35, facing)
    if use_cut:
        shadow_amt = np.clip(1.0 - shadow_factor, 0, 1)
        lit = lit * (1.0 - shadow_amt * CUT_STRENGTH)

    keyw = smoothstep(-0.1, 0.55, facing)[..., None]
    col = AMBIENT * 0.45 * (1 - keyw) + KEY * keyw
    outer = 1.0 - smoothstep(0.05, 0.5, a)
    side_w = (1.0 - outer) + 0.62 * outer
    return np.clip(col * (band * lit * gain * side_w)[..., None], 0, 4)


def lightwrap(a, facing, shadow_factor, use_cut, wrap_px=5.0, strength=0.35):
    """对应 FRAG_LIGHTWRAP ⑤b：occlusion = max(backW, shadowAmt)，地板 0.15。"""
    X, Y = grid(W, H)
    prox = np.ones_like(a)
    for i in range(8):
        ang = i * math.pi / 4
        for k in (1, 2, 3):
            r = wrap_px * k / 3.0
            prox = np.minimum(prox, bilinear(a, X + math.cos(ang) * r, Y + math.sin(ang) * r))
    depth = np.clip(1.0 - prox, 0, 1)
    mask = (depth ** 1.8) * smoothstep(0.45, 0.80, a)

    if use_cut:
        back = back_weight(facing)
        shadow_amt = np.clip(1.0 - shadow_factor, 0, 1)
        occ = np.maximum(back, shadow_amt * 1.0)
        keep = 1.0 - occ * CUT_STRENGTH
        w = AMBIENT_FLOOR + (1.0 - AMBIENT_FLOOR) * keep
    else:
        w = 1.0 - np.clip(1.0 - shadow_factor, 0, 1) * 0.8   # 旧行为：只按遮罩打折

    keyw = smoothstep(-0.1, 0.55, facing)[..., None]
    col = AMBIENT * (1 - keyw) + KEY * keyw
    return np.clip(col * (mask * w * strength * 2.0)[..., None], 0, 1)


def compose(a, person, bg, wrap, glow, air_ratio=0.35):
    comp = bg * (1 - a[..., None]) + person * a[..., None]
    c = screen(comp, wrap)
    air = air_ratio + (1 - air_ratio) * smoothstep(0.05, 0.55, a)
    return screen(c, np.clip(glow * 1.4 * air[..., None], 0, 0.85))


def main():
    a = make_person()
    bg = make_background_backlit()
    shadow = make_shadow_factor(a)
    levels = plan_levels(W, H, 28.0 * H / 1080)

    def render(use_backlit, use_cut, use_tint=True):
        person, facing = person_shade(a, use_backlit, use_tint)
        emit = emit_pass(a, facing, shadow, use_cut)
        glow = downsample(gauss_pyramid(downsample(emit, W // 2, H // 2), levels), W, H)
        wrap = lightwrap(a, facing, shadow, use_cut)
        return compose(a, person, bg, wrap, glow), facing

    old, _ = render(use_backlit=False, use_cut=False)      # 旧行为：无压暗、无熔断
    dark_only, _ = render(use_backlit=True, use_cut=False)
    cut_only, _ = render(use_backlit=False, use_cut=True)
    new, lw = render(use_backlit=True, use_cut=True)
    no_tint, _ = render(use_backlit=True, use_cut=True, use_tint=False)

    # 人物区域亮度（只统计 a>0.9 的实心像素）用来给"够不够暗"一个数字
    solid = a > 0.9
    def mean_luma(img):
        return float(np.mean((img[solid] @ LUMA)))

    def mean_chroma(img):
        px = img[solid]
        return float(np.mean(px.max(axis=1) - px.min(axis=1)))
    stats = {
        "old": mean_luma(old), "dark_only": mean_luma(dark_only),
        "cut_only": mean_luma(cut_only), "new": mean_luma(new), "no_tint": mean_luma(no_tint),
    }

    tiles = [
        label(np.repeat(a[..., None], 3, 2), "1. mask + shadow footprint"),
        label(np.repeat(shadow[..., None], 3, 2), "2. shadow factor (1=lit, dark=occluded)"),
        label(old, "3. OLD: no darken / no cutoff"),
        label(dark_only, "4. +backlit darken only (still leaks)"),
        label(cut_only, "5. +cutoff only (front too bright)"),
        label(new, "6. NEW: darken + cutoff"),
    ]
    sheet = Image.new("RGB", (3 * W, 2 * H), (16, 16, 16))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % 3) * W, (i // 3) * H))
    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs", "backlit-cutoff-sim.png"))
    sheet.save(out)
    print("saved", out)
    print("人物实心区平均线性亮度：",
          {k: round(v, 4) for k, v in stats.items()})
    print("旧 → 新 压暗比：", round(stats["new"] / max(stats["old"], 1e-6), 3))
    # 染色的目标是"只改色相、不改亮度"，所以要看**色度**而不是 luma
    print("染色对色度的贡献：", round(mean_chroma(new) - mean_chroma(no_tint), 4),
          "（亮度差", round(stats["new"] - stats["no_tint"], 4), "应 ≈ 0）")


if __name__ == "__main__":
    main()
