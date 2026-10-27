#!/usr/bin/env python3
"""
暗部层次 + 底部投影的离线对照（**只用于验证，不是产品代码**）。

两个修复各自做一组 A/B：
  左列 = 修复前（固定暗度 0.32、接触阴影向下采样）
  右列 = 修复后（三级高度 + 环境吸收、接触阴影向上采样）

对应 `FRAG_P2` ⑥ 与 `FRAG_P2C` ④⑤。常量与
`DeepShadowTint` / `BacklitShade` 保持一致。输出 docs/shadow-deep-sim.png（2×2）。

运行：python tools/sim_shadow_deep.py
"""
import io
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from sim_rim_glow import W, H, bilinear, grid, label, make_person, smoothstep  # noqa: E402

LUMA = np.array([0.2126, 0.7152, 0.0722])

# 与 BacklitShade / DeepShadowTint 对齐
DARKEN_BACK = 0.20
DARKEN_GLOBAL = 0.82
CHROMA_MIX = 0.70
DEEP_LEVEL = 0.42
CONTACT_STRENGTH = 1.0
CONTACT_PX = 4.0 * H / 1080      # 按画布缩放（1080p 基准 4px）
REACH_PX = 60.0 * H / 1080
ANCHOR = (0.5, 0.90)
LIGHT_POS = (0.72, 0.18)


def deep_factor(dark_rgb):
    """对应 DeepShadowTint.factor：色相归一 × DEEP_LEVEL"""
    r, g, b = [float(v) for v in dark_rgb]
    luma = float(np.dot([r, g, b], LUMA))
    if luma < 1e-3:
        return np.array([DEEP_LEVEL] * 3, np.float32)
    return np.array([min(r / luma * DEEP_LEVEL, 1.0),
                     min(g / luma * DEEP_LEVEL, 1.0),
                     min(b / luma * DEEP_LEVEL, 1.0)], np.float32)


def height_tier(y):
    v = 0.40 + (0.20 - 0.40) * smoothstep(0.28, 0.60, y)
    return v + (0.05 - v) * smoothstep(0.78, 0.96, y)


def shade(a, deep, new_dark, back_w):
    """对应 FRAG_P2 ⑥：返回线性 RGB。"""
    base = np.array([0.36, 0.38, 0.46], np.float32) * (0.85 + 0.35 * a)[..., None]
    if not new_dark:
        # 旧：全身一个固定系数，仅与 backW 线性混合
        k = DARKEN_GLOBAL + (0.32 - DARKEN_GLOBAL) * back_w
        return base * k[..., None]

    Y = np.linspace(0, 1, H)[:, None] * np.ones((1, W))
    tier = 0.40 + (0.20 - 0.40) * smoothstep(0.28, 0.60, Y)
    tier = tier + (0.05 - tier) * smoothstep(0.78, 0.96, Y)

    absorb = np.array([(1 - CHROMA_MIX) * DARKEN_BACK] * 3, np.float32) + deep * CHROMA_MIX
    target = absorb[None, None, :] * tier[..., None]
    k = DARKEN_GLOBAL + (target - DARKEN_GLOBAL) * back_w[..., None]
    return np.clip(base * k, 0, 1)


def back_weight(a):
    X, Y = grid(W, H)
    gx = (bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y)) * 0.5
    gy = (bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1)) * 0.5
    glen = np.hypot(gx, gy)
    safe = np.maximum(glen, 1e-6)
    nx = np.where(glen > 1e-6, -gx / safe, 0.0)
    ny = np.where(glen > 1e-6, -gy / safe, 0.0)
    lx, ly = LIGHT_POS[0] * W - X, LIGHT_POS[1] * H - Y
    ln = np.maximum(np.hypot(lx, ly), 1e-6)
    facing = nx * (lx / ln) + ny * (ly / ln)
    edge_front = smoothstep(0.0, 0.30, np.hypot(
        bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y),
        bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1),
    )) * smoothstep(0.0, 0.35, facing)
    return np.clip(1.0 - edge_front, 0, 1)


