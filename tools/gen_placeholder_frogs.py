#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
奶蛙相机 · 自绘占位奶蛙素材生成器

为什么要这个脚本：
  合规要求内置素材必须是「自绘 / 自有 / CC0」，而项目开工时手上没有素材。
  用程序画出来属于「自绘」范畴，可以安全内置，并且**可复现**（同样参数永远得到同样的图）。

设计要点：
  · 纯标准库（zlib + struct + math），零第三方依赖，任何装了 Python 3.8+ 的机器都能跑；
  · 3 倍超采样做边缘抗锯齿 —— 直接画会在 alpha 边缘出现硬锯齿，融合进场景后非常显眼；
  · 输出 8bit RGBA PNG（color type 6），带 alpha 通道；
  · 生成后会**回读校验**：PNG 签名、IHDR 尺寸、色彩类型、可见区域 bbox、与 manifest.json 的一致性。

⚠️ 重要变更（2026-10-16）：本应用已**取消内置素材**，素材改为由用户从相册导入。
   因此本脚本的默认输出目录已从 assets 改为 tools/generated-frogs/，
   目的是避免"随手跑一次脚本，素材就悄悄进了安装包"。
   确实要重新内置时才显式加 --to-assets，并且必须手动登记 manifest.json。

用法：
  python tools/gen_placeholder_frogs.py                     # 生成到 tools/generated-frogs/（不入包）
  python tools/gen_placeholder_frogs.py --to-assets         # 生成到 assets/frogs/（需手动登记 manifest）
  python tools/gen_placeholder_frogs.py --out <dir>         # 指定输出目录
  python tools/gen_placeholder_frogs.py --to-assets --verify-only   # 只校验 assets 里已有素材
