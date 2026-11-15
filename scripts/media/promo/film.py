#!/usr/bin/env python3
"""The Glacier promo film (~2 min) and its highlight cut (~52 s) in English, German and Spanish.

What:   Builds both films from the screen recordings (takes), the 3D plates rendered in Blender, the music and the texts, and
        writes the 1080p masters, a silent highlight, a README GIF and contact sheets. With --website it also writes the media
        of the website (720p film, silent highlight as mp4 and webm, GIF, poster images). The timeline is data: scenes placed
        on the bars of the music (118 bpm, measured from the track), texts per language, take moments per language.
        The compositor (frames, stage, type, sound) is compositor.py.

          full       S0 3D opening (wall -> library)          S1 library, home            S2 profiles, kids
                     S3 versions, audio and subtitles         S4 type break (formats)     S5 player: skip, seek, next episode
                     S6 lyrics   S7 photos, live TV   S8 3D end (library -> poster diamond -> logo and wordmark)
          highlight  8 s opening, library, kids, player montage, photos and live TV, end; the music is edited on bar lines

        Screens follow each other with a 3D crossfade (10 degrees, 0.6 s, whoosh) or a dissolve; changes inside a screen are
        eased (no jumps), long holds get a slow push. Every cut is snapped to the beat grid of the music.

Pipeline: see scripts/README.md ("The promo film"): record takes (capture-website-media.py) -> layers (layers/) -> posters
        (fetch_posters.py) -> Blender plates (posterwall.py, assemble.py) -> this script.

Inputs (all under build/promo/, the folder is git-ignored):
  cache/film-music.flac   the track, made with ACE-Step 1.5 (MIT; generated music, usable commercially). Copy it there or pass
                          --music FILE. sha256 a2fa4e197c88b85e30f52c7309ecb7686d1682d812d901a1c6719fd594ef970f
  cache/film-takes/       <take>[-de|-es].mp4 for hero, family, library, detail, skip, upnext, trickplay, music, livetv, photos
                          (1920x1080 screen recordings of the demo server, variable frame rate; --takes DIR for another folder).
                          library.mp4 must be the take whose frame 0 the 3D plates end on (and the language's own for de/es).
  blender/assemble/       the plates as PNG sequences: wall2lib_long[_de|_es] (466 frames), wall2lib[_de|_es] (241),
                          lib2logo[_de|_es] (241); a language without its own folder uses the English one
  layers/LIB[_de|_es]/keyframe.png   the library key frame (what the plates end on), shown before the end plate
Needs:  Python 3.9+, numpy, Pillow, OpenCV (opencv-python-headless), fontTools + brotli, ffmpeg and ffprobe on PATH.

Usage:  film.py [--lang en,de,es] [--cut full,highlight] [--preview] [--sheet] [--website] [--jobs N] [--out DIR]
                [--takes DIR] [--music FILE] [--frames T,T] [--range T0,T1]
        --cut site only encodes the website media from existing masters. --frames writes single frames as PNGs, --range renders
        a stretch without sound (both for checking).
Output: build/promo/film/glacier-promo[-de|-es].mp4 (1080p), glacier-highlight[-de|-es].mp4, glacier-highlight-silent[-de|-es].mp4,
        glacier-highlight.gif (English silent cut), *.sheet.png. Master loudness -14 LUFS, true peak <= -1 dBTP (measured on the AAC).
"""
from __future__ import annotations

import argparse
import shutil
import multiprocessing as mp
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compositor as P  # noqa: E402
from compositor import Chip, Film, Headline, Layer, Scene, Segment, Shot, Sfx, XFADE, ease, smooth  # noqa: E402

BUILD = P.BUILD
OUT = BUILD / "film"
LAYERS = BUILD / "layers"
ASSEMBLE = P.BLENDER / "assemble"
LANGS = ("en", "de", "es")
BUTTON = (1720, 746)                                 # Skip Intro button centre in the take (1080p)
LIBRARY_MOVES = (2.87, 3.57, 4.23, 5.20, 5.73)       # focus moves in the library take (seconds)
LENGTH_FULL, LENGTH_HIGHLIGHT = 120.0, 52.0

