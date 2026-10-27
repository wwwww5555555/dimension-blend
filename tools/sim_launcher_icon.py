#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
次元溶图 · 启动图标离线预览

为什么需要这个脚本：
  自适应图标是**纯路径 + 渐变**，没有位图可看。AAPT 只能保证 XML 语法正确，
  保证不了"两个环到底叠没叠上""图形有没有跑出 66x66 安全区""缩到 48dp 还认不认得出"。
  这里按 `ic_launcher_foreground.xml` / `ic_launcher_background.xml` 里的
  每一条 path、每一个 gradient 端点逐像素复刻一遍，再用启动器真实遮罩裁切。

  所以它同时是"预览"和"契约"：
   - 打印前景图形半径（必须 ≤ 33，否则圆形遮罩会切掉边缘）；
   - 打印两环是否真的相交（否则"图层叠加"的语义就不成立）。

输出：docs/launcher-icon.png

⚠️ 与 XML 的对应关系（改图标必须同步改这里，否则预览会骗人）：
  背景   线性 (0,0)→(108,108)  #10141C → #161C28@0.55 → #0B0E14
         径向 青 (38,72) r52   #3D2DD4BF@0 → #1A14B8A6@0.45 → 透明
         径向 橙 (72,34) r50   #40FB923C@0 → #1AF97316@0.45 → 透明
  前景   径向 柔光 (54,54) r18 #66FFE8C7@0 → #26FFC078@0.45 → 透明
         青环 中心(45,59) r17 描边6.5  #0F766E→#2DD4BF→#7DF9E4
         橙环 中心(63,49) r17 描边6.5  #FFD9A8→#FB923C→#F06A1F
         交点 中心(54,53) r3.4         #FFFFFF→#FFF1DC@0.7→#CCFFD9A0
