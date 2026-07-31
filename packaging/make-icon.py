#!/usr/bin/env python3
"""
生成拾光 NAS 的应用图标。

图标是**用代码画出来的**，不是一张来路不明的 png：
  · 完全由几何图形构成，不含任何第三方素材、图库图片或字体轮廓
    （字形轮廓可能带字体授权，所以刻意没用"拾"字）
  · 配色直接取自 frontend/src/styles/tokens.css 的设计令牌

产物：
  packaging/icon.icns                     macOS 应用图标
  packaging/icon.ico                      Windows 应用图标
  packaging/icon.png                      Linux 应用图标
  src/main/resources/icon.png             系统托盘（走 classpath 读）
  frontend/public/favicon.png             浏览器标签页

用法：
    python3 packaging/make-icon.py
需要 Pillow；macOS 上还需要 iconutil（系统自带）生成 .icns。
"""

import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "packaging"

# 画布尺寸。所有东西都在这个尺寸下绘制，再降采样到目标尺寸，
# 靠降采样拿到抗锯齿——PIL 的绘图接口本身不做 AA。
CANVAS = 2048

# 取自 tokens.css 的 nebula 主题
PINK = (0xFF, 0x4D, 0x8D)
AMBER = (0xFF, 0xC2, 0x4B)
TEAL = (0x46, 0xE3, 0xD5)
VIOLET = (0x7A, 0x5C, 0xFF)
DEEP = (0x0D, 0x09, 0x18)


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def background(size):
    """
    暗色极光底，对应原型里 .app::before 的三团光晕。

    刻意**不用**"粉橙渐变 + 圆形镜头"那套：那个组合和某个知名相机社交产品的
    商业外观过于接近。这里改用应用自己的 nebula 深色主题，辨识度来自极光，
    不会和任何现有品牌撞车。
    """
    # 先在小图上按像素算，再放大——2048² 逐像素算纯 Python 太慢
    small = 192
    img = Image.new("RGB", (small, small))
    px = img.load()

    # 三团极光的中心、半径、颜色，位置沿用原型里的 radial-gradient 布局
    blobs = [
        (0.22, 0.26, 0.62, PINK, 0.55),
        (0.78, 0.18, 0.58, TEAL, 0.34),
        (0.54, 0.78, 0.66, VIOLET, 0.52),
    ]

    for y in range(small):
        for x in range(small):
            u, v = x / small, y / small
            # 底色本身也有一点纵向渐变，纯平色会显得很死
            base = lerp((0x18, 0x10, 0x2E), DEEP, v)
            for bx, by, br, color, strength in blobs:
                dx, dy = (u - bx) / br, (v - by) / br
                falloff = max(0.0, 1.0 - (dx * dx + dy * dy))
                base = lerp(base, color, falloff * falloff * strength)
            px[x, y] = base
    return img.resize((size, size), Image.LANCZOS)


def squircle_mask(size, inset):
    """
    超椭圆遮罩（|x|^n + |y|^n = 1）。

    n=5 比普通圆角矩形更接近 macOS 现在的图标轮廓，转角处的曲率变化更连续，
    不会像圆角矩形那样在直线和圆弧交接处出现视觉上的"折点"。
    """
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    n = 5.0
    r = size / 2 - inset
    cx = cy = size / 2

    points = []
    steps = 2048
    for i in range(steps):
        theta = 2 * math.pi * i / steps
        ct, st = math.cos(theta), math.sin(theta)
        # 超椭圆的参数方程
        x = cx + r * math.copysign(abs(ct) ** (2 / n), ct)
        y = cy + r * math.copysign(abs(st) ** (2 / n), st)
        points.append((x, y))
    draw.polygon(points, fill=255)
    return mask


def accent_gradient(size, box):
    """
    粉 → 琥珀 → 青的对角渐变，就是应用的三个强调色。

    渐变按 box（卡片的包围盒）而不是整张画布来算。按画布算的话，
    卡片只截到渐变中间一小段，三个颜色里只有琥珀能看出来。
    """
    small = 128
    img = Image.new("RGB", (small, small))
    px = img.load()
    x0, y0, x1, y1 = box
    for py in range(small):
        for pxi in range(small):
            # 把画布坐标换算成 box 内的 0-1 参数
            gx = (pxi / small * size - x0) / max(1.0, x1 - x0)
            gy = (py / small * size - y0) / max(1.0, y1 - y0)
            t = min(1.0, max(0.0, gx * 0.62 + gy * 0.38))
            px[pxi, py] = lerp(PINK, AMBER, t * 2) if t < 0.5 else lerp(AMBER, TEAL, (t - 0.5) * 2)
    return img.resize((size, size), Image.LANCZOS)


def card(size, w, h, radius, fill, keyline):
    """
    画一张"照片"。

    keyline 是沿边缘描的一圈底色，作用是让叠在一起的卡片之间留出视觉缝隙——
    没有它，深色背景上几张卡片会粘成一块无法分辨的色斑。
    """
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx = cy = size / 2
    box = [cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2]
    d.rounded_rectangle(box, radius=radius, fill=fill,
                        outline=(*DEEP, 210), width=max(1, round(keyline)))
    return layer