# ---------------------------------------------------------------------------------------------------------------------
# Texts (kinetic headlines: "|" breaks a line, *...* is the accent colour)
# ---------------------------------------------------------------------------------------------------------------------
TEXT = {
    "library": {"en": "Your whole library,|*beautifully* arranged.", "de": "Deine ganze Bibliothek,|*schön* geordnet.",
                "es": "Toda tu biblioteca,|*bien* ordenada."},
    "hero": {"en": "Pick up where|you *left off.*", "de": "Weiterschauen,|wo du *aufgehört* hast.", "es": "Sigue donde|lo *dejaste.*"},
    "profiles": {"en": "A profile|for *everyone.*", "de": "Ein Profil|für *jeden.*", "es": "Un perfil|para *cada uno.*"},
    "kids": {"en": "Kids only see|what suits their *age.*", "de": "Kinder sehen nur,|was zu ihrem *Alter* passt.",
             "es": "Los niños solo ven|lo que va con su *edad.*"},
    "versions": {"en": "Pick the version:|*2160p HDR* or 1080p.", "de": "Die Fassung wählen:|*2160p HDR* oder 1080p.",
                 "es": "Elige la versión:|*2160p HDR* o 1080p."},
    "tracks": {"en": "Every track,|*clearly* marked.", "de": "Jede Spur,|klar *gekennzeichnet.*", "es": "Cada pista,|*bien* marcada."},
    "disc": {"en": "Sound and picture|as on the *disc.*", "de": "Ton und Bild|wie auf der *Disc.*",
             "es": "Imagen y sonido|como en el *disco.*"},
    "intro": {"en": "Intro?|*One press.*", "de": "Intro?|Ein *Tastendruck.*", "es": "¿Intro?|*Una tecla.*"},
    "seek": {"en": "Seek with|*preview* pictures.", "de": "Spulen mit|*Vorschaubildern.*", "es": "Avanza con|*miniaturas.*"},
    "next": {"en": "The next episode,|ready before the *credits.*", "de": "Die nächste Folge,|bereit vor dem *Abspann.*",
             "es": "El siguiente episodio,|listo antes de los *créditos.*"},
    "lyrics": {"en": "Lyrics, *large*|and in time.", "de": "Songtexte, *groß*|und im Takt.", "es": "Letras *grandes*|y al ritmo."},
    "photos": {"en": "Your photos|and *home videos.*", "de": "Deine Fotos|und *Heimvideos.*", "es": "Tus fotos|y *videos caseros.*"},
    "live": {"en": "Live TV with|a *full guide.*", "de": "Live-TV mit|*Programmführer.*", "es": "TV en directo|con *guía.*"},
}
CHIPS = ("TrueHD", "DTS-HD", "Dolby Vision")
# Where each headline stays clear of the key element of its screen: x (left, right edge or centre), top y, alignment, scrim
HPOS = {
    "library": (112, 832, "left", 0.9), "hero": (1810, 124, "right", 0.6), "profiles": (112, 832, "left", 0.8),
    "kids": (1810, 124, "right", 0.6), "versions": (1810, 790, "right", 0.6), "tracks": (1810, 790, "right", 0.6),
    "intro": (112, 330, "left", 0.6), "seek": (112, 330, "left", 0.85), "next": (112, 150, "left", 0.7),
    "lyrics": (1810, 800, "right", 0.75), "photos": (112, 118, "left", 0.85), "live": (1810, 860, "right", 0.9),
}

# Moments in the English takes (seconds, found by frame differencing and by looking); DE/ES override what differs
EV = {
    "en": dict(
        family_focus=3.1, family_select=4.8, family_kids=7.0, family_picker2=19.0, family_select2=22.07, family_nora=25.0,
        detail_start=2.96, detail_clicks=(5.0, 7.7, 9.03, 12.77, 14.27, 16.7, 18.13), detail_end=19.23,
        skip_press=11.47, trick_start=20.5, trick_keys=(21.0, 21.8, 22.6, 23.4, 24.2),
        upnext_start=22.75, lyrics_start=26.0, hero_start=0.2, hero_pre=3.8, hero_post=4.6,
        photos=((4.6, 7.4), 9.15, 17.0, 21.93), live=(9.5, 18.2, 21.3), lib_loaded=11.45, lib_hold=13.25,
    ),
}
# German and Spanish takes differ by a few tenths (the German skip take plays on until its press at 22.2 s)
EV["de"] = {**EV["en"], "family_focus": 3.1, "family_select": 4.57, "family_kids": 6.9, "family_picker2": 19.16,
            "family_select2": 21.0, "family_nora": 23.93, "detail_start": 2.86, "detail_clicks": (4.9, 7.53, 8.93, 12.67, 14.47, 16.7, 18.13),
            "skip_press": 22.2, "trick_start": 20.0, "trick_keys": (20.5, 21.3, 22.1, 22.9, 23.7), "upnext_start": 22.69,
            "lib_loaded": 11.41, "lib_hold": 12.65}
EV["es"] = {**EV["en"], "family_focus": 3.13, "family_select": 5.33, "family_kids": 7.9, "family_picker2": 19.73,
            "family_select2": 21.77, "family_nora": 24.7, "detail_start": 3.10, "detail_clicks": (4.97, 7.83, 9.17, 12.93, 14.37, 16.9, 18.43),
            "skip_press": 11.6, "trick_start": 20.36, "trick_keys": (20.86, 21.66, 22.46, 23.26, 24.06), "upnext_start": 23.22,
            "lib_loaded": 10.68, "lib_hold": 12.48}


def take_name(name: str, lang: str) -> str:
    """The take for a language (the English library is the one the 3D plates land on)."""
    if lang == "en":
        return name
    return f"{name}-{lang}" if (P.CACHE / "film-takes" / f"{name}-{lang}.mp4").exists() else name


def plate_folder(name: str, lang: str) -> str:
    """3D plate folder of a language (falls back to the English one until the per-language plates exist)."""
    if lang != "en" and (ASSEMBLE / f"{name}_{lang}").is_dir() and any((ASSEMBLE / f"{name}_{lang}").glob("*.png")):
        return f"{name}_{lang}"
    return name


