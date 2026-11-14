#!/usr/bin/env python3
"""Renders the promo video for the stores and the website from recorded takes.

Takes come from scripts/media/capture-website-media.py --takes DIR. Every frame is
composed here and piped to ffmpeg: the app runs in a rounded frame on Glacier's
dark background, a camera eases into the part of the screen a caption talks
about, captions slide in and out, scenes push into each other on the beat of
the music, and the title and end cards are animated. Writes into build/promo/:

  glacier-promo[-de].mp4        the full film, 1080p, with music (stores, YouTube)
  glacier-promo-short[-de].mp4  a 30-second cut
  glacier-promo-web[-de].mp4    the full film at 720p for the website

The shots are timed on the English takes. The German takes run a little
differently (loading takes as long as it takes), so each part of a shot is
matched to the German take by comparing the motion in both.

Music: "Inspired" by Kevin MacLeod (incompetech.com), CC BY 4.0, which asks for
credit where the video is published; the end card carries it too. Font: Google
Sans, SIL Open Font License. Both are downloaded once and checked against their
sha256.

Needs Python 3.9+ with Pillow and numpy, and ffmpeg on PATH (or FFMPEG set).

With --only website it also writes the website's media: the section pictures and
the highlight video into website/assets/screenshots/, the film into
website/assets/promo/ (render the full film first). With --only store it
writes the same pictures at 1080p as the Google Play screenshots into
fastlane/metadata/android/<locale>/images/tvScreenshots/.

Usage: scripts/media/make-promo-video.py --takes DIR [--lang en,de] [--only full,short,website,store]
"""
from __future__ import annotations

import argparse
import hashlib
import math
import os
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "build" / "promo"
CACHE = OUT / "cache"
FONT = ("https://raw.githubusercontent.com/google/fonts/5412d702cdfe09da921cb24f0824fe398cace6d2/ofl/googlesans/"
        "GoogleSans%5BGRAD,opsz,wght%5D.ttf", "d0a87d835a944b8b40d0e82a5651bb59ab97b936a2aeed5946eb57e7b2a3a90a")
MUSIC = ("https://incompetech.com/music/royalty-free/mp3-royaltyfree/Inspired.mp3",
         "40b1af74145cedac77814d060155ad4658e04efa14d1b649decedc98fbb25f69")
# "Inspired" runs at 95.7 bpm; scene changes land on its beats.
BEAT = 60 / 95.7

W, H, FPS = 1920, 1080, 30
SRC_W, SRC_H = 1920, 1080
# The app's frame: 80 % of the screen, low enough to leave room for the caption above it.
FRAME_W, FRAME_H, FRAME_X, FRAME_Y, RADIUS = 1536, 864, 192, 190, 30
PUSH = 0.9  # seconds two scenes overlap while one pushes the other out
VOID, DEEP, ICE, MIST, ACCENT, DIM = (6, 13, 22), (14, 26, 38), (238, 248, 250), (144, 168, 182), (125, 199, 217), (98, 121, 138)


# Shots ------------------------------------------------------------------------

@dataclass
class Part:
    """A stretch of a take: seconds in the English take, played at [speed]."""
    take: str
    start: float
    end: float
    speed: float = 1.0
    # Seconds to add in another language's take, where offset() cannot find the moment by itself.
    shift: dict[str, float] = field(default_factory=dict)

    @property
    def length(self) -> float:
        return (self.end - self.start) / self.speed


@dataclass
class Caption:
    at: float  # seconds into the shot
    text: dict[str, str]


@dataclass
class Shot:
    eyebrow: dict[str, str]
    parts: list[Part]
    captions: list[Caption]
    # Camera keyframes: (seconds into the shot, zoom, centre x, centre y) on the 1920x1080 take.
    camera: list[tuple[float, float, float, float]] = field(default_factory=list)


def caption(at: float, en: str, de: str, es: str) -> Caption:
    return Caption(at, {"en": en, "de": de, "es": es})


