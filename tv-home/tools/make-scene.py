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
FILL = 0
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


def sky_mask(img, step, far, floor=1.0, spread=6, paint=0.0, thin=0, smooth=0.0, treeline=0.0):
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
    # fog down to the trees (--treeline): from the horizon floor, each column that is still sky
    # carries on down through anything without texture (haze, fog) and stops at the first rows
    # with real texture (treetops), so the trees' own ragged edge is the skyline, not a straight line
    if treeline:
        g = a.mean(axis=2)
        m = ndimage.uniform_filter(g, 9); m2 = ndimage.uniform_filter(g * g, 9)
        rough = np.sqrt(np.maximum(m2 - m * m, 0)) > treeline
        # a few textured rows in a row, so a speck in the fog does not stop it
        rough = ndimage.binary_opening(rough, structure=np.ones((5, 1), bool))
        start = max(0, int(floor * H) - 2)
        edge = np.full(W, start)
        # columns that are sky at the floor, or just above it (the colour test can stop a few rows short)
        open_col = sky[max(0, start - 40):start + 1].any(axis=0)
        for x in range(W):
            if not open_col[x]:
                continue
            hit = np.nonzero(rough[start:, x])[0]
            edge[x] = start + (hit[0] if len(hit) else 0)
        edge = ndimage.median_filter(edge, size=5)   # treetops, not single-pixel spikes
        # narrow blocks rising out of the fog are specks of texture in the haze, not trees (crowns
        # are wider): close them over
        edge = ndimage.grey_closing(edge, size=25)
        for x in range(W):
            if open_col[x]:
                top_sky = start - 40 + int(np.nonzero(sky[max(0, start - 40):start + 1, x])[0].max()) if start >= 40 else start
                sky[min(top_sky, start):edge[x], x] = True
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
    # narrow slivers of "sky" cutting down into the scenery (a snow face as smooth as the sky):
    # filled, they are scenery (--fill: widest sliver, in pixels)
    if FILL:
        fg = ndimage.binary_closing(fg, structure=np.ones((1, FILL), bool), border_value=0) | fg
        fg = ndimage.binary_closing(fg, structure=np.ones((FILL // 2, 1), bool), border_value=0) | fg
    # scenery floating in the sky (stars, bits of cloud) is not scenery: only what reaches the
    # bottom of the picture, or the sides, stays
    lab, n = ndimage.label(fg)
    if n:
        edges = set(np.unique(lab[-1])) | set(np.unique(lab[:, 0])) | set(np.unique(lab[:, -1]))
        fg = np.isin(lab, list(edges - {0}))
    # one pixel in from the edge: the skyline's last pixels are half sky and showed as a halo
    return ~fg


def fade(img, sky, floor, rough, hard):
    """Trees in fog (with --treeline): below the horizon floor, in the columns that were sky there,
    the trees grow solid as their texture shows through the mist, so the clock's digits melt into
    the foggy forest instead of meeting a hard edge. Once solid, solid all the way down."""
    a = np.asarray(img.filter(ImageFilter.GaussianBlur(1.6)), dtype=np.float32).mean(axis=2)
    m = ndimage.uniform_filter(a, 9); m2 = ndimage.uniform_filter(a * a, 9)
    tex = np.sqrt(np.maximum(m2 - m * m, 0))
    lo, hi = rough * 0.5, rough * 1.8
    soft = ndimage.gaussian_filter(np.clip((tex - lo) / (hi - lo), 0, 1), 5)
    start = max(1, int(floor * H) - 2)
    soft[start:] = np.maximum.accumulate(soft[start:], axis=0)
    open_col = sky[start - 1]
    out = hard.copy()
    region = out[start:, open_col]
    out[start:, open_col] = np.maximum(np.minimum(region, soft[start:, open_col]), 0)
    return out


def refine(img, sky, band=10):
    """The exact edge (alpha matting): the rough sky mask, shrunk away from its edge on both sides,
    says what is surely sky and surely scenery; in the band between, every pixel's share of scenery
    is worked out from the photo's own colours, so sharp ridges, needle peaks, building corners and
    single trees keep their real outline instead of a smoothed one."""
    from pymatting import estimate_alpha_cf
    fg = ~sky
    sure_fg = ndimage.binary_erosion(fg, iterations=band, border_value=1)
    sure_sky = ndimage.binary_erosion(sky, iterations=band, border_value=1)
    tri = np.full(sky.shape, 0.5)
    tri[sure_fg] = 1.0; tri[sure_sky] = 0.0
    rgb = np.asarray(img, dtype=np.float64) / 255.0
    a = estimate_alpha_cf(rgb, tri)
    a = np.clip(a, 0, 1)
    # tidy the matte: near-certain values snap, so no faint haze of sky is drawn over the clock
    a[a < 0.15] = 0; a[a > 0.8] = 1
    return a


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("photo"); ap.add_argument("id")
    ap.add_argument("--top", type=float, default=0.1)
    ap.add_argument("--step", type=float, default=9.0)
    ap.add_argument("--far", type=float, default=46.0)
    ap.add_argument("--floor", type=float, default=1.0, help="the horizon, as a share of the height: nothing below it is sky")
    ap.add_argument("--paint", type=float, default=0.0, help="also sky: anything above the horizon this close to the top's colour")
    ap.add_argument("--smooth", type=float, default=0.0, help="also sky: texture-free areas (fog, cloud) reaching the top; how calm, e.g. 4")
    ap.add_argument("--treeline", type=float, default=0.0, help="from the horizon floor, carry the sky down through fog to the first texture this rough (trees), e.g. 3.5")
    ap.add_argument("--thin", type=int, default=0, help="drop anything thinner than this many pixels (wires, poles) from the cut-out")
    ap.add_argument("--spread", type=int, default=6, help="how far the sky may spread sideways per row (around wires, posts)")
    ap.add_argument("--bright", type=float, default=1.3, help="how much brighter the clock's frosted fill is than the photo")
    ap.add_argument("--band", type=int, default=10, help="how far either side of the rough edge the exact edge is looked for, in pixels")
    ap.add_argument("--no-refine", action="store_true", help="skip the exact-edge step (faster, softer edge)")
    ap.add_argument("--preview")
    ap.add_argument("--fill", type=int, default=0, help="fill slivers of sky this narrow that cut down into the scenery, e.g. 40")
    o = ap.parse_args()
    global FILL; FILL = o.fill
    os.makedirs(OUT, exist_ok=True)
    img = band(Image.open(o.photo), o.top)
    sky = sky_mask(img, o.step, o.far, o.floor, o.spread, o.paint, o.thin, o.smooth, o.treeline)

    name = os.path.join(OUT, "scene-" + o.id)
    img.save(name + ".jpg", quality=86, optimize=True, progressive=True)
    # the clock's frosted fill: blurred, a little brighter and richer
    glass = img.filter(ImageFilter.GaussianBlur(22))
    glass = ImageEnhance.Brightness(ImageEnhance.Color(glass).enhance(1.05)).enhance(o.bright)
    glass.save(name + "-glass.jpg", quality=80, optimize=True)
    # the cut-out: the photo where it is not sky, its edge softened by a pixel
    if o.no_refine:
        a = np.asarray(Image.fromarray(((~sky) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.0))) / 255.0
    else:
        a = refine(img, sky, o.band)
    if o.treeline:
        a = fade(img, sky, o.floor, o.treeline, a)
    alpha = Image.fromarray((a * 255).astype(np.uint8))
    if not o.no_refine:
        # the edge's own colours, without the sky mixed in, so no sky-coloured fringe is drawn
        from pymatting import estimate_foreground_ml
        f = estimate_foreground_ml(np.asarray(img, dtype=np.float64) / 255.0, a)
        img_fg = Image.fromarray((np.clip(f, 0, 1) * 255).astype(np.uint8))
    else:
        img_fg = img
    fg = img_fg.copy(); fg.putalpha(alpha)
    fg.save(name + "-fg.webp", quality=88, method=6)
    share = 100 * sky.mean()
    print(f"{o.id}: sky {share:.0f}% of the picture; skyline from y={np.argmin(sky.mean(axis=1) > 0.5)} px")
    if o.preview:
        os.makedirs(o.preview, exist_ok=True)
        check = Image.new("RGB", (W, H), (255, 0, 200)); check.paste(fg, (0, 0), fg)
        check.resize((960, 540)).save(os.path.join(o.preview, o.id + "-check.jpg"), quality=85)
        check.save(os.path.join(o.preview, o.id + "-full.png"))


if __name__ == "__main__":
    sys.exit(main())