def lib_keyframe(lang: str) -> str:
    for d in (f"LIB_{lang}", "LIB"):
        f = LAYERS / d / "keyframe.png"
        if f.exists():
            return str(f)
    raise FileNotFoundError("layers/LIB/keyframe.png")


# ---------------------------------------------------------------------------------------------------------------------
# Music grid
# ---------------------------------------------------------------------------------------------------------------------

class Bars:
    """Beat times of the music: t(k) is beat k; bars are 4 beats, sections start on multiples of 4."""

    def __init__(self):
        self.g = P.analyse_music(P.CACHE / "film-music.flac", (117.5, 118.5), drop_hint=67.1)
        self.beat = self.g.beat
        self.phase = self.g.phase % self.g.beat

    def t(self, k: float) -> float:
        return round(self.phase + k * self.beat, 3)


# ---------------------------------------------------------------------------------------------------------------------
# Scene assembly
# ---------------------------------------------------------------------------------------------------------------------

@dataclass
class Entry:
    """A screen (a segment of a take, or nothing: the bare stage) that is visible from `start` until the next entry."""
    seg: int | None
    start: float
    trans: str = "3d"                  # how it arrives: 3d (crossfade with tilt), cut, dissolve
    cam: list = field(default_factory=list)   # camera keys (seconds into the entry, zoom, centre x, centre y)
    dis: float = 0.35                  # length of a dissolve into this entry


