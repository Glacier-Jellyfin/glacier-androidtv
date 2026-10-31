#!/usr/bin/env python3
"""Cuts the promo video for the stores and the website from recorded takes.

Takes come from scripts/capture-website-media.py --takes DIR. Each scene shows a
take scaled into a rounded frame on Glacier's dark background, with a caption
above it, so no text covers the app. Writes into build/promo/:

  glacier-promo[-de].mp4        the full film, 1080p, with music (stores, YouTube)
  glacier-promo-short[-de].mp4  a 30-second cut
  glacier-promo-web[-de].mp4    the full film at 720p for the website

Music: "Inspired" by Kevin MacLeod (incompetech.com), CC BY 4.0, which asks for
credit where the video is published; the end card carries it too. Font: Google
Sans, SIL Open Font License. Both are downloaded once and checked against their
sha256.

Needs Python 3.9+ with Pillow, and ffmpeg on PATH (or FFMPEG set).

Usage: scripts/make-promo-video.py --takes DIR [--lang en,de]
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "build" / "promo"
CACHE = OUT / "cache"
FONT = ("https://raw.githubusercontent.com/google/fonts/5412d702cdfe09da921cb24f0824fe398cace6d2/ofl/googlesans/"
        "GoogleSans%5BGRAD,opsz,wght%5D.ttf", "d0a87d835a944b8b40d0e82a5651bb59ab97b936a2aeed5946eb57e7b2a3a90a")
MUSIC = ("https://incompetech.com/music/royalty-free/mp3-royaltyfree/Inspired.mp3",
         "40b1af74145cedac77814d060155ad4658e04efa14d1b649decedc98fbb25f69")

W, H, FPS = 1920, 1080, 30
# The app's frame: 80 % of the screen, centred horizontally, low enough to leave room for the caption.
FRAME_W, FRAME_H, FRAME_X, FRAME_Y, RADIUS = 1536, 864, 192, 186, 28
FADE = 0.6
VOID, DEEP, ICE, MIST, ACCENT = "#060d16", "#0e1a26", "#eef8fa", "#90a8b6", "#7dc7d9"


@dataclass
class Scene:
    take: str
    start: float
    end: float
    eyebrow: dict[str, str]
    headline: dict[str, str]


SCENES = [
    Scene("profiles", 1.0, 9.5, {"en": "Profiles", "de": "Profile"},
          {"en": "One TV, the whole family", "de": "Ein Fernseher, die ganze Familie"}),
    Scene("library", 1.0, 13.0, {"en": "Library", "de": "Bibliothek"},
          {"en": "Big libraries, found in seconds", "de": "Große Bibliotheken, in Sekunden durchsucht"}),
    Scene("detail", 1.5, 22.0, {"en": "Details", "de": "Details"},
          {"en": "Every version, track and subtitle at a glance",
           "de": "Jede Version, Tonspur und Untertitel auf einen Blick"}),
    Scene("skip", 7.5, 15.8, {"en": "Player", "de": "Player"},
          {"en": "Skip intros and recaps", "de": "Intros und Rückblicke überspringen"}),
    Scene("trickplay", 19.0, 27.0, {"en": "Player", "de": "Player"},
          {"en": "See where you're going", "de": "Sehen, wohin du spulst"}),
    Scene("upnext", 17.0, 30.0, {"en": "Player", "de": "Player"},
          {"en": "The next episode, ready when you are", "de": "Die nächste Folge, sobald du willst"}),
    Scene("music", 2.5, 27.0, {"en": "Music", "de": "Musik"},
          {"en": "Music with lyrics that keeps playing", "de": "Musik mit Songtexten, die weiterläuft"}),
    Scene("livetv", 1.0, 26.0, {"en": "Live TV", "de": "Live-TV"},
          {"en": "Live TV with a full guide", "de": "Live-TV mit Programmführer"}),
    Scene("kids", 1.0, 11.0, {"en": "Parental controls", "de": "Jugendschutz"},
          {"en": "Only what's right for their age", "de": "Nur, was zum Alter passt"}),
]
# The short cut: the same scenes, fewer and shorter.
SHORT = [("profiles", 1.0, 6.5), ("library", 4.0, 10.0), ("skip", 9.0, 15.0), ("music", 9.0, 14.5), ("livetv", 12.0, 18.0)]
INTRO = {"en": "Jellyfin, made simple.", "de": "Jellyfin, ganz einfach."}
OUTRO = {
    "en": ("Free. No ads. No tracking.", "Android TV · Google TV · Fire TV",
           "Amazon Appstore · Google Play · GitHub"),
    "de": ("Kostenlos. Ohne Werbung. Ohne Tracking.", "Android TV · Google TV · Fire TV",
           "Amazon Appstore · Google Play · GitHub"),
}
CREDIT = "Music: “Inspired” by Kevin MacLeod (incompetech.com), CC BY 4.0"
SITE = "glacier-jellyfin.github.io/glacier-androidtv"
INTRO_S, OUTRO_S, SHORT_OUTRO_S = 4.0, 7.0, 4.5


def tool(name: str, env: str) -> str:
    found = os.environ.get(env) or shutil.which(name)
    if not found:
        sys.exit(f"{name} not found; put it on PATH or set {env}")
    return found


def fetch(url: str, sha256: str) -> Path:
    path = CACHE / f"{sha256[:12]}-{url.rsplit('/', 1)[-1].split('%')[0]}"
    if path.exists():
        return path
    CACHE.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "Glacier promo (github.com/Glacier-Jellyfin)"})
    with urllib.request.urlopen(request) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != sha256:
        sys.exit(f"{url}: sha256 does not match")
    path.write_bytes(data)
    return path


class Art:
    def __init__(self, font: Path):
        self.font_path = font

    def font(self, size: int, weight: int) -> ImageFont.FreeTypeFont:
        font = ImageFont.truetype(str(self.font_path), size)
        # Axes: optical size, weight, grade.
        font.set_variation_by_axes([18, weight, 0])
        return font

    def background(self) -> Image.Image:
        """Glacier's dark blue with a soft glow at the top, like the website."""
        image = Image.new("RGB", (W, H), VOID)
        glow = Image.new("L", (W, H), 0)
        ImageDraw.Draw(glow).ellipse((W * 0.15, -H * 0.55, W * 0.85, H * 0.45), fill=110)
        glow = glow.filter(ImageFilter.GaussianBlur(160))
        return Image.composite(Image.new("RGB", (W, H), DEEP), image, glow)

    def mark(self, draw: ImageDraw.ImageDraw, cx: float, cy: float, size: float) -> None:
        """The Glacier mark: an outlined diamond around a filled one (assets/mark.svg)."""
        s = size / 26
        outer = [(cx, cy - 12 * s), (cx + 12 * s, cy), (cx, cy + 12 * s), (cx - 12 * s, cy)]
        inner = [(cx, cy - 4.5 * s), (cx + 4.5 * s, cy), (cx, cy + 4.5 * s), (cx - 4.5 * s, cy)]
        draw.polygon(outer, outline=ACCENT, width=max(2, round(s)))
        draw.polygon(inner, fill=ACCENT)

    def centred(self, draw: ImageDraw.ImageDraw, y: float, text: str, font, fill: str, spacing: float = 0) -> None:
        if spacing:
            widths = [draw.textlength(c, font=font) for c in text]
            x = (W - sum(widths) - spacing * (len(text) - 1)) / 2
            for c, w in zip(text, widths):
                draw.text((x, y), c, font=font, fill=fill)
                x += w + spacing
        else:
            draw.text(((W - draw.textlength(text, font=font)) / 2, y), text, font=font, fill=fill)

    def scene(self, eyebrow: str, headline: str) -> Image.Image:
        """Background, caption and the frame's shadow; the take goes into the frame."""
        image = self.background()
        shadow = Image.new("L", (W, H), 0)
        ImageDraw.Draw(shadow).rounded_rectangle((FRAME_X, FRAME_Y + 18, FRAME_X + FRAME_W, FRAME_Y + FRAME_H + 18),
                                                 RADIUS, fill=150)
        image = Image.composite(Image.new("RGB", (W, H), "#000000"), image, shadow.filter(ImageFilter.GaussianBlur(40)))
        draw = ImageDraw.Draw(image)
        self.centred(draw, 38, eyebrow.upper(), self.font(26, 700), ACCENT, spacing=4)
        self.centred(draw, 78, headline, self.font(64, 600), ICE)
        return image

    def frame_mask(self) -> Image.Image:
        mask = Image.new("L", (FRAME_W, FRAME_H), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, FRAME_W - 1, FRAME_H - 1), RADIUS, fill=255)
        return mask

    def intro(self, tagline: str) -> Image.Image:
        image = self.background()
        draw = ImageDraw.Draw(image)
        self.mark(draw, W / 2, H / 2 - 150, 150)
        self.centred(draw, H / 2 - 10, "GLACIER", self.font(96, 600), ICE, spacing=22)
        self.centred(draw, H / 2 + 120, tagline, self.font(44, 400), MIST)
        return image

    def outro(self, lines: tuple[str, str, str]) -> Image.Image:
        image = self.background()
        draw = ImageDraw.Draw(image)
        self.mark(draw, W / 2, 330, 110)
        self.centred(draw, 420, "GLACIER", self.font(72, 600), ICE, spacing=16)
        self.centred(draw, 560, lines[0], self.font(56, 600), ICE)
        self.centred(draw, 650, lines[1], self.font(38, 400), MIST)
        self.centred(draw, 720, lines[2], self.font(38, 500), ACCENT)
        self.centred(draw, 840, SITE, self.font(34, 400), MIST)
        self.centred(draw, 980, CREDIT, self.font(24, 400), "#62798a")
        return image


