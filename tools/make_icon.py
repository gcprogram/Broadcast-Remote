"""Generates the app icon: a remote control with a slanted "MCP" above it and radio waves at its sides.

  python tools/make_icon.py [path-to-bold-font.ttf]

Writes the adaptive-icon foreground PNGs and XML into android/res and a 512 px preview into docs/img/icon.png.
Needs Pillow. The generated files are committed, so this only has to be run to change the design.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = sys.argv[1] if len(sys.argv) > 1 else r"C:\Windows\Fonts\ariblk.ttf"
BG_TOP, BG_BOTTOM = (13, 110, 230), (8, 52, 140)       # background gradient (preview); the adaptive icon uses BG_FLAT
BG_FLAT = "#0A57B8"
BODY, BODY_SHADE = (247, 249, 252), (205, 214, 228)
BUTTON, OFF_RED, WAVE = (84, 174, 255), (229, 72, 77), (255, 255, 255)
TEXT, TEXT_SHADOW = (255, 214, 10), (6, 38, 100)
SS = 4                                                  # supersampling for smooth edges
FIT, SHIFT = 0.76, 14                                   # scale of the artwork and downward shift (432-unit space)


def foreground(size):
    """The artwork on a transparent square. Coordinates are in 432-unit adaptive-icon space (safe zone: centre 288)."""
    s = size * SS / 432.0
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def P(x, y):
        return (x * s, y * s)

    def box(x0, y0, x1, y1):
        return [x0 * s, y0 * s, x1 * s, y1 * s]

    # radio waves left and right of the remote
    cx, cy = 216, 222
    for radius, width in ((88, 9), (114, 9)):
        for side in (-1, 1):
            start = -30 if side == 1 else 150
            d.arc(box(cx - radius, cy - radius, cx + radius, cy + radius), start, start + 60, fill=WAVE, width=int(width * s))

    # remote body with a soft shade at the bottom
    x0, y0, x1, y1 = 161, 150, 271, 352
    d.rounded_rectangle(box(x0, y0, x1, y1), radius=30 * s, fill=BODY_SHADE)
    d.rounded_rectangle(box(x0, y0, x1, y1 - 7), radius=30 * s, fill=BODY)
    # OFF button
    d.ellipse(box(216 - 14, 180 - 14, 216 + 14, 180 + 14), fill=OFF_RED)
    # 3 x 3 buttons
    for row in range(3):
        for col in range(3):
            bx, by = 216 + (col - 1) * 31, 226 + row * 31
            d.ellipse(box(bx - 10, by - 10, bx + 10, by + 10), fill=BUTTON)
    # long button at the bottom
    d.rounded_rectangle(box(190, 318, 242, 334), radius=8 * s, fill=BUTTON)

    # slanted "MCP"
    font = ImageFont.truetype(FONT, int(96 * s))
    text = "MCP"
    l, t, r, b = d.textbbox((0, 0), text, font=font)
    layer = Image.new("RGBA", (int((r - l) + 40 * s), int((b - t) + 40 * s)), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ox, oy = 20 * s - l, 20 * s - t
    ld.text((ox + 4 * s, oy + 5 * s), text, font=font, fill=TEXT_SHADOW)
    ld.text((ox, oy), text, font=font, fill=TEXT)
    layer = layer.rotate(8, resample=Image.BICUBIC, expand=True)        # counter-clockwise: rising to the right
    img.alpha_composite(layer, (int(216 * s - layer.size[0] / 2), int(104 * s - layer.size[1] / 2)))
    # Launchers mask the icon (circle, squircle ...): keep everything well inside the 66 dp safe zone,
    # which is why the artwork is scaled down a little and moved down (the "MCP" corner is the critical one)
    big = size * SS
    fit = img.resize((int(big * FIT), int(big * FIT)), Image.LANCZOS)
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    canvas.alpha_composite(fit, (int((big - fit.size[0]) / 2), int((big - fit.size[1]) / 2 + SHIFT * s)))
    return canvas.resize((size, size), Image.LANCZOS)


def preview(size=512):
    art = foreground(size)
    bg = Image.new("RGBA", (size, size))
    px = bg.load()
    for y in range(size):
        k = y / (size - 1)
        color = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * k) for i in range(3)) + (255,)
        for x in range(size):
            px[x, y] = color
    bg.alpha_composite(art)
    # round the corners like a launcher tile, and zoom in a little: the foreground has transparent margins
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size // 5, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out


def social_preview():
    """1280 x 640 image for the GitHub repository's social preview (uploaded by hand in the repo settings)."""
    w, h = 1280, 640
    img = Image.new("RGB", (w, h), (13, 17, 23))
    icon = preview(420)
    img.paste(icon, (90, 110), icon)
    d = ImageDraw.Draw(img)
    title = ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf", 76)
    sub = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 36)
    d.text((560, 195), "Broadcast-Remote", font=title, fill=(255, 255, 255))
    for i, line in enumerate(("Remote control for receive-only", "Bluetooth LE devices, from your phone", "or from an AI assistant (MCP)")):
        d.text((564, 310 + i * 52), line, font=sub, fill=(176, 196, 222))
    return img


def main():
    res = os.path.join(ROOT, "android", "res")
    for folder, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        path = os.path.join(res, "mipmap-" + folder)
        os.makedirs(path, exist_ok=True)
        foreground(px).save(os.path.join(path, "ic_launcher_foreground.png"), optimize=True)
    os.makedirs(os.path.join(res, "mipmap-anydpi-v26"), exist_ok=True)
    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    with open(os.path.join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    with open(os.path.join(res, "values", "ic_launcher_background.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="ic_launcher_background">%s</color>\n</resources>\n' % BG_FLAT)
    os.makedirs(os.path.join(res, "drawable-nodpi"), exist_ok=True)
    tile = Image.new("RGBA", (192, 192), (0, 0, 0, 0))                                      # icon in the title bar,
    tile.alpha_composite(preview(136), (28, 28))                                            # with a transparent margin
    tile.save(os.path.join(res, "drawable-nodpi", "ic_title.png"), optimize=True)
    preview().save(os.path.join(ROOT, "docs", "img", "icon.png"), optimize=True)
    social_preview().save(os.path.join(ROOT, "docs", "img", "social-preview.png"), optimize=True)
    print("icon written")


if __name__ == "__main__":
    main()