class Build:
    def __init__(self, lang: str, B: Bars):
        self.lang, self.B, self.ev = lang, B, EV[lang]
        self.segments: list[Segment] = []
        self.entries: list[Entry] = []
        self.headlines: list[Headline] = []
        self.sfx: list[Sfx] = []
        self.chips: list[Chip] = []

    def seg(self, take: str, knots, **kw) -> tuple[int, Segment]:
        s = Segment(take if take.startswith("@") else take_name(take, self.lang), knots, **kw)
        self.segments.append(s)
        return len(self.segments) - 1, s

    def enter(self, idx, start, trans="3d", cam=None, whoosh=True, dis=0.35) -> None:
        self.entries.append(Entry(idx, start, trans, cam or [], dis))
        if trans == "3d" and whoosh:
            self.sfx.append(Sfx("whoosh", start, "3D crossfade", 1.1))

    def head(self, key: str, start: float, end: float, **kw) -> None:
        x, y, align, scrim = HPOS[key]
        self.headlines.append(Headline(TEXT[key][self.lang], start, end, x, y, align, scrim=scrim, **kw))

    def click(self, seg: Segment, take_t: float, note: str, soft: bool = False) -> None:
        self.sfx.append(Sfx("click_soft" if soft else "click", seg.when(take_t) - 0.02, note))

    # ---- the scenes: each places one stretch of a take on the film timeline ---------------------------------------
    def library(self, t0: float, dur: float) -> None:
        """Focus moves, then the A-Z jump: a 0.45 s dissolve into the loaded page with a small push (not a hard jump)."""
        ff = t0 + 0.7 + (5.9 - 2.6)
        i, s = self.seg("library", [(t0, 0.0), (t0 + 0.7, 2.6), (ff, 5.9)])
        self.enter(i, t0, "cut", [(0, 1.0, 960, 540), (ff - t0 - 0.6, 1.0, 960, 540), (ff - t0 + 0.1, 1.02, 960, 540)])
        j, _ = self.seg("library", [(ff, self.ev["lib_loaded"]), (t0 + dur + 0.3, self.ev["lib_hold"])])
        self.enter(j, ff, "dissolve", [(0, 1.02, 960, 540), (t0 + dur - ff, 1.05, 960, 540)], dis=0.45)
        for c in LIBRARY_MOVES:
            if t0 + 0.7 + (c - 2.6) < t0 + dur - 1.0:
                self.click(s, c, "library focus move", True)
        self.sfx.append(Sfx("click", ff + 0.08, "A-Z jump"))
        self.head("library", t0 + 2 * self.B.beat * 2, t0 + dur - 0.5)

    def hero(self, t0: float, dur: float) -> None:
        """The take scrolls down from the hero to the rows at about 4 s (a few half-scrolled frames): that is never shown. The
        pre-scroll home holds (slow push), then a dissolve goes straight to the settled rows, where the focus moves on."""
        e = self.ev
        a, pre, post = e["hero_start"], e["hero_pre"], e["hero_post"]
        hold = t0 + (pre - a) + 0.8                     # the pre-scroll picture stays a little longer than the take plays
        i, _ = self.seg("hero", [(t0, a), (t0 + pre - a, pre), (hold, pre)])
        self.enter(i, t0, cam=[(0, 1.0, 960, 540), (hold - t0, 1.03, 960, 540)])
        j, _ = self.seg("hero", [(hold, post), (t0 + dur + 0.3, post + dur + 0.3 - (hold - t0))])
        self.enter(j, hold, "dissolve", [(0, 1.03, 960, 540), (t0 + dur - hold, 1.05, 960, 540)], dis=0.45)
        self.head("hero", t0 + 2 * self.B.beat, t0 + dur - 0.5)

    def family_a(self, t0: float, pick: float, kids: float, with_head: bool = True) -> float:
        """Profile picker -> Kids chosen -> Kids home (dissolve). Returns the time Kids is chosen."""
        e = self.ev
        sel = t0 + pick
        i, s = self.seg("family", [(t0, e["family_select"] - pick), (sel, e["family_select"])], hold_max=e["family_select"] - 0.03)
        self.enter(i, t0, cam=[(0, 1.0, 960, 540), (pick, 1.05, 960, 540)])        # no frozen picker: a slow push
        if e["family_select"] - pick < e["family_focus"]:
            self.click(s, e["family_focus"], "focus moves to Kids", True)
        self.sfx.append(Sfx("click", sel - 0.02, "Kids selected"))
        j, _ = self.seg("family", [(sel, e["family_kids"]), (sel + kids + 0.3, e["family_kids"])])
        self.enter(j, sel, "dissolve", [(0, 1.0, 960, 540), (kids, 1.15, 880, 690)])
        if with_head:
            if pick >= 3:
                self.head("profiles", t0 + self.B.beat * 2, sel - 0.4)
            self.head("kids", sel + self.B.beat * 2, sel + kids - 0.5)
        return sel

    def family_b(self, t0: float, pick: float, nora: float) -> None:
        e = self.ev
        i, s = self.seg("family", [(t0, e["family_picker2"]), (t0 + pick, e["family_picker2"] + pick)], hold_max=e["family_select2"] - 0.05)
        self.enter(i, t0, "dissolve", [(0, 1.0, 960, 540), (pick, 1.04, 960, 540)])
        self.sfx.append(Sfx("click", t0 + pick - 0.02, "Nora selected"))
        j, _ = self.seg("family", [(t0 + pick, e["family_nora"]), (t0 + pick + nora, e["family_nora"] + nora)])
        self.enter(j, t0 + pick, "dissolve", [(0, 1.0, 960, 540), (nora, 1.04, 960, 540)])

    def detail(self, t0: float, dur: float) -> None:
        e = self.ev
        a = e["detail_start"]
        i, s = self.seg("detail", [(t0, a), (t0 + dur, a + dur)])
        self.enter(i, t0)
        cl = e["detail_clicks"]
        for n, c in enumerate(cl):
            if a <= c <= a + dur:
                self.click(s, c, "detail: OK / focus", n in (3, 5))
        self.head("versions", s.when(cl[0]) + 0.1, s.when(cl[1]) - 0.3)
        self.head("tracks", s.when(cl[2]) + 0.1, t0 + dur - 1.3)

    def disc(self, t0: float, dur: float) -> None:
        """Type only on the dark stage: the headline big and kinetic, a format chip on each of the next beats."""
        self.enter(None, t0)
        b = self.B.beat
        self.headlines.append(Headline(TEXT["disc"][self.lang], t0 + 0.2, t0 + dur - 0.05, 960, 330, "center", scrim=0.0,
                                       size=104, max_w=1500))
        for n, text in enumerate(CHIPS):
            self.chips.append(Chip(text, t0 + b * (n + 1), t0 + dur + 0.3, (560, 960, 1400)[n], 640))

    def skip(self, t0: float, dur: float, pre: float, with_head: bool = True) -> None:
        e = self.ev
        tp = t0 + pre
        i, s = self.seg("skip", [(t0, e["skip_press"] - pre), (tp, e["skip_press"]), (t0 + dur + 0.3, e["skip_press"] + dur + 0.3 - pre)])
        cam = [(0, 1.0, 960, 540), (pre - 0.6, 1.0, 960, 540), (pre - 0.05, 2.0, *BUTTON), (pre + 0.65, 2.0, *BUTTON),
               (min(pre + 1.7, dur - 0.1), 1.0, 960, 540)]
        self.enter(i, t0, cam=cam)
        self.sfx.append(Sfx("click", tp - 0.02, "Skip Intro pressed"))
        if with_head:
            self.head("intro", t0 + (0.5 if pre > 2.5 else 0.2), min(tp + 1.6, t0 + dur - 0.3))

    def trick(self, t0: float, dur: float, with_head: bool = True) -> None:
        e = self.ev
        a = e["trick_start"]
        i, s = self.seg("trickplay", [(t0, a), (t0 + dur + 0.3, a + dur + 0.3)])
        cam = [(0, 1.0, 960, 540), (0.9, 1.0, 960, 540), (1.7, 1.8, 1300, 700), (dur - 1.6, 1.8, 1700, 700), (dur - 0.5, 1.0, 960, 540)]
        self.enter(i, t0, cam=cam)
        for c in e["trick_keys"]:
            if a <= c <= a + dur:
                self.click(s, c, "seek step", True)
        if with_head:
            self.head("seek", t0 + self.B.beat * 2, t0 + dur - 0.5)

    def upnext(self, t0: float, dur: float, with_head: bool = True) -> None:
        a = self.ev["upnext_start"]
        i, s = self.seg("upnext", [(t0, a), (t0 + dur + 0.3, a + dur + 0.3)])
        cam = [(0, 1.0, 960, 540), (0.7, 1.0, 960, 540), (1.5, 1.7, 1560, 830), (dur - 0.6, 1.7, 1560, 830), (dur - 0.15, 1.0, 960, 540)]
        self.enter(i, t0, cam=cam)
        if with_head:
            self.head("next", t0 + self.B.beat, t0 + dur - 0.4)

    def lyrics(self, t0: float, dur: float) -> None:
        a = self.ev["lyrics_start"]
        i, _ = self.seg("music", [(t0, a), (t0 + dur + 0.3, a + dur + 0.3)])
        self.enter(i, t0)
        self.head("lyrics", t0 + self.B.beat * 2, t0 + dur - 0.5)

    def photos(self, t0: float, dur: float, with_head: bool = True) -> None:
        (g0, g1), opened, ff, end = self.ev["photos"]
        d = min(g1 - g0, dur - 1.7)   # the gallery (shorter in the short cut)
        t1, hold = t0 + d, 0.9
        i, _ = self.seg("photos", [(t0, g1 - d), (t1, g1)])
        self.enter(i, t0, cam=[(0, 1.0, 960, 540), (d, 1.03, 960, 540)])
        j, s_ = self.seg("photos", [(t1, opened), (t1 + hold, opened + hold)])     # the picture opens (the take's menu popup is skipped)
        self.enter(j, t1, "dissolve", [(0, 1.02, 960, 540), (hold, 1.04, 960, 540)], dis=0.4)
        k_, _ = self.seg("photos", [(t1 + hold, ff), (t1 + hold + (end - ff), end)])   # on to the next pictures (static strip skipped)
        self.enter(k_, t1 + hold, "dissolve", [(0, 1.03, 960, 540), (dur, 1.05, 960, 540)], dis=0.4)
        self.sfx.append(Sfx("click", t1 + 0.1, "photo opens"))
        if with_head:
            self.head("photos", t1 + 0.6, t0 + dur - 0.5)

    def live(self, t0: float, dur: float, with_head: bool = True) -> None:
        a, g, e = self.ev["live"]
        ch = min(2.4, dur)                       # channels; with room left: dissolve to the guide (the loading skipped)
        i, _ = self.seg("livetv", [(t0, a), (t0 + ch, a + ch)])
        self.enter(i, t0, "3d", [(0, 1.0, 960, 540), (ch, 1.02, 960, 540)])      # a new screen: the 3D crossfade, never a hard cut
        if dur > ch + 0.8:
            j, _ = self.seg("livetv", [(t0 + ch, g), (t0 + ch + (e - g) + 4, e + 4)])
            self.enter(j, t0 + ch, "dissolve", [(0, 1.02, 960, 540), (dur, 1.05, 960, 540)], dis=0.45)
        if with_head:
            self.head("live", t0 + (3.0 if dur > 4 else 1.0), t0 + dur - 0.4)

    def lib_still(self, t0: float) -> None:
        i, _ = self.seg("@" + lib_keyframe(self.lang), [(t0, 0.0), (t0 + 5, 5.0)])
        self.enter(i, t0)

    # ---- the stage ---------------------------------------------------------------------------------------------------
    def scene(self):
        entries, h = sorted(self.entries, key=lambda e: e.start), XFADE / 2

        def layer(e: Entry, t: float, **geo) -> Layer | None:
            if e.seg is None:
                return None
            rect = P.Renderer.camera(Shot(e.start, 1e9, cam=e.cam), t) if e.cam else None
            return Layer(e.seg, rect=rect, **geo)

        def tilt(a: Entry, b: Entry, t0: float, t: float) -> list:
            u = min(max((t - t0) / XFADE, 0), 1)
            k = float(ease(u))
            out = layer(a, t, scale=1 - P.SINK * k, yaw=P.TILT * k, dx=-P.DRIFT * k, alpha=1 - float(smooth(0.25, 0.85, u)))
            inc = layer(b, t, scale=1 - P.SINK * (1 - k), yaw=-P.TILT * (1 - k), dx=P.DRIFT * (1 - k), alpha=float(smooth(0.15, 0.75, u)))
            return [x for x in (out, inc) if x]

        def scene(t: float) -> Scene:
            j = max((n for n, e in enumerate(entries) if e.start <= t), default=0)
            cur = entries[j]
            if j + 1 < len(entries) and entries[j + 1].trans == "3d" and t >= entries[j + 1].start - h:
                return Scene(tilt(cur, entries[j + 1], entries[j + 1].start - h, t))
            if j > 0 and cur.trans == "3d" and t < cur.start + h:
                return Scene(tilt(entries[j - 1], cur, cur.start - h, t))
            if j > 0 and cur.trans == "dissolve" and t < cur.start + cur.dis:
                prev, new = layer(entries[j - 1], t), layer(cur, t, alpha=float(ease((t - cur.start) / cur.dis)))
                return Scene([x for x in (prev, new) if x])
            lay = layer(cur, t)
            return Scene([lay] if lay else [])
        return scene


