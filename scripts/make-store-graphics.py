#!/usr/bin/env python3
"""Google Play 掲載用の画像（icon.png・featureGraphic.png）を作る。

ランチャー アイコンのベクター（app/src/main/res/drawable/ic_launcher_*.xml）と同じ図柄
（背景 #00A4DC、白い円の中が再生の三角で抜けている形）を、Pillow で 8 倍に描いて縮める。
repo 直下で `python3 scripts/make-store-graphics.py` と流すと、
fastlane/metadata/android/{en-US,ja-JP}/images/ に同じ絵を書き出す。
文字の font は DejaVu Sans Bold（FONT 環境変数で差し替えられる）。
"""
import os
from PIL import Image, ImageDraw, ImageFont

BG = (0x00, 0xA4, 0xDC, 255)
FG = (255, 255, 255, 255)
SS = 8  # 縮める前の倍率
FONT = os.environ.get("FONT", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")


def draw_mark(d, ox, oy, scale):
    """108x108 のビューポートを (ox, oy) から scale 倍で描く。"""
    def p(x, y):
        return (ox + x * scale, oy + y * scale)
    r = 24
    d.ellipse([p(54 - r, 54 - r), p(54 + r, 54 + r)], fill=FG)
    d.polygon([p(46, 42), p(70, 54), p(46, 66)], fill=BG)


def icon():
    n = 512 * SS
    im = Image.new("RGBA", (n, n), BG)
    draw_mark(ImageDraw.Draw(im), 0, 0, n / 108)
    return im.resize((512, 512), Image.LANCZOS)


def feature():
    w, h = 1024 * SS, 500 * SS
    im = Image.new("RGBA", (w, h), BG)
    d = ImageDraw.Draw(im)
    mark = 108 * 3.2 * SS  # 図柄の一辺
    font = ImageFont.truetype(FONT, int(120 * SS))
    text = "Kikidame"
    tb = d.textbbox((0, 0), text, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    gap = 20 * SS
    # 円の直径（48 単位）だけを見た幅で左右の中央に置く
    circle_w = 48 * mark / 108
    total = circle_w + gap + tw
    x0 = (w - total) / 2
    scale = mark / 108
    draw_mark(d, x0 - 30 * scale, (h - mark) / 2, scale)
    d.text((x0 + circle_w + gap - tb[0], (h - th) / 2 - tb[1]), text, font=font, fill=FG)
    # フィーチャー グラフィックはアルファの無い 24 ビット PNG にする（Play の規定。ヘルプの原文では未確認）
    return im.resize((1024, 500), Image.LANCZOS).convert("RGB")


if __name__ == "__main__":
    i, f = icon(), feature()
    for lang in ("en-US", "ja-JP"):
        base = f"fastlane/metadata/android/{lang}/images"
        os.makedirs(base, exist_ok=True)
        i.save(f"{base}/icon.png")
        f.save(f"{base}/featureGraphic.png")
