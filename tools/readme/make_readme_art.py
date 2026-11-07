#!/usr/bin/env python3
"""Builds the README art (hero banner, framed screenshots) in light and dark.

The README follows Material 3: colour roles from one tonal scheme, the M3 shape
scale (screens at extra-large corners, chips at full), Roboto type and Material
Symbols. GitHub markdown cannot style anything itself, so the styled parts are
baked into images and each one ships a light and a dark variant that the README
picks with <picture> + prefers-color-scheme.

Inputs:
  docs/screenshots/raw/<name>-<light|dark>.jpg   phone captures, status bar cropped
  --fonts DIR   Roboto-Regular.ttf, Roboto-Medium.ttf and MaterialSymbolsRounded.ttf
                (both from Google Fonts, OFL / Apache-2.0; not committed)
Outputs:
  docs/readme/hero-<light|dark>.png
  docs/readme/<name>-<light|dark>.jpg            rounded, on GitHub's page colour

Usage: python3 tools/readme/make_readme_art.py --fonts /path/to/fonts
Needs Pillow built with libraqm (for Material Symbols ligatures).
"""
import argparse
import math
import os

from PIL import Image, ImageDraw, ImageFont, features

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RAW = os.path.join(ROOT, "docs", "screenshots", "raw")
OUT = os.path.join(ROOT, "docs", "readme")

# Tonal-spot roles generated (Material Color Utilities) from the source colour the
# app's dynamic scheme produced on the capture device (#4878F6), so the art matches
# the screenshots. primary is the device's own value for the same reason.
SCHEMES = {
    "light": dict(
        page="#ffffff",  # GitHub light page
        surfaceContainer="#eeedf6",
        surfaceContainerHigh="#e8e7f1",
        primary="#4878f6",
        onPrimary="#ffffff",
        primaryContainer="#dce2ff",
        onPrimaryContainer="#2e3c68",
        secondaryContainer="#dde1f9",
        onSecondaryContainer="#4c5164",
        tertiaryContainer="#f3dcff",
        onSurface="#1b1b21",
        onSurfaceVariant="#5d5f68",
        outlineVariant="#c6c6d0",
    ),
    "dark": dict(
        page="#0d1117",  # GitHub dark page
        surfaceContainer="#181920",
        surfaceContainerHigh="#1e1f26",
        primary="#9bb6f9",
        onPrimary="#1a2c61",
        primaryContainer="#2f3f6e",
        onPrimaryContainer="#dce2ff",
        secondaryContainer="#363b4d",
        onSecondaryContainer="#d4d8ee",
        tertiaryContainer="#4f3d5f",
        onSurface="#e4e4f0",
        onSurfaceVariant="#aaaab5",
        outlineVariant="#464750",
    ),
}

SCALE = 2  # every size below is in dp; images render at 2x for sharp retina display
SCREENS = ["look", "film", "print", "export"]


def dp(v):
    return int(round(v * SCALE))


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def framed(shot, width_dp, radius_dp, outline):
    """A screenshot clipped to the M3 extra-large corner with a 1dp outlineVariant edge."""
    w = dp(width_dp)
    h = round(shot.height * w / shot.width)
    img = shot.resize((w, h), Image.LANCZOS).convert("RGBA")
    r = dp(radius_dp)
    img.putalpha(rounded_mask((w, h), r))
    ImageDraw.Draw(img).rounded_rectangle((0, 0, w - 1, h - 1), r, outline=outline, width=dp(1))
    return img