# ---------------------------------------------------------------------------------------------------------------------
# The two cuts
# ---------------------------------------------------------------------------------------------------------------------

def end_logo(lang: str, hit: float) -> dict:
    """Posters fade out of the diamond, the vector logo shrinks and moves left, GLACIER slides in on its right; the lockup lands on the
    final hit, the tagline follows a beat later with the small line below. Times are relative to the hit (beats of 0.51 s)."""
    lines = P.promo.END[lang]
    return dict(plate="L2L", plate_center=(960, 421), plate_radius=372, fade=(hit - 2.55, hit - 1.53), move=(hit - 1.53, hit - 0.51),
                reveal=(hit - 1.0, hit), tagline_at=hit + 0.5, tagline=P.promo.TITLE[lang], tag_size=54, tag_gap=60,
                lines=[lines[1], lines[2]], line_size=32, size=166, spacing=22, gap=76, r_lock=138, lock_y=410)


def build_full(lang: str, B: Bars) -> Film:
    T = B.t
    b = Build(lang, B)
    b.library(T(32), T(48) - T(32))
    b.hero(T(48), T(64) - T(48))
    b.family_a(T(64), T(72) - T(64), T(86) - T(72))
    b.family_b(T(86), T(92) - T(86), T(96) - T(92))
    b.detail(T(96), T(128) - T(96))
    b.disc(T(128), T(132) - T(128))
    b.skip(T(132), T(144) - T(132), T(137) - T(132))
    b.trick(T(144), T(156) - T(144))
    b.upnext(T(156), T(164) - T(156))
    b.lyrics(T(164), T(180) - T(164))
    b.photos(T(180), T(196) - T(180))
    b.live(T(196), T(208) - T(196))
    b.lib_still(T(208))
    seq = {"LONG": (plate_folder("wall2lib_long", lang), 466), "L2L": (plate_folder("lib2logo", lang), 241)}
    shots = [Shot(0, T(32), "LONG"), Shot(T(32), T(210), scene=b.scene()), Shot(T(210), LENGTH_FULL, "L2L", plate_speed=1.0)]
    film = Film("film-full", LENGTH_FULL, shots, b.segments, b.headlines, breath=None, grade=False,
                music="film-music.flac", lufs=-14.0, takes="film-takes", fade_out=(117.0, 119.6), fade_in=0.4, seq_plates=seq,
                chips=b.chips, endlogo=end_logo(lang, T(226)), smooth_takes=True,
                events=dict(opening_end=T(32), library=T(32), hero=T(48), profiles=T(64), detail=T(96), formats=T(128),
                            player=T(132), lyrics=T(164), photos=T(180), live=T(196), end=T(208), plate=T(210), wordmark=T(226),
                            press=T(137)))
    film.sfx = b.sfx + [Sfx("thump", T(226), "wordmark hit"), Sfx("whoosh", T(208), "into the end", 1.4)]
    return film


