#!/usr/bin/env python3
"""Builds the README and docs art (banner, framed screens, chain diagram) in light and dark.

GitHub Markdown cannot style anything itself, so the styled parts are baked into
images. Each one ships a light and a dark variant that the page picks with
<picture> + prefers-color-scheme.

Inputs:
  docs/images/raw/<screen>-<light|dark>.jpg   phone captures (720 px wide, no status bar)
  --fonts DIR   Roboto-Regular.ttf, Roboto-Medium.ttf, Roboto-Bold.ttf, RobotoMono-Medium.ttf
                and Display.ttf (Bricolage Grotesque ExtraBold). All from Google Fonts
                (OFL / Apache-2.0); not committed.
Outputs:
  docs/images/hero-<light|dark>.png
  docs/images/<screen>-<light|dark>.jpg        rounded, on GitHub's page colour
  docs/images/chain-<light|dark>.png

The four banner frames are an illustration of the stages (negative, enlarger,
print, scan) made from the Looks capture with simple image operations. They are
not engine renders of each stage.

Usage: python3 tools/readme/make_readme_art.py --fonts /path/to/fonts
"""
import argparse
import os

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RAW = os.path.join(ROOT, "docs", "images", "raw")
OUT = os.path.join(ROOT, "docs", "images")

SCREENS = ["looks", "edit", "crop", "masks"]

# Film amber, the app's own accent when Material You is off.
THEMES = {
    "light": dict(
        page="#ffffff",  # GitHub light page
        card="#f6efe5",
        ink="#1f1a15",
        muted="#6b5f53",
        accent="#b8650f",
        line="#e4d8c8",
        strip="#15110d",
        stripHole="#3a3027",
        stripText="#d8c3a5",
    ),
    "dark": dict(
        page="#0d1117",  # GitHub dark page
        card="#1a1510",
        ink="#fbf1e4",
        muted="#c9b59c",
        accent="#f2a64a",
        line="#3a2f24",
        strip="#0b0806",
        stripHole="#2c241b",
        stripText="#c9b59c",
    ),
}

SCALE = 2  # sizes below are in px at 1x; images render at 2x for sharp display


def px(v):
    return int(round(v * SCALE))


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def load_fonts(d):
    def f(name, size):
        return ImageFont.truetype(os.path.join(d, name), px(size))

    return dict(
        display=lambda s: f("Display.ttf", s),
        regular=lambda s: f("Roboto-Regular.ttf", s),
        medium=lambda s: f("Roboto-Medium.ttf", s),
        bold=lambda s: f("Roboto-Bold.ttf", s),
        mono=lambda s: f("RobotoMono-Medium.ttf", s),
    )


def photo_from_looks():
    """The rendered photo area of the dark Looks capture, cropped to 3:2."""
    shot = Image.open(os.path.join(RAW, "looks-dark.jpg")).convert("RGB")
    w = shot.width
    # The preview sits between the top bar and the Looks sheet; take a 3:2 band from it.
    left, right = int(w * 0.13), int(w * 0.87)
    band_h = int((right - left) * 2 / 3)
    top = int(shot.height * 0.16)
    return shot.crop((left, top, right, top + band_h))


def stage_frames(photo):
    # Negative: inverted tones on an orange mask, the way a colour negative looks.
    neg = ImageOps.invert(ImageOps.grayscale(photo).convert("RGB")).point(lambda v: 40 + v * 0.75)
    neg = ImageChops.multiply(neg, Image.new("RGB", photo.size, (238, 146, 84)))
    # Enlarger: the negative's image projected as soft light, still faint on the paper.
    enl = ImageEnhance.Contrast(photo).enhance(0.45)
    enl = ImageEnhance.Brightness(enl).enhance(1.35).filter(ImageFilter.GaussianBlur(1.2))
    enl = ImageChops.multiply(enl, Image.new("RGB", photo.size, (255, 236, 214)))
    # Print: full density on paper, a little warmer and richer.
    prt = ImageEnhance.Contrast(photo).enhance(1.12)
    prt = ImageEnhance.Color(prt).enhance(1.12)
    # Scan: the final render as the app shows it.
    return [("Negative", neg), ("Enlarger", enl), ("Print", prt), ("Scan", photo)]


