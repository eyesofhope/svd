"""
Generate the Flash launcher icon set (Concept B: play-triangle forged from a lightning bolt).
Reproduces the approved SVG geometry faithfully at every Android density.
Run with system Python (Pillow installed): py -3 images/_gen_icons.py
"""
import os
from PIL import Image, ImageDraw

RES = r"d:\underdevelopment\SVD\app\src\main\res"
IMAGES = r"d:\underdevelopment\SVD\images"

TEAL = (6, 182, 169, 255)          # #06B6A9 brand background
WHITE = (255, 255, 255, 255)
SS = 8                              # supersampling factor for smooth edges

# Approved Concept B glyph path, in the 512x512 design viewport.
PLAY_BOLT = [
    (196, 150), (196, 246), (150, 246), (240, 300),
    (196, 300), (196, 362), (350, 256),
]

def scaled(points, size):
    f = size / 512.0
    return [(x * f, y * f) for (x, y) in points]

def render_glyph(size, fill):
    """White (or given) play-bolt glyph on transparent canvas, supersampled."""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon(scaled(PLAY_BOLT, big), fill=fill)
    return img.resize((size, size), Image.LANCZOS)

def squircle_mask(size, radius_ratio=0.222):
    big = size * SS
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    r = int(big * radius_ratio)
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=255)
    return m.resize((size, size), Image.LANCZOS)

def circle_mask(size):
    big = size * SS
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, big - 1, big - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)

def legacy_icon(size, mask):
    """Flat teal background + white glyph, clipped by the given mask."""
    base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg = Image.new("RGBA", (size, size), TEAL)
    base = Image.composite(bg, base, mask)
    glyph = render_glyph(size, WHITE)
    base.alpha_composite(glyph)
    # re-clip in case glyph bleeds (it doesn't, but safe)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out = Image.composite(base, out, mask)
    return out

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", os.path.relpath(path, RES))

# Legacy launcher icon sizes (px)
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# Adaptive foreground / monochrome sizes (108dp * density)
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

for dens, size in LEGACY.items():
    folder = os.path.join(RES, f"mipmap-{dens}")
    save(legacy_icon(size, squircle_mask(size)), os.path.join(folder, "ic_launcher.png"))
    save(legacy_icon(size, circle_mask(size)), os.path.join(folder, "ic_launcher_round.png"))

for dens, size in ADAPTIVE.items():
    folder = os.path.join(RES, f"mipmap-{dens}")
    # adaptive foreground: white glyph on transparent (background supplied by color)
    save(render_glyph(size, WHITE), os.path.join(folder, "ic_launcher_foreground.png"))
    # monochrome (themed icon): solid black glyph on transparent; system tints it
    save(render_glyph(size, (0, 0, 0, 255)), os.path.join(folder, "ic_launcher_monochrome.png"))

# Play Store master (512) + a quick brand mark for the listing
save(legacy_icon(512, squircle_mask(512)), os.path.join(IMAGES, "flash_icon_master_512.png"))
print("done")
