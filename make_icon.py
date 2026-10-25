"""Draws the Apexview launcher icon: a white F1 car in side profile on a red circle.

Writes Android adaptive-icon layers (108dp foreground PNGs per density; the red background is a
color resource) and legacy square/round icons, plus a preview.
Usage: python make_icon.py <res dir> <preview.png>
"""
import os
import sys

from PIL import Image, ImageDraw

RES, PREVIEW = sys.argv[1], sys.argv[2]
S = 1728                      # 108dp at 16x, downscaled later for smooth edges
C = S // 2
RED = (212, 0, 0)
WHITE = (255, 255, 255)

# The car in its own units, facing right: x 0..100, y 0..30 (y down).
BODY = [(6, 22), (6, 15), (14, 12.5), (26, 11.5), (31, 8), (37, 8), (39, 12), (46, 13),
        (58, 14.5), (74, 17.5), (90, 20.5), (93, 22), (93, 25), (6, 25)]
AIRBOX = [(30, 12.5), (32.5, 6.5), (36.5, 6.5), (37.5, 12)]
REAR_WING = [(0, 4.5), (12, 4.5), (12, 8.5), (6.5, 8.5), (6.5, 20), (2.5, 20), (2.5, 8.5), (0, 8.5)]
FRONT_WING = [(80, 24.5), (99, 24.5), (99, 22), (100, 22), (100, 26.5), (80, 26.5)]
HALO = [(37, 12.5), (40, 10.5), (46, 11), (46.5, 12), (40.5, 12), (38.5, 13.5)]
HELMET = (40.5, 11.2, 2.7)    # cx, cy, r
WHEELS = [(19, 23.2, 8.8), (80, 23.4, 8.2)]
GAP = 1.1                     # red ring that separates each wheel from the body


def foreground() -> Image.Image:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    width = S * 0.54          # wide enough to read at launcher size, wings clear of the 66dp circle
    k = width / 100
    ox = C - width / 2
    oy = C - 18 * k           # centers the car's mass, which sits low

    def pts(shape):
        return [(ox + x * k, oy + y * k) for x, y in shape]

    for shape in (BODY, AIRBOX, REAR_WING, FRONT_WING, HALO):
        d.polygon(pts(shape), fill=WHITE)
    hx, hy, hr = HELMET
    d.ellipse((ox + (hx - hr) * k, oy + (hy - hr) * k, ox + (hx + hr) * k, oy + (hy + hr) * k), fill=WHITE)
    for wx, wy, wr in WHEELS:
        cx, cy = ox + wx * k, oy + wy * k
        ring = (wr + GAP) * k
        d.ellipse((cx - ring, cy - ring, cx + ring, cy + ring), fill=RED)
        d.ellipse((cx - wr * k, cy - wr * k, cx + wr * k, cy + wr * k), fill=WHITE)
        hub = wr * 0.32 * k
        d.ellipse((cx - hub, cy - hub, cx + hub, cy + hub), fill=RED)
    return img


def save(img: Image.Image, path: str, size: int):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


fg = foreground()
densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# Legacy icons show the middle 72dp of the 108dp canvas.
crop = int(S * 18 / 108)
full = Image.new("RGBA", (S, S), RED + (255,))
full.alpha_composite(fg)
legacy = full.crop((crop, crop, S - crop, S - crop))
square_mask = Image.new("L", legacy.size, 0)
ImageDraw.Draw(square_mask).rounded_rectangle((0, 0, legacy.size[0] - 1, legacy.size[1] - 1), radius=legacy.size[0] // 5, fill=255)
round_mask = Image.new("L", legacy.size, 0)
ImageDraw.Draw(round_mask).ellipse((0, 0, legacy.size[0] - 1, legacy.size[1] - 1), fill=255)

for name, k in densities.items():
    save(fg, f"{RES}/mipmap-{name}/ic_launcher_foreground.png", round(108 * k))
    sq = legacy.copy(); sq.putalpha(square_mask)
    save(sq, f"{RES}/mipmap-{name}/ic_launcher.png", round(48 * k))
    rd = legacy.copy(); rd.putalpha(round_mask)
    save(rd, f"{RES}/mipmap-{name}/ic_launcher_round.png", round(48 * k))

# Preview: how the launcher's circle mask shows it.
circle = Image.new("L", (S, S), 0)
r = int(S * 33 / 108)  # the adaptive icon's visible circle is 66dp across
ImageDraw.Draw(circle).ellipse((C - r, C - r, C + r, C + r), fill=255)
out = Image.new("RGBA", (S, S), (240, 240, 240, 255))
out.paste(full, (0, 0), circle)
out.resize((512, 512), Image.LANCZOS).save(PREVIEW)
print("ok")
