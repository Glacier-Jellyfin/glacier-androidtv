#!/usr/bin/env python3
"""Records the website's clips and screenshots from the running app.

Drives a debug build on an Android TV emulator over adb, against the Glacier demo
server (github.com/Glacier-Jellyfin/glacier-demo), and writes per language:

  website/assets/clips/<take>[-de].mp4 and .webm   short muted loops for each section
  website/assets/screenshots/<still>[-de].jpg      their posters and the stills
  website/assets/screenshots/demo[-de].mp4/.webm   the hero video, demo.gif for the README

The demo library is made up and licensed for advertising (see the demo repo's
CREDITS.md), so everything recorded here may be published. Never capture another
server.

Before running:
  - the demo server runs on this machine (glacier-demo: demo.py server start)
  - an emulator with a 1920x1080 Google TV image runs with a snapshot named
    "signed-in-family": the debug build installed, the profiles Nora, Alex and
    Kids signed in to the demo server (http://10.0.2.2:8098), the app stopped.
    Every take starts from that snapshot, so remembered tracks and the like from
    an earlier take never show up in the next.

Needs Python 3.9+, adb and ffmpeg on PATH (or ADB / FFMPEG set). The script sets
resume points of the demo profile Nora before each take.

Usage: scripts/capture-website-media.py [--serial emulator-5560] [--lang en,de]
                                        [--only library,detail,...] [--takes DIR]
--takes keeps the full 1080p recordings in DIR, for the promo video.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "website" / "assets" / "screenshots"
CLIPS = ROOT / "website" / "assets" / "clips"
PACKAGE = "io.github.glacier_jellyfin.androidtv.debug"
ACTIVITY = "io.github.glacier_jellyfin.androidtv.MainActivity"
# The server as this machine sees it; the emulator reaches it on 10.0.2.2.
SERVER = "http://localhost:8098"
ADMIN = ("Admin", "glacier-demo")
USER = "Nora"
PROFILES = ("Alex", "Kids", "Nora")
SNAPSHOT = "signed-in-family"
TIMEZONE = "Europe/Berlin"
LOCALES = {"en": "en-US", "de": "de-DE"}
# Positions in the navigation bar, counted from Home.
NAV = {"Movie": 1, "Show": 2, "Music": 3, "Videos": 4, "Photos": 5, "Live TV": 6}


def tool(name: str, env: str) -> str:
    found = os.environ.get(env) or shutil.which(name)
    if not found and name == "adb":
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
            or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
        candidate = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        found = str(candidate) if candidate.exists() else None
    if not found:
        sys.exit(f"{name} not found; put it on PATH or set {env}")
    return found


class Device:
    def __init__(self, serial: str | None):
        self.adb = tool("adb", "ADB")
        if serial is None:
            listing = subprocess.run([self.adb, "devices"], check=True, capture_output=True, text=True).stdout
            emulators = re.findall(r"^(emulator-\d+)\s+device$", listing, re.M)
            if len(emulators) != 1:
                sys.exit("Start exactly one emulator, or pass --serial")
            serial = emulators[0]
        self.base = [self.adb, "-s", serial]
        # Git Bash would turn /sdcard into a Windows path.
        self.env = dict(os.environ, MSYS_NO_PATHCONV="1")

    def run(self, *args: str, binary: bool = False, timeout: float = 120) -> bytes | str:
        out = subprocess.run(self.base + list(args), capture_output=True, env=self.env, timeout=timeout)
        if out.returncode != 0:
            sys.exit(f"adb {' '.join(args)[:80]} failed: {out.stderr.decode('utf-8', 'replace').strip()}")
        return out.stdout if binary else out.stdout.decode("utf-8", "replace")

    def shell(self, command: str, timeout: float = 120) -> str:
        return self.run("shell", command, timeout=timeout)

    def key(self, *names: str, pause: float = 0.6) -> None:
        for name in names:
            self.shell(f"input keyevent KEYCODE_{name}")
            time.sleep(pause)

    def nodes(self) -> list[dict]:
        """The UI tree as flat nodes; empty while the screen never idles (video)."""
        xml = self.run("exec-out", "uiautomator", "dump", "/dev/tty")
        xml = xml[xml.find("<?xml"):xml.rfind(">") + 1]
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return []
        result = []
        for node in root.iter("node"):
            b = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
            if len(b) == 4:
                result.append({
                    "text": node.get("text") or node.get("content-desc") or "",
                    "focused": node.get("focused") == "true",
                    "bounds": b,
                })
        return result

    def texts(self) -> list[str]:
        return [n["text"] for n in self.nodes() if n["text"]]

    def focused_text(self) -> str:
        """All text inside the focused element, joined with " | "."""
        nodes = self.nodes()
        focused = next((n for n in nodes if n["focused"]), None)
        if focused is None:
            return ""
        l, t, r, b = focused["bounds"]
        inside = [n["text"] for n in nodes if n["text"] and
                  n["bounds"][0] >= l and n["bounds"][1] >= t and n["bounds"][2] <= r and n["bounds"][3] <= b]
        return " | ".join(inside)

    def wait_for(self, text: str, timeout: float = 45) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(t == text for t in self.texts()):
                return
            time.sleep(1)
        sys.exit(f"Timed out waiting for '{text}' on screen")

    def press_until(self, key: str, predicate, tries: int = 12) -> None:
        for _ in range(tries):
            if predicate(self.focused_text()):
                return
            self.key(key, pause=0.4)
        if not predicate(self.focused_text()):
            sys.exit(f"Could not reach the wanted element with {key}; focused: '{self.focused_text()}'")

    def fresh(self, locale: str) -> None:
        """Back to the signed-in snapshot, with the real time and the wanted app language."""
        self.run("emu", "avd", "snapshot", "load", SNAPSHOT, timeout=120)
        self.run("wait-for-device", timeout=60)
        time.sleep(2)
        self.shell(f"cmd alarm set-time {int(time.time() * 1000)}")
        self.shell(f"cmd alarm set-timezone {TIMEZONE}")
        self.shell(f"cmd locale set-app-locales {PACKAGE} --locales {locale}")

    def record(self, remote: str, steps: list[str]) -> None:
        """Runs [steps] while screenrecord runs, all in one shell on the device:
        two adb sessions at once hang on Windows now and then.

        A step is KEY[*n][:pause] or sleepSECONDS."""
        lines = [f"rm -f {remote}", f"screenrecord --bit-rate 16000000 --time-limit 170 {remote} & rec=$!", "sleep 1.5"]
        for step in steps:
            if step.startswith("sleep"):
                lines.append(f"sleep {step[5:]}")
                continue
            key, _, pause = step.partition(":")
            key, _, count = key.partition("*")
            for _ in range(int(count or 1)):
                lines += [f"input keyevent KEYCODE_{key}", f"sleep {pause or 0.5}"]
        lines += ["kill -INT $rec", "wait $rec", "echo recorded"]
        if "recorded" not in self.shell("; ".join(lines), timeout=240):
            sys.exit(f"Recording {remote} failed")


class Server:
    """Just enough of the Jellyfin API to find titles and place Nora's resume points."""

    def __init__(self):
        self.token = None
        auth = self.request("/Users/AuthenticateByName", {"Username": ADMIN[0], "Pw": ADMIN[1]})
        self.token = auth["AccessToken"]
        self.id = self.request("/System/Info/Public")["Id"]
        self.user = next(u["Id"] for u in self.request("/Users") if u["Name"] == USER)

    def request(self, path: str, body: dict | None = None, method: str | None = None):
        header = 'MediaBrowser Client="Glacier media capture", Device="script", DeviceId="glacier-capture", Version="1"'
        if self.token:
            header += f', Token="{self.token}"'
        req = urllib.request.Request(
            SERVER + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": header, "Content-Type": "application/json"},
            method=method or ("POST" if body is not None else "GET"),
        )
        with urllib.request.urlopen(req) as response:
            raw = response.read()
        return json.loads(raw) if raw else None

    def find(self, name: str, kind: str) -> dict:
        query = urllib.parse.quote(name)
        items = self.request(f"/Items?userId={self.user}&IncludeItemTypes={kind}&Recursive=true&searchTerm={query}")["Items"]
        match = next((i for i in items if i["Name"] == name), None)
        if match is None:
            sys.exit(f"'{name}' not found on the demo server; is it the Glacier demo server?")
        return match

    def episode(self, series: str, season: int, number: int) -> dict:
        show = self.find(series, "Series")
        episodes = self.request(f"/Shows/{show['Id']}/Episodes?userId={self.user}")["Items"]
        return next(e for e in episodes if e.get("ParentIndexNumber") == season and e.get("IndexNumber") == number)

    def resume_at(self, item: dict, seconds: float, played: bool = False) -> None:
        self.request(f"/UserItems/{item['Id']}/UserData?userId={self.user}",
                     {"PlaybackPositionTicks": int(seconds * 10_000_000), "Played": played})


