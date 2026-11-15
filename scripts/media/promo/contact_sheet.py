"""Contact sheet of a PNG sequence with frame numbers (and the flat-frame diff of the last frame).

  python scripts/media/promo/contact_sheet.py DIR [--cols 4] [--width 480] [--out sheet.png] [--key key.png]
Uses every PNG in DIR named ####.png (so render previews with --step to get a sparse set).
With --key the last frame is compared to the key frame (mean / max abs diff in 0..255) and the result is printed.
"""
import argparse
import glob
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--cols", type=int, default=4)
    ap.add_argument("--width", type=int, default=480)
    ap.add_argument("--out")
    ap.add_argument("--key")
    ap.add_argument("--max", type=int, default=0, help="at most this many frames (evenly picked)")
    a = ap.parse_args()
    files = sorted(f for f in glob.glob(os.path.join(a.dir, "[0-9][0-9][0-9][0-9].png")))
    if a.max and len(files) > a.max:
        idx = np.linspace(0, len(files) - 1, a.max).round().astype(int)
        files = [files[i] for i in idx]
    if not files:
        raise SystemExit("no frames")
    first = Image.open(files[0])
    w = a.width
    h = round(first.height * w / first.width)
    rows = -(-len(files) // a.cols)
    sheet = Image.new("RGB", (a.cols * w, rows * h), (20, 20, 20))
    font = ImageFont.truetype("arial.ttf", 22) if os.name == "nt" else ImageFont.load_default()
    for i, f in enumerate(files):
        im = Image.open(f).convert("RGB").resize((w, h), Image.LANCZOS)
        d = ImageDraw.Draw(im)
        label = str(int(os.path.basename(f)[:4]))
        d.rectangle((0, 0, 70, 30), fill=(0, 0, 0))
        d.text((6, 3), label, fill=(255, 220, 80), font=font)
        sheet.paste(im, ((i % a.cols) * w, (i // a.cols) * h))
    out = a.out or os.path.join(a.dir, "sheet.png")
    sheet.save(out)
    print(out)
    if a.key:
        last = np.asarray(Image.open(files[-1]).convert("RGB"), np.float32)
        key = np.asarray(Image.open(a.key).convert("RGB"), np.float32)
        if last.shape != key.shape:
            key = np.asarray(Image.open(a.key).convert("RGB").resize((last.shape[1], last.shape[0]), Image.LANCZOS), np.float32)
        d = np.abs(last - key)
        print(f"last frame vs key: mean {d.mean():.3f}/255  p99 {np.percentile(d, 99):.1f}  max {d.max():.0f}")


if __name__ == "__main__":
    main()
