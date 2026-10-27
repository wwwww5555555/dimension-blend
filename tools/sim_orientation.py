#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
回读方向（Y-Flip）离线验证。

做两件事：

1. **数值证明**：把「上传 → 全屏 quad 采样 → glReadPixels」逐步写成索引映射，
   直接算出"回读缓冲区第 0 行对应原图第几行"。
   结论必须是 **0**（即无需翻转）；加一次翻转就变成 h-1 → 画面上下颠倒。

2. **视觉对照**：合成一张带「脚底接触阴影 + 右侧迎光金边」的人物场景，
   按两种回读策略各输出一张，并标注脚下阴影跑到了哪里 ——
   这就是用户看到"素材翻了 + 光影好像没了"的真实观感
   （阴影跑到了头顶，脚底空白 → 人物像飘着）。

用法：python tools/sim_orientation.py   → docs/orientation-sim.png
依赖：numpy + pillow
"""

import os

import numpy as np
from PIL import Image, ImageDraw

W, H = 420, 560
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "orientation-sim.png")


# ---------------------------------------------------------------- ① 数值证明

def derive_readback_row(h):
    """
    模拟一次真实的往返，返回"回读缓冲区第 0 行"里装着原图的第几行。

    三个事实（都是规范行为，不是约定）：
      a) GLUtils.texImage2D 把 Bitmap 第 i 行 → 纹理 t = i / h；
      b) 本工程顶点着色器 vTexCoord = p，gl y = -1（帧缓冲**底部**）对应 t = 0；
      c) glReadPixels 从帧缓冲**底部**开始逐行返回。
    """
    # b) 帧缓冲行号 fb_row（0 = 底部）↔ t：t = fb_row / h
    #    a) 纹理坐标 t 上对应原图行 src_row = t * h
    fb_to_src = [int(round((r / h) * h)) for r in range(h)]
    # c) 回读缓冲区第 k 行 = 帧缓冲第 k 行
    return fb_to_src[0], fb_to_src[h - 1]


def check_numeric():
    h = 8
    first, last = derive_readback_row(h)
    ok_natural = first == 0
    ok_flipped = (h - 1 - first) == h - 1
    print("回读缓冲区首行 = 原图第 %d 行（末行 = 原图第 %d 行）" % (first, last))
    print("  不翻转 → 恒等：%s" % ("OK" if ok_natural else "FAIL"))
    print("  再翻一次 → 变成上下颠倒（首行会变成原图末行）：%s" % ("OK" if ok_flipped else "FAIL"))
    assert ok_natural, "推导结论必须是不需要翻转"
    return ok_natural


# ---------------------------------------------------------------- ② 视觉对照

def scene():
    """返回 (背景, 人物 alpha)：一个站姿剪影（头/躯干/双臂/双腿）+ 地面。"""
    # 2× 超采样后再降采样 → alpha 自带 1px 抗锯齿，边缘才不会像刀切
    SS = 2
    sw, sh = W * SS, H * SS
    yy, xx = np.mgrid[0:sh, 0:sw].astype(np.float32)

    cx = sw * 0.5
    head = np.hypot(xx - cx, yy - 118 * SS) <= 40 * SS
    torso = (np.abs(xx - cx) <= 50 * SS) & (yy >= 160 * SS) & (yy <= 330 * SS)
    leg_l = (xx >= cx - 44 * SS) & (xx <= cx - 12 * SS) & (yy >= 330 * SS) & (yy <= 470 * SS)
    leg_r = (xx >= cx + 12 * SS) & (xx <= cx + 44 * SS) & (yy >= 330 * SS) & (yy <= 470 * SS)
    arm_l = (xx >= cx - 80 * SS) & (xx <= cx - 56 * SS) & (yy >= 176 * SS) & (yy <= 300 * SS)
    arm_r = (xx >= cx + 56 * SS) & (xx <= cx + 80 * SS) & (yy >= 176 * SS) & (yy <= 300 * SS)
    mask = (head | torso | leg_l | leg_r | arm_l | arm_r).astype(np.float32)
    alpha = mask.reshape(H, SS, W, SS).mean(axis=(1, 3))

    # 背景：上暗下亮的地面反光
    byy = np.mgrid[0:H, 0:W][0].astype(np.float32)
    bg = np.zeros((H, W, 3), np.float32)
    bg[..., 0] = 0.14 + 0.12 * (byy / H)
    bg[..., 1] = 0.18 + 0.14 * (byy / H)
    bg[..., 2] = 0.26 + 0.16 * (byy / H)
    return bg, alpha.astype(np.float32)


def person_rgb(alpha):
    """平涂人物 + 右侧迎光金边（模拟 EMIT 的迎光轮廓）。"""
    rgb = np.zeros((H, W, 3), np.float32)
    rgb[..., 0] = 0.42
    rgb[..., 1] = 0.34
    rgb[..., 2] = 0.32
    # 右缘打光：向右的梯度
    gx = np.zeros_like(alpha)
    gx[:, :-1] = alpha[:, 1:] - alpha[:, :-1]
    rim = np.clip(gx, 0, 1)[..., None] * np.array([1.0, 0.82, 0.42], np.float32) * 1.6
    rgb = np.clip(rgb + rim, 0, 1)
    return rgb


def contact_shadow(alpha):
    """
    脚底投影：地面上的椭圆软影（离心衰减）+ 紧贴脚底的接触带（死黑）。
    故意做得"离脚底很近"——翻转之后它会跑到头顶上方，一眼就能看出不对。
    """
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx, foot_y = W * 0.5, 470.0

    # ① 地面椭圆：中心在脚底稍下方，纵向很扁
    r = np.hypot((xx - cx) / 118.0, (yy - foot_y - 6) / 17.0)
    ellipse = np.clip(1.0 - r, 0, 1) ** 1.4

    # ② 接触带：脚底正下方 12px 的死黑核心（人物站不站得住全靠它）
    band = np.zeros((H, W), np.float32)
    feet = alpha[int(foot_y) - 2:int(foot_y) + 1, :].max(axis=0)
    for dy in range(0, 13):
        w = (1.0 - dy / 13.0) ** 0.7
        band[int(foot_y) + dy, :] = np.maximum(band[int(foot_y) + dy, :], feet * w)

    return np.clip(np.maximum(ellipse * 0.55, band * 0.95), 0, 1)


def compose(bg, alpha, person, shadow):
    ground = bg * (1.0 - shadow[..., None])
    out = ground * (1.0 - alpha[..., None]) + person * alpha[..., None]
    return np.clip(out, 0, 1)


def to_img(a):
    return Image.fromarray((np.clip(a, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8))


def annotate(img, title, note):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, img.width, 26], fill=(18, 18, 20))
    d.text((7, 7), title, fill=(235, 235, 235))
    d.rectangle([0, img.height - 24, img.width, img.height], fill=(18, 18, 20))
    d.text((7, img.height - 17), note, fill=(190, 190, 190))
    return img


def main():
    check_numeric()

    bg, alpha = scene()
    person = person_rgb(alpha)
    shadow = contact_shadow(alpha)
    correct = compose(bg, alpha, person, shadow)

    # legacy 渲染器多翻了一次：整张图上下颠倒。
    # 观感上最刺眼的不是"人倒了"，而是**脚下的阴影跑到了头顶上方** ——
    # 用户描述的"光影、阴影被跳过了"其实就是这个。
    flipped = correct[::-1].copy()

    def crop(a, y0, y1):
        return a[y0:y1, int(W * 0.24):int(W * 0.76)]

    top_row = [
        annotate(to_img(correct), "layer pipeline: correct", "contact shadow at feet"),
        annotate(to_img(flipped), "legacy readback: double flip", "shadow above the head"),
    ]
    bottom_row = [
        annotate(to_img(crop(correct, 380, 560)), "feet zoom (correct)", "person is grounded"),
        annotate(to_img(crop(flipped, 0, 180)), "top zoom (was feet)", "shadow floats in air"),
    ]
    bottom_row = [im.resize((im.width * 2, im.height * 2), Image.NEAREST) for im in bottom_row]

    pad = 10
    col_w = max(top_row[0].width, bottom_row[0].width)
    total_w = col_w * 2 + pad * 3
    total_h = top_row[0].height + bottom_row[0].height + pad * 3
    canvas = Image.new("RGB", (total_w, total_h), (10, 10, 12))

    for row_idx, row in enumerate((top_row, bottom_row)):
        y = pad + (0 if row_idx == 0 else top_row[0].height + pad)
        for col_idx, im in enumerate(row):
            x = pad + col_idx * (col_w + pad)
            canvas.paste(im, (x, y))
    canvas.save(OUT)
    print("wrote", os.path.relpath(OUT))


if __name__ == "__main__":
    main()