@dataclass
class Take:
    """One recording: where it starts, the keys pressed while recording, and the part kept."""
    start: str  # "picker", "home", a navigation entry from NAV, or "open:Title" / "open:Show/S/E"
    steps: list[str]
    clip: tuple[float, float] | None = None  # seconds kept for the website clip; None for no clip
    still: tuple[str, float] | None = None  # name and second of a still taken from the recording
    resume: dict[str, float] = field(default_factory=dict)  # title or Show/S/E -> seconds
    played: tuple[str, ...] = ()


TAKES: dict[str, Take] = {
    "profiles": Take("picker", ["sleep1.8", "DPAD_RIGHT:1.1", "DPAD_RIGHT:1.4", "DPAD_CENTER:6", "DPAD_DOWN:1.5",
                                "DPAD_RIGHT:1", "DPAD_RIGHT:1.5"],
                     clip=(1.0, 15.5), still=("home", 9.0)),
    "library": Take("Movie", ["sleep1.5", "DPAD_RIGHT:0.6", "DPAD_RIGHT:0.6", "DPAD_RIGHT:0.9", "DPAD_RIGHT*4:0.15",
                              "sleep0.5", "DPAD_RIGHT:0.8", "DPAD_DOWN*12:0.05", "sleep0.5", "DPAD_CENTER:1.6",
                              "DPAD_LEFT:1.4", "DPAD_DOWN:1.2", "DPAD_RIGHT:1.5"],
                    clip=(1.0, 14.0), still=("library", 1.2)),
    "detail": Take("open:Glass Mountain", ["sleep1.5", "DPAD_RIGHT*4:0.3", "sleep0.3", "DPAD_CENTER:1.4", "DPAD_UP:1.2",
                                           "BACK:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:1.0", "DPAD_DOWN*3:0.6", "sleep0.6",
                                           "BACK:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:1.0", "DPAD_DOWN*4:0.45", "sleep0.8",
                                           "BACK:0.7", "DPAD_DOWN:1.4", "DPAD_RIGHT*3:0.5", "sleep0.4", "DPAD_DOWN:1.8"],
                   clip=(1.5, 23.0), still=("detail", 1.6), resume={"Glass Mountain": 4700}),
    # Play, then OK on "Skip intro" while the controls still show, so the timeline jumps visibly.
    "skip": Take("open:Harbor Lights/1/2", ["sleep1.2", "DPAD_CENTER:8.6", "DPAD_CENTER:6"],
                 clip=(5.5, 15.5), still=("player-skip", 9.6), resume={"Harbor Lights/1/2": 0},
                 played=("Harbor Lights/1/2",)),
    "upnext": Take("open:Harbor Lights/1/2", ["sleep1.2", "DPAD_CENTER:28"],
                   clip=(17.0, 30.0), still=("player-upnext", 26.0), resume={"Harbor Lights/1/2": 2566}),
    "trickplay": Take("open:Glass Mountain", ["sleep1", "DPAD_CENTER:10", "DPAD_RIGHT*7:0.5", "sleep3"],
                      clip=(8.5, 18.0), still=("player-trickplay", 14.5), resume={"Glass Mountain": 4700}),
    "music": Take("Music", ["sleep1", "DPAD_DOWN:0.7", "DPAD_RIGHT:0.9", "DPAD_CENTER:2.2", "DPAD_CENTER:4",
                            "DPAD_RIGHT*3:0.35", "sleep0.4", "DPAD_CENTER:7", "BACK:1.5", "BACK:2.5",
                            "DPAD_RIGHT:0.8", "DPAD_RIGHT:1.5"],
                  clip=(2.5, 27.5), still=("music", 12.0)),
    "livetv": Take("Live TV", ["sleep2.5", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4",
                               "DPAD_UP*4:0.15", "sleep0.5", "DPAD_UP:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:2.5",
                               "DPAD_DOWN:1.2", "DPAD_RIGHT*3:0.9", "DPAD_DOWN*2:0.9", "sleep1.5"],
                   clip=(1.0, 26.0), still=("livetv", 2.0)),
}
# The demo server's own watch state for the titles the takes move, put back at the end.
RESTORE = {"Harbor Lights/1/2": (0, True), "Glass Mountain": (4700, False)}
# The hero video: the opening of several takes, cross-faded.
HERO = [("profiles", 1.0, 9.0), ("library", 1.0, 9.5), ("detail", 1.5, 9.0), ("skip", 6.0, 13.0)]
HERO_FADE = 0.5