"""

import argparse
import json
import math
import os
import struct
import sys
import zlib

SIZE = 512          # 输出边长（长边 512，落在素材规范的 512~1024 区间）
SS = 3              # 超采样倍数：SS*SS 个样本决定一个输出像素的 alpha
# 内置素材目录（仅在 --to-assets 时使用）
ASSETS_OUT = os.path.join("core-data", "src", "main", "assets", "frogs")
# 默认输出：工作目录，【不】进入 APK
DEFAULT_OUT = os.path.join("tools", "generated-frogs")


# ---------------------------------------------------------------- 形状
def ell(cx, cy, rx, ry):
    """返回「点是否落在椭圆内」的判定函数；归一化坐标系 0..1，y 向下。"""
    inv_rx = 1.0 / rx
    inv_ry = 1.0 / ry

    def inside(x, y):
        dx = (x - cx) * inv_rx
        dy = (y - cy) * inv_ry
        return dx * dx + dy * dy <= 1.0

    return inside


def band(cx, cy, rx, ry, inner=0.62):
    """圆环/眼睑：外椭圆内、内椭圆外的区域（用于画闭嘴的眼线、嘴巴轮廓）。"""
    outer = ell(cx, cy, rx, ry)
    inner = ell(cx, cy, rx * inner, ry * inner)

    def inside(x, y):
        return outer(x, y) and not inner(x, y)

    return inside


# ---------------------------------------------------------------- 调色
def palette(rgb_body):
    """由主体色推导出暗部/腹部/高光，保证同一套素材风格统一。"""
    r, g, b = rgb_body
    return {
        "body": rgb_body,
        "dark": (int(r * 0.76), int(g * 0.78), int(b * 0.70)),
        "belly": (min(255, int(r + (255 - r) * 0.78)), min(255, int(g + (255 - g) * 0.80)),
                  min(255, int(b + (255 - b) * 0.72))),
        "hilite": (min(255, int(r + (255 - r) * 0.45)), min(255, int(g + (255 - g) * 0.45)),
                   min(255, int(b + (255 - b) * 0.40))),
    }


WHITE = (255, 255, 255)
PUPIL = (22, 30, 20)
MOUTH = (58, 78, 40)
BLUSH = (240, 170, 165)


# ---------------------------------------------------------------- 造型
def shapes_classic(hue: str, wink: bool = False, sleepy: bool = False):
    """标准站立奶蛙。hue 选配色；wink/sleepy 控制眼睛状态。"""
    bodies = {
        "green": (124, 194, 66),
        "teal": (88, 186, 142),
        "lime": (152, 202, 74),
    }
    p = palette(bodies[hue])
    s = []

    # 后脚掌 → 大腿 → 身体 → 头，自下往上叠（后面的自然盖住前面的）
    s.append((p["dark"], ell(0.205, 0.845, 0.115, 0.055)))
    s.append((p["dark"], ell(0.795, 0.845, 0.115, 0.055)))
    s.append((p["body"], ell(0.258, 0.742, 0.104, 0.112)))
    s.append((p["body"], ell(0.742, 0.742, 0.104, 0.112)))
    s.append((p["body"], ell(0.500, 0.660, 0.294, 0.234)))
    s.append((p["body"], ell(0.500, 0.420, 0.256, 0.206)))
    # 前肢
    s.append((p["dark"], ell(0.268, 0.605, 0.058, 0.098)))
    s.append((p["dark"], ell(0.732, 0.605, 0.058, 0.098)))

    # 眼球
    s.append((WHITE, ell(0.385, 0.272, 0.086, 0.086)))
    s.append((WHITE, ell(0.615, 0.272, 0.086, 0.086)))
    if sleepy:
        # 闭眼：一条下弯的眼线
        s.append((PUPIL, band(0.385, 0.292, 0.052, 0.026, 0.55)))
        s.append((PUPIL, band(0.615, 0.292, 0.052, 0.026, 0.55)))
    elif wink:
        s.append((PUPIL, ell(0.397, 0.288, 0.041, 0.049)))
        s.append((WHITE, ell(0.384, 0.271, 0.015, 0.015)))
        s.append((PUPIL, band(0.615, 0.292, 0.052, 0.026, 0.55)))
    else:
        s.append((PUPIL, ell(0.397, 0.288, 0.041, 0.049)))
        s.append((PUPIL, ell(0.603, 0.288, 0.041, 0.049)))
        s.append((WHITE, ell(0.384, 0.271, 0.014, 0.014)))
        s.append((WHITE, ell(0.590, 0.271, 0.014, 0.014)))

    # 腹部 + 腮红 + 嘴 + 头顶高光
    s.append((p["belly"], ell(0.500, 0.712, 0.182, 0.152)))
    s.append((BLUSH, ell(0.305, 0.470, 0.048, 0.030)))
    s.append((BLUSH, ell(0.695, 0.470, 0.048, 0.030)))
    s.append((MOUTH, ell(0.500, 0.502, 0.112, 0.019)))
    s.append((p["hilite"], ell(0.428, 0.352, 0.044, 0.028)))
    return s


def shapes_wave(hue: str):
    """挥手奶蛙：右前肢抬起。"""
    s = shapes_classic(hue)
    bodies = {"green": (124, 194, 66), "teal": (88, 186, 142), "lime": (152, 202, 74)}
    p = palette(bodies[hue])
    # 去掉原来贴身的右前肢（列表里索引 7 是右侧前肢），换成抬起的手臂
    s = [item for idx, item in enumerate(s) if idx != 7]
    s.append((p["dark"], ell(0.860, 0.470, 0.052, 0.108)))
    s.append((p["dark"], ell(0.900, 0.362, 0.058, 0.054)))
    return s


# name, 配色, 造型函数, 形变(scale_x, scale_y, offset_y)
VARIANTS = [
    ("frog_classic_01.png", "green", lambda: shapes_classic("green"), (1.00, 1.00, 0.00)),
    ("frog_classic_02.png", "teal", lambda: shapes_classic("teal"), (1.00, 1.00, 0.00)),
    ("frog_sitting_01.png", "lime", lambda: shapes_classic("lime"), (1.09, 0.85, 0.05)),
    ("frog_wave_01.png", "green", lambda: shapes_wave("green"), (1.00, 1.00, 0.00)),
    ("frog_sleepy_01.png", "teal", lambda: shapes_classic("teal", sleepy=True), (1.05, 0.92, 0.02)),
    ("frog_wink_01.png", "lime", lambda: shapes_classic("lime", wink=True), (1.00, 1.00, 0.00)),
]


# ---------------------------------------------------------------- 渲染
def render(shapes, deform):
    """把形状列表渲染成 SIZE×SIZE 的 RGBA 像素（行优先的 bytearray）。"""
    sx, sy, dy = deform
    inv_sx, inv_sy = 1.0 / sx, 1.0 / sy
    inv_total = 1.0 / (SS * SS)
    inv_size = 1.0 / SIZE
    # 子采样步长必须落在「一个像素内」：= 像素宽度 / 超采样倍数。
    # 写成 1/SS 会把 9 个样本摊到整幅图上，alpha 只剩 6 档且没有不透明区域。
    sub = inv_size / SS
    half = sub * 0.5

    rows = []
    for py in range(SIZE):
        row = bytearray()
        base_y = py * inv_size
        for px in range(SIZE):
            base_x = px * inv_size
            cnt = 0
            sr = sg = sb = 0
            for j in range(SS):
                y = base_y + j * sub + half
                # 逆形变：把采样点映射回原始造型坐标
                yr = 0.5 + (y - 0.5 - dy) * inv_sy
                for i in range(SS):
                    x = base_x + i * sub + half
                    xr = 0.5 + (x - 0.5) * inv_sx
                    color = None
                    for c, inside in shapes:      # 后画的在上层，取最后一个命中的
                        if inside(xr, yr):
                            color = c
                    if color is not None:
                        cnt += 1
                        sr += color[0]
                        sg += color[1]
                        sb += color[2]
            if cnt == 0:
                row += b"\x00\x00\x00\x00"
            else:
                # alpha = 覆盖率；RGB 取被覆盖样本的均值（边缘处自然过渡，不会出现黑边）
                alpha = int(255 * cnt * inv_total + 0.5)
                row += bytes((sr // cnt, sg // cnt, sb // cnt, alpha))
        rows.append(row)
    return rows


def write_png(path, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)            # filter type 0（None）
        raw += row

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)   # 8bit RGBA
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


# ---------------------------------------------------------------- 校验
def verify(path):
    """回读 PNG 并做结构性校验：签名 / 尺寸 / 色彩类型 / 可见区域 / alpha 统计。"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return {"ok": False, "reason": "PNG 签名错误"}

    pos, width, height, depth, ctype, idat = 8, 0, 0, 0, 0, b""
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height, depth, ctype = struct.unpack(">IIBB", payload[:10])
        elif tag == b"IDAT":
            idat += payload
        elif tag == b"IEND":
            break
        pos += 12 + length
    if ctype != 6:
        return {"ok": False, "reason": "色彩类型不是 RGBA(6)"}
    if depth != 8:
        return {"ok": False, "reason": "位深不是 8"}

    raw = zlib.decompress(idat)
    stride = width * 4
    min_x, min_y, max_x, max_y, opaque, semi = width, height, -1, -1, 0, 0
    for y in range(height):
        off = y * (stride + 1) + 1
        line = raw[off:off + stride]
        for x in range(width):
            a = line[x * 4 + 3]
            if a == 255:
                opaque += 1
                if x < min_x: min_x = x
                if x > max_x: max_x = x
                if y < min_y: min_y = y
                if y > max_y: max_y = y
            elif a > 0:
                semi += 1
    coverage = (opaque + semi) / float(width * height)
    return {
        "ok": True, "width": width, "height": height, "color_type": ctype,
        "opaque_px": opaque, "semi_px": semi, "coverage": coverage,
        "bbox": (min_x, min_y, max_x, max_y),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None, help=f"输出目录（默认 {DEFAULT_OUT}）")
    ap.add_argument("--to-assets", action="store_true",
                    help=f"输出到内置素材目录 {ASSETS_OUT}（生成后需手动登记 manifest.json）")
    ap.add_argument("--verify-only", action="store_true", help="只校验已有素材，不重新生成")
    args = ap.parse_args()

    if args.out:
        out_dir = args.out
    elif args.to_assets:
        out_dir = ASSETS_OUT
    else:
        out_dir = DEFAULT_OUT

    # 防呆：写进 assets 等于素材随包分发，必须是显式动作
    if os.path.normpath(out_dir) == os.path.normpath(ASSETS_OUT) and not args.to_assets:
        print("拒绝写入内置素材目录：请显式加 --to-assets（素材进包需人工确认）", file=sys.stderr)
        return 2
    if os.path.normpath(out_dir) == os.path.normpath(ASSETS_OUT):
        print("⚠️ 输出到内置素材目录：生成后请手动登记 manifest.json，否则不会被加载")

    os.makedirs(out_dir, exist_ok=True)
    print(f"输出目录: {os.path.abspath(out_dir)}")
    print(f"每张 {SIZE}x{SIZE} RGBA，超采样 {SS}x{SS}\n")

    if not args.verify_only:
        for name, _hue, make_shapes, deform in VARIANTS:
            rows = render(make_shapes(), deform)
            path = os.path.join(out_dir, name)
            nbytes = write_png(path, rows)
            print(f"  生成 {name}  ({nbytes / 1024:.1f} KB)")
        print()

    print("校验：")
    ok_all = True
    for name, _hue, _f, _d in VARIANTS:
        path = os.path.join(out_dir, name)
        if not os.path.exists(path):
            print(f"  [缺失] {name}")
            ok_all = False
            continue
        r = verify(path)
        if not r["ok"]:
            print(f"  [失败] {name}: {r['reason']}")
            ok_all = False
            continue
        x0, y0, x1, y1 = r["bbox"]
        print(f"  [OK] {name}: {r['width']}x{r['height']} 透明通道 ✓ "
              f"不透明 {r['opaque_px']} px / 半透明 {r['semi_px']} px / "
              f"覆盖率 {r['coverage'] * 100:.1f}% / bbox=({x0},{y0})-({x1},{y1})")

    # 与 manifest.json 交叉校验
    manifest_path = os.path.join(out_dir, "manifest.json")
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        listed = [a["file"] for a in manifest.get("assets", [])]
        actual = sorted(n for n in os.listdir(out_dir) if n.endswith(".png"))
        missing = [n for n in listed if n not in actual]
        extra = [n for n in actual if n not in listed]
        print(f"\nmanifest.json 一致性: 登记 {len(listed)} 条 / 实际 {len(actual)} 个 PNG")
        if missing:
            print(f"  [警告] manifest 登记了但文件不存在: {missing}")
        if extra:
            print(f"  [警告] 存在但未登记（不会被加载）: {extra}")
        if not missing and not extra:
            print("  [OK] 登记与文件一一对应")
        licenses = {}
        for a in manifest.get("assets", []):
            licenses[a.get("license", "<缺失>")] = licenses.get(a.get("license", "<缺失>"), 0) + 1
        print(f"  license 分布: {licenses}")

    print("\n完成。" if ok_all else "\n有素材校验失败。")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