def cookie(size, lobes, depth, fill):
    """M3 shape-library style 'cookie': a circle with soft scalloped edge (decoration only)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    c = size / 2
    base = size / 2 / (1 + depth)
    pts = []
    for i in range(720):
        t = 2 * math.pi * i / 720
        rr = base * (1 + depth * math.cos(lobes * t))
        pts.append((c + rr * math.cos(t), c + rr * math.sin(t)))
    ImageDraw.Draw(img).polygon(pts, fill=fill)
    return img


def hero(mode, fonts):
    s = SCHEMES[mode]
    W, H = dp(800), dp(440)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Container: surfaceContainer, extra-large (28dp) corners.
    d.rounded_rectangle((0, 0, W - 1, H - 1), dp(28), fill=s["surfaceContainer"])

    raqm = ImageFont.Layout.RAQM
    f_label = ImageFont.truetype(fonts["medium"], dp(14), layout_engine=raqm)
    f_display = ImageFont.truetype(fonts["regular"], dp(52), layout_engine=raqm)
    f_headline = ImageFont.truetype(fonts["regular"], dp(22), layout_engine=raqm)
    f_chip = ImageFont.truetype(fonts["medium"], dp(14), layout_engine=raqm)
    f_icon = ImageFont.truetype(fonts["symbols"], dp(18), layout_engine=raqm)

    x0 = dp(40)

    # Assist chip style label: secondaryContainer, full shape.
    label = "Open source  ·  GPLv3  ·  Android 7.0+"
    lw = d.textlength(label, font=f_label)
    d.rounded_rectangle((x0, dp(48), x0 + lw + dp(32), dp(80)), dp(16), fill=s["secondaryContainer"])
    d.text((x0 + dp(16), dp(64)), label, font=f_label, fill=s["onSecondaryContainer"], anchor="lm")

    # Display small for the name, headline small for the promise.
    d.text((x0, dp(104)), "Spektrafilm", font=f_display, fill=s["onSurface"], anchor="lt")
    for i, line in enumerate(["Spectral film simulation", "for Android"]):
        d.text((x0, dp(178) + i * dp(30)), line, font=f_headline, fill=s["onSurfaceVariant"], anchor="lt")

    # The analog chain as a connected group: full outer corners, small inner ones, every
    # step on surfaceContainerHigh with a primary icon (it is a sequence, not a selection).
    steps = [("camera_roll", "Negative"), ("wb_incandescent", "Enlarger"), ("photo", "Print"), ("document_scanner", "Scan")]
    y0, y1 = dp(276), dp(316)
    x = x0
    gap = dp(2)
    seg_w = []
    for icon, text in steps:
        seg_w.append(dp(12) + dp(18) + dp(6) + d.textlength(text, font=f_chip) + dp(14))
    for i, ((icon, text), w) in enumerate(zip(steps, seg_w)):
        first, last = i == 0, i == len(steps) - 1
        fill = s["surfaceContainerHigh"]
        ink = s["onSurface"]
        # Draw a rounded rect with full ends on the outside, 8dp on the inside.
        big, small = dp(20), dp(8)
        box = (x, y0, x + w, y1)
        d.rounded_rectangle(box, small, fill=fill)
        if first:
            d.rounded_rectangle((x, y0, x + big * 2, y1), big, fill=fill)
            d.rectangle((x + big, y0, x + big * 2, y1), fill=fill)
        if last:
            d.rounded_rectangle((x + w - big * 2, y0, x + w, y1), big, fill=fill)
            d.rectangle((x + w - big * 2, y0, x + w - big, y1), fill=fill)
        cy = (y0 + y1) // 2
        d.text((x + dp(12), cy), icon, font=f_icon, fill=s["primary"], anchor="lm")
        d.text((x + dp(12) + dp(18) + dp(6), cy), text, font=f_chip, fill=ink, anchor="lm")
        x += w + gap

    foot = "Film modeling powered by spektrafilm"
    d.text((x0, dp(372)), foot, font=f_label, fill=s["onSurfaceVariant"], anchor="lt")

    # Decoration: one cookie shape in primaryContainer behind the phones (expressive accent).
    ck = cookie(dp(330), 9, 0.06, s["primaryContainer"])
    img.alpha_composite(ck, (dp(470), dp(60)))
    tc = cookie(dp(96), 7, 0.08, s["tertiaryContainer"])
    img.alpha_composite(tc, (dp(712), dp(28)))

    # Three phones, centre one raised, clipped to the container.
    phones = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    names = ["look", "film", "export"]
    xs = [dp(452), dp(548), dp(644)]
    ys = [dp(92), dp(52), dp(92)]
    order = [0, 2, 1]
    for k in order:
        shot = Image.open(os.path.join(RAW, f"{names[k]}-{mode}.jpg"))
        f = framed(shot, 132, 18, s["outlineVariant"])
        phones.alpha_composite(f, (xs[k], ys[k]))
    clip = rounded_mask((W, H), dp(28))
    phones.putalpha(Image.composite(phones.getchannel("A"), Image.new("L", (W, H), 0), clip))
    img.alpha_composite(phones)
    return img


def gallery(mode):
    s = SCHEMES[mode]
    os.makedirs(OUT, exist_ok=True)
    for name in SCREENS:
        shot = Image.open(os.path.join(RAW, f"{name}-{mode}.jpg"))
        f = framed(shot, 280, 24, s["outlineVariant"])
        bg = Image.new("RGBA", f.size, s["page"])
        bg.alpha_composite(f)
        bg.convert("RGB").save(os.path.join(OUT, f"{name}-{mode}.jpg"), quality=86, optimize=True, progressive=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fonts", required=True)
    a = ap.parse_args()
    if not features.check("raqm"):
        raise SystemExit("Pillow needs libraqm for Material Symbols ligatures")
    fonts = {
        "regular": os.path.join(a.fonts, "Roboto-Regular.ttf"),
        "medium": os.path.join(a.fonts, "Roboto-Medium.ttf"),
        "symbols": os.path.join(a.fonts, "MaterialSymbolsRounded.ttf"),
    }
    os.makedirs(OUT, exist_ok=True)
    for mode in SCHEMES:
        hero(mode, fonts).save(os.path.join(OUT, f"hero-{mode}.png"), optimize=True)
        gallery(mode)


if __name__ == "__main__":
    main()