class Capture:
    def __init__(self, device: Device, server: Server, work: Path, lang: str, keep: Path | None):
        self.d = device
        self.server = server
        self.work = work
        self.lang = lang
        self.keep = keep
        self.ffmpeg = tool("ffmpeg", "FFMPEG")
        self.suffix = "" if lang == "en" else f"-{lang}"

    def item(self, ref: str) -> dict:
        if "/" in ref:
            show, season, number = ref.split("/")
            return self.server.episode(show, int(season), int(number))
        return self.server.find(ref, "Movie")

    # Starting points ----------------------------------------------------------

    def launch(self) -> None:
        self.d.shell(f"am force-stop {PACKAGE}")
        self.d.shell(f"monkey -p {PACKAGE} -c android.intent.category.LEANBACK_LAUNCHER 1")
        self.d.wait_for(USER)

    def sign_in(self) -> None:
        """From the profile picker into Nora's home screen. The picker focuses the first profile,
        and the names sit below the pictures, outside the focused element."""
        names = [t for t in self.d.texts() if t in PROFILES]
        self.d.key(*["DPAD_RIGHT"] * names.index(USER), pause=0.4)
        self.d.key("DPAD_CENTER")
        time.sleep(8)  # rows and artwork

    def open_title(self, item: dict) -> None:
        """Straight onto a title's page through the debug build's OPEN_TITLE intent."""
        self.d.shell(f"am force-stop {PACKAGE}")
        self.d.shell(f"am start -S -n {PACKAGE}/{ACTIVITY} -a io.github.glacier_jellyfin.androidtv.OPEN_TITLE "
                     f"--es server {self.server.id} --es user {self.server.user} --es item {item['Id']}")
        time.sleep(8)

    def prepare(self, take: Take) -> None:
        self.d.fresh(LOCALES[self.lang])
        for ref, seconds in take.resume.items():
            self.server.resume_at(self.item(ref), seconds, played=ref in take.played)
        if take.start.startswith("open:"):
            self.open_title(self.item(take.start[5:]))
            return
        self.launch()
        if take.start == "picker":
            return
        self.sign_in()
        if take.start in NAV:
            self.d.key("DPAD_UP", pause=0.8)
            self.d.key(*["DPAD_RIGHT"] * NAV[take.start], pause=0.4)
            self.d.key("DPAD_CENTER", pause=5)

    # Output -------------------------------------------------------------------

    def encode(self, args: list) -> None:
        subprocess.run([self.ffmpeg, "-v", "error", "-y"] + [str(a) for a in args], check=True)

    def web(self, source: Path, start: float, end: float, name: str) -> None:
        """A 720p muted loop as MP4 and WebM."""
        scale = "fps=30,scale=1280:720:flags=lanczos"
        cut = ["-ss", start, "-to", end, "-i", source, "-vf", scale, "-an"]
        self.encode(cut + ["-c:v", "libx264", "-preset", "slow", "-crf", 26, "-pix_fmt", "yuv420p",
                           "-movflags", "+faststart", CLIPS / f"{name}.mp4"])
        self.encode(cut + ["-c:v", "libvpx-vp9", "-crf", 40, "-b:v", 0, "-row-mt", 1, CLIPS / f"{name}.webm"])

    def capture(self, name: str, take: Take) -> Path:
        self.prepare(take)
        remote = f"/sdcard/glacier-{name}.mp4"
        self.d.record(remote, take.steps)
        raw = self.work / f"{name}{self.suffix}.mp4"
        self.d.run("pull", remote, str(raw))
        self.d.shell(f"rm -f {remote}")
        if self.keep:
            shutil.copy(raw, self.keep / raw.name)
        if take.clip:
            self.web(raw, *take.clip, f"{name}{self.suffix}")
        if take.still:
            still, at = take.still
            self.encode(["-ss", at, "-i", raw, "-frames:v", 1, "-vf", "scale=1280:720:flags=lanczos", "-q:v", 3,
                         SHOTS / f"{still}{self.suffix}.jpg"])
        print(f"  {name}{self.suffix}")
        return raw

    def hero(self, raws: dict[str, Path]) -> None:
        """The hero video: the openings of a few takes, cross-faded, as MP4, WebM and a GIF."""
        inputs, chain, offset, last = [], [], 0.0, "0:v"
        for i, (name, start, end) in enumerate(HERO):
            inputs += ["-ss", start, "-to", end, "-i", raws[name]]
            chain.append(f"[{i}:v]fps=30,scale=1120:630:flags=lanczos,setpts=PTS-STARTPTS[v{i}]")
        last, length = "v0", HERO[0][2] - HERO[0][1]
        for i in range(1, len(HERO)):
            offset = length - HERO_FADE
            chain.append(f"[{last}][v{i}]xfade=transition=fade:duration={HERO_FADE}:offset={offset:.2f}[x{i}]")
            last, length = f"x{i}", length + HERO[i][2] - HERO[i][1] - HERO_FADE
        graph = ";".join(chain)
        out = SHOTS / f"demo{self.suffix}"
        base = inputs + ["-filter_complex", graph, "-map", f"[{last}]", "-an"]
        self.encode(base + ["-c:v", "libx264", "-preset", "slow", "-crf", 27, "-pix_fmt", "yuv420p",
                            "-movflags", "+faststart", f"{out}.mp4"])
        self.encode(base + ["-c:v", "libvpx-vp9", "-crf", 40, "-b:v", 0, "-row-mt", 1, f"{out}.webm"])
        if self.lang == "en":
            # The README shows the same animation as a GIF.
            self.encode(["-i", f"{out}.mp4", "-vf",
                         "fps=15,scale=800:450:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];"
                         "[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle", SHOTS / "demo.gif"])
        print(f"  demo{self.suffix}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", help="adb serial of the emulator")
    parser.add_argument("--lang", default="en,de", help="comma-separated app languages, of " + ",".join(LOCALES))
    parser.add_argument("--only", help=f"comma-separated subset of {','.join(TAKES)}, hero")
    parser.add_argument("--takes", type=Path, help="keep the full recordings in this folder")
    args = parser.parse_args()
    wanted = args.only.split(",") if args.only else list(TAKES) + ["hero"]
    unknown = set(wanted) - set(TAKES) - {"hero"}
    langs = args.lang.split(",")
    if unknown or set(langs) - set(LOCALES):
        sys.exit(f"Unknown: {', '.join(sorted(unknown | (set(langs) - set(LOCALES))))}")
    if "hero" in wanted:
        wanted += [name for name, *_ in HERO if name not in wanted]

    device = Device(args.serial)
    size = device.shell("wm size").strip()
    if "1920x1080" not in size:
        sys.exit(f"Expected a 1920x1080 screen, got '{size}'")
    server = Server()
    CLIPS.mkdir(parents=True, exist_ok=True)
    if args.takes:
        args.takes.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as work:
        for lang in langs:
            print(lang)
            capture = Capture(device, server, Path(work), lang, args.takes)
            raws = {name: capture.capture(name, take) for name, take in TAKES.items() if name in wanted}
            if "hero" in wanted:
                capture.hero(raws)
        for ref, (seconds, played) in RESTORE.items():
            server.resume_at(capture.item(ref), seconds, played)
    device.shell(f"am force-stop {PACKAGE}")


if __name__ == "__main__":
    main()