C = (960, 540)
SHOTS = [
    Shot({"en": "Home", "de": "Startseite", "es": "Inicio"},
         [Part("hero", 1.2, 7.6)],
         [caption(0, "A home screen made for the TV", "Ein Startbildschirm, gemacht für den Fernseher", "Una pantalla de inicio hecha para la tele"),
          caption(2.7, "Carry on where you left off", "Weiterschauen, wo du aufgehört hast", "Sigue donde lo dejaste")],
         [(0, 1.0, *C), (2.4, 1.06, 960, 520), (3.4, 1.6, 640, 470), (6.4, 1.66, 700, 470)]),
    Shot({"en": "Profiles", "de": "Profile", "es": "Perfiles"},
         [Part("family", 1.2, 4.7), Part("family", 5.8, 12.6), Part("family", 18.8, 21.4), Part("family", 22.9, 26.4)],
         [caption(0, "Everyone gets their own profile", "Jeder bekommt sein eigenes Profil", "Cada uno tiene su propio perfil"),
          caption(3.5, "Kids see only what suits their age", "Kinder sehen nur, was zu ihrem Alter passt", "Los niños solo ven lo que va con su edad"),
          caption(10.3, "Switch profiles in a moment", "Profil wechseln im Handumdrehen", "Cambia de perfil en un momento")],
         [(0, 1.15, 960, 560), (3.3, 1.7, 960, 600), (3.5, 1.0, *C), (9.8, 1.12, 900, 520), (10.3, 1.7, 960, 600),
          (12.7, 1.7, 960, 600), (12.9, 1.0, *C), (16.4, 1.06, *C)]),
    Shot({"en": "Library", "de": "Bibliothek", "es": "Biblioteca"},
         [Part("library", 0.8, 13.6)],
         [caption(0, "Your whole library, beautifully arranged", "Deine ganze Bibliothek, schön geordnet", "Toda tu biblioteca, bien ordenada"),
          caption(5.0, "The A–Z rail jumps straight to a letter", "Die A–Z-Leiste springt direkt zum Buchstaben", "La barra A–Z salta directo a una letra")],
         [(0, 1.04, *C), (4.9, 1.12, 900, 500), (6.0, 2.0, 1440, 620), (9.6, 2.0, 1440, 620), (10.7, 1.0, *C),
          (12.8, 1.06, *C)]),
    Shot({"en": "Details", "de": "Details", "es": "Detalles"},
         [Part("detail", 0.6, 4.3), Part("detail", 4.6, 7.7), Part("detail", 8.6, 12.8), Part("detail", 13.8, 17.8),
          Part("detail", 18.6, 22.6)],
         [caption(0, "Every title, fully explored", "Jeder Titel, vollständig erschlossen", "Cada título, con todo detalle"),
          caption(3.7, "Pick the version: 2160p HDR or 1080p", "Die Fassung wählen: 2160p HDR oder 1080p", "Elige la versión: 2160p HDR o 1080p"),
          caption(6.8, "Every audio track, clearly labelled", "Jede Tonspur, klar beschriftet", "Cada pista de audio, con un nombre claro"),
          caption(11.0, "Subtitles marked forced, SDH and more", "Untertitel gekennzeichnet: erzwungen, SDH und mehr", "Subtítulos marcados: forzados, SDH y más"),
          caption(15.0, "With the cast and titles like it", "Mit Besetzung und ähnlichen Titeln", "Con el reparto y títulos similares")],
         [(0, 1.0, *C), (3.5, 1.06, *C), (4.0, 1.9, 1000, 600), (6.7, 1.9, 1000, 600), (7.2, 1.75, 940, 560),
          (10.9, 1.75, 940, 560), (11.4, 1.35, 960, 600), (14.9, 1.35, 960, 600), (15.6, 1.5, 700, 700),
          (19.0, 1.5, 760, 700)]),
    Shot({"en": "Player", "de": "Player", "es": "Reproductor"},
         [Part("skip", 6.2, 8.4, shift={"de": 3.0}), Part("skip", 8.8, 11.0, shift={"de": 3.0}),
          Part("skip", 11.0, 13.8, shift={"de": 10.4}), Part("trickplay", 20.6, 26.8), Part("upnext", 19.2, 28.0, shift={"de": -5.5})],
         [caption(0, "Recaps skip themselves", "Rückblicke überspringen sich selbst", "Los resúmenes se saltan solos"),
          caption(2.2, "Intros go with one press", "Intros mit einem Tastendruck weg", "La intro se va con una tecla"),
          caption(7.2, "Preview pictures while you seek", "Vorschaubilder beim Spulen", "Miniaturas mientras avanzas"),
          caption(13.4, "The next episode, ready before the credits end",
                  "Die nächste Folge, bereit noch vor dem Abspann",
                  "El siguiente episodio, listo antes de los créditos")],
         [(0, 1.9, 1415, 284), (2.0, 1.9, 1415, 284), (2.6, 2.0, 1440, 740), (4.3, 2.0, 1440, 740),
          (5.0, 1.6, 700, 800), (7.0, 1.6, 700, 800), (7.4, 1.8, 1250, 780), (13.2, 1.8, 1440, 780),
          (13.4, 1.0, *C), (14.6, 1.0, *C), (15.8, 2.1, 1484, 830), (22.2, 2.1, 1484, 830)]),
    Shot({"en": "Music", "de": "Musik", "es": "Música"},
         [Part("music", 10.4, 13.6), Part("music", 20.8, 31.8), Part("music", 40.4, 43.8), Part("music", 55.0, 61.0)],
         [caption(0, "Music that keeps playing", "Musik, die weiterläuft", "Música que sigue sonando"),
          caption(3.2, "Lyrics, large and in time", "Songtexte, groß und im Takt", "Letras grandes y al ritmo"),
          caption(14.2, "Keep browsing, the mini player stays", "Weiterstöbern, der Mini-Player bleibt", "Sigue navegando, el minirreproductor se queda"),
          caption(17.6, "Music videos play right in the queue", "Musikvideos laufen in der Warteschlange mit", "Los videoclips suenan en la cola")],
         [(0, 1.0, *C), (3.0, 1.05, *C), (3.2, 1.0, *C), (13.9, 1.12, 960, 400), (14.2, 1.0, *C),
          (14.9, 2.2, 1483, 245), (17.3, 2.2, 1483, 245), (17.6, 1.0, *C), (18.6, 1.6, 620, 450),
          (23.6, 1.65, 620, 450)]),
    Shot({"en": "Photos & videos", "de": "Fotos & Videos", "es": "Fotos y videos"},
         [Part("photos", 4.4, 7.2), Part("photos", 10.4, 16.0), Part("photos", 19.6, 24.7),
          Part("photos", 25.0, 31.6, 3.0), Part("photos", 31.6, 33.6)],
         [caption(0, "Photos and home videos, in their folders", "Fotos und Heimvideos, in ihren Ordnern", "Fotos y videos caseros, en sus carpetas"),
          caption(2.8, "Browse them as a gallery", "Als Galerie durchblättern", "Recórrelas como galería"),
          caption(8.4, "Full screen, with smooth transitions", "Im Vollbild, mit weichen Übergängen", "A pantalla completa, con transiciones suaves"),
          caption(13.5, "A slideshow at the press of a button", "Eine Diashow auf Tastendruck", "Una presentación con una sola tecla")],
         [(0, 1.08, 900, 520), (2.6, 1.0, *C), (8.2, 1.06, *C), (8.6, 1.0, *C), (13.3, 1.05, *C), (13.5, 1.0, *C),
          (17.4, 1.08, *C)]),
    Shot({"en": "Live TV", "de": "Live-TV", "es": "TV en directo"},
         [Part("livetv", 1.2, 11.2), Part("livetv", 20.4, 26.6)],
         [caption(0, "Pick a channel, the preview is already on", "Sender wählen, die Vorschau läuft schon", "Elige un canal, la vista previa ya está en marcha"),
          caption(10.0, "And a guide for every channel", "Und ein Programmführer für jeden Sender", "Y una guía para cada canal")],
         [(0, 1.0, *C), (1.0, 1.15, 1000, 560), (9.6, 1.18, 1020, 560), (10.0, 1.0, *C), (16.2, 1.08, 960, 520)]),
]
# The website's highlight video: one moment of each shot, without title and end card (shot, from, to).
HIGHLIGHT = [(0, 2.7, 6.4), (1, 3.5, 10.3), (2, 5.0, 12.8), (3, 6.8, 11.0), (4, 2.2, 7.2), (5, 3.4, 10.0),
             (6, 2.8, 8.4), (7, 10.0, 16.2)]
