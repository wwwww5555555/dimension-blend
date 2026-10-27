#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主光被蓝背景污染的量化复现（纯 CPU，不需要真机）。

复刻的是 Kotlin 侧真实链路（常量与实现一一对应，改代码要同步改这里）：

    背景像素 ──► SceneLightColors ──► keyRgb（亮度前 5% 加权均值）
              └─► 暗部中位 ──────────► ambientRgb
                                            │
     keyRgb ──► EmissiveColor.sanitize(key, ambient) ──► uKeyLight（着色器输入）
                                            │
              AmbientSaturation.cap(ambient) ──► uAmbientLight

然后按 `FRAG_EMIT` 的混色公式算出"迎光面最终是什么颜色"，看 R−B 的正负：
**负 = 偏冷（蓝荧光），正 = 偏暖（正确）**。

用法：python tools/sim_ambient_pollution.py
"""

import numpy as np


# ---------------------------------------------------------------- 常量（与 Kotlin 对齐）

MAX_CHANNEL = 235          # EmissiveColor.MAX_CHANNEL
MIN_CHROMA = 20            # EmissiveColor.MIN_CHROMA
DEFAULT_WARM = (1.00, 0.78, 0.52)
MAX_SAT = 0.55             # AmbientSaturation.MAX_SAT
GRAY_MIX = 0.40            # 本轮：按需求从 0.30 提到 0.40
AMBIENT_GAIN = 0.45        # 环境色参与"光"的混色前的衰减（v0.30 有、v0.31 丢了、v0.33 恢复）

LUMA_R, LUMA_G, LUMA_B = 0.2126, 0.7152, 0.0722


# ---------------------------------------------------------------- 颜色工具

def luma(c):
    return LUMA_R * c[0] + LUMA_G * c[1] + LUMA_B * c[2]


def sat(c):
    mx, mn = max(c), min(c)
    return 0.0 if mx <= 0 else (mx - mn) / mx


def hexs(c):
    return "#%02X%02X%02X" % tuple(int(round(max(0, min(255, v)))) for v in c)


def desaturate(c, k=GRAY_MIX):
    """需求给的公式：混 k 比例的自灰度。"""
    y = luma(c)
    return [v * (1 - k) + y * k for v in c]


def cap_saturation(c, target=MAX_SAT):
    """
    AmbientSaturation.cap 的核心：以亮度为轴缩放色度（闭式解，保亮度保色相）。
        c' = luma + (c - luma) * s
        s  = T*luma / [(max-min) - T*(max-luma)]
    """
    mx, mn = max(c), min(c)
    if mx <= 0 or mx - mn <= 1e-6:
        return list(c)
    y = luma(c)
    if y <= 1e-6:
        return list(c)
    denom = (mx - mn) - target * (mx - y)
    s = 0.0 if denom <= 1e-6 else min(1.0, max(0.0, target * y / denom))
    return [y + (v - y) * s for v in c]


def hue_dir(c, allow_cool_from_ambient=False):
    """
    EmissiveColor.hueDirection：减均值后归一化。
    `allow_cool_from_ambient=False` 是本轮修的点 —— 环境色偏冷时不拿它当补色方向。
    """
    m = sum(c) / 3.0
    d = [v - m for v in c]
    L = sum(v * v for v in d) ** 0.5
    if L < 8.0:
        wm = sum(DEFAULT_WARM) / 3.0
        w = [v - wm for v in DEFAULT_WARM]
        wl = sum(v * v for v in w) ** 0.5
        return [v / wl for v in w]
    d = [v / L for v in d]
    # 蓝分量大于红分量 = 这个色相在把人往冷里带 → 不用它给"光源"补色
    if not allow_cool_from_ambient and d[2] > d[0]:
        wm = sum(DEFAULT_WARM) / 3.0
        w = [v - wm for v in DEFAULT_WARM]
        wl = sum(v * v for v in w) ** 0.5
        return [v / wl for v in w]
    return d


def sanitize(key, ambient, guard=True):
    """EmissiveColor.sanitize 的移植（guard=False 复刻修复前的行为）。"""
    c = list(key)
    mx = max(c)
    if mx > MAX_CHANNEL:
        k = MAX_CHANNEL / mx
        c = [v * k for v in c]
    chroma = max(c) - min(c)
    if chroma < MIN_CHROMA:
        d = hue_dir(ambient, allow_cool_from_ambient=not guard)
        need = MIN_CHROMA - chroma
        c = [min(255.0, max(0.0, v + d[i] * need)) for i, v in enumerate(c)]
        mx2 = max(c)
        if mx2 > MAX_CHANNEL:
            k = MAX_CHANNEL / mx2
            c = [v * k for v in c]
    return c


def ambient_weight(lit):
    """LightMix.ambientWeight：迎光 0 / 过渡 ≤0.30 / 背光 1.0。"""
    mid = 0.30 * (1.0 - smoothstep(0.35, 1.0, lit))
    deep = 1.0 - smoothstep(0.0, 0.35, lit)
    return min(1.0, max(mid, deep))


def smoothstep(e0, e1, x):
    if e1 <= e0:
        return 0.0
    t = min(1.0, max(0.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    return [a[i] + (b[i] - a[i]) * t for i in range(3)]


# ---------------------------------------------------------------- 场景统计

def scene_stats(px):
    """复刻 SceneLightColors：key=亮度前 5% 加权均值；ambient=≤中位亮度 的均值。"""
    flat = px.reshape(-1, 3).astype(np.float64)
    lum = flat @ np.array([LUMA_R, LUMA_G, LUMA_B])
    order = np.argsort(lum)
    top = order[int(len(order) * 0.95):]
    w = (lum[top] / 255.0) ** 2
    key = (flat[top] * w[:, None]).sum(0) / max(w.sum(), 1e-6)
    med = np.median(lum)
    dark = flat[lum <= med]
    ambient = dark.mean(0) if len(dark) else flat.mean(0)
    return key, ambient


def local_light_sample(px, lx, ly, radius_ratio=0.05, keep=0.30, min_luma=8):
    """
    LocalLightSample 的移植：在光源位置半径内，**只取亮度前 30%** 再按 luma² 加权平均。
    这是 App 里 keyLightColorRgb 的真实来源（不是全图前 5%）。
    """
    Hh, Ww = px.shape[:2]
    r = max(4, int(min(Hh, Ww) * radius_ratio))
    cx, cy = int(lx * Ww), int(ly * Hh)
    y0, y1 = max(0, cy - r), min(Hh - 1, cy + r)
    x0, x1 = max(0, cx - r), min(Ww - 1, cx + r)
    patch = px[y0:y1 + 1, x0:x1 + 1].reshape(-1, 3)
    lum = patch @ np.array([LUMA_R, LUMA_G, LUMA_B])
    patch = patch[lum >= min_luma]
    lum = lum[lum >= min_luma]
    if len(patch) < 12:
        return None
    k = max(12, int(len(patch) * keep))
    idx = np.argsort(lum)[-k:]
    w = (lum[idx] / 255.0) ** 2
    return (patch[idx] * w[:, None]).sum(0) / max(w.sum(), 1e-6)


def make_scene():
    """蓝调天空 + 一小块暖色夕阳（薄雾里，太阳本身接近中性白）。"""
    W, H = 240, 240
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    img = np.zeros((H, W, 3))

    # 天空：从上到下由深蓝到浅蓝（整体偏冷）
    t = (yy / H)
    img[..., 0] = 40 + 70 * t
    img[..., 1] = 80 + 80 * t
    img[..., 2] = 190 + 55 * t

    # 夕阳：暖橙的光晕（半径 70）+ 近中性白的核心（半径 16，薄雾/过曝）
    d = np.hypot(xx - W * 0.78, yy - H * 0.22)
    glow = np.clip(1.0 - d / 70.0, 0, 1) ** 1.6
    img[..., 0] += glow * (255 - img[..., 0]) * 0.95
    img[..., 1] += glow * (190 - img[..., 1]) * 0.95
    img[..., 2] += glow * (110 - img[..., 2]) * 0.95
    core = np.clip(1.0 - d / 16.0, 0, 1) ** 0.8
    img += core[..., None] * (np.array([252.0, 250.0, 247.0]) - img) * 0.9

    return np.clip(img, 0, 255)


def key_with_fallback(key):
    """v0.34 的兜底：蓝通道 > 红通道 → 换默认暖黄（sRGB 1.0/0.8/0.5）。"""
    if key[2] > key[0]:
        return [255.0, 204.0, 127.5]      # sRGB(1.0, 0.8, 0.5)
    return list(key)


def lightwrap_color(key, lit_ratio):
    """v0.34 光包裹：颜色 100% 来自主光，只乘迎光比例（无环境色、无背景色）。"""
    k = key_with_fallback(key)
    return [v * lit_ratio for v in k]


def main():
    scene = make_scene()
    key_global, ambient = scene_stats(scene)
    # App 的真实链路：key 来自**光源位置邻域**（LocalLightSample），不是全图
    light_x, light_y = 0.78, 0.22          # 夕阳中心（与 make_scene 一致）
    key = local_light_sample(scene, light_x, light_y)
    assert key is not None

    print("场景：蓝调天空 + 薄雾里的暖夕阳")
    print("  ambient（暗部均值）        = %s  sat=%.3f" % (hexs(ambient), sat(ambient)))
    print("  key·全图前 5%%（对照，未采用）= %s  R-B=%+.0f  ← 蓝天空把全图统计拉冷"
          % (hexs(key_global), key_global[0] - key_global[2]))
    print("  key·光源邻域（实际传入）    = %s  chroma=%.0f  ← 太阳核心近中性"
          % (hexs(key), max(key) - min(key)))

    print("\n[修正 1] 环境色去毒（混 %.0f%% 灰 → 再压到 sat≤%.2f）"
          % (GRAY_MIX * 100, MAX_SAT))
    step1 = desaturate(ambient)
    step2 = cap_saturation(step1)
    print("  原始           %s  sat=%.3f" % (hexs(ambient), sat(ambient)))
    print("  只混灰 40%%     %s  sat=%.3f   ← 只降 %.0f%%，肉眼几乎看不出"
          % (hexs(step1), sat(step1), (1 - sat(step1) / max(sat(ambient), 1e-6)) * 100))
    print("  再压到上限     %s  sat=%.3f   ← 真正的去荧光" % (hexs(step2), sat(step2)))

    print("\n[修正 2] 主光补色方向（不再沿用冷环境色的色相）")
    safe_before = sanitize(key, ambient, guard=False)
    safe_after = sanitize(key, ambient, guard=True)
    print("  uKeyLight 修复前 %s  R-B=%+.0f" % (hexs(safe_before), safe_before[0] - safe_before[2]))
    print("  uKeyLight 修复后 %s  R-B=%+.0f" % (hexs(safe_after), safe_after[0] - safe_after[2]))

    print("\n[合成] 迎光面最终色（FRAG_EMIT：mix(key, ambient*0.45, ambientWeight(lit))）")
    print("       ↑ ambient*0.45 是 v0.30 的内层衰减；v0.31 重写时弄丢过，v0.33 恢复")
    print("  lit   v0.30 旧公式                       修复后")
    for lit in (1.0, 0.7, 0.4, 0.1, 0.0):
        old = mix([v * 0.45 for v in ambient], safe_before, smoothstep(-0.1, 0.55, lit))
        new = mix(safe_after, [v * AMBIENT_GAIN for v in step2], ambient_weight(lit))
        print("  %.1f   %s R-B=%+6.1f            %s R-B=%+6.1f"
              % (lit, hexs(old), old[0] - old[2], hexs(new), new[0] - new[2]))

    bad = []
    for lit in (1.0, 0.7, 0.4):
        new = mix(safe_after, [v * AMBIENT_GAIN for v in step2], ambient_weight(lit))
        if new[0] - new[2] <= 0:
            bad.append(lit)
    print("\n[v0.34] 光包裹硬覆盖：颜色 100% 来自主光（无环境色 / 无背景色）")
    print("  用同一个暖主光 %s：" % hexs(safe_after))
    for lit_ratio in (1.0, 0.5, 0.2, 0.0):
        c = lightwrap_color(safe_after, lit_ratio)
        print("    litRatio=%.1f  %s  R-B=%+.0f" % (lit_ratio, hexs(c), c[0] - c[2]))

    print("  模拟「提取失败」（全图太蓝，主光被污染成 #A1ADCF）：")
    polluted = [161.0, 173.0, 207.0]
    fb = key_with_fallback(polluted)
    c = lightwrap_color(polluted, 1.0)
    print("    兜底后 %s  R-B=%+.0f  ← 强制暖黄，绝不输出荧光蓝"
          % (hexs(c), c[0] - c[2]))
    assert c[0] > c[2], "兜底失败"
    # 硬覆盖的核心性质：颜色比例恒定，只有亮度变化 → 任何 litRatio 下都 R>B
    for lit_ratio in (0.05, 0.3, 1.0):
        cc = lightwrap_color(safe_after, lit_ratio)
        assert cc[0] >= cc[2], "硬覆盖下仍出现偏冷"

    print("\n结论：迎光区与交界带（lit≥0.4）必须 R>B。%s"
          % ("通过" if not bad else "仍偏冷于 lit=%s" % bad))
    assert not bad, "迎光面仍然偏冷"
    assert sat(step2) <= MAX_SAT + 1e-6


if __name__ == "__main__":
    main()
