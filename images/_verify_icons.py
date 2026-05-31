"""Build a contact sheet from the generated PNGs so the result can be eyeballed."""
from PIL import Image
import os

RES = r"d:\underdevelopment\SVD\app\src\main\res"
OUT = r"d:\underdevelopment\SVD\images\flash_icon_result.png"

CHECKER = (210, 214, 220, 255)
def bg(size):
    img = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    t = 8
    for y in range(0, size, t):
        for x in range(0, size, t):
            if (x // t + y // t) % 2 == 0:
                for j in range(y, min(y + t, size)):
                    for i in range(x, min(x + t, size)):
                        img.putpixel((i, j), CHECKER)
    return img

items = [
    ("xxxhdpi/ic_launcher.png", "launcher (squircle)"),
    ("xxxhdpi/ic_launcher_round.png", "round"),
    ("xxxhdpi/ic_launcher_foreground.png", "adaptive fg"),
    ("xxxhdpi/ic_launcher_monochrome.png", "monochrome"),
]
cell, pad = 200, 20
sheet = Image.new("RGBA", (cell * len(items) + pad * (len(items) + 1), cell + pad * 2), (245, 246, 248, 255))
x = pad
for rel, _ in items:
    p = os.path.join(RES, "mipmap-" + rel)
    im = Image.open(p).convert("RGBA").resize((cell, cell), Image.LANCZOS)
    base = bg(cell)
    base.alpha_composite(im)
    sheet.alpha_composite(base, (x, pad))
    x += cell + pad
sheet.save(OUT)
print("wrote", OUT, sheet.size)

# Report transparency / bbox sanity for the adaptive foreground
fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_foreground.png")).convert("RGBA")
print("foreground bbox (content extent):", fg.getbbox(), "of", fg.size)