# The website's section pictures: a moment of an English take each, found again in the other languages.
STILLS = {"home": ("family", 25.0), "library": ("library", 12.2), "detail": ("detail", 10.6),
          "player": ("skip", 7.8), "music": ("music", 24.5), "photos": ("photos", 18.2), "livetv": ("livetv", 9.0)}
# Website-only pictures: the TV in the living room and the family scene (not in the stores).
SITE_STILLS = {"picker": ("family", 2.5), "kids": ("family", 8.5), "trickplay": ("trickplay", 24.0)}
SITE_DIR = ROOT / "website" / "assets"
STORE_DIR = ROOT / "fastlane" / "metadata" / "android"
STORE_LOCALES = {"en": "en-US", "de": "de-DE", "es": "es-ES"}
# The short cut: the strongest moment of a few shots, one caption each (shot, from, to).
SHORT = [(1, 3.5, 10.3), (2, 5.0, 12.8), (4, 2.2, 7.2), (5, 3.2, 9.8), (7, 0.0, 9.0)]

TITLE = {"en": "Jellyfin, made simple.", "de": "Jellyfin, ganz einfach.", "es": "Jellyfin, así de fácil."}
END = {
    "en": ("Free. No ads. No tracking.", "Android TV · Google TV · Fire TV", "Amazon Appstore · Google Play · GitHub"),
    "de": ("Kostenlos. Ohne Werbung. Ohne Tracking.", "Android TV · Google TV · Fire TV",
           "Amazon Appstore · Google Play · GitHub"),
    "es": ("Gratis. Sin anuncios. Sin seguimiento.", "Android TV · Google TV · Fire TV",
           "Amazon Appstore · Google Play · GitHub"),
}
CREDIT = "Music: “Inspired” by Kevin MacLeod (incompetech.com), CC BY 4.0"
SITE = "glacier-jellyfin.github.io/glacier-androidtv"
INTRO_S, OUTRO_S = 8 * BEAT, 12 * BEAT


# Helpers ----------------------------------------------------------------------

def tool(name: str, env: str) -> str:
    found = os.environ.get(env) or shutil.which(name)
    if not found:
        sys.exit(f"{name} not found; put it on PATH or set {env}")
    return found


FFMPEG, FFPROBE = tool("ffmpeg", "FFMPEG"), tool("ffprobe", "FFPROBE")


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


def ease(t: float) -> float:
    """Ease in and out (cubic), for camera moves and slides."""
    t = min(max(t, 0.0), 1.0)
    return 4 * t * t * t if t < 0.5 else 1 - (-2 * t + 2) ** 3 / 2


def ease_out(t: float) -> float:
    t = min(max(t, 0.0), 1.0)
    return 1 - (1 - t) ** 3


def back_out(t: float) -> float:
    """Overshoots a little before it settles, for the logo."""
    t = min(max(t, 0.0), 1.0)
    c = 1.4
    return 1 + (c + 1) * (t - 1) ** 3 + c * (t - 1) ** 2


def snap(seconds: float) -> float:
    """Rounds a point in the film to the nearest beat."""
    return round(seconds / BEAT) * BEAT


def duration(path: Path) -> float:
    return float(subprocess.run([FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                 str(path)], capture_output=True, text=True).stdout)


