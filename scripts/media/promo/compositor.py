#!/usr/bin/env python3
"""Compositor of the Glacier promo films: plates, screens on a 3D stage, type, grain, sound.

What:   Everything film.py needs to turn a timeline into frames and a sound track. Every frame is composed with numpy + OpenCV
        + Pillow and piped to ffmpeg by film.py.
        * Takes: the app screen recordings (variable frame rate, written only on change) are cut into 30 fps frames once and
          every change in them is softened (eased blends, dissolves) so nothing steps or jumps.
        * Plates: 3D renders from Blender (build/promo/blender/assemble/<folder>/NNNN.png) are read as image sequences.
        * Stage: screens are placed on a dark stage with perspective, tilt, shadow and fade (the 3D crossfade); camera
          pushes and punch-ins are source rectangles of the take.
        * Type: Bricolage Grotesque kinetic headlines, format chips, the logo lockup (the poster diamond becomes the vector
          logo, moves left, the wordmark slides in).
        * Sound: the music's tempo and beat grid are measured, clicks and whooshes are synthesised and placed on the take
          events, the track is mastered (gain + limiter, corrected until the ENCODED AAC measures the target loudness).
Usage:  imported by film.py, no command line of its own.
Needs:  Python 3.9+, numpy, Pillow, OpenCV (opencv-python-headless), fontTools + brotli (unpack the woff2 fonts), ffmpeg on PATH.
        Reuses make-promo-video.py (easing, colours, ffmpeg lookup, END and TITLE texts).
"""
from __future__ import annotations

import bisect
import importlib.util
import json
import math
import re
import subprocess
import sys
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

cv2.setNumThreads(2)

# make-promo-video.py has dashes in its name: load it by path and reuse its helpers.
_spec = importlib.util.spec_from_file_location("make_promo_video", Path(__file__).resolve().parents[1] / "make-promo-video.py")
promo = importlib.util.module_from_spec(_spec)
sys.modules["make_promo_video"] = promo
_spec.loader.exec_module(promo)
ease, ease_out, back_out, FFMPEG = promo.ease, promo.ease_out, promo.back_out, promo.FFMPEG

ROOT = promo.ROOT
BUILD = ROOT / "build" / "promo"
BLENDER = BUILD / "blender"
CACHE = BUILD / "cache"
FONT_DIR = ROOT / "website" / "assets" / "fonts"

W, H, FPS = 1920, 1080, 30
FULL = np.array([[0, 0], [W, 0], [W, H], [0, H]], np.float64)
ICE, ACCENT, MIST, DEEP = (np.array(c, np.float32) / 255 for c in (promo.ICE, promo.ACCENT, promo.MIST, promo.DEEP))

# ---------------------------------------------------------------------------------------------------------
# Tunable look and sound constants (everything else is data in the timelines further down)
# ---------------------------------------------------------------------------------------------------------
UI_LIFT = (7, 10, 14)            # black point of the UI in 8 bit (lifted blacks so it does not look pasted)
UI_GAIN = 0.975                  # and a hint less white
GLARE = 0.055                    # strength of the diagonal sheen on the screen
BLOOM = 0.16                     # strength of the screen bloom
VIGNETTE = 0.30
GRAIN = 0.006                    # sigma of the animated film grain (0..1)
HEAD_SIZE, HEAD_LEAD, HEAD_X, HEAD_Y = 74, 82, 112, 118   # headline, 1080p pixels
HEAD_WORD_GAP, HEAD_LINE_GAP, HEAD_RISE = 0.12, 0.60, 36  # seconds between words, between lines (one beat), px
CLICK_PEAK_DB, WHOOSH_RMS_DB = -18.0, -33.0   # SFX level before mastering (music peaks near 0 dBFS)
TARGET_LUFS, TRUE_PEAK = -16.0, -1.0
SR = 48000

# ---------------------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------------------