def draw_card_stack(img, inset):
    """
    主体：三张扇开的照片。

    为什么是照片堆而不是光圈/镜头：一是"粉橙渐变 + 圆形镜头"和某知名相机社交产品的
    商业外观太接近；二是照片堆在 16px 下仍然是三条可分辨的色带，而光圈叶片会糊成灰点。
    图标在任务栏里能不能认出来，比 512px 下好不好看更重要。
    """
    size = img.size[0]
    content = size - inset * 2

    w, h = content * 0.495, content * 0.385
    radius = content * 0.055
    # 描边只是为了在深色底上让三张卡片分开，粗一点就会像贴纸的白边
    keyline = content * 0.0055

    cx = cy = size / 2
    box = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    # (旋转角, x 偏移, y 偏移, 填充)
    # PIL 的 rotate 正角度是逆时针：左边那张要用正角才会向左倒，
    # 用负角会变成两张牌朝里靠，看着像被压扁了。
    layout = [
        # 两张后卡的 y 偏移必须一致：不一致的话高的那张会盖住矮的那张的顶边，
        # 顶部中间会冒出一小块颜色，看起来像画歪了
        # 旋转方向：左边那张要顺时针（负角）、右边那张要逆时针（正角），
        # 抬起来的是**朝外**的那条边，扇形才是向上张开的。
        # 反过来的话两张卡都抬内侧边，会在正中间交叉出一道细缝，像渲染错误。
        (-11, -content * 0.122, -content * 0.020, (*TEAL, 255)),
        (11, content * 0.122, -content * 0.020, (*PINK, 255)),
        (0, 0, content * 0.052, None),  # None = 用强调色渐变填充
    ]

    for angle, dx, dy, fill in layout:
        if fill is None:
            # 渐变卡片：先画一张纯白的当遮罩，再把渐变贴进去
            shape = card(size, w, h, radius, (255, 255, 255, 255), keyline)
            grad = accent_gradient(size, box).convert("RGBA")
            grad.putalpha(shape.getchannel("A"))
            # 描边要盖回去，否则渐变会淹掉缝隙
            outline = card(size, w, h, radius, (0, 0, 0, 0), keyline)
            layer = Image.alpha_composite(grad, outline)
        else:
            layer = card(size, w, h, radius, fill, keyline)

        layer = layer.rotate(angle, resample=Image.BICUBIC, center=(size / 2, size / 2))
        layer = layer.transform(
            layer.size, Image.AFFINE, (1, 0, -dx, 0, 1, -dy), resample=Image.BICUBIC)
        img = Image.alpha_composite(img, layer)

    return img


def render(inset_ratio):
    """inset_ratio: 四周留白占画布的比例。"""
    inset = CANVAS * inset_ratio
    bg = background(CANVAS).convert("RGBA")
    bg.putalpha(squircle_mask(CANVAS, inset))
    return draw_card_stack(bg, inset)


def write_png(img, path, size):
    img.resize((size, size), Image.LANCZOS).save(path)


def main():
    # macOS 的图标要留白：系统会在图标四周画阴影，画满会显得比其他图标大一圈
    mac = render(0.085)
    # Windows / Linux 的图标基本是满幅的
    flat = render(0.02)

    OUT.mkdir(exist_ok=True)

    write_png(flat, OUT / "icon.png", 512)
    print("写出 packaging/icon.png (512)")

    # 托盘图标：菜单栏里最大也就 @2x 的 44px，256 足够且能少占点 jar 空间
    tray = ROOT / "src/main/resources/icon.png"
    tray.parent.mkdir(parents=True, exist_ok=True)
    write_png(flat, tray, 256)
    print("写出 src/main/resources/icon.png (256)")

    favicon = ROOT / "frontend/public/favicon.png"
    favicon.parent.mkdir(parents=True, exist_ok=True)
    write_png(flat, favicon, 180)
    print("写出 frontend/public/favicon.png (180)")

    # PIL 直接支持多尺寸 ico
    flat.resize((256, 256), Image.LANCZOS).save(
        OUT / "icon.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("写出 packaging/icon.ico (16-256)")

    if sys.platform == "darwin" and shutil.which("iconutil"):
        with tempfile.TemporaryDirectory() as tmp:
            iconset = Path(tmp) / "icon.iconset"
            iconset.mkdir()
            for size in (16, 32, 128, 256, 512):
                write_png(mac, iconset / f"icon_{size}x{size}.png", size)
                write_png(mac, iconset / f"icon_{size}x{size}@2x.png", size * 2)
            subprocess.run(
                ["iconutil", "-c", "icns", str(iconset), "-o", str(OUT / "icon.icns")],
                check=True)
        print("写出 packaging/icon.icns")
    else:
        print("跳过 .icns：需要在 macOS 上运行（iconutil 是系统自带工具）")


if __name__ == "__main__":
    main()
