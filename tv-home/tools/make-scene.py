#!/usr/bin/env python3
"""Make a home-screen scene from your own photo (fork addition): the photo, the frosted copy the
glass clock is filled with, and the cut-out of everything in front of the sky, drawn over the clock
so the skyline overlaps its digits (the built-in scenes' effect).

    .venv/bin/python tv-home/tools/make-scene.py PHOTO ID [--top 0.1] [--step 9] [--far 46] [--floor 1] [--preview DIR]

  PHOTO   any photo Pillow reads (JPEG, PNG, HEIC); portrait ones are cut to a 16:9 band
  ID      the scene's name, e.g. "rocks": writes backgrounds/mine/scene-rocks.jpg, -glass.jpg, -fg.webp
  --top   where the 16:9 band starts, as a share of the photo's height (0 = the top)
  --step  how much the sky may change from one row to the next and still be sky
  --far   how far it may drift from the sky just above (stops it running into a hazy sea)
  --preview DIR  also writes DIR/<ID>-check.jpg: the cut-out over magenta, to judge the edge

The sky is found by growing it down from the top edge: a pixel is sky when it is close to the sky
just above it (and beside it), so a smooth gradient (a sunset) stays sky while a skyline, which
changes sharply, stops it. Nothing leaves the computer; location data (EXIF) is not copied.
"""
import argparse
import os
import sys

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter, ImageOps
from scipy import ndimage

try:
    import pillow_heif
    pillow_heif.register_heif_opener()
except ImportError:
    pass

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web", "backgrounds", "mine")


def band(img, top):
    img = ImageOps.exif_transpose(img).convert("RGB")
    w, h = img.size
    bh = round(w * 9 / 16)
    if bh <= h:   # taller than 16:9: a band of the full width
        y = round(min(max(top, 0.0), 1.0) * (h - bh))
        img = img.crop((0, y, w, y + bh))
    else:         # wider: the middle
        bw = round(h * 16 / 9)
        x = (w - bw) // 2
        img = img.crop((x, 0, x + bw, h))
    return img.resize((W, H), Image.LANCZOS)


def sky_mask(img, step, far, floor=1.0, spread=6, paint=0.0, thin=0, smooth=0.0):
    a = np.asarray(img.filter(ImageFilter.GaussianBlur(1.6)), dtype=np.float32)
    sky = np.zeros((H, W), bool)
    # the top row: whatever is close to its typical colour
    top = np.median(a[0], axis=0)
    sky[0] = np.linalg.norm(a[0] - top, axis=1) < far
    ref = a[0].copy()   # per column, the sky colour seen most recently
    for y in range(1, min(H, int(floor * H))):   # below the horizon (--floor) nothing is sky
        near = np.linalg.norm(a[y] - a[y - 1], axis=1) < step
        drift = np.linalg.norm(a[y] - ref, axis=1) < far
        ok = near & drift
        row = ok & (sky[y - 1] | np.roll(sky[y - 1], 1) | np.roll(sky[y - 1], -1))
        # spread along the row to neighbours that are just as sky-like (around the sides of things)
        for _ in range(spread):
            grow = ok & (np.roll(row, 1) | np.roll(row, -1))
            if (grow == row).all():
                break
            row = row | grow
        sky[y] = row
        ref[row] = 0.85 * ref[row] + 0.15 * a[y][row]
    # sky walled in by wires or branches: anything above the horizon this close to the sky's
    # colour at the top (--paint) is sky too
    if paint:
        rows = np.arange(H)[:, None] < int(floor * H)
        sky |= rows & (np.linalg.norm(a - top, axis=2) < paint)
    # fog and cloud (--smooth): whatever has no texture and reaches the top is sky too, whatever
    # its colour (the colour test stops where blue sky turns to fog; texture does not change there)
    if smooth:
        g = a.mean(axis=2)
        m = ndimage.uniform_filter(g, 15); m2 = ndimage.uniform_filter(g * g, 15)
        calm = np.sqrt(np.maximum(m2 - m * m, 0)) < smooth
        calm &= np.arange(H)[:, None] < int(floor * H)
        lab, n = ndimage.label(calm | sky)
        if n:
            sky |= np.isin(lab, list(set(np.unique(lab[0])) - {0})) & calm
    # wires and poles across the sky (--thin): too thin to be scenery, they leave the cut-out
    if thin:
        fg = ~sky
        fg = ndimage.binary_opening(fg, structure=np.ones((1, thin), bool))
        fg = ndimage.binary_opening(fg, structure=np.ones((thin, 1), bool))
        sky = ~fg
    # tidy: no pin-holes in the sky, no stray specks of sky below the skyline
    sky = ndimage.binary_closing(sky, iterations=2, border_value=1)   # past the edge counts as sky, or the top row is lost
    lab, n = ndimage.label(sky)
    if n:
        keep = set(np.unique(lab[0])) - {0}
        sky = np.isin(lab, list(keep))
    fg = ~sky
    fg = ndimage.binary_opening(fg, iterations=1)
    # one pixel in from the edge: the skyline's last pixels are half sky and showed as a halo
    fg = ndimage.binary_erosion(fg, iterations=1, border_value=1)
    return ~fg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("photo"); ap.add_argument("id")
    ap.add_argument("--top", type=float, default=0.1)
    ap.add_argument("--step", type=float, default=9.0)
    ap.add_argument("--far", type=float, default=46.0)
    ap.add_argument("--floor", type=float, default=1.0, help="the horizon, as a share of the height: nothing below it is sky")
    ap.add_argument("--paint", type=float, default=0.0, help="also sky: anything above the horizon this close to the top's colour")
    ap.add_argument("--smooth", type=float, default=0.0, help="also sky: texture-free areas (fog, cloud) reaching the top; how calm, e.g. 4")
    ap.add_argument("--thin", type=int, default=0, help="drop anything thinner than this many pixels (wires, poles) from the cut-out")
    ap.add_argument("--spread", type=int, default=6, help="how far the sky may spread sideways per row (around wires, posts)")
    ap.add_argument("--bright", type=float, default=1.3, help="how much brighter the clock's frosted fill is than the photo")
    ap.add_argument("--preview")
    o = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    img = band(Image.open(o.photo), o.top)
    sky = sky_mask(img, o.step, o.far, o.floor, o.spread, o.paint, o.thin, o.smooth)

    name = os.path.join(OUT, "scene-" + o.id)
    img.save(name + ".jpg", quality=86, optimize=True, progressive=True)
    # the clock's frosted fill: blurred, a little brighter and richer
    glass = img.filter(ImageFilter.GaussianBlur(22))
    glass = ImageEnhance.Brightness(ImageEnhance.Color(glass).enhance(1.05)).enhance(o.bright)
    glass.save(name + "-glass.jpg", quality=80, optimize=True)
    # the cut-out: the photo where it is not sky, its edge softened by a pixel
    alpha = Image.fromarray(((~sky) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0))
    fg = img.copy(); fg.putalpha(alpha)
    fg.save(name + "-fg.webp", quality=88, method=6)
    share = 100 * sky.mean()
    print(f"{o.id}: sky {share:.0f}% of the picture; skyline from y={np.argmin(sky.mean(axis=1) > 0.5)} px")
    if o.preview:
        os.makedirs(o.preview, exist_ok=True)
        check = Image.new("RGB", (W, H), (255, 0, 200)); check.paste(fg, (0, 0), fg)
        check.resize((960, 540)).save(os.path.join(o.preview, o.id + "-check.jpg"), quality=85)


if __name__ == "__main__":
    sys.exit(main())
