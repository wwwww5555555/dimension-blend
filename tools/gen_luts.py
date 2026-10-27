#!/usr/bin/env python3
"""
生成 4 套内置 3D 颜色查找表（.cube 格式），用于规格 8.1 的前景颜色映射。

四套 LUT 对应四种场景氛围：
  warm_indoor   暖光室内（低色温 ~2800K，偏橙红，高光偏暖）
  cool_night    冷光夜景（高色温 ~9000K，偏蓝，暗部更冷）
  overcast      阴天户外（中性偏灰，低饱和，柔和）
  backlit       逆光场景（暖高光 + 冷阴影，高对比）

实现：纯标准库，不依赖 colour-science（避免重依赖）。每套 LUT 是「白平衡偏移 + 饱和度 + 对比度」
三参数的组合变换，通过把 3D 网格的每个顶点做一次颜色映射得到。物理含义清晰、可复现。

用法：
  python tools/gen_luts.py                    # 生成到 core-cv/src/main/assets/luts/
  python tools/gen_luts.py --out <dir>        # 指定输出目录
  python tools/gen_luts.py --size 17          # 自定义 LUT 边长（默认 17）
"""
import argparse
import os

# 色温 → 白平衡增益（简化 Kelvin 近似，R/G/B 各自相对 6500K 的偏移）
# 每套 LUT 定义：白平衡 RGB 增益 + 饱和度系数 + 对比度系数 + 阴影偏移（暗部着色）
LUTS = {
    "warm_indoor": {
        "title": "暖光室内",
        "wb": (1.15, 0.95, 0.70),   # 偏橙红：R 增、B 减
        "saturation": 1.05,
        "contrast": 1.05,
        "shadow_tint": (0.02, 0.00, -0.03),  # 阴影略偏暖
    },
    "cool_night": {
        "title": "冷光夜景",
        "wb": (0.80, 0.95, 1.25),   # 偏蓝：R 减、B 增
        "saturation": 0.95,
        "contrast": 1.10,
        "shadow_tint": (-0.03, 0.00, 0.04),  # 阴影略偏冷蓝
    },
    "overcast": {
        "title": "阴天户外",
        "wb": (0.95, 1.00, 1.02),   # 接近中性，轻微偏冷
        "saturation": 0.85,         # 低饱和，去"贴纸感"
        "contrast": 0.95,           # 柔和低对比
        "shadow_tint": (0.00, 0.00, 0.01),
    },
    "backlit": {
        "title": "逆光场景",
        "wb": (1.08, 0.97, 0.82),   # 暖高光
        "saturation": 1.00,
        "contrast": 1.20,           # 高对比
        "shadow_tint": (0.00, 0.00, -0.04),  # 冷阴影
    },
}


def apply_transform(r, g, b, spec):
    """对一个 0~1 的 RGB 顶点应用 LUT 变换。"""
    wb = spec["wb"]
    sat = spec["saturation"]
    con = spec["contrast"]
    st = spec["shadow_tint"]

    # 1) 白平衡
    r *= wb[0]
    g *= wb[1]
    b *= wb[2]

    # 2) 饱和度（转亮度加权灰度，向灰靠拢/远离）
    luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
    r = luma + (r - luma) * sat
    g = luma + (g - luma) * sat
    b = luma + (b - luma) * sat

    # 3) 对比度（以 0.5 为中心）
    r = (r - 0.5) * con + 0.5
    g = (g - 0.5) * con + 0.5
    b = (b - 0.5) * con + 0.5

    # 4) 阴影着色（暗部越暗，着色越明显）
    dark = max(0.0, 1.0 - luma)  # luma 越低 dark 越大
    r += st[0] * dark
    g += st[1] * dark
    b += st[2] * dark

    return clamp(r), clamp(g), clamp(b)


def clamp(v):
    return max(0.0, min(1.0, v))


def write_cube(path, title, size, spec):
    lines = []
    lines.append(f'TITLE "{title}"')
    lines.append(f"LUT_3D_SIZE {size}")
    lines.append("DOMAIN_MIN 0.0 0.0 0.0")
    lines.append("DOMAIN_MAX 1.0 1.0 1.0")
    # LUT 顶点布局：B 最慢、G 次之、R 最快（标准约定）
    step = 1.0 / (size - 1)
    for b_idx in range(size):
        b = b_idx * step
        for g_idx in range(size):
            g = g_idx * step
            for r_idx in range(size):
                r = r_idx * step
                rr, gg, bb = apply_transform(r, g, b, spec)
                lines.append(f"{rr:.6f} {gg:.6f} {bb:.6f}")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--out",
        default=os.path.join("core-cv", "src", "main", "assets", "luts"),
        help="输出目录（默认 core-cv/src/main/assets/luts）",
    )
    ap.add_argument("--size", type=int, default=17, help="LUT 边长（默认 17）")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    size = max(2, args.size)
    print(f"输出目录: {os.path.abspath(args.out)}   LUT 边长: {size}")

    for name, spec in LUTS.items():
        path = os.path.join(args.out, f"{name}.cube")
        write_cube(path, spec["title"], size, spec)
        n = size ** 3
        print(f"  {name:14s} -> {os.path.basename(path)}  ({n} 顶点, {spec['title']})")

    print("完成。")

if __name__ == "__main__":
    main()