def smooth(a: float, b: float, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def run(cmd: list, **kw) -> subprocess.CompletedProcess:
    return subprocess.run([str(c) for c in cmd], **kw)


def decode(path: Path, sr: int, channels: int, extra: list | None = None) -> np.ndarray:
    raw = run([FFMPEG, "-v", "error", "-i", path, *(extra or []), "-ac", channels, "-ar", sr, "-f", "f32le", "-"],
              capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).reshape(-1, channels).copy()


# ---------------------------------------------------------------------------------------------------------
# Music analysis: tempo, beat grid, the drop
# ---------------------------------------------------------------------------------------------------------

@dataclass
class Grid:
    bpm: float
    phase: float      # time of a beat, seconds
    drop: float       # the full-band drop, snapped to the grid
    drop_raw: float
    length: float

    @property
    def beat(self) -> float:
        return 60 / self.bpm

    def snap(self, t: float) -> float:
        return round(self.phase + round((t - self.phase) / self.beat) * self.beat, 3)


def analyse_music(path: Path, bpm_range=(96, 104), drop_hint: float | None = None) -> Grid:
    cache = CACHE / ("pilot-beats.json" if path.name == "pilot-music.flac" else f"pilot-beats-{path.stem}.json")
    stamp = path.stat().st_mtime
    if cache.exists():
        d = json.loads(cache.read_text())
        if d.get("stamp") == stamp:
            return Grid(**d["grid"])
    sr, hop, n = (22050, 128, 512) if drop_hint else (22050, 256, 1024)   # W: finer onsets, its grid is ambiguous by a half beat
    x = decode(path, sr, 1)[:, 0]
    frames = np.lib.stride_tricks.sliding_window_view(x, n)[::hop] * np.hanning(n)
    spec = np.log1p(10 * np.abs(np.fft.rfft(frames, axis=1)))
    flux = np.r_[0, np.maximum(0, np.diff(spec, axis=0)).sum(1)]
    flux = np.maximum(flux - np.convolve(flux, np.ones(21) / 21, "same"), 0)
    fps = sr / hop
    best = (-1.0, 100.0, 0.0)
    for bpm in np.arange(bpm_range[0], bpm_range[1] + 0.01, 0.05):
        step = 60 / bpm
        for phase in np.arange(0, step, 0.01):
            idx = (np.arange(phase, len(x) / sr - 0.5, step) * fps).astype(int)
            score = flux[idx].mean() if drop_hint else flux[idx].sum()
            if score > best[0]:
                best = (score, bpm, phase)
    _, bpm, phase = best
    # level in dB every 0.1 s, the drop is the first sustained rise to the loud section
    h = sr // 10
    level = np.array([20 * np.log10(np.sqrt((x[i * h:(i + 1) * h] ** 2).mean()) + 1e-9) for i in range(len(x) // h)])
    loud = np.percentile(level[level > -30], 75)
    drop_raw = drop_hint if drop_hint is not None else \
        next(i for i in range(30, len(level) - 30) if level[i] >= loud and level[i:i + 30].mean() >= loud - 2.5) / 10
    grid = Grid(round(float(bpm), 2), round(float(phase), 3), 0.0, drop_raw, len(x) / sr)
    grid.drop = grid.snap(drop_raw)
    cache.write_text(json.dumps({"stamp": stamp, "grid": grid.__dict__}, indent=1))
    return grid


# ---------------------------------------------------------------------------------------------------------
# Timeline data
# ---------------------------------------------------------------------------------------------------------

@dataclass
class Shot:
    """A stretch of the film: either a Blender plate with the app in its screen, or the app full screen."""
    start: float
    end: float
    plate: str | None = None
    cam: list[tuple] = field(default_factory=list)   # UI camera for full-screen shots: (secs into shot, zoom, x, y)
    scene: object = None                             # variant W: callable(t) -> Scene (layers on a dark stage)
    plate_speed: float | None = None                 # sequence plates: plate seconds per film second (None = stretch to fit)

    @property
    def length(self) -> float:
        return self.end - self.start


@dataclass
class Segment:
    """Part of a take shown on the screen. knots map film time -> take time (piecewise linear)."""
    take: str
    knots: list[tuple[float, float]]
    start: float = 0.0           # dissolve from the segment before between start and start + fade
    fade: float = 0.0
    hold_max: float | None = None
    settle_to: float | None = None   # after the last knot the take eases to a stop at this take time

    def at(self, t: float) -> float:
        k = self.knots
        if t <= k[0][0]:
            v = k[0][1]
        else:
            i = bisect.bisect_right([a for a, _ in k], t)
            if i >= len(k):
                (t0, c0), v_last = k[-1], (k[-1][1] - k[-2][1]) / (k[-1][0] - k[-2][0]) if len(k) > 1 else 1.0
                tau = t - t0
                if self.settle_to is None:
                    v = c0 + v_last * tau
                else:
                    span = 2 * (self.settle_to - c0) / v_last
                    tau = min(tau, span)
                    v = c0 + v_last * tau - v_last * tau * tau / (2 * span)
            else:
                (t0, c0), (t1, c1) = k[i - 1], k[i]
                v = lerp(c0, c1, (t - t0) / (t1 - t0))
        return min(v, self.hold_max) if self.hold_max is not None else v

    def when(self, take_time: float) -> float:
        """Film time at which the take reaches take_time (first moving stretch that covers it)."""
        for (t0, c0), (t1, c1) in zip(self.knots, self.knots[1:]):
            if c1 > c0 and c0 <= take_time <= c1:
                return t0 + (take_time - c0) / (c1 - c0) * (t1 - t0)
        raise ValueError(f"{self.take} never reaches {take_time}")


@dataclass
class Headline:
    text: str
    start: float
    end: float
    x: float = HEAD_X            # left edge, or right edge with align="right" (1080p pixels)
    y: float = HEAD_Y            # top of the first line
    align: str = "left"
    scrim: float = 0.55          # darkness of the soft patch behind the text
    size: float = HEAD_SIZE      # type size at 1080p; shrunk to fit max_w when the lines are wider
    max_w: float = 1000.0






@dataclass
class Sfx:
    kind: str             # click | click_soft | whoosh
    t: float
    note: str = ""
    length: float = 0.9   # whoosh: seconds; it swells towards t (peak at t) unless rise is False
    rise: bool = True


@dataclass
class Film:
    name: str
    length: float
    shots: list[Shot]
    segments: list[Segment]
    headlines: list[Headline]
    sfx: list[Sfx] = field(default_factory=list)
    breath: tuple[float, float] | None = (12.0, 14.4)
    blurs: list = field(default_factory=list)        # extra motion blur windows (from, to, steps, shutter)
    grade: bool = True                               # lift the blacks of the UI (off where it must match a plate exactly)
    music: str = "film-music.flac"
    seq_plates: dict = field(default_factory=dict)   # name -> (folder under blender/assemble, frames)
    chips: list = field(default_factory=list)
    endlogo: dict | None = None                      # the poster diamond becomes the logo, which moves left of the wordmark
    smooth_takes: bool = False                       # blend between the real frames of a variable-frame-rate take
    music_edits: list | None = None                  # (film time, music time, length, crossfade) pieces of the track
    lufs: float = TARGET_LUFS
    takes: str = "film-takes"
    fade_in: float = 0.35
    fade_out: tuple[float, float] = (28.4, 29.8)
    events: dict = field(default_factory=dict)       # named cut times for the report

    def shot_at(self, t: float) -> Shot:
        for s in self.shots:
            if s.start <= t < s.end:
                return s
        return self.shots[-1]

    def blur_windows(self) -> list[tuple[float, float, int, float]]:
        """Motion blur by frame blending: (from, to, sub-steps, shutter in seconds)."""
        out = []
        out.append((self.events["press"] - 0.5, self.events["press"] + 1.0, 5, 1 / 80))   # the punch-in on the press
        return out + self.blurs




@dataclass
class Layer:
    """A screen on the 3D stage: which segment, how it is placed, how visible, which part of the UI it shows."""
    seg: int
    scale: float = 1.0
    yaw: float = 0.0
    dx: float = 0.0
    dy: float = 0.0
    alpha: float = 1.0
    rect: tuple | None = None


@dataclass
class Scene:
    layers: list


XFADE = 0.6            # seconds of the 3D crossfade between screens
TILT, SINK, DRIFT = 10.0, 0.09, 50.0    # yaw (degrees), loss of scale and sideways drift of a screen in the crossfade




@dataclass
class Chip:
    """A small pill of text on the stage (variant film: the audio and picture formats)."""
    text: str
    start: float
    end: float
    x: float
    y: float


# ---------------------------------------------------------------------------------------------------------
# Plates
# ---------------------------------------------------------------------------------------------------------

_NAME = re.compile(r"(still_|matte)?0*(\d+)$")


def scan_frames(folder: Path) -> tuple[dict[int, Path], dict[int, Path]]:
    """Frames (digits in the file name) and stills (still_ prefix) of a folder."""
    frames, stills = {}, {}
    if folder.is_dir():
        for f in folder.glob("*.png"):
            m = _NAME.match(f.stem)
            if m:
                (stills if m.group(1) == "still_" else frames)[int(m.group(2))] = f
    return frames, stills


@lru_cache(maxsize=48)
def load_image(path: str, canvas_w: int, gray: bool) -> np.ndarray:
    im = cv2.imread(path, cv2.IMREAD_GRAYSCALE if gray else cv2.IMREAD_COLOR)
    if im is None:
        raise FileNotFoundError(path)
    if im.shape[1] > canvas_w:   # never keep more pixels than the canvas needs
        im = cv2.resize(im, (canvas_w, round(im.shape[0] * canvas_w / im.shape[1])), interpolation=cv2.INTER_AREA)
    return im if gray else cv2.cvtColor(im, cv2.COLOR_BGR2RGB)






class Wall2Lib:
    """The 3D opening: 241 frames, finals when complete, otherwise the preview frames blended and upscaled."""
    TOTAL = 241

    def __init__(self, k: float, folder: str = "wall2lib", total: int = 241):
        self.canvas_w = round(W * k)
        self.TOTAL = total
        base = BLENDER / "assemble"
        final, stills = scan_frames(base / folder)
        prev, _ = scan_frames(base / f"{folder}-preview")
        keys = sorted(final)
        done = len(keys) >= 2 and keys[0] <= 2 and keys[-1] >= self.TOTAL - 1 and max(b - a for a, b in zip(keys, keys[1:])) <= 2
        # finals when complete; otherwise whatever exists (finals over previews over stills), blended between neighbours
        self.mode, self.frames = ("final", final) if done else ("preview", {**stills, **prev, **final})
        self.keys = sorted(self.frames)

    def image(self, p: float) -> np.ndarray:
        i = bisect.bisect_left(self.keys, p)
        if i < len(self.keys) and self.keys[i] == p:
            pick = [(self.keys[i], 1.0)]
        elif i == 0 or i == len(self.keys):
            pick = [(self.keys[min(i, len(self.keys) - 1)], 1.0)]
        else:
            a, b = self.keys[i - 1], self.keys[i]
            pick = [(a, 1 - (p - a) / (b - a)), (b, (p - a) / (b - a))]
        out = 0.0
        for f, w in pick:
            try:
                im = load_image(str(self.frames[f]), self.canvas_w, False)
            except FileNotFoundError:     # being re-rendered: forget the frame
                self.keys.remove(f)
                return self.image(p)
            if im.shape[1] != self.canvas_w:
                im = cv2.resize(im, (self.canvas_w, round(im.shape[0] * self.canvas_w / im.shape[1])), interpolation=cv2.INTER_CUBIC)
            out = out + w * im.astype(np.float32)
        return out / 255.0


# ---------------------------------------------------------------------------------------------------------
# App takes
# ---------------------------------------------------------------------------------------------------------

class Takes:
    def __init__(self, folder: Path, grade: bool = True):
        self.folder = folder
        self.frames: dict[str, Path] = {}
        self.count: dict[str, int] = {}
        self.smooth = False
        self._pts: dict[str, np.ndarray] = {}
        self._plans: dict[str, dict] = {}
        self.legacy = folder.name == "pilot-takes"    # the first pilots keep their frame cache where it was
        if self.legacy:                              # (their takes are few: extract them up front)
            for f in sorted(folder.glob("*.mp4")):
                self.ensure(f.stem)
        lut = np.zeros((256, 1, 3), np.uint8)
        for c, lift in enumerate(UI_LIFT):
            lut[:, 0, c] = np.clip(lift + np.arange(256) * UI_GAIN * (255 - lift) / 255, 0, 255) if grade else np.arange(256)
        self.lut = lut

    def ensure(self, name: str) -> None:
        """Extracts a take to 30 fps JPEGs the first time it is needed (variable frame rate: by pts)."""
        if name in self.count:
            return
        out = CACHE / ("pilot-frames" if self.legacy else f"pilot-frames-{self.folder.name}") / name
        if not out.exists() or not any(out.glob("*.jpg")):
            out.mkdir(parents=True, exist_ok=True)
            print(f"  extracting {name}.mp4 at {FPS} fps", flush=True)
            run([FFMPEG, "-v", "error", "-y", "-i", self.folder / f"{name}.mp4", "-vf", f"fps={FPS}", "-q:v", "3",
                 "-pix_fmt", "yuvj444p", out / "%05d.jpg"], check=True)
        self.frames[name] = out
        self.count[name] = len(list(out.glob("*.jpg")))

    @lru_cache(maxsize=40)
    def _frame(self, take: str, index: int) -> np.ndarray:
        self.ensure(take)
        index = min(max(index, 0), self.count[take] - 1)
        im = cv2.cvtColor(cv2.imread(str(self.frames[take] / f"{index + 1:05d}.jpg")), cv2.COLOR_BGR2RGB)
        return cv2.LUT(im, self.lut)

    def pts(self, name: str) -> np.ndarray:
        """Times of the frames the recorder really wrote (it only writes on change)."""
        if name not in self._pts:
            raw = run([promo.FFPROBE, "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time", "-of", "csv=p=0",
                       self.folder / f"{name}.mp4"], capture_output=True, text=True).stdout
            self._pts[name] = np.array(sorted(float(v) for v in raw.split() if v.strip()), np.float64)
        return self._pts[name]

    def real(self, name: str, j: int) -> np.ndarray:
        ts = self.pts(name)
        return self._frame(name, int(round(ts[min(max(j, 0), len(ts) - 1)] * FPS)))

    def _small(self, name: str, j: int) -> np.ndarray:
        return cv2.resize(cv2.cvtColor(self.real(name, j), cv2.COLOR_RGB2GRAY), (96, 54), interpolation=cv2.INTER_AREA).astype(np.float32)

    def pair(self, name: str, i: int, j: int) -> float:
        """How different two real frames are (mean 0..255 on a small grey copy)."""
        return float(np.abs(self._small(name, i) - self._small(name, j)).mean())

    def plan(self, name: str) -> dict:
        """How the changes of a take are softened. The recorder writes frames only on change, so a change shows up as a burst of
        frames (or one): a short burst with a big change becomes an eased dissolve of at least 0.4 s, a long burst (the app's own
        animation) is interpolated in time, small changes get a blend of at most 0.3 s. Cached next to the frames."""
        if name in self._plans:
            return self._plans[name]
        self.ensure(name)
        cache = self.frames[name] / "plan.json"
        ts = self.pts(name)
        if cache.exists():
            d = json.loads(cache.read_text())
            if d.get("n") == len(ts):
                self._plans[name] = {"dis": [tuple(x) for x in d["dis"]], "lin": set(d["lin"])}
                return self._plans[name]
        n = len(ts)
        bursts, a = [], 0
        for i in range(1, n + 1):
            if i == n or ts[i] - ts[i - 1] > 0.12:
                bursts.append((a, i - 1))
                a = i
        dis, lin, prev_end = [], set(), -1.0
        for a, b in bursts:
            if a == 0 or max(self.pair(name, a - 1, x) for x in {a, min(a + 2, b), b}) <= 6:
                continue
            dur = ts[b] - ts[a]
            if dur < 0.3:
                c = (ts[a] + ts[b]) / 2
                length = max(0.4, dur + 0.25)
                st, en = c - length / 2, c + length / 2
            else:                                       # the app's own animation: dissolve into it, then play it in time
                st, en = ts[a] - 0.3, ts[a]
                lin.update(range(a, b + 1))
            st = max(st, prev_end)
            en = max(en, st + 0.2)
            dis.append((float(st), float(en), a - 1, b if dur < 0.3 else a))
            prev_end = en
        # inside a long animation the recorder can still hold hard cuts (a dialog dimming in 3 big steps, a video starting): dissolve
        # those too; cuts that follow each other merge into one longer dissolve
        for i in sorted(lin):
            if i - 1 in lin and self.pair(name, i - 1, i) > 20:
                dis.append((float(ts[i] - 0.2), float(ts[i] + 0.2), None, None))
        dis.sort(key=lambda d: d[0])
        merged: list = []
        for st, en, fa, fb in dis:
            if merged and st <= merged[-1][1] + 0.05:
                m = merged[-1]
                merged[-1] = [m[0], max(m[1], en), m[2], None]
            else:
                merged.append([st, en, fa, fb])
        dis = []
        for st, en, fa, fb in merged:
            if fa is None:
                fa = int(np.searchsorted(ts, st, side="right")) - 1
            if fb is None or fb < int(np.searchsorted(ts, en, side="right")) - 1:
                fb = int(np.searchsorted(ts, en, side="right")) - 1
            dis.append((float(st), float(en), max(fa, 0), max(fb, 0)))
        self._plans[name] = {"dis": dis, "lin": lin}
        cache.write_text(json.dumps({"n": n, "dis": dis, "lin": sorted(lin)}))
        return self._plans[name]

    def smooth_frame(self, name: str, t: float) -> np.ndarray:
        """Frame of a variable-frame-rate take with its changes softened (see plan)."""
        pl = self.plan(name)
        ts = self.pts(name)
        starts = [d[0] for d in pl["dis"]]
        k = bisect.bisect_right(starts, t) - 1
        if k >= 0 and t <= pl["dis"][k][1]:
            st, en, fa, fb = pl["dis"][k]
            w = float(ease((t - st) / (en - st)))
            return cv2.addWeighted(self.real(name, fa), 1 - w, self.real(name, fb), w, 0)
        j = int(np.searchsorted(ts, t, side="right")) - 1
        if j < 0:
            return self.real(name, 0)
        if j + 1 < len(ts) and (j + 1) in pl["lin"] and j in pl["lin"]:     # inside a long animation: interpolate in time
            w = (t - ts[j]) / (ts[j + 1] - ts[j])
            return cv2.addWeighted(self.real(name, j), 1 - w, self.real(name, j + 1), w, 0)
        for b in (j + 1, j):                                # small changes: a blend of at most 0.3 s around the moment
            if 0 < b < len(ts) and b not in pl["lin"]:
                span = min(0.3, ts[b] - ts[b - 1])
                if span >= 0.034 and ts[b] - span / 2 <= t <= ts[b] + span / 2 and self.pair(name, b - 1, b) <= 6:
                    w = float(smooth(0, 1, (t - (ts[b] - span / 2)) / span))
                    return cv2.addWeighted(self.real(name, b - 1), 1 - w, self.real(name, b), w, 0)
        return self.real(name, j)

    @lru_cache(maxsize=8)
    def still(self, path: str) -> np.ndarray:
        im = cv2.cvtColor(cv2.imread(path), cv2.COLOR_BGR2RGB)
        return cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA) if im.shape[1] != W else im

    def frame(self, take: str, t: float) -> np.ndarray:
        if take.startswith("@"):                    # a picture instead of a take
            return self.still(take[1:])
        if self.smooth:
            return self.smooth_frame(take, t)
        x = t * FPS
        i = math.floor(x + 1e-6)
        f = x - i
        if f < 0.04:
            return self._frame(take, i)
        return cv2.addWeighted(self._frame(take, i), 1 - f, self._frame(take, i + 1), f, 0)


# ---------------------------------------------------------------------------------------------------------
# Type
# ---------------------------------------------------------------------------------------------------------

def unpack_font(name: str) -> Path:
    from fontTools.ttLib import TTFont
    out = CACHE / "pilot-fonts" / f"{name}.ttf"
    if not out.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
        font = TTFont(FONT_DIR / f"{name}.woff2")
        font.flavor = None
        font.save(out)
    return out


class Type:
    """Text as float masks (cached), with blur levels for the blur-to-sharp entrance."""
    BLURS = (0, 2, 4, 7, 11)

    def __init__(self, k: float):
        self.k = k
        self.paths = {"display": unpack_font("bricolage-grotesque-latin"), "hand": unpack_font("caveat-latin")}

    @lru_cache(maxsize=64)
    def font(self, family: str, size: int, weight: int) -> ImageFont.FreeTypeFont:
        f = ImageFont.truetype(str(self.paths[family]), size)
        f.set_variation_by_axes([96, weight] if family == "display" else [weight])
        return f

    @lru_cache(maxsize=256)
    def mask(self, text: str, family: str, size: int, weight: int, blur: float) -> tuple[np.ndarray, int]:
        font = self.font(family, max(1, round(size * self.k)), weight)
        pad = round(26 * self.k)
        asc, desc = font.getmetrics()
        w = int(font.getlength(text)) + 2 * pad + 2
        im = Image.new("L", (w, asc + desc + 2 * pad), 0)
        ImageDraw.Draw(im).text((pad, pad), text, font=font, fill=255, anchor="la")
        m = np.asarray(im, np.float32) / 255
        if blur > 0:
            m = cv2.GaussianBlur(m, (0, 0), blur * self.k)
        return m, pad

    def width(self, text: str, family: str, size: int, weight: int) -> float:
        return self.font(family, max(1, round(size * self.k)), weight).getlength(text) / self.k

    def blurred(self, text, family, size, weight, sigma: float):
        """Mask at blur sigma (1080p px), interpolated between cached levels."""
        lv = self.BLURS
        j = min(max(bisect.bisect_right(lv, sigma) - 1, 0), len(lv) - 2)
        t = min(max((sigma - lv[j]) / (lv[j + 1] - lv[j]), 0), 1)
        a, pad = self.mask(text, family, size, weight, lv[j])
        if t < 0.02:
            return a, pad
        b, _ = self.mask(text, family, size, weight, lv[j + 1])
        return lerp(a, b, t), pad


def blit(canvas: np.ndarray, mask: np.ndarray, x: float, y: float, color, alpha: float) -> None:
    """Alpha-blend a mask at a subpixel position."""
    if alpha <= 0.002:
        return
    h, w = mask.shape
    ch, cw = canvas.shape[:2]
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    if fx > 0.01 or fy > 0.01:
        mask = cv2.warpAffine(mask, np.float32([[1, 0, fx], [0, 1, fy]]), (w, h), flags=cv2.INTER_LINEAR)
    x0, y0, x1, y1 = max(ix, 0), max(iy, 0), min(ix + w, cw), min(iy + h, ch)
    if x1 <= x0 or y1 <= y0:
        return
    m = mask[y0 - iy:y1 - iy, x0 - ix:x1 - ix, None] * alpha
    roi = canvas[y0:y1, x0:x1]
    roi += (np.asarray(color, np.float32) - roi) * m


# ---------------------------------------------------------------------------------------------------------
# The renderer
# ---------------------------------------------------------------------------------------------------------

class Renderer:
    def __init__(self, film: Film, k: float, takes_dir: Path):
        self.film, self.k = film, k
        self.cw, self.ch = round(W * k), round(H * k)
        self.takes = Takes(takes_dir, film.grade)
        self.takes.smooth = film.smooth_takes
        self.type = Type(k)
        self.plates = {s.plate: Wall2Lib(k, *film.seq_plates[s.plate]) for s in film.shots if s.plate}
        yy, xx = np.mgrid[0:self.ch, 0:self.cw].astype(np.float32)
        r = np.hypot((xx / self.cw - 0.5) * 1.6, (yy / self.ch - 0.5) * 1.1)
        self.stage = (np.float32(promo.VOID) / 255 * (0.55 + 0.9 * np.clip(1 - r, 0, 1) ** 1.5)[..., None]).astype(np.float32)
        yy, xx = np.mgrid[0:self.ch, 0:self.cw].astype(np.float32)
        r = np.hypot((xx - self.cw / 2) / (self.cw / 2), (yy - self.ch / 2) / (self.ch / 2) * 0.85)
        self.vignette = (1 - VIGNETTE * np.clip(r - 0.35, 0, None) ** 1.7)[..., None].astype(np.float32)
        gl = np.mgrid[0:270, 0:480].astype(np.float32)
        v, u = gl[0] / 270, gl[1] / 480
        sheen = np.clip(1 - (0.9 * u + 0.6 * v) / 0.95, 0, 1) ** 2 * 0.9
        streak = np.exp(-(((u - 0.66 + 0.30 * v) / 0.05) ** 2)) * 0.55
        self.glare = (sheen + streak).astype(np.float32)
        self._gradients: dict = {}
        self._end = None

    # -- UI -----------------------------------------------------------------------------------------------


    @staticmethod
    def camera(shot: Shot, t: float) -> tuple[float, float, float, float]:
        keys = shot.cam
        if not keys:
            return 0, 0, W, H
        dt = t - shot.start
        i = min(max(bisect.bisect_right([c[0] for c in keys], dt) - 1, 0), len(keys) - 2)
        (t0, z0, x0, y0), (t1, z1, x1, y1) = keys[i], keys[i + 1]
        u = ease(min(max((dt - t0) / (t1 - t0), 0), 1)) if t1 > t0 else 1
        z, fx, fy = lerp(z0, z1, u), lerp(x0, x1, u), lerp(y0, y1, u)
        w, h = W / z, H / z
        x0, y0 = min(max(fx - w / 2, 0), W - w), min(max(fy - h / 2, 0), H - h)   # (fx, fy) is the view's centre
        return x0, y0, x0 + w, y0 + h

    def paint_ui(self, base: np.ndarray, ui: np.ndarray, rect, quad: np.ndarray, alpha: float,
                 matte: np.ndarray | None, room: float) -> None:
        """Warps the rect of the UI onto the quad (canvas pixels) and blends it into base."""
        x0, y0, x1, y1 = rect
        if (x0, y0, x1, y1) == (0, 0, W, H) and np.abs(quad - [[0, 0], [self.cw, 0], [self.cw, self.ch], [0, self.ch]]).max() < 0.01 \
                and matte is None and room <= 0.01:
            img = ui if self.k == 1 else cv2.resize(ui, (self.cw, self.ch), interpolation=cv2.INTER_AREA)
            base += (img.astype(np.float32) / 255 - base) * alpha
            return
        bx0, by0 = max(int(np.floor(quad[:, 0].min())) - 2, 0), max(int(np.floor(quad[:, 1].min())) - 2, 0)
        bx1, by1 = min(int(np.ceil(quad[:, 0].max())) + 2, self.cw), min(int(np.ceil(quad[:, 1].max())) + 2, self.ch)
        if bx1 - bx0 < 2 or by1 - by0 < 2 or alpha <= 0.002:
            return
        rw, rh = bx1 - bx0, by1 - by0
        dest_w = 0.5 * (np.linalg.norm(quad[1] - quad[0]) + np.linalg.norm(quad[2] - quad[3]))
        c = min(1.0, dest_w / (x1 - x0) * 1.2)       # prefilter so the warp itself shrinks by <= 1.2
        src = cv2.resize(ui, None, fx=c, fy=c, interpolation=cv2.INTER_AREA) if c < 0.999 else ui
        pad = 3
        src = cv2.copyMakeBorder(src, pad, pad, pad, pad, cv2.BORDER_REPLICATE)
        corners = np.float32([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
        dst = (quad - [bx0, by0]).astype(np.float32)
        m = cv2.getPerspectiveTransform((corners * c + pad).astype(np.float32), dst)
        rgb = cv2.warpPerspective(src, m, (rw, rh), flags=cv2.INTER_LANCZOS4 if c >= 0.999 else cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        rgb = rgb.astype(np.float32) / 255
        mask = np.zeros((rh, rw), np.uint8)
        cv2.fillConvexPoly(mask, np.round(dst * 16).astype(np.int32), 255, cv2.LINE_AA, shift=4)
        a = mask.astype(np.float32) / 255 * alpha
        if matte is not None:
            a = a * matte[by0:by1, bx0:bx1]
        if room > 0.01:
            g = cv2.warpPerspective(self.glare, cv2.getPerspectiveTransform((corners * 0.25).astype(np.float32), dst), (rw, rh),
                                    flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
            rgb = rgb + (g * GLARE * room)[..., None] * np.float32([1.0, 0.97, 0.93])
        roi = base[by0:by1, bx0:bx1]
        roi += (rgb - roi) * a[..., None]
        if room > 0.01:    # bloom: the bright parts of the screen glow into the room
            glow = np.zeros((self.ch, self.cw, 3), np.float32)
            glow[by0:by1, bx0:bx1] = np.maximum(rgb - 0.45, 0) * a[..., None]
            small = cv2.resize(glow, (self.cw // 4, self.ch // 4), interpolation=cv2.INTER_AREA)
            small = cv2.GaussianBlur(small, (0, 0), max(1.0, 22 * self.k / 4 * 1.0))
            base += cv2.resize(small, (self.cw, self.ch), interpolation=cv2.INTER_LINEAR) * (BLOOM * 4 * room)

    # -- shots --------------------------------------------------------------------------------------------




    # -- the 3D stage of variant W -----------------------------------------------------------------------

    def tilt_quad(self, scale: float, yaw: float, dx: float, dy: float) -> np.ndarray:
        """Corners (canvas pixels) of a screen turned about its vertical axis, seen with a little perspective."""
        th, f = math.radians(yaw), 1.4 * W
        pts = []
        for x, y in ((-W / 2, -H / 2), (W / 2, -H / 2), (W / 2, H / 2), (-W / 2, H / 2)):
            s = f / (f + x * math.sin(th))
            pts.append((W / 2 + dx + x * math.cos(th) * s * scale, H / 2 + dy + y * s * scale))
        return np.array(pts) * self.k

    def shadow(self, base: np.ndarray, quad: np.ndarray, alpha: float) -> None:
        m = np.zeros((self.ch // 4, self.cw // 4), np.float32)
        pts = (quad + [0, 34 * self.k]) / 4
        cv2.fillConvexPoly(m, np.round(pts * 16).astype(np.int32), 1.0, cv2.LINE_AA, 4)
        m = cv2.GaussianBlur(m, (0, 0), max(1.0, 26 * self.k / 4))
        base *= 1 - 0.6 * alpha * cv2.resize(m, (self.cw, self.ch), interpolation=cv2.INTER_LINEAR)[..., None]


    def scene_shot(self, shot: Shot, t: float) -> np.ndarray:
        sc = shot.scene(t)
        base = self.stage.copy()
        for layer in sc.layers:
            if layer.alpha <= 0.002:
                continue
            quad = self.tilt_quad(layer.scale, layer.yaw, layer.dx, layer.dy)
            if layer.scale < 0.999 or abs(layer.yaw) > 0.01:
                self.shadow(base, quad, layer.alpha)
            seg = self.film.segments[layer.seg]
            self.paint_ui(base, self.takes.frame(seg.take, seg.at(t)), layer.rect or (0, 0, W, H), quad, layer.alpha, None, 0.0)
        return base

    # -- overlays -----------------------------------------------------------------------------------------

    def gradient(self, cx: float, cy: float, rx: float, ry: float) -> np.ndarray:
        """Soft dark patch behind a headline (centre and radii in 1080p pixels)."""
        key = (round(cx), round(cy), round(rx), round(ry))
        if key not in self._gradients:
            yy, xx = np.mgrid[0:self.ch, 0:self.cw].astype(np.float32)
            d = ((xx / self.k - cx) / rx) ** 2 + ((yy / self.k - cy) / ry) ** 2
            self._gradients[key] = np.exp(-d ** 1.2 * 1.6)[..., None].astype(np.float32)
        return self._gradients[key]

    def headlines(self, canvas: np.ndarray, t: float) -> None:
        ty = self.type
        for head in self.film.headlines:
            if not head.start - 0.05 <= t <= head.end + 0.5:
                continue
            exit_ = float(ease(min(1.0, (head.end - t) / 0.45)))
            size = float(head.size)
            for _ in range(2):             # the second pass fits the width
                isz = max(8, round(size))
                space = ty.width(" ", "display", isz, 700) * 0.9
                layout = []                # per line: [(word, accent, width)]
                for line in head.text.split("|"):
                    accent, words = False, []
                    for tok in line.split():
                        starts, ends = tok.startswith("*"), tok.endswith("*") and len(tok) > 1
                        accent = accent or starts
                        word = tok.strip("*")
                        words.append((word, accent, ty.width(word, "display", isz, 700)))
                        accent = accent and not ends
                    layout.append(words)
                widths = [sum(w for _, _, w in words) + space * (len(words) - 1) for words in layout]
                if max(widths) <= head.max_w:
                    break
                size = size * head.max_w / max(widths)
            lead = isz * HEAD_LEAD / HEAD_SIZE
            block_w, block_h = max(widths), lead * len(layout)
            left = head.x if head.align == "left" else head.x - block_w if head.align == "right" else head.x - block_w / 2
            wide = 1.0 if head.scrim <= 0.6 else 1.35
            grad = self.gradient(left + block_w / 2, head.y + block_h / 2, (block_w * 0.75 + 260) * wide, (block_h * 0.9 + 150) * wide)
            canvas *= 1 - head.scrim * grad * float(ease_out((t - head.start) / 0.8)) * exit_
            rise = HEAD_RISE * isz / HEAD_SIZE
            for j, words in enumerate(layout):
                x = left if head.align == "left" else head.x - widths[j] if head.align == "right" else head.x - widths[j] / 2
                for i, (word, acc, width) in enumerate(words):
                    t0 = head.start + j * HEAD_LINE_GAP + i * HEAD_WORD_GAP
                    ap = float(ease_out((t - t0) / 0.6))
                    alpha = ap * exit_
                    y = head.y + j * lead + (1 - ap) * rise - (1 - exit_) * 18
                    sigma = 11 * (1 - ap) ** 1.5 + 5 * (1 - exit_)
                    mask, pad = ty.blurred(word, "display", isz, 700, sigma)
                    shadow, _ = ty.blurred(word, "display", isz, 700, 11)
                    px, py = x * self.k - pad, y * self.k - pad
                    blit(canvas, shadow, px, py + 5 * self.k, DEEP * 0.4, alpha * 0.5)
                    blit(canvas, mask, px, py, ACCENT if acc else ICE, alpha)
                    x += width + space

    def chips(self, canvas: np.ndarray, t: float) -> None:
        """Pills with a format name that pop in on the beats."""
        ty, k = self.type, self.k
        for chip in self.film.chips:
            if not chip.start - 0.02 <= t <= chip.end + 0.4:
                continue
            a = float(ease_out((t - chip.start) / 0.35)) * float(ease(min(1.0, (chip.end - t) / 0.3)))
            pop = 0.92 + 0.08 * float(back_out((t - chip.start) / 0.45))
            size = 38
            w, h = (ty.width(chip.text, "display", size, 600) + 110) * pop, 78 * pop
            from PIL import Image, ImageDraw
            pw, ph = max(4, round(w * k)), max(4, round(h * k))
            ss = 4                                           # drawn 4x and shrunk: smooth edges
            im = Image.new("L", (pw * ss, ph * ss), 0)
            dr = ImageDraw.Draw(im)
            dr.rounded_rectangle((0, 0, pw * ss - 1, ph * ss - 1), radius=ph * ss // 2, outline=255, width=max(2, round(3 * k)) * ss)
            ring = cv2.resize(np.asarray(im, np.float32) / 255, (pw, ph), interpolation=cv2.INTER_AREA)
            im2 = Image.new("L", (pw * ss, ph * ss), 0)
            ImageDraw.Draw(im2).rounded_rectangle((0, 0, pw * ss - 1, ph * ss - 1), radius=ph * ss // 2, fill=255)
            body = cv2.resize(np.asarray(im2, np.float32) / 255, (pw, ph), interpolation=cv2.INTER_AREA)
            px, py = (chip.x - w / 2) * k, (chip.y - h / 2) * k
            blit(canvas, body, px, py, DEEP * 1.6, a * 0.7)
            blit(canvas, ring, px, py, ACCENT, a)
            mask, pad = ty.blurred(chip.text, "display", size, 600, 0)
            tw = ty.width(chip.text, "display", size, 600)
            blit(canvas, mask, (chip.x - tw * pop / 2) * k - pad, (chip.y - size * 0.62 * pop) * k - pad, ICE, a)




    def _end_assets(self):
        """The poster-free background of the end plate and how large its diamond is over time (measured on the plate)."""
        if self._end is not None:
            return self._end
        e = self.film.endlogo
        plate = self.plates[e["plate"]]
        k = self.k
        cx, cy = e["plate_center"]

        def radius(img: np.ndarray) -> float:
            lum = img.mean(2)
            out = []
            for a in (0, 90, 180, 270):
                ang = math.radians(a)
                rr = [r for r in range(int(100 * k), int(520 * k))
                      if 0 <= int(cx * k + r * math.cos(ang)) < self.cw and 0 <= int(cy * k + r * math.sin(ang)) < self.ch
                      and lum[int(cy * k + r * math.sin(ang)), int(cx * k + r * math.cos(ang))] > 0.45]
                out.append(max(rr) / k if rr else 0)
            return float(np.median(out))
        frames = [f for f in range(120, plate.TOTAL + 1, 8)] + [plate.TOTAL]
        radii = [(f, radius(plate.image(float(f)))) for f in frames]
        last = plate.image(float(plate.TOTAL))
        # background: median colour by distance from the middle of the frame, measured outside the diamond
        yy, xx = np.mgrid[0:self.ch, 0:self.cw].astype(np.float32)
        rho = np.hypot((xx / k - 960) / 960, (yy / k - 540) / 540)
        outside = (np.abs(xx / k - cx) + np.abs(yy / k - cy)) > e["plate_radius"] * 1.3
        bins = np.arange(0, 1.7, 0.02)
        prof = np.full((len(bins), 3), np.nan, np.float32)
        for n, b in enumerate(bins):
            m = outside & (rho >= b) & (rho < b + 0.02)
            if m.sum() > 50:
                prof[n] = np.median(last[m], axis=0)
        for n in range(len(bins)):                         # inside the diamond: the nearest measured ring
            if np.isnan(prof[n, 0]):
                nxt = next((q for q in range(n, len(bins)) if not np.isnan(prof[q, 0])), None)
                prv = next((q for q in range(n, -1, -1) if not np.isnan(prof[q, 0])), None)
                prof[n] = prof[nxt if nxt is not None else prv]
        bg = np.stack([np.interp(rho, bins, prof[:, c]) for c in range(3)], -1).astype(np.float32)
        self._end = (bg, radii)
        return self._end

    def endlogo(self, canvas: np.ndarray, t: float) -> None:
        """Posters fade out, only the diamond stays and becomes the vector logo; it moves left, GLACIER slides in on its right."""
        e = self.film.endlogo
        if not e or t < e["fade"][0]:
            return
        k, ty = self.k, self.type
        bg, radii = self._end_assets()
        plate_start = self.film.shots[-1].start
        pf = 1 + (t - plate_start) * FPS
        r_plate = float(np.interp(pf, [f for f, _ in radii], [r for _, r in radii])) * 0.985
        a_bg = float(ease(smooth(*e["fade"], t)))
        canvas += (bg - canvas) * a_bg
        mv = float(ease(smooth(*e["move"], t)))
        big, small = r_plate, e["r_lock"]
        r = lerp(big, small, mv) * (1 + 0.012 * math.sin(2 * math.pi * (t - e["fade"][0]) / 1.7) * (1 - mv))
        word_w = sum(ty.width(c, "display", e["size"], 700) for c in "GLACIER") + e["spacing"] * 6
        lock_w = 2 * small + e["gap"] + word_w
        lx = 960 - lock_w / 2 + small                         # where the logo ends up
        cx, cy = lerp(e["plate_center"][0], lx, mv), lerp(e["plate_center"][1], e["lock_y"], mv)
        col = tuple(int(c * 255) for c in ACCENT) + (255,)
        layer = np.zeros((self.ch, self.cw, 4), np.uint8)
        stroke = max(2, round(lerp(14, small / 12, mv) * k))
        outer = np.array([(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]) * k * 16
        cv2.polylines(layer, [np.round(outer).astype(np.int32)], True, col, stroke, cv2.LINE_AA, 4)
        pop = float(back_out((t - (e["fade"][0] + 0.45)) / 0.55)) if t > e["fade"][0] + 0.45 else 0.0
        if pop > 0:
            si = r * 4.5 / 12 * 1.0 * pop
            poly = np.array([(cx, cy - si), (cx + si, cy), (cx, cy + si), (cx - si, cy)]) * k * 16
            cv2.fillConvexPoly(layer, np.round(poly).astype(np.int32), col, cv2.LINE_AA, 4)
        a = layer[..., 3].astype(np.float32) / 255 * float(ease_out((t - e["fade"][0]) / 0.45))
        glow = cv2.GaussianBlur(a, (0, 0), max(2.0, 14 * k)) * 0.55
        rgb = np.minimum(layer[..., :3].astype(np.float32) / 255 / np.maximum(layer[..., 3:4].astype(np.float32) / 255, 1e-3), 1)
        canvas += (ACCENT - canvas) * glow[..., None] * 0.6
        canvas += (rgb - canvas) * a[..., None]
        # the wordmark slides out from the logo's right side
        x = lx + small + e["gap"]
        font = ty.font("display", max(1, round(e["size"] * k)), 700)
        bx0, by0, bx1, by1 = font.getbbox("H", anchor="la")
        cap_c = (by0 + by1) / 2 / k
        for i, c in enumerate("GLACIER"):
            w = ty.width(c, "display", e["size"], 700)
            f = float(ease_out((t - e["reveal"][0] - i * 0.05) / 0.6))
            if f > 0:
                mask, pad = ty.blurred(c, "display", e["size"], 700, 7 * (1 - f))
                dx = -90 * (1 - f)
                blit(canvas, mask, (x + dx) * k - pad, (e["lock_y"] - cap_c) * k - pad, ICE, f)
            x += w + e["spacing"]
        f = float(ease_out((t - e["tagline_at"]) / 0.8))
        tag = e["tagline"]
        ty_ = e["lock_y"] + small + e["tag_gap"]
        mask, pad = ty.blurred(tag, "display", e["tag_size"], 400, 5 * (1 - f))
        blit(canvas, mask, (W - ty.width(tag, "display", e["tag_size"], 400)) / 2 * k - pad, (ty_ + (1 - f) * 16) * k - pad, (ICE + MIST) / 2, f)
        for i, line in enumerate(e["lines"]):
            f = float(ease_out((t - e["tagline_at"] - 0.5 - 0.25 * i) / 0.8))
            mask, pad = ty.blurred(line, "display", e["line_size"], 400, 4 * (1 - f))
            blit(canvas, mask, (W - ty.width(line, "display", e["line_size"], 400)) / 2 * k - pad,
                 (ty_ + e["tag_size"] * 1.5 + 24 + i * e["line_size"] * 1.55 + (1 - f) * 12) * k - pad, (ICE + MIST) / 2, f * 0.95)

    # -- a frame ------------------------------------------------------------------------------------------

    def base(self, t: float) -> np.ndarray:
        shot = self.film.shot_at(t)
        if shot.scene:
            canvas = self.scene_shot(shot, t)
        else:                       # a sequence plate: played at plate_speed, or stretched over the shot
            plate = self.plates[shot.plate]
            rate = shot.plate_speed * FPS if shot.plate_speed else (plate.TOTAL - 1) / (shot.length - 1 / FPS)
            canvas = plate.image(1 + min((t - shot.start) * rate, plate.TOTAL - 1))
        self.headlines(canvas, t)
        self.chips(canvas, t)
        self.endlogo(canvas, t)
        return canvas

    def frame(self, n: int) -> bytes:
        t = n / FPS
        film = self.film
        blur = next((b for b in film.blur_windows() if b[0] <= t <= b[1]), None)
        if blur:
            _, _, steps, shutter = blur
            offsets = np.linspace(-shutter / 2, shutter / 2, steps)
            canvas = sum(self.base(min(max(t + d, 0), film.length)) for d in offsets) / steps
        else:
            canvas = self.base(t)
        # one grade for plates and UI: a gentle S curve, vignette, grain, fades
        canvas = np.clip(canvas, 0, 1)
        canvas = canvas + 0.10 * (canvas * canvas * (3 - 2 * canvas) - canvas)
        canvas = canvas * self.vignette
        rng = np.random.default_rng(n * 7919 + 13)
        grain = rng.standard_normal((self.ch // 2 + 1, self.cw // 2 + 1)).astype(np.float32)
        grain = cv2.resize(grain, (self.cw, self.ch), interpolation=cv2.INTER_LINEAR)
        canvas = canvas + grain[..., None] * GRAIN * (0.6 + 0.8 * canvas.mean(2, keepdims=True) ** 0.5)
        fade = (0.12 + 0.88 * float(smooth(0.0, film.fade_in, t))) * (1 - float(smooth(film.fade_out[0], film.fade_out[1], t)))
        return (np.clip(canvas * fade, 0, 1) * 255 + 0.5).astype(np.uint8).tobytes()


_R: Renderer | None = None






# ---------------------------------------------------------------------------------------------------------
# Sound
# ---------------------------------------------------------------------------------------------------------

def one_pole(x: np.ndarray, fc: np.ndarray) -> np.ndarray:
    """Low-pass with a cutoff (Hz) that changes per sample."""
    a = (1 - np.exp(-2 * np.pi * fc / SR)).tolist()
    xs, y, out = x.tolist(), 0.0, []
    for i, v in enumerate(xs):
        y += a[i] * (v - y)
        out.append(y)
    return np.array(out, np.float32)


def two_pole(x, fc):
    return one_pole(one_pole(x, fc), fc)


def db(x: float) -> float:
    return 10 ** (x / 20)


def make_click(rng: np.random.Generator, soft: bool) -> np.ndarray:
    """A soft rubber-dome remote click: filtered noise tick, a little resonance, a faint body and the release."""
    n = int(0.12 * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n).astype(np.float32)
    noise = noise - two_pole(noise, np.full(n, 1800.0))            # high-passed
    tick = noise * np.exp(-t / 0.0011) * (1 - np.exp(-t / 0.0002))
    res = np.sin(2 * np.pi * 3100 * t) * np.exp(-t / 0.0035) * 0.35 + np.sin(2 * np.pi * 4700 * t) * np.exp(-t / 0.0022) * 0.18
    body = np.sin(2 * np.pi * (170 - 60 * np.minimum(t / 0.02, 1)) * t) * np.exp(-t / 0.010) * 0.55
    click = tick * 0.9 + res + body
    rel = np.zeros(n, np.float32)
    d = int(0.062 * SR)
    rel[d:] = click[:n - d] * 0.22                                      # the key coming back up
    click = click + rel
    click = two_pole(click.astype(np.float32), np.full(n, 7500.0 if soft else 9500.0))
    return click / np.abs(click).max()


def make_whoosh(rng: np.random.Generator, length: float, rise: bool) -> np.ndarray:
    n = int(length * SR)
    u = np.linspace(0, 1, n, dtype=np.float32)
    noise = rng.standard_normal(n).astype(np.float32)
    if rise:      # swells towards the end (peak at 82 %), short tail
        env = np.where(u < 0.82, (u / 0.82) ** 2.2, np.exp(-(u - 0.82) / 0.05))
        fc = 350 + 5200 * np.minimum(u / 0.82, 1) ** 1.6
    else:         # starts at the cut, opens up and fades away
        env = np.minimum(u / 0.08, 1) * np.exp(-u * 3.4)
        fc = 4200 * np.exp(-u * 1.6) + 500
    lo = two_pole(noise, fc.astype(np.float64))
    hi = lo - two_pole(lo, np.full(n, 220.0))
    out = hi * env
    return out / (np.sqrt((out ** 2).mean()) + 1e-9)


def make_riser(rng: np.random.Generator, length: float) -> np.ndarray:
    """Reverse swell: noise that opens up while it gets louder, with a faint rising tone."""
    n = int(length * SR)
    u = np.linspace(0, 1, n)
    out = np.zeros((n, 2), np.float32)
    for ch in range(2):
        noise = rng.standard_normal(n).astype(np.float32)
        band = two_pole(noise, 300 + 7500 * u ** 2.0)
        band = band - two_pole(band, np.full(n, 350.0))
        out[:, ch] = band * (u ** 2.4)
    tone = np.sin(2 * np.pi * np.cumsum(300 + 900 * u ** 2) / SR) * u ** 3 * 0.9
    out += tone[:, None].astype(np.float32)
    return out / (np.sqrt((out ** 2).mean()) + 1e-9)


def render_audio(film: Film, g: Grid, music_path: Path, out_raw: Path, seed: int = 7) -> None:
    rng = np.random.default_rng(seed)
    n = int(film.length * SR)
    music = decode(music_path, SR, 2)
    if film.music_edits:        # pieces of the track, joined with equal-power crossfades
        src, music = music, np.zeros((n, 2), np.float32)
        for i, (dst_t, src_t, length, fade) in enumerate(film.music_edits):
            piece = src[int(src_t * SR):int((src_t + length) * SR)].copy()
            f = int(fade * SR)
            if i > 0:
                piece[:f] *= np.sin(np.linspace(0, np.pi / 2, f))[:, None]
            if i < len(film.music_edits) - 1:
                piece[-f:] *= np.cos(np.linspace(0, np.pi / 2, f))[:, None]
            d = int(dst_t * SR)
            end = min(d + len(piece), n)
            music[d:end] += piece[:end - d]
    music = music[:n] if len(music) >= n else np.pad(music, ((0, n - len(music)), (0, 0)))
    # the breath: low-pass sweep down, duck, then everything back on the downbeat
    if film.breath:
        b0, b1 = film.breath
        i0, i1 = int(b0 * SR), int(b1 * SR)
        cutoffs = [20000, 6000, 2500, 1100, 500, 250]
        versions = [music[i0:i1]] + [
            decode(music_path, SR, 2, ["-af", f"lowpass=f={fc}:p=2,lowpass=f={fc}:p=2"])[:n][i0:i1] for fc in cutoffs[1:]]
        tt = (np.arange(i1 - i0) / SR)
        span = b1 - b0
        sweep = float(0.55)
        fc_t = np.where(tt < sweep * span, 20000 * (260 / 20000) ** ease_np(tt / (sweep * span)), 260.0)
        fc_t = np.where(tt > span * 0.78, 260 * (1800 / 260) ** ease_np((tt - span * 0.78) / (span * 0.22)), fc_t)
        lf = np.log(np.clip(fc_t, 250, 20000))
        grid_l = np.log(np.array(cutoffs, float))
        idx = np.clip(np.searchsorted(-grid_l, -lf) - 1, 0, len(cutoffs) - 2)
        w = np.clip((grid_l[idx] - lf) / (grid_l[idx] - grid_l[idx + 1]), 0, 1)[:, None].astype(np.float32)
        stack = np.stack(versions)                       # (versions, samples, 2)
        breath = (1 - w) * stack[idx, np.arange(len(tt))] + w * stack[idx + 1, np.arange(len(tt))]
        duck = db(-6.0 * ease_np(tt / 0.5) * (1 - float(1) * ease_np((tt - (span - 0.12)) / 0.12)))
        music[i0:i1] = breath * duck[:, None]
    music[:int(0.05 * SR)] *= np.linspace(0, 1, int(0.05 * SR))[:, None]   # the file starts with a click
    # local loudness of the music, for the level of the clicks
    sq = np.cumsum(np.r_[0, (music ** 2).mean(1)])
    half = SR // 2
    rms_db = lambda i: 10 * math.log10((sq[min(i + half, n)] - sq[max(i - half, 0)]) / (min(i + half, n) - max(i - half, 0)) + 1e-12)

    mix = music.copy()
    thump = np.sin(2 * np.pi * 52 * np.arange(int(0.5 * SR)) / SR) * np.exp(-np.arange(int(0.5 * SR)) / SR / 0.12) * db(-16.0)
    if film.breath:
        riser = make_riser(rng, 1.2)
        ri = int((film.breath[1] - 1.2) * SR)
        mix[ri:ri + len(riser)] += riser * db(-26.0)
        mix[int(film.breath[1] * SR):int(film.breath[1] * SR) + len(thump)] += thump[:, None]
    for s in film.sfx:
        i = int(s.t * SR)
        if s.kind == "thump":
            mix[i:i + len(thump)] += thump[:, None] * db(-3.0)
            continue
        if s.kind.startswith("click"):
            c = make_click(rng, s.kind == "click_soft")
            peak = min(CLICK_PEAK_DB, rms_db(i) + 2) - (4 if s.kind == "click_soft" else 0)
            pan = rng.uniform(-0.2, 0.2)
            sig = np.stack([c * (1 - pan), c * (1 + pan)], 1) * db(peak)
            i0_, i1_ = i, i + len(sig)
        else:
            wh = make_whoosh(rng, s.length, s.rise)
            sig = np.stack([wh, np.roll(wh, 37)], 1) * db(min(WHOOSH_RMS_DB, rms_db(i) - 18))
            i0_ = i - int(0.82 * len(wh)) if s.rise else i      # a rising whoosh peaks at 82 % of its length
            i1_ = i0_ + len(sig)
        i0_c = max(i0_, 0)
        mix[i0_c:min(i1_, n)] += sig[i0_c - i0_:min(i1_, n) - i0_]
    mix.astype(np.float32).tofile(out_raw)


def ease_np(x):
    x = np.clip(x, 0, 1)
    return np.where(x < 0.5, 4 * x ** 3, 1 - (-2 * x + 2) ** 3 / 2)


def measure(path: Path) -> tuple[float, float]:
    err = run([FFMPEG, "-nostats", "-i", path, "-af", "ebur128=peak=true", "-f", "null", "-"],
              capture_output=True, text=True).stderr
    summary = err[err.rfind("Summary:"):]
    i = float(re.search(r"I:\s+(-?[\d.]+) LUFS", summary).group(1))
    tp = float(re.search(r"Peak:\s+(-?[\d.]+) dBFS", summary).group(1))
    return i, tp


def measure_aac(wav: Path) -> tuple[float, float]:
    """Loudness and true peak of what the film's AAC track will really be (encoded and decoded again)."""
    tmp = wav.with_suffix(".m4a")
    run([FFMPEG, "-v", "error", "-y", "-i", wav, "-c:a", "aac", "-b:a", "192k", "-ar", SR, "-ac", 2, tmp], check=True)
    try:
        return measure(tmp)
    finally:
        tmp.unlink()


def master(raw: Path, out_wav: Path, target: float = TARGET_LUFS) -> tuple[float, float]:
    """Gain to the target loudness and a limiter, corrected until the ENCODED track (AAC 192k) measures the target (+-0.2 LU)
    with a true peak <= TRUE_PEAK. Returns what the encoded track measures."""
    tmp = out_wav.with_suffix(".tmp.wav")
    run([FFMPEG, "-v", "error", "-y", "-f", "f32le", "-ar", SR, "-ac", 2, "-i", raw, "-c:a", "pcm_f32le", tmp], check=True)
    loud, _ = measure(tmp)
    gain, ceiling = target - loud, TRUE_PEAK - 1.0
    for _ in range(6):
        run([FFMPEG, "-v", "error", "-y", "-i", tmp, "-af",
             f"volume={gain:.2f}dB,alimiter=limit={db(ceiling):.3f}:attack=3:release=60:level=0", "-c:a", "pcm_f32le", out_wav], check=True)
        i, tp = measure_aac(out_wav)
        ok_peak, ok_loud = tp <= TRUE_PEAK - 0.05, abs(i - target) <= 0.2
        if ok_peak and ok_loud:
            break
        if not ok_peak:
            ceiling -= tp - (TRUE_PEAK - 0.3)
        if not ok_loud:
            gain += target - i
    tmp.unlink()
    return i, tp


# ---------------------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------------------



def contact_sheet(video: Path, out: Path, step: float = 0.5, cols: int = 6, thumb: int = 320) -> None:
    th = thumb * 9 // 16
    raw = run([FFMPEG, "-v", "error", "-i", video, "-vf", f"fps={1 / step},scale={thumb}:{th}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
              capture_output=True, check=True).stdout
    frames = np.frombuffer(raw, np.uint8).reshape(-1, th, thumb, 3)
    rows = math.ceil(len(frames) / cols)
    sheet = Image.new("RGB", (cols * thumb, rows * th), (20, 20, 20))
    draw = ImageDraw.Draw(sheet)
    for i, f in enumerate(frames):
        x, y = (i % cols) * thumb, (i // cols) * th
        sheet.paste(Image.fromarray(f), (x, y))
        draw.rectangle((x, y, x + 50, y + 15), fill=(0, 0, 0))
        draw.text((x + 3, y + 2), f"{i * step:4.1f}s", fill=(255, 235, 90))
    sheet.save(out)








if __name__ == "__main__":
    main()