def build_highlight(lang: str, B: Bars) -> Film:
    """8 s opening on the drop, library, kids, player montage, photos + live TV, end. Music pieces on bar lines."""
    T, bar = B.t, 4 * B.beat
    b = Build(lang, B)
    seam = 4 * bar                         # 8.14 s: the opening lands on the rise (music bar 16 -> 8 s)
    t = seam
    b.library(t, 3 * bar); t += 3 * bar
    b.family_a(t, bar, 2 * bar, with_head=True); t += 3 * bar
    t_skip = t
    b.skip(t, 2 * bar, bar, True); t += 2 * bar
    b.trick(t, 2 * bar, True); t += 2 * bar
    b.upnext(t, 2 * bar, False); t += 2 * bar
    t_photos = t
    b.photos(t, 2 * bar, True); t += 2 * bar
    b.live(t, bar, False); t += bar
    t_still = t
    b.lib_still(t)
    t_plate = t + 2 * B.beat                   # the still holds for two beats (as in the full film) before the plate
    t_end = t_plate + (T(226) - T(210))      # wordmark on the final hit
    # film time -> music time: the opening lands on the rise (bar 16), then jumps on bar lines with short crossfades
    joins = [(0.0, T(48)), (t_skip, T(132)), (t_photos, T(180)), (t_still, T(208))]
    fade = 0.3
    edits = []
    for n, (ft, src) in enumerate(joins):
        end_ft = joins[n + 1][0] if n + 1 < len(joins) else LENGTH_HIGHLIGHT
        a = ft - (fade / 2 if n else 0)
        z = end_ft + (fade / 2 if n + 1 < len(joins) else 0)
        edits.append((a, src + (a - ft), z - a, fade))
    seq = {"OPEN": (plate_folder("wall2lib", lang), 241), "L2L": (plate_folder("lib2logo", lang), 241)}
    shots = [Shot(0, seam, "OPEN"), Shot(seam, t_plate, scene=b.scene()), Shot(t_plate, LENGTH_HIGHLIGHT, "L2L", plate_speed=1.0)]
    film = Film("film-highlight", LENGTH_HIGHLIGHT, shots, b.segments, b.headlines, breath=None, grade=False,
                music="film-music.flac", lufs=-14.0, takes="film-takes", fade_out=(LENGTH_HIGHLIGHT - 2.2, LENGTH_HIGHLIGHT - 0.2),
                fade_in=0.4, seq_plates=seq, endlogo=end_logo(lang, t_end), smooth_takes=True, music_edits=edits,
                events=dict(opening_end=seam, kids=seam + 3 * bar, skip=t_skip, photos=t_photos, still=t_still, plate=t_plate,
                            wordmark=t_end, press=t_skip + bar))
    film.sfx = b.sfx + [Sfx("thump", t_end, "wordmark hit"), Sfx("whoosh", t_still, "into the end", 1.4)]
    return film