# Matching the German takes ----------------------------------------------------

@lru_cache(maxsize=None)
def motion(path: Path) -> np.ndarray:
    """How much each tenth of a second differs from the one before, on a tiny greyscale copy."""
    raw = subprocess.run([FFMPEG, "-v", "error", "-i", str(path), "-vf", "fps=10,scale=96:54,format=gray",
                          "-f", "rawvideo", "-"], capture_output=True).stdout
    frames = np.frombuffer(raw, np.uint8).reshape(-1, 54, 96).astype(np.float32)
    return np.concatenate([[0], np.abs(np.diff(frames, axis=0)).mean(axis=(1, 2))])


def offset(en: Path, other: Path, start: float, end: float, search: float = 4.0) -> float:
    """Seconds to add to [start, end] in [en] to find the same moment in [other]."""
    a, b = motion(en), motion(other)
    i, j = int(start * 10), int(end * 10)
    ref = a[i:j]
    if len(ref) < 5 or ref.std() < 1e-3:
        return 0.0
    best, best_score = 0, -1e9
    for shift in range(-int(search * 10), int(search * 10) + 1):
        k = i + shift
        if k < 0 or k + len(ref) > len(b):
            continue
        cand = b[k:k + len(ref)]
        score = np.corrcoef(ref, cand)[0, 1] if cand.std() > 1e-3 else -1
        # A slight preference for small shifts keeps ties from wandering off.
        score -= abs(shift) * 0.002
        if score > best_score:
            best, best_score = shift, score
    return best / 10


# Pictures ---------------------------------------------------------------------

class Art:
    def __init__(self, font: Path):
        self.font_path = font
        self.background = self.make_background()
        self.mask = self.rounded((FRAME_W, FRAME_H), RADIUS)
        self.shadow = self.make_shadow()

    def font(self, size: int, weight: int) -> ImageFont.FreeTypeFont:
        font = ImageFont.truetype(str(self.font_path), size)
        font.set_variation_by_axes([18, weight, 0])  # optical size, weight, grade
        return font

    def make_background(self) -> np.ndarray:
        """A field larger than the screen with soft glows; frames pan across it slowly."""
        bw, bh = W + 480, H + 270
        base = Image.new("RGB", (bw, bh), VOID)
        glow = Image.new("L", (bw, bh), 0)
        draw = ImageDraw.Draw(glow)
        draw.ellipse((bw * 0.18, -bh * 0.45, bw * 0.82, bh * 0.42), fill=120)
        draw.ellipse((-bw * 0.2, bh * 0.55, bw * 0.35, bh * 1.3), fill=55)
        draw.ellipse((bw * 0.7, bh * 0.6, bw * 1.2, bh * 1.25), fill=45)
        glow = glow.filter(ImageFilter.GaussianBlur(180))
        image = Image.composite(Image.new("RGB", (bw, bh), DEEP), base, glow)
        return np.asarray(image, np.float32)

    @staticmethod
    def rounded(size: tuple[int, int], radius: int) -> Image.Image:
        mask = Image.new("L", size, 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
        return mask

    def make_shadow(self) -> Image.Image:
        pad = 120
        shadow = Image.new("L", (FRAME_W + 2 * pad, FRAME_H + 2 * pad), 0)
        ImageDraw.Draw(shadow).rounded_rectangle((pad, pad + 24, pad + FRAME_W, pad + FRAME_H + 24), RADIUS, fill=170)
        return shadow.filter(ImageFilter.GaussianBlur(44))

    @lru_cache(maxsize=256)
    def text(self, text: str, size: int, weight: int, color: tuple, spacing: float = 0) -> Image.Image:
        """Text as an RGBA image, trimmed to its box."""
        font = self.font(size, weight)
        probe = ImageDraw.Draw(Image.new("L", (1, 1)))
        widths = [probe.textlength(c, font=font) for c in text] if spacing else [probe.textlength(text, font=font)]
        width = int(sum(widths) + spacing * max(0, len(text) - 1)) + 8
        ascent, descent = font.getmetrics()
        image = Image.new("RGBA", (width, ascent + descent + 8), color + (0,))
        draw = ImageDraw.Draw(image)
        if spacing:
            x = 4.0
            for c, w in zip(text, widths):
                draw.text((x, 4), c, font=font, fill=color + (255,))
                x += w + spacing
        else:
            draw.text((4, 4), text, font=font, fill=color + (255,))
        return image


class Canvas:
    """One output frame, built up in numpy."""

    def __init__(self, art: Art, t: float):
        # The background drifts in a slow circle.
        dx = 240 + 200 * math.sin(t / 9.0)
        dy = 135 + 110 * math.cos(t / 11.0)
        self.px = art.background[int(dy):int(dy) + H, int(dx):int(dx) + W].copy()

    def paste(self, image: Image.Image, x: float, y: float, alpha: float = 1.0) -> None:
        if alpha <= 0.003:
            return
        x, y = int(round(x)), int(round(y))
        rgba = np.asarray(image if image.mode == "RGBA" else image.convert("RGBA"), np.float32)
        h, w = rgba.shape[:2]
        x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + w, W), min(y + h, H)
        if x1 <= x0 or y1 <= y0:
            return
        part = rgba[y0 - y:y1 - y, x0 - x:x1 - x]
        a = part[..., 3:4] / 255.0 * min(alpha, 1.0)
        region = self.px[y0:y1, x0:x1]
        region *= 1 - a
        region += part[..., :3] * a

    def darken(self, mask: Image.Image, x: float, y: float, alpha: float) -> None:
        x, y = int(round(x)), int(round(y))
        m = np.asarray(mask, np.float32) / 255.0 * alpha
        h, w = m.shape
        x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + w, W), min(y + h, H)
        if x1 <= x0 or y1 <= y0:
            return
        self.px[y0:y1, x0:x1] *= (1 - m[y0 - y:y1 - y, x0 - x:x1 - x])[..., None]

    def bytes(self) -> bytes:
        return np.clip(self.px, 0, 255).astype(np.uint8).tobytes()


