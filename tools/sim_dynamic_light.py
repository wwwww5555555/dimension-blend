#!/usr/bin/env python3
"""
「指哪打哪」验证：光源换位置，包裹光有没有跟着走。（离线参考实现，不是产品代码）

对应 `FRAG_EMIT` 的迎光判定。两行对比：
  上排 = **旧写法**：`dot(N, uLightDir.xy)`，整张图共用 CV 给的方位角常数。
  下排 = **新写法**：`dot(N, normalize(uLightPos - uv))`，逐像素动态。

三列 = 光源在左 / 中上 / 右。旧写法三列几乎一样（不跟随），
新写法分别亮左缘 / 头顶肩线 / 右缘。输出 docs/dynamic-light-sim.png（3×2）。

运行：python tools/sim_dynamic_light.py
"""
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from sim_rim_glow import (  # noqa: E402
    W, H, AMBIENT, KEY, bilinear, downsample, gauss_pyramid, grid, label,
    make_person, plan_levels, screen, smoothstep,
)

BAND_PX = 2.2
GAIN = 0.9
# 旧实现用的"全局方向"：模拟 CV 方位角直方图给出的一个常数（指向右下）
FIXED_DIR = (0.7071, 0.7071)

LIGHT_POSITIONS = [(0.14, 0.26), (0.50, 0.10), (0.86, 0.26)]


def emit(a, light_pos, dynamic):
    """对应 FRAG_EMIT ① ②：形态学梯度环带 + 迎光门（+ 顶光偏置）。"""
    X, Y = grid(W, H)

    a_min = np.ones_like(a)
    a_max = np.zeros_like(a)
    for i in range(8):
        ang = i * math.pi / 4
        s = bilinear(a, X + math.cos(ang) * BAND_PX, Y + math.sin(ang) * BAND_PX)
        a_min = np.minimum(a_min, s)
        a_max = np.maximum(a_max, s)
    band = smoothstep(0.08, 0.55, a_max - a_min) * smoothstep(0.02, 0.25, a_max)

    # 像素空间梯度（1 texel 中心差分 → 半宽），外法向取负
    gx = (bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y)) * 0.5
    gy = (bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1)) * 0.5
    glen = np.hypot(gx, gy)
    safe = np.maximum(glen, 1e-6)
    nxp = np.where(glen > 1e-6, -gx / safe, 0.0)
    nyp = np.where(glen > 1e-6, -gy / safe, 0.0)

    if dynamic:
        dx = light_pos[0] * W - X
        dy = light_pos[1] * H - Y
        ln = np.maximum(np.hypot(dx, dy), 1e-6)
        lx, ly = dx / ln, dy / ln
        # 顶光：光源水平居中时点积趋 0 → 把权重压到上边缘（nPx.y < 0）
        top_bias = 1.0 - smoothstep(0.06, 0.20, abs(light_pos[0] - 0.5))
        upper = smoothstep(0.0, 0.6, -nyp)
        facing = (nxp * lx + nyp * ly) * (1 - top_bias * 0.8) + upper * (top_bias * 0.8)
    else:
        facing = nxp * FIXED_DIR[0] + nyp * FIXED_DIR[1]

    lit = smoothstep(0.0, 0.35, facing)
    keyw = smoothstep(-0.1, 0.55, facing)[..., None]
    col = AMBIENT * 0.45 * (1 - keyw) + KEY * keyw

    # 外侧 0.62：光会溢出轮廓，不是等宽描边
    outer = 1.0 - smoothstep(0.05, 0.5, a)
    side_w = (1.0 - outer) + 0.62 * outer
    return np.clip(col * (band * lit * GAIN * side_w)[..., None], 0, 4)


def main():
    a = make_person()
    bg = np.array([0.14, 0.15, 0.20], np.float32) * np.ones((H, W, 1), np.float32)
    levels = plan_levels(W, H, 26.0 * H / 1080)
    person = np.array([0.36, 0.38, 0.46], np.float32) * (0.85 + 0.3 * a)[..., None]

    def render(light_pos, dynamic):
        e = emit(a, light_pos, dynamic)
        glow = downsample(gauss_pyramid(downsample(e, W // 2, H // 2), levels), W, H)
        base = bg * (1 - a[..., None]) + person * a[..., None]
        air = 0.35 + 0.65 * smoothstep(0.05, 0.55, a)
        return screen(base, np.clip(glow * 1.5 * air[..., None], 0, 0.85))

    tiles = []
    for pos in LIGHT_POSITIONS:
        tiles.append(label(render(pos, dynamic=False), "OLD: fixed global dir (light x=%.2f)" % pos[0]))
    for pos in LIGHT_POSITIONS:
        tiles.append(label(render(pos, dynamic=True), "NEW: per-pixel dir to light (x=%.2f)" % pos[0]))

    sheet = Image.new("RGB", (3 * W, 2 * H), (16, 16, 16))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % 3) * W, (i // 3) * H))
    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs", "dynamic-light-sim.png"))
    sheet.save(out)
    print("saved", out)

    # 量化：光晕质心应该跟着光源走
    for dynamic in (False, True):
        cxs = []
        for pos in LIGHT_POSITIONS:
            e = emit(a, pos, dynamic)
            tot = float(e.sum())
            if tot < 1e-6:
                cxs.append(float("nan")); continue
            cxs.append(float((e.sum(axis=2) * np.arange(W)[None, :]).sum() / tot / W))
        tag = "NEW" if dynamic else "OLD"
        print(f"{tag} 光晕质心 X（光源 0.14/0.50/0.86）:", [round(c, 3) for c in cxs])


if __name__ == "__main__":
    main()