def build(cut: str, lang: str) -> Film:
    B = Bars()
    return build_full(lang, B) if cut == "full" else build_highlight(lang, B)


# ---------------------------------------------------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------------------------------------------------

_R = None


def _init(cut: str, lang: str, k: float, takes: str) -> None:
    global _R
    cv2.setNumThreads(1)
    _R = P.Renderer(build(cut, lang), k, Path(takes))


def _work(n: int):
    return n, _R.frame(n)


def encode_cmd(w: int, h: int, audio: Path | None, out: Path, preview: bool, crf: int, maxrate: str) -> list:
    cmd = [P.FFMPEG, "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", P.FPS, "-i", "-"]
    if audio:
        cmd += ["-i", audio]
    cmd += ["-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p", "-c:v", "libx264",
            "-preset", "veryfast" if preview else "slow", "-crf", 26 if preview else crf]
    if not preview:
        cmd += ["-maxrate", maxrate, "-bufsize", "12M"]
    cmd += ["-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709"]
    if audio:
        cmd += ["-c:a", "aac", "-b:a", "192k", "-ar", P.SR, "-ac", 2, "-shortest"]
    else:
        cmd += ["-an"]
    return [str(c) for c in cmd + ["-movflags", "+faststart", out]]


def suffix(lang: str) -> str:
    return "" if lang == "en" else f"-{lang}"


def render(cut: str, lang: str, args) -> Path:
    film = build(cut, lang)
    k = 0.5 if args.preview else 1.0
    cw, ch = round(P.W * k), round(P.H * k)
    name = ("glacier-promo" if cut == "full" else "glacier-highlight") + suffix(lang) + ("-preview" if args.preview else "")
    out = args.out / f"{name}.mp4"
    takes = args.takes or P.CACHE / film.takes
    tk = P.Takes(takes, grade=False)
    for s in film.segments:                       # extract the takes once, here, not in every worker
        if not s.take.startswith("@"):
            tk.ensure(s.take)
            tk.plan(s.take)                           # how its changes are softened (computed once, cached)
    total = round(film.length * P.FPS)
    first, last = 0, total
    if args.range:
        first, last = (round(float(v) * P.FPS) for v in args.range.split(","))
    wav = None
    if args.frames:
        _init(cut, lang, k, str(takes))
        folder = args.out / f"frames-{cut}-{lang}"
        folder.mkdir(parents=True, exist_ok=True)
        from PIL import Image
        for t in (float(v) for v in args.frames.split(",")):
            Image.fromarray(np.frombuffer(_R.frame(round(t * P.FPS)), np.uint8).reshape(ch, cw, 3)).save(folder / f"{t:07.2f}.png")
        return folder
    if not args.range:
        raw, wav = args.out / f"{name}.f32", args.out / f"{name}-audio.wav"
        print(f"[{cut} {lang}] sound", flush=True)
        g = Bars().g
        P.render_audio(film, g, P.CACHE / film.music, raw)
        lufs, tp = P.master(raw, wav, film.lufs)
        raw.unlink()
        print(f"  mastered: {lufs:.1f} LUFS integrated, {tp:.1f} dBTP", flush=True)
    crf, maxrate = (20, "4.6M") if cut == "full" else (19, "7M")
    print(f"[{cut} {lang}] {last - first} frames at {cw}x{ch}", flush=True)
    enc = subprocess.Popen(encode_cmd(cw, ch, wav, out, args.preview, crf, maxrate), stdin=subprocess.PIPE)
    started = time.time()
    with mp.Pool(args.jobs, _init, (cut, lang, k, str(takes))) as pool:
        for n, data in pool.imap(_work, range(first, last), chunksize=2):
            enc.stdin.write(data)
            if (n - first) % 150 == 0:
                print(f"  {n / P.FPS:6.1f} s  ({(n - first + 1) / (time.time() - started):.1f} fps)", flush=True)
    enc.stdin.close()
    enc.wait()
    if wav:
        wav.unlink()
    print(f"  wrote {out} ({out.stat().st_size / 1e6:.1f} MB)", flush=True)
    return out


def silent_and_gif(video: Path, lang: str, args) -> None:
    """The highlight without sound (website hero) and a README GIF from the English one (fps 8, 560 px, 96 colours, under 10 MB)."""
    silent = video.with_name(video.name.replace("glacier-highlight", "glacier-highlight-silent"))
    subprocess.run([P.FFMPEG, "-v", "error", "-y", "-i", video, "-an", "-c:v", "copy", "-movflags", "+faststart", silent], check=True)
    print(f"  wrote {silent} ({silent.stat().st_size / 1e6:.1f} MB)")
    if lang == "en" and not args.preview:
        gif = args.out / "glacier-highlight.gif"
        for length in (26, 24, 22, 20):                # GitHub leaves out images over 10 MB: shorten the excerpt until it fits
            subprocess.run([P.FFMPEG, "-v", "error", "-y", "-ss", "8", "-t", str(length), "-i", silent, "-vf",
                            "fps=8,scale=560:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];"
                            "[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle", gif], check=True)
            if gif.stat().st_size < 9.8e6:
                break
        print(f"  wrote {gif} ({gif.stat().st_size / 1e6:.1f} MB)")