# Reading takes ----------------------------------------------------------------

class Reader:
    """Frames of one part of a take at the output frame rate, holding the last one at the end."""

    def __init__(self, path: Path, start: float, end: float, speed: float):
        self.length = int(round((end - start) / speed * FPS))
        # screenrecord writes a frame only when the screen changes, so the picture at [start] may come from
        # a frame seconds earlier: read from before it, fill to a constant rate, then cut.
        lead = min(start, 8.0)
        vf = (f"fps={FPS},trim=start={lead:.3f}:end={lead + end - start:.3f},setpts=(PTS-STARTPTS)/{speed},"
              f"fps={FPS},scale={SRC_W}:{SRC_H}")
        self.proc = subprocess.Popen([FFMPEG, "-v", "error", "-ss", f"{start - lead:.3f}", "-i",
                                      str(path), "-vf", vf, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                                     stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.size = SRC_W * SRC_H * 3
        self.last: Image.Image | None = None
        self.count = 0

    def next(self) -> Image.Image:
        if self.count < self.length:
            data = self.proc.stdout.read(self.size)
            if len(data) == self.size:
                self.last = Image.frombytes("RGB", (SRC_W, SRC_H), data)
        self.count += 1
        if self.last is None:
            self.last = Image.new("RGB", (SRC_W, SRC_H), VOID)
        return self.last

    def close(self) -> None:
        self.proc.stdout.close()
        self.proc.kill()


class ShotPlayer:
    """The app picture of a shot at each of its frames: its parts in a row, each joined to the next by a
    short cross-fade, seen through the shot's camera."""

    JOIN = 6  # frames

    def __init__(self, camera: list, takes: Path, lang: str, length: float, parts: list[Part]):
        self.camera, self.length = camera, length
        suffix = "" if lang == "en" else f"-{lang}"
        self.parts = []
        for part in parts:
            path = takes / f"{part.take}{suffix}.mp4"
            if not suffix:
                shift = 0.0
            elif lang in part.shift:
                shift = part.shift[lang]
            else:
                shift = offset(takes / f"{part.take}.mp4", path, part.start, part.end)
            self.parts.append((path, part.start + shift, part.end + shift, part.speed, part.length))
        self.starts, t = [], 0.0
        for *_, length in self.parts:
            self.starts.append(t)
            t += length
        self.readers: dict[int, Reader] = {}

    def reader(self, i: int) -> Reader:
        if i not in self.readers:
            path, start, end, speed, length = self.parts[i]
            if i == len(self.parts) - 1:
                # The last part runs on to fill the scene up to the next beat.
                end = start + (self.length - self.starts[i] + 0.5) * speed
            self.readers[i] = Reader(path, start, min(end, duration(path) - 0.05), speed)
        return self.readers[i]

    def frame(self, t: float) -> Image.Image:
        i = max(k for k, s in enumerate(self.starts) if s <= t + 1e-6)
        picture = self.reader(i).next()
        if i + 1 < len(self.parts):
            left = self.starts[i + 1] - t
            if left < self.JOIN / FPS:
                picture = Image.blend(picture, self.reader(i + 1).next(), 1 - left / (self.JOIN / FPS))
        for k in [k for k in self.readers if k < i]:
            self.readers.pop(k).close()
        return self.look(picture, t)

    def look(self, picture: Image.Image, t: float) -> Image.Image:
        keys = self.camera or [(0, 1.0, *C)]
        if t <= keys[0][0]:
            zoom, cx, cy = keys[0][1:]
        elif t >= keys[-1][0]:
            zoom, cx, cy = keys[-1][1:]
        else:
            k = max(n for n, key in enumerate(keys) if key[0] <= t)
            a, b = keys[k], keys[k + 1]
            f = ease((t - a[0]) / (b[0] - a[0]))
            zoom, cx, cy = (a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f)
        cw, ch = SRC_W / zoom, SRC_H / zoom
        cx = min(max(cx, cw / 2), SRC_W - cw / 2)
        cy = min(max(cy, ch / 2), SRC_H - ch / 2)
        return picture.resize((FRAME_W, FRAME_H), Image.BICUBIC,
                              box=(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2))

    def close(self) -> None:
        for r in self.readers.values():
            r.close()
        self.readers.clear()


# The film ---------------------------------------------------------------------

@dataclass
class Scene:
    """A shot placed on the film's timeline."""
    shot: Shot
    start: float
    length: float
    parts: list[Part]
    captions: list[Caption]
    camera: list
    player: ShotPlayer | None = None


class Film:
    """The full film, the short cut, or the website's highlight video (no title, no end card, no sound)."""

    def __init__(self, art: Art, takes: Path, lang: str, kind: str = "full"):
        self.art, self.takes, self.lang, self.kind = art, takes, lang, kind
        self.scenes: list[Scene] = []
        self.intro = 0.0 if kind == "highlight" else INTRO_S
        t = max(0.0, self.intro - PUSH)
        cuts = {"short": SHORT, "highlight": HIGHLIGHT}.get(kind)
        plan = [(SHOTS[i], a, b) for i, a, b in cuts] if cuts else [(s, None, None) for s in SHOTS]
        for shot, a, b in plan:
            parts, captions, camera = (shot.parts, shot.captions, shot.camera) if a is None else self.cut(shot, a, b)
            raw = sum(p.length for p in parts)
            # Each scene hands over to the next on a beat of the music.
            length = snap(t + raw + PUSH) - PUSH - t
            self.scenes.append(Scene(shot, t, length, parts, captions, camera))
            t += length - PUSH
        if kind == "highlight":
            self.outro_start, self.length = float("inf"), t + PUSH
        else:
            self.outro_start = t
            self.length = t + (OUTRO_S if kind == "full" else 8 * BEAT)

    @staticmethod
    def cut(shot: Shot, a: float, b: float) -> tuple[list[Part], list[Caption], list]:
        """The stretch [a, b) of a shot: its parts, the caption shown at its start and the camera."""
        parts, t = [], 0.0
        for p in shot.parts:
            s, e = t, t + p.length
            if e > a and s < b:
                lo = p.start + max(0, a - s) * p.speed
                hi = p.start + (min(e, b) - s) * p.speed
                parts.append(Part(p.take, lo, hi, p.speed))
            t = e
        current = max((c for c in shot.captions if c.at <= a + 1e-6), key=lambda c: c.at)
        return parts, [Caption(0, current.text)], [(k - a, z, x, y) for k, z, x, y in shot.camera]

    def placement(self, scene: Scene, t: float) -> tuple[float, float, float, float]:
        """Where the app frame sits: it pushes in from the right and out to the left."""
        x, alpha, scale = float(FRAME_X), 1.0, 1.0
        local = t - scene.start
        if local < PUSH and scene is not self.scenes[0]:
            f = ease(local / PUSH)
            x += (1 - f) * W * 0.62
            scale = 0.9 + 0.1 * f
            alpha = min(1.0, f * 1.6)
        elif local < PUSH:
            # The first scene rises out of the title card.
            f = ease(local / PUSH)
            scale = 0.94 + 0.06 * f
            alpha = f
        out = scene.start + scene.length - t
        if out < PUSH and scene is not self.scenes[-1]:
            f = ease(1 - out / PUSH)
            x -= f * W * 0.62
            scale = min(scale, 1.0 - 0.1 * f)
            alpha = min(alpha, (1 - f) * 1.6)
        elif out < PUSH:
            f = ease(1 - out / PUSH)
            scale = min(scale, 1.0 - 0.06 * f)
            alpha = min(alpha, 1 - f)
        return x, scale, alpha, local

    def draw_scene(self, canvas: Canvas, scene: Scene, t: float) -> None:
        x, scale, alpha, local = self.placement(scene, t)
        if scene.player is None:
            scene.player = ShotPlayer(scene.camera, self.takes, self.lang, scene.length, scene.parts)
        picture = scene.player.frame(min(local, scene.length))
        w, h = int(FRAME_W * scale), int(FRAME_H * scale)
        fx, fy = x + (FRAME_W - w) / 2, FRAME_Y + (FRAME_H - h) / 2
        shadow = self.art.shadow
        if scale != 1.0:
            shadow = shadow.resize((int(shadow.width * scale), int(shadow.height * scale)))
            picture = picture.resize((w, h), Image.BILINEAR)
            mask = self.art.rounded((w, h), int(RADIUS * scale))
        else:
            mask = self.art.mask
        canvas.darken(shadow, fx - 120 * scale, fy - 120 * scale, alpha * 0.9)
        picture = picture.convert("RGBA")
        picture.putalpha(mask)
        canvas.paste(picture, fx, fy, alpha)
        self.draw_captions(canvas, scene, local)

    def draw_captions(self, canvas: Canvas, scene: Scene, local: float) -> None:
        caps = scene.captions
        eyebrow = self.art.text(scene.shot.eyebrow[self.lang].upper(), 26, 700, ACCENT, 4)
        # The eyebrow names the section: in with the scene, out with it.
        f_in = ease_out((local - PUSH * 0.5) / 0.5)
        f_out = ease(min(1.0, (scene.length - PUSH * 0.3 - local) / 0.35))
        a = min(f_in, f_out)
        canvas.paste(eyebrow, (W - eyebrow.width) / 2, 34 + (1 - f_in) * 20, a)
        for n, cap in enumerate(caps):
            begin = max(cap.at, PUSH * 0.55) if n == 0 else cap.at
            end = caps[n + 1].at if n + 1 < len(caps) else scene.length - PUSH * 0.3
            if not begin <= local < end + 0.35:
                continue
            head = self.art.text(cap.text[self.lang], 62, 600, ICE)
            f_in = ease_out((local - begin) / 0.55)
            f_out = ease(min(1.0, (end - local) / 0.35)) if local > end - 0.35 else 1.0
            rise = (1 - f_in) * 28 - (1 - f_out) * 16
            canvas.paste(head, (W - head.width) / 2, 72 + rise, min(f_in, f_out))

    def draw_intro(self, canvas: Canvas, t: float) -> None:
        art = self.art
        fade = 1 - ease((t - (INTRO_S - PUSH)) / PUSH)  # leaves while the first scene rises in
        lift = (1 - fade) * -50
        cx, cy = W / 2, H / 2 - 130 + lift
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        # The outer diamond draws itself, the inner one pops in.
        size = 12 * 150 / 26
        corners = [(cx, cy - size), (cx + size, cy), (cx, cy + size), (cx - size, cy), (cx, cy - size)]
        progress = ease(t / 1.3) * 4
        for i in range(4):
            seg = min(1.0, max(0.0, progress - i))
            if seg > 0:
                (x0, y0), (x1, y1) = corners[i], corners[i + 1]
                draw.line((x0, y0, x0 + (x1 - x0) * seg, y0 + (y1 - y0) * seg), fill=ACCENT + (255,), width=6)
        pop = back_out((t - 0.9) / 0.6) if t > 0.9 else 0
        if pop > 0:
            s = 4.5 * 150 / 26 * pop
            draw.polygon([(cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)], fill=ACCENT + (255,))
        canvas.paste(layer, 0, 0, fade)
        # GLACIER, letter by letter, then the line below.
        spacing = 26
        letters = [art.text(c, 104, 600, ICE) for c in "GLACIER"]
        total = sum(l.width - 8 for l in letters) + spacing * 6
        x = (W - total) / 2
        for i, letter in enumerate(letters):
            f = ease_out((t - 1.2 - i * 0.07) / 0.5)
            canvas.paste(letter, x - 4, H / 2 - 20 + (1 - f) * 30 + lift, f * fade)
            x += letter.width - 8 + spacing
        tag = art.text(TITLE[self.lang], 46, 400, MIST)
        f = ease_out((t - 2.1) / 0.6)
        canvas.paste(tag, (W - tag.width) / 2, H / 2 + 120 + (1 - f) * 20 + lift, f * fade)

    def draw_outro(self, canvas: Canvas, t: float) -> None:
        art = self.art
        local = t - self.outro_start
        out = ease(min(1.0, (self.length - t) / 0.8))
        lines = END[self.lang]
        items = [
            (art.text("GLACIER", 76, 600, ICE, 18), 420, 0.0),
            (art.text(lines[0], 58, 600, ICE), 560, 0.25),
            (art.text(lines[1], 38, 400, MIST), 652, 0.45),
            (art.text(lines[2], 38, 500, ACCENT), 720, 0.6),
            (art.text(SITE, 34, 400, MIST), 840, 0.8),
            (art.text(CREDIT, 24, 400, DIM), 990, 1.0),
        ]
        f = back_out((local - 0.1) / 0.6)
        if f > 0:
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            draw = ImageDraw.Draw(layer)
            cx, cy, s = W / 2, 330, 12 * 110 / 26 * f
            draw.polygon([(cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)], outline=ACCENT + (255,), width=5)
            si = s * 4.5 / 12
            draw.polygon([(cx, cy - si), (cx + si, cy), (cx, cy + si), (cx - si, cy)], fill=ACCENT + (255,))
            canvas.paste(layer, 0, 0, min(1.0, local / 0.3) * out)
        for image, y, delay in items:
            f = ease_out((local - 0.3 - delay) / 0.6)
            canvas.paste(image, (W - image.width) / 2, y + (1 - f) * 24, f * out)

    def render(self, out: Path, music: Path | None) -> None:
        frames = int(round(self.length * FPS))
        fade_at = self.length - 2.5
        video = ["-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-"]
        if music:
            cmd = [FFMPEG, "-v", "error", "-y", *video, "-i", str(music), "-filter_complex",
                   f"[0:v]format=yuv420p[v];[1:a]atrim=0:{self.length:.3f},afade=t=out:st={fade_at:.3f}:d=2.5,"
                   f"volume=0.9[a]", "-map", "[v]", "-map", "[a]", "-c:a", "aac", "-b:a", "192k"]
        else:
            cmd = [FFMPEG, "-v", "error", "-y", *video, "-vf", "format=yuv420p", "-an"]
        cmd += ["-c:v", "libx264", "-preset", "slow", "-crf", "18", "-movflags", "+faststart", str(out)]
        encoder = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        for n in range(frames):
            t = n / FPS
            canvas = Canvas(self.art, t)
            if t < self.intro:
                self.draw_intro(canvas, t)
            for scene in self.scenes:
                if scene.start <= t < scene.start + scene.length:
                    self.draw_scene(canvas, scene, t)
                elif t >= scene.start + scene.length and scene.player:
                    scene.player.close()
                    scene.player = None
            if t >= self.outro_start:
                self.draw_outro(canvas, t)
            encoder.stdin.write(canvas.bytes())
            if n % (FPS * 15) == 0:
                print(f"    {t:5.1f} / {self.length:.1f} s", flush=True)
        encoder.stdin.close()
        encoder.wait()
        print(f"  {out.name} ({self.length:.0f} s)")


def encode(source: Path, out: Path, *args) -> None:
    subprocess.run([FFMPEG, "-v", "error", "-y", "-i", str(source), *[str(a) for a in args], str(out)], check=True)


def still(takes: Path, lang: str, take: str, at: float) -> Image.Image:
    """A picture from a take. A still moment has little motion to match on, so the other languages are
    matched on the seconds that lead up to it."""
    suffix = "" if lang == "en" else f"-{lang}"
    path = takes / f"{take}{suffix}.mp4"
    if suffix:
        at += offset(takes / f"{take}.mp4", path, max(0.0, at - 6), at + 2, search=6.0)
    reader = Reader(path, at, at + 0.2, 1.0)
    picture = reader.next()
    reader.close()
    return picture


def stills(takes: Path, lang: str) -> None:
    """The website's section pictures."""
    suffix = "" if lang == "en" else f"-{lang}"
    pictures = {**STILLS, **SITE_STILLS}
    for name, (take, at) in pictures.items():
        still(takes, lang, take, at).resize((1280, 720), Image.LANCZOS).save(
            SITE_DIR / "screenshots" / f"{name}{suffix}.jpg", quality=88)
    print(f"  {len(pictures)} section pictures")


def store(takes: Path, lang: str) -> None:
    """The Google Play screenshots: the section pictures at the takes' 1080p, numbered in their order."""
    folder = STORE_DIR / STORE_LOCALES[lang] / "images" / "tvScreenshots"
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    for n, (name, (take, at)) in enumerate(STILLS.items(), 1):
        still(takes, lang, take, at).convert("RGB").save(folder / f"{n}-{name}.jpg", quality=92)
    print(f"  {len(STILLS)} store screenshots ({STORE_LOCALES[lang]})")


def website(art: Art, takes: Path, lang: str) -> None:
    """The website's media: section pictures, the highlight video at the top and the film at the end."""
    suffix = "" if lang == "en" else f"-{lang}"
    shots, promo = SITE_DIR / "screenshots", SITE_DIR / "promo"
    promo.mkdir(parents=True, exist_ok=True)
    stills(takes, lang)
    highlight = OUT / f"highlight{suffix}.mp4"
    Film(art, takes, lang, "highlight").render(highlight, None)
    scale = "scale=1280:-2:flags=lanczos"
    encode(highlight, shots / f"demo{suffix}.mp4", "-vf", scale, "-c:v", "libx264", "-preset", "slow", "-crf", 26,
           "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an")
    encode(highlight, shots / f"demo{suffix}.webm", "-vf", scale, "-c:v", "libvpx-vp9", "-crf", 38, "-b:v", 0,
           "-row-mt", 1, "-an")
    # Its poster: the first scene once it has settled.
    encode(highlight, shots / f"hero{suffix}.jpg", "-ss", 2.0, "-frames:v", 1, "-vf", "scale=1280:-2:flags=lanczos",
           "-q:v", 3)
    if not suffix:
        # The README shows the highlight video as a GIF; GitHub leaves out images over 10 MB.
        encode(highlight, shots / "demo.gif", "-vf", "fps=8,scale=560:-2:flags=lanczos,split[a][b];"
               "[a]palettegen=max_colors=96:stats_mode=diff[p];"
               "[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle")
    film = OUT / f"glacier-promo-web{suffix}.mp4"
    if film.exists():
        shutil.copy(film, promo / f"glacier-promo{suffix}.mp4")
        encode(OUT / f"glacier-promo{suffix}.mp4", promo / f"glacier-promo{suffix}.jpg", "-ss", INTRO_S - 1.5,
               "-frames:v", 1, "-vf", "scale=1280:720:flags=lanczos", "-q:v", 3)
    print(f"  website media ({lang})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--takes", type=Path, required=True, help="folder with the takes of capture-website-media.py")
    parser.add_argument("--lang", default="en,de", help="comma-separated languages")
    parser.add_argument("--only", default="full,short,website", help="any of full, short, website, stills, store")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    art = Art(fetch(*FONT))
    music = fetch(*MUSIC)
    wanted = args.only.split(",")
    for lang in args.lang.split(","):
        print(lang)
        suffix = "" if lang == "en" else f"-{lang}"
        if "full" in wanted:
            master = OUT / f"glacier-promo{suffix}.mp4"
            Film(art, args.takes, lang, "full").render(master, music)
            # The website's copy: the same film, smaller.
            subprocess.run([FFMPEG, "-v", "error", "-y", "-i", str(master), "-vf", "scale=1280:-2:flags=lanczos",
                            "-c:v", "libx264", "-preset", "slow", "-crf", "26", "-c:a", "aac", "-b:a", "160k",
                            "-movflags", "+faststart", str(OUT / f"glacier-promo-web{suffix}.mp4")], check=True)
            print(f"  build/promo/glacier-promo-web{suffix}.mp4")
        if "short" in wanted:
            Film(art, args.takes, lang, "short").render(OUT / f"glacier-promo-short{suffix}.mp4", music)
        if "website" in wanted:
            website(art, args.takes, lang)
        elif "stills" in wanted:
            stills(args.takes, lang)
        if "store" in wanted:
            store(args.takes, lang)


if __name__ == "__main__":
    main()
