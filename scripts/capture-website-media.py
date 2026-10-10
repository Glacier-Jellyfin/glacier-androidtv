#!/usr/bin/env python3
"""Captures the website's screenshots and demo video from the running app.

Drives a debug build on an Android TV emulator over adb and writes into
website/assets/screenshots/:

  home.jpg, library.jpg, detail.jpg, player-trickplay.jpg, player-upnext.jpg,
  music.jpg,
  demo.mp4 and demo.webm (the hero video)

Everything comes from the public Jellyfin demo server, whose library is public
domain, so the pictures may be published. Never capture another server.

Before running:
  - an emulator with a 1920x1080 TV image is running (adb sees one device, or
    pass --serial)
  - the debug build is installed and signed in to the demo server
    (https://demo.jellyfin.org/stable, user "demo", no password),
    and the demo server is the last one used (the app opens on its
    "Who's watching?" screen)
  - the app's language is English and the accent is the default

Needs Python 3.9+, adb and ffmpeg on PATH (or ADB / FFMPEG set). The script
moves resume points on the demo server so "Continue watching" and the player
shots are reproducible. The demo server resets itself regularly anyway.

Usage: scripts/capture-website-media.py [--serial emulator-5554] [--only home,video,...]
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
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "website" / "assets" / "screenshots"
PACKAGE = "io.github.glacier_jellyfin.androidtv.debug"
SERVER = "https://demo.jellyfin.org/stable"
USER = "demo"
DETAIL_TITLE = "Dracula"
SHOW_TITLE = "Pioneer One"
# The demo server has spelt this artist both ways.
MUSIC_ARTIST = ("Binärpilot", "Binaerpilot")
# Movies placed in "Continue watching" before the run, with the share left to watch.
CONTINUE = {"Night of the Living Dead": 0.3, "King Lear": 0.9, "Jungle Book": 0.6}
NAV = ["Home", "Movie", "Show", "Music"]
SHOTS = ["home", "video", "library", "detail", "trickplay", "upnext", "music"]

# The up-next card appears 30 s before the end (UpNextMode.FALLBACK_MS); the
# episode resumes just before that, so the card is up once the OSD has hidden.
UPNEXT_RESUME_BEFORE_END_S = 32


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

    def run(self, *args: str, binary: bool = False) -> bytes | str:
        out = subprocess.run(self.base + list(args), capture_output=True)
        if out.returncode != 0:
            sys.exit(f"adb {' '.join(args)} failed: {out.stderr.decode('utf-8', 'replace').strip()}")
        return out.stdout if binary else out.stdout.decode("utf-8", "replace")

    def shell(self, command: str) -> str:
        return self.run("shell", command)

    def key(self, *names: str, pause: float = 0.6) -> None:
        for name in names:
            self.shell(f"input keyevent KEYCODE_{name}")
            time.sleep(pause)

    def screencap(self, path: Path) -> None:
        path.write_bytes(self.run("exec-out", "screencap", "-p", binary=True))

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

    def wait_for(self, text: str, timeout: float = 30, exact: bool = False) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if any(t == text if exact else text in t for t in self.texts()):
                return
            time.sleep(1)
        sys.exit(f"Timed out waiting for '{text}' on screen")

    def wait_gone(self, text: str, timeout: float = 30) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if text not in self.texts():
                return
            time.sleep(0.5)
        sys.exit(f"Timed out waiting for '{text}' to disappear")

    def press_until(self, key: str, predicate, tries: int = 12) -> None:
        for _ in range(tries):
            if predicate(self.focused_text()):
                return
            self.key(key, pause=0.4)
        if not predicate(self.focused_text()):
            sys.exit(f"Could not reach the wanted element with {key}; focused: '{self.focused_text()}'")


class Server:
    """Just enough of the Jellyfin API to place resume points."""

    def __init__(self):
        self.token = None
        auth = self.request("/Users/AuthenticateByName", {"Username": USER, "Pw": ""})
        self.token, self.user = auth["AccessToken"], auth["User"]["Id"]

    def request(self, path: str, body: dict | None = None):
        header = 'MediaBrowser Client="Glacier media capture", Device="script", DeviceId="glacier-capture", Version="1"'
        if self.token:
            header += f', Token="{self.token}"'
        req = urllib.request.Request(
            SERVER + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": header, "Content-Type": "application/json"},
            method="POST" if body is not None else "GET",
        )
        with urllib.request.urlopen(req) as response:
            raw = response.read()
        return json.loads(raw) if raw else None

    def find(self, name: str, kind: str) -> dict:
        items = self.request(f"/Items?userId={self.user}&IncludeItemTypes={kind}&Recursive=true&searchTerm={urllib.request.quote(name)}")["Items"]
        match = next((i for i in items if i["Name"] == name), None)
        if match is None:
            sys.exit(f"'{name}' not found on the demo server")
        return match

    def first_episode(self, series: dict) -> dict:
        return self.request(f"/Shows/{series['Id']}/Episodes?userId={self.user}")["Items"][0]

    def fill_continue_watching(self) -> None:
        """The demo server resets itself, so the home row is filled before every run."""
        for name, left in CONTINUE.items():
            movie = self.find(name, "Movie")
            full = self.request(f"/Items/{movie['Id']}?userId={self.user}")
            self.resume_at(movie, full["RunTimeTicks"] / 10_000_000 * left)

    def resume_at(self, item: dict, seconds_before_end: float) -> None:
        full = self.request(f"/Items/{item['Id']}?userId={self.user}")
        ticks = int(full["RunTimeTicks"] - seconds_before_end * 10_000_000)
        self.request(f"/UserItems/{item['Id']}/UserData?userId={self.user}",
                     {"PlaybackPositionTicks": ticks, "Played": False})


class Capture:
    def __init__(self, device: Device, work: Path):
        self.d = device
        self.work = work
        self.ffmpeg = tool("ffmpeg", "FFMPEG")

    # Navigation -----------------------------------------------------------

    def start_app(self) -> None:
        """Fresh start, through "Who's watching?" onto the home screen."""
        self.d.shell(f"am force-stop {PACKAGE}")
        self.d.shell(f"monkey -p {PACKAGE} -c android.intent.category.LEANBACK_LAUNCHER 1")
        self.d.wait_for("watching?")
        # The first profile has focus; the demo account must be that one.
        self.d.wait_for(USER, exact=True)
        self.d.key("DPAD_CENTER")
        self.d.wait_for("Home", timeout=45, exact=True)
        time.sleep(6)  # rows and artwork

    def nav_to(self, label: str) -> None:
        self.d.press_until("DPAD_UP", lambda f: f in NAV, tries=6)
        here = NAV.index(self.d.focused_text())
        step = "DPAD_RIGHT" if NAV.index(label) > here else "DPAD_LEFT"
        self.d.press_until(step, lambda f: f == label, tries=len(NAV))
        self.d.key("DPAD_CENTER")

    def open_in_grid(self, title: str) -> None:
        """Walks the library grid row by row until the card named [title] has focus."""
        # Cards read "Title | Year"; the nav and filter chips above them have no separator.
        self.d.press_until("DPAD_DOWN", lambda f: " | " in f, tries=4)
        direction = "DPAD_RIGHT"
        for _ in range(6):
            last = None
            for _ in range(10):
                focused = self.d.focused_text()
                if title in focused.split(" | "):
                    self.d.key("DPAD_CENTER")
                    return
                if focused == last:
                    break
                last = focused
                self.d.key(direction, pause=0.4)
            self.d.key("DPAD_DOWN")
            direction = "DPAD_LEFT" if direction == "DPAD_RIGHT" else "DPAD_RIGHT"
        sys.exit(f"'{title}' not found in the library grid")

    def wait_for_playback(self) -> None:
        """Until the video plays ("Pause" on the OSD) and the OSD has hidden itself again."""
        self.d.wait_for("Pause", exact=True, timeout=45)
        self.d.wait_gone("Pause")
        time.sleep(1)

    # Output ---------------------------------------------------------------

    def save_jpg(self, name: str) -> None:
        png = self.work / f"{name}.png"
        self.d.screencap(png)
        self.encode(["-i", str(png), "-vf", "scale=1280:720:flags=lanczos", "-q:v", "3", str(OUT / f"{name}.jpg")])
        print(f"  {name}.jpg")

    def save_jpg_from(self, remote: str, name: str) -> None:
        png = self.work / f"{name}.png"
        self.d.run("pull", remote, str(png))
        self.d.shell(f"rm -f {remote}")
        self.encode(["-i", str(png), "-vf", "scale=1280:720:flags=lanczos", "-q:v", "3", str(OUT / f"{name}.jpg")])
        print(f"  {name}.jpg")

    def encode(self, args: list[str]) -> None:
        subprocess.run([self.ffmpeg, "-v", "error", "-y"] + args, check=True)

    # Shots ----------------------------------------------------------------

    def home(self) -> None:
        self.start_app()
        self.save_jpg("home")

    def video(self) -> None:
        """Home, its rows, the movie library and a detail page, like a first look around."""
        self.start_app()
        remote = "/sdcard/glacier-demo.mp4"
        recorder = subprocess.Popen(self.d.base + ["shell", f"screenrecord --bit-rate 12000000 --time-limit 60 {remote}"])
        time.sleep(1.5)
        k = self.d.key
        time.sleep(2.5)
        k("DPAD_DOWN", pause=1.4)
        k("DPAD_RIGHT", pause=0.9)
        k("DPAD_RIGHT", pause=1.4)
        k("DPAD_UP", pause=0.7)
        k("DPAD_UP", pause=0.8)
        k("DPAD_RIGHT", pause=0.6)
        k("DPAD_CENTER", pause=3.6)  # the library opens with the first card focused
        k("DPAD_RIGHT", pause=0.8)
        k("DPAD_RIGHT", pause=1.0)
        k("DPAD_CENTER", pause=3.8)
        k("DPAD_DOWN", pause=1.8)
        self.d.shell("pkill -INT screenrecord")
        recorder.wait(timeout=30)
        time.sleep(1)
        raw = self.work / "demo-raw.mp4"
        self.d.run("pull", remote, str(raw))
        self.d.shell(f"rm -f {remote}")
        scale = "scale=1120:630:flags=lanczos,fps=30"
        # Skip the first second: the recorder starts before anything moves.
        self.encode(["-ss", "1", "-i", str(raw), "-vf", scale, "-an", "-c:v", "libx264", "-preset", "slow",
                     "-crf", "27", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(OUT / "demo.mp4")])
        self.encode(["-ss", "1", "-i", str(raw), "-vf", scale, "-an", "-c:v", "libvpx-vp9", "-crf", "40",
                     "-b:v", "0", "-row-mt", "1", str(OUT / "demo.webm")])
        print("  demo.mp4, demo.webm")

    def library(self) -> None:
        self.start_app()
        self.nav_to("Movie")
        self.d.wait_for("titles")
        time.sleep(3)
        self.save_jpg("library")

    def detail(self) -> None:
        self.start_app()
        self.nav_to("Movie")
        self.d.wait_for("titles")
        self.open_in_grid(DETAIL_TITLE)
        self.d.wait_for("Cast")
        time.sleep(3)
        self.save_jpg("detail")

    def trickplay(self, server: Server) -> None:
        """Scrubbing with the OSD hidden: Left moves only the knob and shows the preview."""
        server.resume_at(server.find(DETAIL_TITLE, "Movie"), 14 * 60)
        self.start_app()
        self.nav_to("Movie")
        self.d.wait_for("titles")
        self.open_in_grid(DETAIL_TITLE)
        self.d.wait_for("Cast")
        self.d.press_until("DPAD_LEFT", lambda f: f.startswith("Resume"), tries=4)
        self.d.key("DPAD_CENTER")
        self.wait_for_playback()
        remote = "/sdcard/glacier-trickplay.png"
        self.d.shell("input keyevent KEYCODE_DPAD_LEFT; sleep 0.4; input keyevent KEYCODE_DPAD_LEFT; sleep 0.4; "
                     "input keyevent KEYCODE_DPAD_LEFT; sleep 0.4; input keyevent KEYCODE_DPAD_LEFT; sleep 0.3; "
                     f"screencap -p {remote}")
        self.save_jpg_from(remote, "player-trickplay")
        self.d.key("BACK")

    def upnext(self, server: Server) -> None:
        series = server.find(SHOW_TITLE, "Series")
        episode = server.first_episode(series)
        server.resume_at(episode, UPNEXT_RESUME_BEFORE_END_S)
        self.start_app()
        self.nav_to("Show")
        self.d.wait_for("title")
        self.open_in_grid(SHOW_TITLE)
        self.d.wait_for("Season 1")
        # The show's own button follows the server's resume list, which the demo
        # server leaves empty, so the episode page resumes instead.
        self.d.press_until("DPAD_DOWN", lambda f: episode["Name"] in f, tries=4)
        self.d.key("DPAD_CENTER")
        self.d.wait_for("Resume", exact=True)
        self.d.press_until("DPAD_LEFT", lambda f: f.startswith("Resume"), tries=4)
        self.d.key("DPAD_CENTER")
        self.wait_for_playback()
        self.save_jpg("player-upnext")
        self.d.key("BACK")

    def music(self) -> None:
        """The music player with an artist's songs in the queue, paused again afterwards."""
        self.start_app()
        self.nav_to("Music")
        self.d.wait_for("albums")
        # The demo albums hold one or two songs each; an artist fills the queue better.
        self.d.press_until("DPAD_UP", lambda f: f == "Albums", tries=3)
        self.d.key("DPAD_RIGHT", "DPAD_CENTER")
        self.d.wait_for("artists")
        self.d.key("DPAD_DOWN")
        self.d.press_until("DPAD_RIGHT", lambda f: any(name in f for name in MUSIC_ARTIST), tries=8)
        self.d.key("DPAD_CENTER")
        self.d.press_until("DPAD_LEFT", lambda f: f.startswith("Play"), tries=4)
        self.d.key("DPAD_CENTER")
        self.d.wait_for("PLAYING FROM", timeout=45)
        time.sleep(2)
        if "NOW PLAYING" not in self.d.texts():
            # Lyrics are a saved choice of the profile; the shot shows the cover.
            self.d.press_until("DPAD_RIGHT", lambda f: f == "Lyrics", tries=6)
            self.d.key("DPAD_CENTER")
            self.d.wait_for("NOW PLAYING", exact=True)
        time.sleep(4)  # artwork in the queue
        self.save_jpg("music")
        self.d.key("MEDIA_PAUSE", "BACK")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", help="adb serial of the emulator")
    parser.add_argument("--only", help=f"comma-separated subset of {','.join(SHOTS)}")
    args = parser.parse_args()
    wanted = args.only.split(",") if args.only else SHOTS
    unknown = set(wanted) - set(SHOTS)
    if unknown:
        sys.exit(f"Unknown: {', '.join(sorted(unknown))}")

    device = Device(args.serial)
    size = device.shell("wm size").strip()
    if "1920x1080" not in size:
        sys.exit(f"Expected a 1920x1080 screen, got '{size}'")
    server = Server()
    server.fill_continue_watching()

    with tempfile.TemporaryDirectory() as work:
        capture = Capture(device, Path(work))
        for shot in SHOTS:
            if shot not in wanted:
                continue
            print(shot)
            step = getattr(capture, shot)
            step(server) if shot in ("trickplay", "upnext") else step()
    device.shell(f"am force-stop {PACKAGE}")


if __name__ == "__main__":
    main()