def hero(mode, fonts, photo):
    t = THEMES[mode]
    W, H = px(880), px(500)
    img = Image.new("RGBA", (W, H), t["page"])
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, W - 1, H - 1), px(24), fill=t["card"])

    x0 = px(44)
    d.text((x0, px(44)), "SPEKTRAFILM 1.0  ·  ANDROID", font=fonts["mono"](13), fill=t["accent"], anchor="lt")
    d.text((x0, px(72)), "Shoot digital.", font=fonts["display"](52), fill=t["ink"], anchor="lt")
    d.text((x0, px(130)), "Develop it on film.", font=fonts["display"](52), fill=t["ink"], anchor="lt")
    d.text(
        (x0, px(202)),
        "A spectral simulation of the whole darkroom on your phone:",
        font=fonts["regular"](18),
        fill=t["muted"],
        anchor="lt",
    )
    d.text(
        (x0, px(228)),
        "the negative, the enlarger, the paper and the scan.",
        font=fonts["regular"](18),
        fill=t["muted"],
        anchor="lt",
    )

    # Film strip with sprocket holes and four frames; the strip's height follows the frames.
    sx0, sx1, sy0 = px(28), W - px(28), px(272)
    fw_est = (sx1 - sx0 - px(28) - px(12) * 3) // 4
    sy1 = sy0 + px(24) + int(fw_est * 2 / 3) + px(44)
    d.rounded_rectangle((sx0, sy0, sx1, sy1), px(10), fill=t["strip"])
    hole_w, hole_h, step = px(10), px(6), px(20)
    for y in (sy0 + px(8), sy1 - px(14)):
        x = sx0 + px(12)
        while x + hole_w < sx1 - px(10):
            d.rounded_rectangle((x, y, x + hole_w, y + hole_h), px(1.5), fill=t["stripHole"])
            x += step
    frames = stage_frames(photo)
    gap = px(12)
    inner_x0, inner_x1 = sx0 + px(14), sx1 - px(14)
    fw = (inner_x1 - inner_x0 - gap * 3) // 4
    fh = int(fw * 2 / 3)
    fy = sy0 + px(24)
    for i, (label, im) in enumerate(frames):
        fx = inner_x0 + i * (fw + gap)
        tile = im.resize((fw, fh), Image.LANCZOS).convert("RGBA")
        tile.putalpha(rounded_mask((fw, fh), px(4)))
        img.alpha_composite(tile, (fx, fy))
        ly = fy + fh + px(10)
        d.text((fx, ly), label.upper(), font=fonts["mono"](11), fill=t["stripText"], anchor="lt")
        d.text((fx + fw, ly), f"0{i + 1}", font=fonts["mono"](11), fill=t["stripText"], anchor="rt")
    img.convert("RGB").save(os.path.join(OUT, f"hero-{mode}.png"), optimize=True)


def framed_screen(name, mode):
    t = THEMES[mode]
    shot = Image.open(os.path.join(RAW, f"{name}-{mode}.jpg")).convert("RGB")
    w = px(200)
    h = round(shot.height * w / shot.width)
    tile = shot.resize((w, h), Image.LANCZOS).convert("RGBA")
    r = px(24)
    tile.putalpha(rounded_mask((w, h), r))
    canvas = Image.new("RGBA", (w, h), t["page"])
    canvas.alpha_composite(tile)
    ImageDraw.Draw(canvas).rounded_rectangle((0, 0, w - 1, h - 1), r, outline=t["line"], width=px(1))
    canvas.convert("RGB").save(os.path.join(OUT, f"{name}-{mode}.jpg"), quality=88, optimize=True)


def chain(mode, fonts):
    t = THEMES[mode]
    stages = [
        ("01", "Negative", ["Each pixel becomes a spectrum", "and exposes the film's", "real emulsion layers."],
         [(194, 107, 42), (231, 161, 92)]),
        ("02", "Enlarger", ["The negative is projected", "through yellow, magenta", "and cyan filters."],
         [(233, 211, 106), (210, 91, 155), (79, 182, 200)]),
        ("03", "Print", ["Light lands on a virtual", "paper with its own", "measured curves."],
         [(244, 237, 225), (43, 37, 34)]),
        ("04", "Scan", ["The print is scanned", "into the colour space", "you export in."],
         [(91, 143, 214), (127, 199, 123), (231, 161, 92)]),
    ]
    W, H = px(880), px(168)
    img = Image.new("RGB", (W, H), t["page"])
    d = ImageDraw.Draw(img)
    gap = px(10)
    cw = (W - gap * 3) // 4
    for i, (num, title, lines, colours) in enumerate(stages):
        x = i * (cw + gap)
        d.rounded_rectangle((x, 0, x + cw - 1, H - 1), px(14), fill=t["card"], outline=t["line"], width=px(1))
        d.text((x + px(16), px(16)), num, font=fonts["mono"](11), fill=t["accent"], anchor="lt")
        d.text((x + px(16), px(34)), title, font=fonts["bold"](18), fill=t["ink"], anchor="lt")
        for j, line in enumerate(lines):
            d.text((x + px(16), px(64) + j * px(19)), line, font=fonts["regular"](13), fill=t["muted"], anchor="lt")
        # Gradient swatch along the bottom of the card.
        sw_y0, sw_y1 = H - px(22), H - px(14)
        sw_x0, sw_x1 = x + px(16), x + cw - px(16)
        grad = Image.new("RGB", (sw_x1 - sw_x0, sw_y1 - sw_y0))
        gd = ImageDraw.Draw(grad)
        n = len(colours) - 1
        for gx in range(grad.width):
            p = gx / max(1, grad.width - 1) * n
            k = min(int(p), n - 1)
            f = p - k
            c = tuple(int(colours[k][c] * (1 - f) + colours[k + 1][c] * f) for c in range(3))
            gd.line((gx, 0, gx, grad.height), fill=c)
        img.paste(grad, (sw_x0, sw_y0), rounded_mask(grad.size, px(4)))
        if i < 3:
            ax = x + cw + gap // 2
            d.text((ax, H // 2), "›", font=fonts["bold"](16), fill=t["accent"], anchor="mm")
    img.save(os.path.join(OUT, f"chain-{mode}.png"), optimize=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fonts", required=True)
    args = ap.parse_args()
    fonts = load_fonts(args.fonts)
    os.makedirs(OUT, exist_ok=True)
    photo = photo_from_looks()
    for mode in THEMES:
        hero(mode, fonts, photo)
        chain(mode, fonts)
        for name in SCREENS:
            framed_screen(name, mode)
    print("wrote", sorted(f for f in os.listdir(OUT) if not os.path.isdir(os.path.join(OUT, f))))


if __name__ == "__main__":
    main()