def shadow_factor(a, new_contact, strength=0.9):
    """对应 FRAG_P2C ④⑤：返回 Multiply 因子图。"""
    X, Y = grid(W, H)
    ax, ay = ANCHOR[0] * W, ANCHOR[1] * H
    dx, dy = ax - LIGHT_POS[0] * W, ay - LIGHT_POS[1] * H
    dy = max(dy, 1.0)
    ln = math.hypot(dx, dy)
    ux, uy = dx / ln, dy / ln

    along = np.maximum(0.0, (X - ax) * ux + (Y - ay) * uy)
    dist_k = np.clip(along / max(REACH_PX, 1.0), 0, 1)

    # 沿轴 12 tap 回溯（push 模型）
    acc = np.zeros_like(a)
    for i in range(12):
        k = (i + 0.5) / 12.0 - 0.5
        d = np.clip(along + k * 3.0, 0, REACH_PX)
        acc += bilinear(a, X - ux * d, Y - uy * d)
    occ = acc / 12.0

    near_k = 1.0 - dist_k
    soft = smoothstep(0.10, 0.60, occ) * 0.55
    core = smoothstep(0.45, 0.90, occ) * (1.0 - smoothstep(0.0, 0.30, dist_k)) * 0.95
    depth = np.maximum(soft, core) * (0.30 + 0.70 * near_k)
    mask = np.clip(depth * strength, 0, 1) * (1.0 - smoothstep(0.55, 0.90, a))

    # 接触阴影：★ 关键差异在采样方向
    sign = -1.0 if new_contact else 1.0
    c_near = np.zeros_like(a)
    c_soft = np.zeros_like(a)
    for i in (-1, 0, 1):
        c_near += bilinear(a, X + i, Y + sign * CONTACT_PX)
        c_soft += bilinear(a, X + i, Y + sign * CONTACT_PX * 3.0)
    if new_contact:
        cn = smoothstep(0.40, 0.70, c_near / 3.0)
        cs = smoothstep(0.20, 0.55, c_soft / 3.0) * 0.40
    else:
        cn = smoothstep(0.55, 0.80, c_near / 3.0)
        cs = 0.0
    contact = np.maximum(cn, cs)
    contact *= (0.25 + 0.75 * smoothstep(0.45, 0.85, Y / H))
    contact *= (1.0 - smoothstep(0.80, 0.95, a)) * CONTACT_STRENGTH

    shadow_rgb = np.array([0.16, 0.15, 0.13], np.float32)   # 背景暗部色（sRGB 示意）
    contact_rgb = np.array([0.03, 0.03, 0.035], np.float32)  # deepenShadow ≈ 近黑
    f = (1.0 - mask[..., None]) * 1.0 + mask[..., None] * shadow_rgb
    f = f * ((1.0 - contact[..., None]) + contact[..., None] * contact_rgb)
    return np.clip(f, 0, 1), mask, contact


def main():
    a = make_person()
    bg = np.zeros((H, W, 3), np.float32)
    t = np.linspace(0, 1, H)[:, None, None]
    bg[:] = np.array([0.92, 0.86, 0.74], np.float32) * (1.0 - 0.30 * t)   # 明亮地面

    deep = deep_factor((0.16, 0.19, 0.34))     # 背景暗部 → 深蓝
    bw = back_weight(a)

    old_person = shade(a, deep, new_dark=False, back_w=bw)
    new_person = shade(a, deep, new_dark=True, back_w=bw)
    old_shadow, old_mask, old_contact = shadow_factor(a, new_contact=False)
    new_shadow, new_mask, new_contact_amt = shadow_factor(a, new_contact=True)

    def comp(person, sh):
        c = bg * sh
        return c * (1 - a[..., None]) + person * a[..., None]

    tiles = [
        label(comp(old_person, old_shadow), "1. OLD: flat 0.32 dark + contact sampling DOWN"),
        label(np.repeat((1 - old_contact)[..., None], 3, 2), "2. OLD contact term (all zero -> never worked)"),
        label(comp(new_person, old_shadow), "3. NEW dark: 3-tier + ambient absorb"),
        label(np.repeat((1 - new_contact_amt)[..., None], 3, 2), "4. NEW contact term (fetches feet)"),
    ]
    sheet = Image.new("RGB", (2 * W, 2 * H), (16, 16, 16))
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % 2) * W, (i // 2) * H))
    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs", "shadow-deep-sim.png"))
    sheet.save(out)
    print("saved", out)

    solid = a > 0.9
    def band(y0, y1):
        m = solid.copy()
        m[: int(y0 * H)] = False
        m[int(y1 * H):] = False
        return float(np.mean((new_person[m] @ LUMA))) if m.any() else float("nan")
    print("新版暗部三层平均亮度：头", round(band(0.05, 0.35), 4),
          "腰", round(band(0.45, 0.62), 4), "脚", round(band(0.80, 0.98), 4))
    print("旧版全身平均亮度：", round(float(np.mean(old_person[solid] @ LUMA)), 4))
    print("接触阴影最大暗度：旧", round(float(np.max(old_contact)), 3),
          "新", round(float(np.max(new_contact_amt)), 3))
    print("主投影最大暗度：旧/新", round(float(np.max(old_mask)), 3), round(float(np.max(new_mask)), 3))


if __name__ == "__main__":
    main()
