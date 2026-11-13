"""Writes the website's social preview (og:image and GitHub social preview, 1280x640) with
the current home screen: a frame of the promo video, cropped to its UI card, next to the
brand, headline and tagline.

Usage: scripts/media/make-social-preview.py (needs ffmpeg and Pillow; writes
website/assets/social-preview.png)
"""
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
VIDEO = ROOT / "website" / "assets" / "promo" / "glacier-promo.mp4"
FONT = ROOT / "core" / "designsystem" / "src" / "main" / "res" / "font" / "google_sans.ttf"
OUT = ROOT / "website" / "assets" / "social-preview.png"

FRAME_AT = "5.5"  # seconds: the home screen, settled, no cross-fade
# The UI card inside the 1280x720 frame, inset by a pixel to drop its anti-aliased edge.
CARD_BOX = (129, 128, 1151, 701)
CARD_RADIUS = 22  # corner radius in frame pixels
W, H = 1280, 640
ACCENT = (125, 199, 217)
WHITE = (238, 248, 250)
MUTED = (122, 150, 165)
SS = 4  # supersampling for shapes


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_axes([weight])
    return f


def background():
    top, bottom, glow = (6, 12, 20), (8, 22, 30), (14, 42, 51)
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(W):
            t = y / (H - 1)
            base = [top[i] + (bottom[i] - top[i]) * t for i in range(3)]
            # soft glow in the lower left corner
            d = (((x - 60) / 700) ** 2 + ((y - 640) / 380) ** 2) ** 0.5
            g = max(0.0, 1 - d) ** 2 * 0.8
            px[x, y] = tuple(int(base[i] + (glow[i] - base[i]) * g) for i in range(3))
    return img


def grab_frame(dest):
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-ss", FRAME_AT, "-i", str(VIDEO), "-frames:v", "1", str(dest)],
        check=True,
    )
    return Image.open(dest).convert("RGB")


def rounded_mask(size, radius):
    w, h = size
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w * SS - 1, h * SS - 1), radius * SS, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def place_card(img, shot):
    card_h = 452
    scale = card_h / shot.height
    card = shot.resize((round(shot.width * scale), card_h), Image.LANCZOS)
    radius = round(CARD_RADIUS * scale)
    x, y = 540, (H - card_h) // 2
    mask = rounded_mask(card.size, radius)

    shadow = Image.new("L", (W, H), 0)
    shadow.paste(mask, (x, y + 14))
    shadow = shadow.filter(ImageFilter.GaussianBlur(24)).point(lambda v: int(v * 0.75))
    img.paste((0, 0, 0), (0, 0), shadow)

    img.paste(card, (x, y), mask)
    # thin light border, drawn supersampled
    ring = Image.new("L", (card.width * SS, card.height * SS), 0)
    d = ImageDraw.Draw(ring)
    d.rounded_rectangle((0, 0, card.width * SS - 1, card.height * SS - 1), radius * SS, outline=255, width=SS * 2)
    ring = ring.resize(card.size, Image.LANCZOS).point(lambda v: int(v * 0.35))
    img.paste((190, 225, 235), (x, y), ring)


def left_column(img):
    big = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    # mark: outlined rhombus with a filled one inside (website/assets/mark.svg, 26 unit box)
    u, ox, oy = 2.58 * SS, 78 * SS, 180 * SS
    def pt(px, py):
        return (ox + px * u, oy + py * u)
    d.polygon([pt(13, 1), pt(25, 13), pt(13, 25), pt(1, 13)], outline=ACCENT + (255,), width=round(1.2 * SS))
    d.polygon([pt(13, 8.5), pt(17.5, 13), pt(13, 17.5), pt(8.5, 13)], fill=ACCENT + (255,))
    layer = big.resize((W, H), Image.LANCZOS)
    img.paste(layer, (0, 0), layer)

    draw = ImageDraw.Draw(img)
    # wordmark, vertically centred on the mark (mark spans y 182..244)
    draw.text((164, 213), "Glacier", font=font(64, 600), fill=WHITE, anchor="lm")
    head = font(46, 600)
    draw.text((80, 288), "Jellyfin,", font=head, fill=WHITE)
    draw.text((80, 333), "made simple.", font=head, fill=WHITE)
    sub = font(25, 400)
    draw.text((80, 396), "A Jellyfin client for Android TV,", font=sub, fill=MUTED)
    draw.text((80, 430), "Google TV and Fire TV.", font=sub, fill=MUTED)


def main():
    with tempfile.TemporaryDirectory() as tmp:
        frame = grab_frame(Path(tmp) / "frame.png")
    img = background()
    place_card(img, frame.crop(CARD_BOX))
    left_column(img)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT, optimize=True)
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KiB)")


if __name__ == "__main__":
    main()