"""

import math
import os

import numpy as np
from PIL import Image

S = 108
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "docs", "launcher-icon.png")


# ------------------------------------------------------------------ 基础工具

def hex_rgba(h):
    """
    ⚠️ Android 的 8 位颜色是 **#AARRGGBB**（alpha 在前），不是 CSS 的 #RRGGBBAA。
    第一版写成 RRGGBBAA，直接把"透明橙 #00FFC078"读成了"不透明青绿"，
    结果前景 alpha 铺满整张画布，安全区检查报出半径 75 —— 这个坑值得留一行注释。
    """
    h = h.lstrip("#")
    if len(h) == 8:
        return (np.array([int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)], np.float64),
                int(h[0:2], 16) / 255.0)
    return np.array([int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)], np.float64), 1.0


def grid():
    y, x = np.mgrid[0:S, 0:S].astype(np.float64)
    return x + 0.5, y + 0.5


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(e1 - e0, 1e-9), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def lerp_stops(t, stops):
    """stops: [(offset, '#RRGGBBAA')] → (rgb 0~255, alpha 0~1)，形状均为 (S,S)。"""
    t = np.clip(t, 0.0, 1.0)
    rgb = np.zeros(t.shape + (3,), np.float64)
    a = np.zeros(t.shape, np.float64)
    n = len(stops)
    for i in range(n - 1):
        o0, c0 = stops[i]
        o1, c1 = stops[i + 1]
        r0, a0 = hex_rgba(c0)
        r1, a1 = hex_rgba(c1)
        seg = (t >= o0) & ((t <= o1) if i < n - 2 else (t <= o1 + 1e-9))
        k = np.zeros_like(t)
        k[seg] = (t[seg] - o0) / max(o1 - o0, 1e-9)
        k3 = k[..., None]
        rgb = np.where(seg[..., None], r0 * (1 - k3) + r1 * k3, rgb)
        a = np.where(seg, a0 * (1 - k) + a1 * k, a)
    return rgb, a


def over(dst_rgb, dst_a, src_rgb, src_a):
    out_a = src_a + dst_a * (1 - src_a)
    safe = np.maximum(out_a[..., None], 1e-9)
    out_rgb = np.where(
        out_a[..., None] > 1e-9,
        (src_rgb * src_a[..., None] + dst_rgb * dst_a[..., None] * (1 - src_a[..., None])) / safe,
        0.0,
    )
    return out_rgb, out_a


def field(cx, cy):
    x, y = grid()
    return np.hypot(x - cx, y - cy)


def cov_circle(cx, cy, r):
    """实心圆的覆盖率（1px 软边，替代超采样）。"""
    return 1.0 - smoothstep(r - 0.5, r + 0.5, field(cx, cy))


def cov_annulus(cx, cy, r, stroke):
    d = field(cx, cy)
    inner, outer = r - stroke / 2, r + stroke / 2
    return smoothstep(inner - 0.5, inner + 0.5, d) * (1.0 - smoothstep(outer - 0.5, outer + 0.5, d))


# ------------------------------------------------------------------ 图层

def build_background():
    x, y = grid()
    t = (x * S + y * S) / (2.0 * S * S)
    rgb, _ = lerp_stops(t, [(0.0, "#FF10141C"), (0.55, "#FF161C28"), (1.0, "#FF0B0E14")])
    acc_rgb, acc_a = rgb, np.ones(t.shape)

    for (cx, cy, r), stops in (
        ((38, 72, 52), [(0.0, "#3D2DD4BF"), (0.45, "#1A14B8A6"), (1.0, "#00000000")]),
        ((72, 34, 50), [(0.0, "#40FB923C"), (0.45, "#1AF97316"), (1.0, "#00000000")]),
    ):
        g_rgb, g_a = lerp_stops(field(cx, cy) / r, stops)
        acc_rgb, acc_a = over(acc_rgb, acc_a, g_rgb, g_a)
    return acc_rgb, acc_a


def build_foreground():
    acc_rgb = np.zeros((S, S, 3), np.float64)
    acc_a = np.zeros((S, S), np.float64)
    x, y = grid()

    # ① 交叠处柔光（实心圆 + 径向渐变；半径外 alpha 已为 0，无需再遮罩）
    g_rgb, g_a = lerp_stops(field(54, 54) / 18.0,
                            [(0.0, "#66FFE8C7"), (0.45, "#26FFC078"), (1.0, "#00FFC078")])
    acc_rgb, acc_a = over(acc_rgb, acc_a, g_rgb, g_a)

    # ② 青环 / ③ 橙环：渐变沿各自的对角轴，与 XML 的 start/end 一致
    for center, axis, stops in (
        ((45, 59), ((28, 76), (62, 42)), [(0.0, "#FF0F766E"), (0.5, "#FF2DD4BF"), (1.0, "#FF7DF9E4")]),
        ((63, 49), ((46, 32), (80, 66)), [(0.0, "#FFFFD9A8"), (0.5, "#FFFB923C"), (1.0, "#FFF06A1F")]),
    ):
        (x0, y0), (x1, y1) = axis
        vx, vy = x1 - x0, y1 - y0
        t = ((x - x0) * vx + (y - y0) * vy) / (vx * vx + vy * vy)
        rgb, _ = lerp_stops(t, stops)
        acc_rgb, acc_a = over(acc_rgb, acc_a, rgb, cov_annulus(center[0], center[1], 17, 6.5))

    # ④ 交点高光
    core_rgb, core_a = lerp_stops(field(54, 53) / 3.4,
                                  [(0.0, "#FFFFFFFF"), (0.7, "#FFFFF1DC"), (1.0, "#CCFFD9A0")])
    core_a = core_a * cov_circle(54, 54, 3.4)
    acc_rgb, acc_a = over(acc_rgb, acc_a, core_rgb, core_a)
    return acc_rgb, acc_a


def compose(bg, fg):
    rgb, a = over(bg[0], np.ones((S, S)), fg[0], fg[1])
    return np.clip(rgb / 255.0, 0, 1), np.clip(a, 0, 1)


# ------------------------------------------------------------------ 遮罩与输出

def masked(rgba, mask):
    out = rgba.copy()
    out[..., 3] *= mask
    return out


def mask_circle():
    return cov_circle(54, 54, 54)


def mask_squircle(n=4.0):
    x, y = grid()
    v = (np.abs(x - 54) / 54) ** n + (np.abs(y - 54) / 54) ** n
    return 1.0 - smoothstep(1.0 - 0.02, 1.0 + 0.02, v)


def to_img(rgba, size=None):
    arr = (np.clip(rgba, 0, 1) * 255).astype(np.uint8)
    im = Image.fromarray(arr, "RGBA")
    return im.resize((size, size), Image.LANCZOS) if size else im


def on_wallpaper(img, pad=8):
    canvas = Image.new("RGB", (img.width + pad * 2, img.height + pad * 2), (58, 60, 66))
    canvas.paste(img, (pad, pad), img)
    return canvas


def main():
    bg = build_background()
    fg = build_foreground()
    rgb, a = compose(bg, fg)
    rgba = np.dstack([rgb, a])
    x, y = grid()
    dist = np.hypot(x - 54, y - 54)

    # ---- 契约 1：前景必须落在 66x66 安全区内（半径 33）----
    r_max = float(dist[fg[1] > 0.02].max())
    print(f"前景图形最大半径：{r_max:.2f}（安全区 33.00）  {'OK' if r_max <= 33 else '★ 会被裁切'}")
    assert r_max <= 33.0, "前景超出安全区，圆形遮罩会切掉边缘"

    # ---- 契约 2：两环必须真的相交 ----
    d_centers = math.hypot(45 - 63, 59 - 49)
    print(f"两环圆心距：{d_centers:.2f}（半径和 34）→ "
          f"{'相交，有叠加区' if d_centers < 34 else '★ 完全分离'}")
    assert d_centers < 34.0
    overlap = 34 - d_centers
    print(f"叠加区宽度：{overlap:.2f}px（占单环直径 {overlap / 34 * 100:.0f}%）")

    # ---- 契约 3：48dp 时两环描边不能糊在一起 ----
    gap = 34 - d_centers - 6.5
    print(f"两环内侧净间距：{gap:.2f}px（>1 才分得开；48dp 图标约缩至 0.44 倍 → {gap * 0.44:.2f}px）")

    circle = on_wallpaper(to_img(masked(rgba, mask_circle()), 216))
    squircle = on_wallpaper(to_img(masked(rgba, mask_squircle()), 216))
    layer_bg = on_wallpaper(to_img(np.dstack([np.clip(bg[0] / 255.0, 0, 1), np.ones((S, S))]), 144), pad=6)
    layer_fg = on_wallpaper(to_img(np.dstack([np.clip(fg[0] / 255.0, 0, 1), fg[1]]), 144), pad=6)
    small = [on_wallpaper(to_img(rgba, s), pad=6) for s in (48, 72, 96)]

    pad = 12
    row1_h = max(circle.height, squircle.height, layer_bg.height)
    row2_h = small[0].height
    total_w = pad * 3 + circle.width + squircle.width + layer_bg.width + layer_fg.width + 8
    total_w = max(total_w, pad * 2 + sum(im.width for im in small) + 8 * (len(small) - 1))
    canvas = Image.new("RGB", (total_w, pad * 2 + row1_h + 8 + row2_h), (28, 30, 34))

    xx = pad
    for im in (circle, squircle, layer_bg, layer_fg):
        canvas.paste(im, (xx, pad))
        xx += im.width + pad

    xx = pad
    for im in small:
        canvas.paste(im, (xx, pad + row1_h + 8))
        xx += im.width + 8

    canvas.save(OUT)
    print("wrote", os.path.relpath(OUT))
    print("（上排左→右：圆形遮罩 / 圆角方形 / 背景层 / 前景层；下排 48·72·96dp）")


if __name__ == "__main__":
    main()
