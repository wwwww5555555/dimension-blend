#!/usr/bin/env python3
"""
边缘光 / 泛光链的 CPU 参考实现（**只用于离线验证形状，不是产品代码**）。

用途：没有真机时回答两个问题——
  1. 迎光门控到底有没有把背光面剔掉？
  2. 光晕是不是真的"从轮廓向外扩散"，而不是一圈等宽描边？

每一段都标注了对应的 GLSL 行，**参数与权重必须与
`core-cv/.../gles/GlesLayerPipeline.kt` 完全一致**（改一边就要改另一边）。
生成图：docs/rim-glow-sim.png

运行：
  python tools/sim_rim_glow.py
依赖：numpy、pillow
"""
import math
import os

import numpy as np
from PIL import Image

W = H = 512

# 与 shader 一致的常量 -------------------------------------------------------
MAX_CHANNEL = 235 / 255.0
KEY = np.array([1.00, 0.72, 0.42])       # EmissiveColor 整形后的暖橙（示意值，见下）
AMBIENT = np.array([0.28, 0.38, 0.52])   # 环境冷色


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(e1 - e0, 1e-9), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# ------------------------------------------------------------------ 采样器
def bilinear(img, xs, ys):
    """与 GL_LINEAR + CLAMP_TO_EDGE 等价。img: (h,w) 或 (h,w,3)"""
    h, w = img.shape[:2]
    xs = np.clip(xs, 0, w - 1)
    ys = np.clip(ys, 0, h - 1)
    x0 = np.floor(xs).astype(np.int32)
    y0 = np.floor(ys).astype(np.int32)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = (xs - x0)[..., None] if img.ndim == 3 else (xs - x0)
    fy = (ys - y0)[..., None] if img.ndim == 3 else (ys - y0)
    a = img[y0, x0]
    b = img[y0, x1]
    c = img[y1, x0]
    d = img[y1, x1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def grid(w, h):
    xs = (np.arange(w) + 0.5)
    ys = (np.arange(h) + 0.5)
    return np.meshgrid(xs, ys)


# ------------------------------------------------------------------ 造素材
def make_person():
    """中心人物剪影：带 4 根发丝 + 裙摆抛物线 + 手臂，用来暴露"等宽带"问题。"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    cx = W * 0.5
    m = np.zeros((H, W), np.float32)

    # 头（椭圆）
    head = ((xx - cx) / 52.0) ** 2 + ((yy - 150.0) / 62.0) ** 2 <= 1
    m[head] = 1.0
    # 脖子 + 身体
    m[(np.abs(xx - cx) < 22) & (yy > 200) & (yy < 240)] = 1.0
    body = (np.abs(xx - cx) < 78 - 0.16 * (yy - 240)) & (yy >= 240) & (yy <= 380)
    m[body] = 1.0
    # 裙摆（抛物线，下缘是曲线 → 法向连续变化，最能看出方向性）
    skirt = (np.abs(xx - cx) < 78 + 0.55 * (yy - 380)) & (yy > 380) & (yy < 470)
    skirt &= yy < 470 - 0.0022 * (xx - cx) ** 2
    m[skirt] = 1.0
    # 手臂
    for sgn in (-1, 1):
        arm = (np.abs(xx - (cx + sgn * 96)) < 14) & (yy > 250) & (yy < 360)
        m[arm] = 1.0
    # 发丝：4 根 1.2px 宽的细线（验证半透明边缘也参与发光）
    for k, (x0, y0, x1, y1) in enumerate([
        (cx - 30, 100, cx - 62, 176), (cx + 34, 96, cx + 70, 180),
        (cx - 52, 130, cx - 92, 210), (cx + 54, 132, cx + 96, 214),
    ]):
        n = 220
        t = np.linspace(0, 1, n)
        px = x0 + (x1 - x0) * t
        py = y0 + (y1 - y0) * t + 12 * np.sin(t * 3.0)
        for i in range(n):
            d2 = (xx - px[i]) ** 2 + (yy - py[i]) ** 2
            m = np.maximum(m, np.exp(-d2 / (2 * 1.2 ** 2)))
    # 抗锯齿：轻微羽化（模拟抠图边缘 1.5px 过渡）
    m = np.clip(m, 0, 1)
    return m.astype(np.float32)


def make_background():
    """夕阳：上暖下暗 + 右上一团亮光源。"""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    t = yy / H
    bg = np.zeros((H, W, 3), np.float32)
    bg[..., 0] = 0.95 - 0.45 * t
    bg[..., 1] = 0.62 - 0.34 * t
    bg[..., 2] = 0.42 - 0.30 * t
    dist2 = ((xx - W * 0.78) ** 2 + (yy - H * 0.18) ** 2) / (W * 0.30) ** 2
    core = np.exp(-dist2 * 2.2)[..., None]
    bg = bg + core * np.array([0.55, 0.42, 0.22])
    return np.clip(bg, 0, 1)


# ------------------------------------------------------------------ ① EMIT
def emit_pass(a, L, band_px, gain):
    """对应 FRAG_EMIT。a: (h,w) alpha；L: 单位光向（xy）"""
    h, w = a.shape
    X, Y = grid(w, h)

    # ---- 形态学梯度环带 ----
    a_min = np.ones_like(a)
    a_max = np.zeros_like(a)
    for i in range(8):
        ang = i * math.pi / 4
        a_min = np.minimum(a_min, bilinear(a, X + math.cos(ang) * band_px, Y + math.sin(ang) * band_px))
        a_max = np.maximum(a_max, bilinear(a, X + math.cos(ang) * band_px, Y + math.sin(ang) * band_px))
    band = smoothstep(0.08, 0.55, a_max - a_min) * smoothstep(0.02, 0.25, a_max)

    # ---- 外法向（1 texel 中心差分）----
    gx = bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y)
    gy = bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1)
    glen = np.hypot(gx, gy)
    nx = np.where(glen > 1e-4, -gx / np.maximum(glen, 1e-6), 0.0)
    ny = np.where(glen > 1e-4, -gy / np.maximum(glen, 1e-6), 0.0)

    ll = math.hypot(*L)
    facing = nx * (L[0] / ll) + ny * (L[1] / ll) if ll > 0.15 else np.full_like(a, 0.35)
    lit = smoothstep(0.0, 0.35, facing)

    keyw = smoothstep(-0.1, 0.55, facing)[..., None]
    col = AMBIENT * 0.45 * (1 - keyw) + KEY * keyw

    outer = 1.0 - smoothstep(0.05, 0.5, a)
    side_w = (1.0 - outer) + 0.62 * outer

    emit = col * (band * lit * gain * side_w)[..., None]
    return np.clip(emit, 0, 4).astype(np.float32), band, facing


# ------------------------------------------------------------------ ② 金字塔
GAUSS_W = (0.2270270270, 0.3162162162, 0.0702702703)
GAUSS_O = (0.0, 1.3846153846, 3.2307692308)


def gauss_dir(img, dx, dy, sigma=1.0):
    """对应 FRAG_GAUSS：5 次采样等效 9 抽头，权重和恰好 1.0"""
    if img.ndim == 2:
        img = img[..., None]
    h, w = img.shape[:2]
    X, Y = grid(w, h)
    out = img * GAUSS_W[0]
    for k in (1, 2):
        d = GAUSS_O[k] * max(sigma, 0.5)
        out = out + (bilinear(img, X + dx * d, Y + dy * d) +
                     bilinear(img, X - dx * d, Y - dy * d)) * GAUSS_W[k]
    return np.maximum(out, 0)


def downsample(img, tw, th):
    """双线性重采样（对应"目标更小 + 用源 texel 采样"的降采样）。"""
    h, w = img.shape[:2]
    X, Y = grid(tw, th)
    return bilinear(img, X * (w / tw), Y * (h / th))


def plan_levels(w, h, spread):
    """与 GlowPyramid.plan 同一套公式"""
    raw = int(round(math.log2(max(spread, 1.0) / 1.0)))
    want = min(max(raw, 2), 5)
    out = []
    cw, ch = w, h
    for _ in range(want):
        cw //= 2
        ch //= 2
        if cw < 4 or ch < 4:
            break
        out.append((cw, ch))
    return out


def gauss_pyramid(img, levels):
    chain = [img]
    for (tw, th) in levels:
        src = chain[-1]
        hpass = downsample(src, tw, th)
        hpass = gauss_dir(hpass, 1, 0)
        chain.append(gauss_dir(hpass, 0, 1))
    # 上采样累加
    acc = chain[-1]
    for i in range(len(chain) - 2, 0, -1):
        hi = chain[i]
        th, tw = hi.shape[:2]
        low = downsample(acc, tw, th)
        # 3×3 tent
        X, Y = grid(tw, th)
        s = low * 4.0
        for ox, oy, wgt in ((1, 0, 2), (-1, 0, 2), (0, 1, 2), (0, -1, 2),
                            (1, 1, 1), (-1, 1, 1), (1, -1, 1), (-1, -1, 1)):
            s = s + bilinear(low, X + ox, Y + oy) * wgt
        s = s / 16.0
        acc = hi + s * 0.85
    return acc


# ------------------------------------------------------------------ ③ 光包裹
def lightwrap(a, L, wrap_px, strength=0.35):
    """对应 FRAG_LIGHTWRAP：只在人物内侧，8 方向 × 3 半径"""
    h, w = a.shape
    X, Y = grid(w, h)
    prox = np.ones_like(a)
    for i in range(8):
        ang = i * math.pi / 4
        for k in (1, 2, 3):
            r = wrap_px * k / 3.0
            prox = np.minimum(prox, bilinear(a, X + math.cos(ang) * r, Y + math.sin(ang) * r))
    depth = np.clip(1.0 - prox, 0, 1)
    inside = smoothstep(0.45, 0.80, a)
    mask = (depth ** 1.8) * inside

    gx = bilinear(a, X + 1, Y) - bilinear(a, X - 1, Y)
    gy = bilinear(a, X, Y + 1) - bilinear(a, X, Y - 1)
    glen = np.hypot(gx, gy)
    nx = np.where(glen > 1e-4, -gx / np.maximum(glen, 1e-6), 0.0)
    ny = np.where(glen > 1e-4, -gy / np.maximum(glen, 1e-6), 0.0)
    ll = math.hypot(*L)
    facing = nx * (L[0] / ll) + ny * (L[1] / ll)
    keyw = smoothstep(-0.1, 0.55, facing)[..., None]
    col = AMBIENT * (1 - keyw) + KEY * keyw
    return np.clip(col * (mask * strength * 2.0)[..., None], 0, 1), mask


# ------------------------------------------------------------------ 旧实现
def old_rim(a, L, strength=0.5, color=np.array([0.98, 0.98, 0.97])):
    """旧版：edgeBand(a) × 迎光 × 纯白，直接加。用它做对照。"""
    h, w = a.shape
    X, Y = grid(w, h)
    band = smoothstep(0.01, 0.15, a) * (1.0 - smoothstep(0.85, 0.99, a))
    gx = bilinear(a, X + 3, Y) - bilinear(a, X - 3, Y)
    gy = bilinear(a, X, Y + 3) - bilinear(a, X, Y - 3)
    glen = np.hypot(gx, gy)
    nx = np.where(glen > 1e-4, -gx / np.maximum(glen, 1e-6), 0.0)
    ny = np.where(glen > 1e-4, -gy / np.maximum(glen, 1e-6), 0.0)
    ll = math.hypot(*L)
    facing = np.clip(nx * (L[0] / ll) + ny * (L[1] / ll), 0, 1)
    return (band * (facing ** 1.5) * strength)[..., None] * color


def screen(base, add):
    return 1.0 - (1.0 - np.clip(base, 0, 1)) * (1.0 - np.clip(add, 0, 1))


def label(img, text):
    from PIL import ImageDraw
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, im.width, 22], fill=(0, 0, 0))
    d.text((6, 6), text, fill=(255, 255, 255))
    return im


def main():
    rng = np.random.default_rng(7)
    a = make_person()
    bg = make_background()
    L = (0.62, -0.48)          # 光来自右上
    band_px = 3.0 * H / 1080
    spread_px = 28.0 * H / 1080

    emit, band, facing = emit_pass(a, L, max(band_px, 1.0), gain=0.9)
    levels = plan_levels(W, H, max(spread_px, 4))
    emit_half = downsample(emit, W // 2, H // 2)
    glow = gauss_pyramid(emit_half, levels)
    glow_full = downsample(glow, W, H)
    wrap, wmask = lightwrap(a, L, wrap_px=8.0 * H / 1080 * 3.0)

    # 人物本体颜色（深蓝灰，二次元角色）
    person = np.array([0.30, 0.33, 0.42], np.float32) * (0.85 + 0.3 * a)[..., None]
    comp = bg * (1 - a[..., None]) + person * a[..., None]

    # 新版：先合成 → 向内包裹 → 泛光 Screen 到整幅画面
    new = screen(comp, wrap)
    air = 0.35 + 0.65 * smoothstep(0.05, 0.55, a)
    new = screen(new, np.clip(glow_full * 1.4 * air[..., None], 0, 0.85))

    # 旧版：边缘光直接加进人物 → 合成
    rim_old = old_rim(a, L)
    comp_old = bg * (1 - a[..., None]) + np.clip(person + rim_old, 0, 1) * a[..., None]
    # 旧版的光包裹：dilate 外扩
    foreign = np.clip(wmask * 0.9, 0, 1)[..., None] * np.array([0.95, 0.93, 0.88])

    tiles = [
        label(np.repeat(a[..., None], 3, axis=2), "1. person mask (input)"),
        label(np.repeat(np.clip(band * smoothstep(0.0, 0.35, facing), 0, 1)[..., None], 3, 2),
              "2. EMIT band x lit-gate (front only)"),
        label(emit * 2.0, "3. EMIT color (key=warm, ambient=cool)"),
        label(glow_full * 3.0, "4. gaussian pyramid result (x3)"),
        label(wrap * 3.0, "5. lightwrap INWARD (5-10px inside)"),
        label(comp_old, "6. OLD: hard white rim (bad)"),
        label(new, "7. NEW: emit+pyramid+wrap"),
        label(np.clip((new - comp_old) * 3 + 0.5, 0, 1), "8. diff (new - old) x3"),
    ]

    cols, rows = 4, 2
    sheet = Image.new("RGB", (cols * W, rows * H), (16, 16, 16))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * W, (i // cols) * H))
    out = os.path.join(os.path.dirname(__file__), "..", "docs", "rim-glow-sim.png")
    sheet.save(os.path.abspath(out))
    print("saved", os.path.abspath(out), sheet.size)
    print("levels:", levels, "spread_px=", round(spread_px, 2))


if __name__ == "__main__":
    main()
