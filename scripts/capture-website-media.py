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
  - an emulator with a 1920x1080 Google TV image runs with two snapshots,
    "signed-in-family" and "signed-in-family-de": the debug build installed, the
    profiles Nora, Alex and Kids signed in to the demo server (http://10.0.2.2:8098),
    in the second one with Settings › Account › Interface language set to Deutsch
    for each, the app stopped. Every take starts from a snapshot, so remembered
    tracks and the like from an earlier take never show up in the next.

Needs Python 3.9+, adb and ffmpeg on PATH (or ADB / FFMPEG set). The script sets
resume points of the demo profile Nora before each take.

Usage: scripts/capture-website-media.py [--serial emulator-5560] [--lang en,de]
                                        [--only library,detail,...] [--takes DIR]
--takes keeps the full 1080p recordings in DIR, for the promo video. With it, a montage
(family, player, hero) alone is cut again from the kept takes.
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
# One emulator snapshot per language: the profiles signed in, their interface language set in
# Settings › Account. Android's per-app language confuses the app's own one (keys stop moving).
SNAPSHOTS = {"en": "signed-in-family", "de": "signed-in-family-de"}
TIMEZONE = "Europe/Berlin"
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
        self.serial = serial
        self.base = [self.adb, "-s", serial]
        # Git Bash would turn /sdcard into a Windows path.
        self.env = dict(os.environ, MSYS_NO_PATHCONV="1")
        self.avd = self.run("emu", "avd", "name").splitlines()[0].strip()

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

    def online(self, timeout: float) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            out = subprocess.run(self.base + ["shell", "getprop sys.boot_completed"], capture_output=True,
                                 text=True, env=self.env, timeout=30)
            # A snapshot restores sys.boot_completed at once; the services the takes use come a bit later.
            if out.stdout.strip() == "1" and "not found" not in subprocess.run(
                    self.base + ["shell", "service check alarm"], capture_output=True, text=True,
                    env=self.env, timeout=30).stdout:
                return True
            time.sleep(2)
        return False

    def restart(self, snapshot: str) -> None:
        """Starts the emulator again from the snapshot. Now and then adb loses an emulator for good
        after a snapshot load; only a new start brings it back."""
        print("  emulator offline after the snapshot, starting it again")
        subprocess.run(self.base + ["emu", "kill"], capture_output=True, env=self.env, timeout=60)
        time.sleep(8)
        emulator = Path(self.adb).parent.parent / "emulator" / ("emulator.exe" if os.name == "nt" else "emulator")
        port = self.serial.split("-")[1]
        subprocess.Popen([str(emulator), "-avd", self.avd, "-port", port, "-no-boot-anim", "-snapshot", snapshot,
                          "-no-snapshot-save"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if not self.online(240):
            sys.exit(f"Emulator {self.avd} did not come back")

    def fresh(self, snapshot: str) -> None:
        """Back to a signed-in snapshot, with the real time."""
        self.run("emu", "avd", "snapshot", "load", snapshot, timeout=120)
        if not self.online(60):
            self.restart(snapshot)
        time.sleep(2)
        self.shell(f"cmd alarm set-time {int(time.time() * 1000)}")
        self.shell(f"cmd alarm set-timezone {TIMEZONE}")

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
                     still=("home", 9.0)),
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
                 still=("player-skip", 10.5), resume={"Harbor Lights/1/2": 0},
                 played=("Harbor Lights/1/2",)),
    "upnext": Take("open:Harbor Lights/1/2", ["sleep1.2", "DPAD_CENTER:28"],
                   resume={"Harbor Lights/1/2": 2566}),
    # The emulator cannot decode the German E-AC-3 track, so the server converts it and playback takes a while.
    "trickplay": Take("open:Glass Mountain", ["sleep1", "DPAD_CENTER:18", "DPAD_RIGHT*7:0.5", "sleep3"],
                      resume={"Glass Mountain": 4700}),
    "music": Take("Music", ["sleep1", "DPAD_DOWN:0.7", "DPAD_RIGHT:0.9", "DPAD_CENTER:2.2", "DPAD_CENTER:4",
                            "DPAD_RIGHT*3:0.35", "sleep0.4", "DPAD_CENTER:7", "BACK:1.5", "BACK:2.5",
                            "DPAD_RIGHT:0.8", "DPAD_RIGHT:1.5"],
                  clip=(2.5, 27.5), still=("music", 16.0)),
    "livetv": Take("Live TV", ["sleep2.5", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4",
                               "DPAD_UP*4:0.15", "sleep0.5", "DPAD_UP:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:2.5",
                               "DPAD_DOWN:1.2", "DPAD_RIGHT*3:0.9", "DPAD_DOWN*2:0.9", "sleep1.5"],
                   clip=(1.0, 26.0), still=("livetv", 2.0)),
    # The profiles section in one take: Kids first, then back through the profile button to Nora.
    "family": Take("picker", ["sleep1.5", "DPAD_RIGHT:1.3", "DPAD_CENTER:5.5", "DPAD_DOWN:1.3", "DPAD_RIGHT:0.8",
                              "DPAD_RIGHT:1.2", "DPAD_UP:0.5", "DPAD_UP:0.8", "DPAD_RIGHT*10:0.12", "sleep0.6",
                              "DPAD_CENTER:2.2", "DPAD_RIGHT:0.9", "DPAD_RIGHT:1.1", "DPAD_CENTER:6"]),
    # The hero video in one take: a first look around, from the home screen to a title's page.
    "hero": Take("home", ["sleep2.5", "DPAD_DOWN:1.6", "DPAD_RIGHT:1.0", "DPAD_RIGHT:1.6", "DPAD_UP:0.6",
                          "DPAD_UP:0.9", "DPAD_RIGHT:0.6", "DPAD_CENTER:3.4", "DPAD_RIGHT:0.8", "DPAD_RIGHT:0.8",
                          "DPAD_DOWN:1.2", "DPAD_CENTER:3.8", "DPAD_DOWN:2.6"]),
    # A folder of photos and videos: switch it to the gallery, browse the row, then full screen and
    # the slideshow.
    "photos": Take("Photos", ["sleep1.5", "DPAD_CENTER:3", "DPAD_UP:0.8", "DPAD_RIGHT*3:0.4", "DPAD_CENTER:1",
                              "DPAD_DOWN:0.6", "DPAD_CENTER:2.5", "DPAD_DOWN:1.6", "DPAD_LEFT*6:0.2", "sleep1",
                              "DPAD_RIGHT:1.4", "DPAD_RIGHT:1.4", "DPAD_RIGHT:1.6", "DPAD_CENTER:2.5",
                              "DPAD_RIGHT:2.2", "DPAD_RIGHT:2.2", "DPAD_CENTER:9"],
                   clip=(5.0, 36.5), still=("photos", 22.0)),
    # The Kids profile: limited to FSK 6 on the demo server, so only its titles show.
    "kids": Take("picker", ["sleep1.5", "DPAD_RIGHT:1.4", "DPAD_CENTER:6", "DPAD_DOWN:1.4", "DPAD_RIGHT*3:0.8",
                            "sleep1"]),
}
# The demo server's own watch state for the titles the takes move, put back at the end.
RESTORE = {"Harbor Lights/1/2": (0, True), "Glass Mountain": (4700, False)}
# Clips cut from one or more takes, cross-faded: (take, start, end). One clip per website section,
# and the hero video at the top of the page.
MONTAGES = {
    "family": [("family", 3.0, 26.5)],
    "player": [("skip", 7.5, 15.8), ("trickplay", 19.0, 27.0), ("upnext", 21.0, 30.0)],
    "hero": [("hero", 2.0, 24.0)],
}
FADE = 0.5


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
        self.d.fresh(SNAPSHOTS[self.lang])
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
        # screenrecord writes frames only when the screen changes: hold the last one to the full length.
        scale = (f"fps=30,scale=1280:720:flags=lanczos,setpts=PTS-STARTPTS,"
                 f"tpad=stop_mode=clone:stop_duration={end - start},trim=duration={end - start}")
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
            self.encode(["-ss", at, "-i", raw, "-frames:v", 1, "-vf", "scale=1280:720:flags=lanczos:out_range=full,format=yuvj420p", "-q:v", 3,
                         SHOTS / f"{still}{self.suffix}.jpg"])
        print(f"  {name}{self.suffix}")
        return raw

    def raw(self, raws: dict[str, Path], name: str) -> Path:
        """A take of this run, or one kept by an earlier run with --takes."""
        if name in raws:
            return raws[name]
        kept = self.keep / f"{name}{self.suffix}.mp4" if self.keep else None
        if kept is None or not kept.exists():
            sys.exit(f"No take '{name}' for {self.lang}: record it in this run or keep takes with --takes")
        return kept

    def montage(self, name: str, raws: dict[str, Path]) -> None:
        """Parts of several takes, cross-faded, as MP4 and WebM. The hero video goes next to the
        screenshots as demo.*, with a GIF for the README."""
        parts = MONTAGES[name]
        hero = name == "hero"
        width, height = (1120, 630) if hero else (1280, 720)
        inputs, chain = [], []
        for i, (take, start, end) in enumerate(parts):
            inputs += ["-ss", start, "-to", end, "-i", self.raw(raws, take)]
            # screenrecord writes frames only when the screen changes: hold the last one to the full length.
            chain.append(f"[{i}:v]fps=30,scale={width}:{height}:flags=lanczos,setpts=PTS-STARTPTS,"
                         f"tpad=stop_mode=clone:stop_duration={end - start},trim=duration={end - start}[v{i}]")
        last, length = "v0", parts[0][2] - parts[0][1]
        for i in range(1, len(parts)):
            chain.append(f"[{last}][v{i}]xfade=transition=fade:duration={FADE}:offset={length - FADE:.2f}[x{i}]")
            last, length = f"x{i}", length + parts[i][2] - parts[i][1] - FADE
        out = SHOTS / f"demo{self.suffix}" if hero else CLIPS / f"{name}{self.suffix}"
        base = inputs + ["-filter_complex", ";".join(chain), "-map", f"[{last}]", "-an"]
        self.encode(base + ["-c:v", "libx264", "-preset", "slow", "-crf", 27 if hero else 26, "-pix_fmt", "yuv420p",
                            "-movflags", "+faststart", f"{out}.mp4"])
        self.encode(base + ["-c:v", "libvpx-vp9", "-crf", 40, "-b:v", 0, "-row-mt", 1, f"{out}.webm"])
        if hero and self.lang == "en":
            # The README shows the same animation as a GIF.
            self.encode(["-i", f"{out}.mp4", "-vf",
                         "fps=15,scale=800:450:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];"
                         "[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle", SHOTS / "demo.gif"])
        print(f"  {out.name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", help="adb serial of the emulator")
    parser.add_argument("--lang", default="en,de", help="comma-separated app languages, of " + ",".join(SNAPSHOTS))
    parser.add_argument("--only", help=f"comma-separated subset of {','.join(list(TAKES) + list(MONTAGES))}")
    parser.add_argument("--takes", type=Path, help="keep the full recordings in this folder")
    args = parser.parse_args()
    wanted = args.only.split(",") if args.only else list(TAKES) + list(MONTAGES)
    unknown = set(wanted) - set(TAKES) - set(MONTAGES)
    langs = args.lang.split(",")
    if unknown or set(langs) - set(SNAPSHOTS):
        sys.exit(f"Unknown: {', '.join(sorted(unknown | (set(langs) - set(SNAPSHOTS))))}")

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
            for name in MONTAGES:
                if name in wanted:
                    capture.montage(name, raws)
        for ref, (seconds, played) in RESTORE.items():
            server.resume_at(capture.item(ref), seconds, played)
    device.shell(f"am force-stop {PACKAGE}")


if __name__ == "__main__":
    main()