class Cutter:
    def __init__(self, takes: Path, work: Path, art: Art, music: Path):
        self.takes = takes
        self.work = work
        self.art = art
        self.music = music
        self.ffmpeg = tool("ffmpeg", "FFMPEG")
        art.frame_mask().save(work / "mask.png")

    def run(self, args: list) -> None:
        subprocess.run([self.ffmpeg, "-v", "error", "-y"] + [str(a) for a in args], check=True)

    def card(self, image: Image.Image, seconds: float, name: str) -> Path:
        png, out = self.work / f"{name}.png", self.work / f"{name}.mp4"
        image.save(png)
        self.run(["-loop", 1, "-framerate", FPS, "-t", seconds, "-i", png, "-c:v", "libx264", "-crf", 12,
                  "-pix_fmt", "yuv420p", out])
        return out

    def scene(self, take: Path, start: float, end: float, eyebrow: str, headline: str, name: str) -> Path:
        png, out = self.work / f"{name}.png", self.work / f"{name}.mp4"
        self.art.scene(eyebrow, headline).save(png)
        # screenrecord writes frames only when the screen changes: hold the last one to the full length.
        graph = (f"[1:v]fps={FPS},scale={FRAME_W}:{FRAME_H}:flags=lanczos,format=rgba,setpts=PTS-STARTPTS,"
                 f"tpad=stop_mode=clone:stop_duration={end - start},trim=duration={end - start}[app];"
                 f"[2:v]format=gray[m];[app][m]alphamerge[framed];"
                 f"[0:v][framed]overlay={FRAME_X}:{FRAME_Y}:shortest=1,format=yuv420p")
        self.run(["-loop", 1, "-framerate", FPS, "-i", png, "-ss", start, "-to", end, "-i", take,
                  "-loop", 1, "-i", self.work / "mask.png", "-filter_complex", graph,
                  "-c:v", "libx264", "-crf", 12, "-an", out])
        return out

    def join(self, parts: list[Path], out: Path, width: int, crf: int) -> None:
        """Cross-fades the parts and lays the music under them, faded out at the end."""
        lengths = [float(subprocess.run([tool("ffprobe", "FFPROBE"), "-v", "error", "-show_entries", "format=duration",
                                         "-of", "csv=p=0", p], capture_output=True, text=True).stdout) for p in parts]
        inputs, chain, last, total = [], [], "0:v", lengths[0]
        for p in parts:
            inputs += ["-i", p]
        for i in range(1, len(parts)):
            chain.append(f"[{last}][{i}:v]xfade=transition=fade:duration={FADE}:offset={total - FADE:.3f}[x{i}]")
            last, total = f"x{i}", total + lengths[i] - FADE
        chain.append(f"[{last}]scale={width}:-2:flags=lanczos,format=yuv420p[v]")
        music = len(parts)
        chain.append(f"[{music}:a]atrim=0:{total:.3f},afade=t=in:d=0.8,afade=t=out:st={total - 3:.3f}:d=3,"
                     f"volume=0.9[a]")
        self.run(inputs + ["-i", self.music, "-filter_complex", ";".join(chain), "-map", "[v]", "-map", "[a]",
                           "-c:v", "libx264", "-preset", "slow", "-crf", crf, "-r", FPS, "-c:a", "aac", "-b:a", "192k",
                           "-movflags", "+faststart", out])
        print(f"  {out.relative_to(ROOT)} ({total:.0f} s)")

    def film(self, lang: str) -> None:
        suffix = "" if lang == "en" else f"-{lang}"
        take = lambda name: self.takes / f"{name}{suffix}.mp4"
        missing = [s.take for s in SCENES if not take(s.take).exists()]
        if missing:
            sys.exit(f"Missing takes in {self.takes}: {', '.join(missing)} ({lang})")
        intro = self.card(self.art.intro(INTRO[lang]), INTRO_S, f"intro-{lang}")
        outro = self.card(self.art.outro(OUTRO[lang]), OUTRO_S, f"outro-{lang}")
        scenes = {s.take: s for s in SCENES}
        full = [intro] + [self.scene(take(s.take), s.start, s.end, s.eyebrow[lang], s.headline[lang], f"{s.take}-{lang}")
                          for s in SCENES] + [outro]
        self.join(full, OUT / f"glacier-promo{suffix}.mp4", W, 18)
        self.join(full, OUT / f"glacier-promo-web{suffix}.mp4", 1280, 26)
        short_outro = self.card(self.art.outro(OUTRO[lang]), SHORT_OUTRO_S, f"outro-short-{lang}")
        short = [self.card(self.art.intro(INTRO[lang]), 2.5, f"intro-short-{lang}")]
        short += [self.scene(take(name), a, b, scenes[name].eyebrow[lang], scenes[name].headline[lang],
                             f"short-{name}-{lang}") for name, a, b in SHORT]
        self.join(short + [short_outro], OUT / f"glacier-promo-short{suffix}.mp4", W, 18)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--takes", type=Path, required=True, help="folder with the takes of capture-website-media.py")
    parser.add_argument("--lang", default="en,de", help="comma-separated languages")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    art = Art(fetch(*FONT))
    music = fetch(*MUSIC)
    with tempfile.TemporaryDirectory() as work:
        cutter = Cutter(args.takes, Path(work), art, music)
        for lang in args.lang.split(","):
            print(lang)
            cutter.film(lang)


if __name__ == "__main__":
    main()