SITE = P.ROOT / "website" / "assets"


def encode_to_fit(src: Path, dst: Path, limit: float, args_for: callable, steps: range) -> None:
    """Encodes with the first quality setting in steps that keeps dst under limit bytes."""
    for q in steps:
        subprocess.run([P.FFMPEG, "-v", "error", "-y", "-i", src, *[str(a) for a in args_for(q)], dst], check=True)
        if dst.stat().st_size <= limit:
            break
    print(f"  wrote {dst.relative_to(P.ROOT)} ({dst.stat().st_size / 1e6:.1f} MB, quality {q})")


def website_media(lang: str, out: Path) -> None:
    """The website's media from the masters: the film (720p, at most 16 MB; Amazon takes the same file), its poster, the silent
    highlight at the page top as mp4 and webm with its poster, and for English the README GIF."""
    suf = suffix(lang)
    film, silent = out / f"glacier-promo{suf}.mp4", out / f"glacier-highlight-silent{suf}.mp4"
    promo, shots = SITE / "promo", SITE / "screenshots"
    scale = "scale=1280:-2:flags=lanczos"
    encode_to_fit(film, promo / f"glacier-promo{suf}.mp4", 16e6, lambda q: [
        "-vf", scale, "-c:v", "libx264", "-preset", "slow", "-crf", q, "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart"], range(26, 32))
    subprocess.run([P.FFMPEG, "-v", "error", "-y", "-ss", "116.95", "-i", film, "-frames:v", "1", "-vf", "scale=1280:720:flags=lanczos",
                    "-q:v", "3", promo / f"glacier-promo{suf}.jpg"], check=True)      # the logo lockup with the tagline
    encode_to_fit(silent, shots / f"demo{suf}.mp4", 8e6, lambda q: [
        "-vf", scale, "-c:v", "libx264", "-preset", "slow", "-crf", q, "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an"], range(27, 33))
    encode_to_fit(silent, shots / f"demo{suf}.webm", 8e6, lambda q: [
        "-vf", scale, "-c:v", "libvpx-vp9", "-crf", q, "-b:v", "0", "-row-mt", "1", "-an"], range(36, 46, 2))
    subprocess.run([P.FFMPEG, "-v", "error", "-y", "-ss", "11.4", "-i", silent, "-frames:v", "1", "-vf", scale, "-q:v", "3",
                    shots / f"hero{suf}.jpg"], check=True)       # poster of the top video: the library with its headline
    if lang == "en" and (out / "glacier-highlight.gif").exists():
        shutil.copy(out / "glacier-highlight.gif", shots / "demo.gif")
        print(f"  wrote {(shots / 'demo.gif').relative_to(P.ROOT)} ({(shots / 'demo.gif').stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lang", default="en", help="en,de,es")
    ap.add_argument("--cut", default="full,highlight", help="full,highlight")
    ap.add_argument("--preview", action="store_true", help="half resolution, fast encode")
    ap.add_argument("--sheet", action="store_true", help="contact sheet (every second, every 0.5 s for the highlight)")
    ap.add_argument("--jobs", type=int, default=5)
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--frames", help="comma separated seconds: write just these frames as PNGs")
    ap.add_argument("--range", help="T0,T1 seconds: render only this stretch, without sound")
    ap.add_argument("--takes", type=Path, default=None, help="folder of the takes (default build/promo/cache/film-takes)")
    ap.add_argument("--music", type=Path, default=None, help="the track (flac); copied to build/promo/cache/film-music.flac")
    ap.add_argument("--website", action="store_true", help="also write the website's media (video, demo, GIF, posters) into website/assets")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.music:
        dst = P.CACHE / "film-music.flac"
        dst.parent.mkdir(parents=True, exist_ok=True)
        if not dst.exists() or dst.read_bytes() != args.music.read_bytes():
            shutil.copy(args.music, dst)
    if not (P.CACHE / "film-music.flac").exists():
        sys.exit("build/promo/cache/film-music.flac is missing: pass the track with --music FILE (see the docstring)")
    B = Bars()
    print(f"music: {B.g.bpm} bpm, beat {B.beat:.4f} s, bars of {4 * B.beat:.3f} s")
    cuts = [c for c in args.cut.split(",") if c in ("full", "highlight")]
    for lang in args.lang.split(","):
        for cut in cuts:
            film = build(cut, lang)
            print(f"[{cut} {lang}] " + ", ".join(f"{k} {v:.2f}" for k, v in film.events.items()))
            result = render(cut, lang, args)
            if result.suffix != ".mp4":
                continue
            if args.sheet:
                P.contact_sheet(result, result.with_suffix(".sheet.png"), step=1.0 if cut == "full" else 0.5, cols=8, thumb=240)
            if cut == "highlight" and not args.range:
                silent_and_gif(result, lang, args)
        if (args.website or "site" in args.cut.split(",")) and not args.preview and not args.frames and not args.range:
            website_media(lang, args.out)


if __name__ == "__main__":
    main()
