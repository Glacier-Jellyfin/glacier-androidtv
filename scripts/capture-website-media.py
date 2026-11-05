#!/usr/bin/env python3
"""Records the takes for the website and the promo video from the running app.

Drives a debug build on an Android TV emulator over adb, against the Glacier demo
server (github.com/Glacier-Jellyfin/glacier-demo), and keeps each take as a
1080p recording in the --takes folder, <take>[-de|-es].mp4 per language.
scripts/make-promo-video.py turns them into the promo film and the website's
media.

The demo library is made up and licensed for advertising (see the demo repo's
CREDITS.md), so everything recorded here may be published. Never capture another
server.

Before running:
  - the demo server runs on this machine (glacier-demo: demo.py server start)
  - an emulator with a 1920x1080 Google TV image runs with one snapshot per
    language, "signed-in-family", "signed-in-family-de" and "signed-in-family-es":
    the debug build installed, the profiles Nora, Alex and Kids signed in to the
    demo server (http://10.0.2.2:8098), in the other languages with Settings ›
    Account › Interface language set to Deutsch or Español for each, the app
    stopped. Every take starts from a snapshot, so remembered
    tracks and the like from an earlier take never show up in the next.

Needs Python 3.9+, adb and ffmpeg on PATH (or ADB / FFMPEG set). The script sets
resume points of the demo profile Nora before each take.

Usage: scripts/capture-website-media.py --takes DIR [--serial emulator-5560] [--lang en,de]
                                        [--only library,detail,...]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.glacier_jellyfin.androidtv.debug"
ACTIVITY = "io.github.glacier_jellyfin.androidtv.MainActivity"
# The server as this machine sees it; the emulator reaches it on 10.0.2.2.
SERVER = "http://localhost:8098"
ADMIN = ("Admin", "glacier-demo")
USER = "Nora"
PROFILES = ("Alex", "Kids", "Nora")
# One emulator snapshot per language: the profiles signed in, their interface language set in
# Settings › Account. Android's per-app language confuses the app's own one (keys stop moving).
SNAPSHOTS = {"en": "signed-in-family", "de": "signed-in-family-de", "es": "signed-in-family-es"}
TIMEZONE = "Europe/Berlin"
# Positions in the navigation bar, counted from Home.
NAV = {"Movie": 1, "Show": 2, "Music": 3, "Photos": 4, "Live TV": 5}


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
    """One recording: where it starts and the keys pressed while recording."""
    start: str  # "picker", "home", a navigation entry from NAV, or "open:Title" / "open:Show/S/E"
    steps: list[str]
    resume: dict[str, float] = field(default_factory=dict)  # title or Show/S/E -> seconds
    played: tuple[str, ...] = ()
    # Other steps for a language, where its playback starts much later.
    steps_by_lang: dict[str, list[str]] = field(default_factory=dict)


TAKES: dict[str, Take] = {
    "library": Take("Movie", ["sleep1.5", "DPAD_RIGHT:0.6", "DPAD_RIGHT:0.6", "DPAD_RIGHT:0.9", "DPAD_RIGHT*4:0.15",
                              "sleep0.5", "DPAD_RIGHT:0.8", "DPAD_DOWN*12:0.05", "sleep0.5", "DPAD_CENTER:1.6",
                              "DPAD_LEFT:1.4", "DPAD_DOWN:1.2", "DPAD_RIGHT:1.5"]),
    "detail": Take("open:Glass Mountain", ["sleep1.5", "DPAD_RIGHT*4:0.3", "sleep0.3", "DPAD_CENTER:1.4", "DPAD_UP:1.2",
                                           "BACK:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:1.0", "DPAD_DOWN*3:0.6", "sleep0.6",
                                           "BACK:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:1.0", "DPAD_DOWN*4:0.45", "sleep0.8",
                                           "BACK:0.7", "DPAD_DOWN:1.4", "DPAD_RIGHT*3:0.5", "sleep0.4", "DPAD_DOWN:1.8"],
                   resume={"Glass Mountain": 4700}),
    # Play, then OK on "Skip intro" while the controls still show, so the timeline jumps visibly.
    # In German the server converts the E-AC-3 track first, and playback starts 9 to 15 s after OK.
    "skip": Take("open:Harbor Lights/1/2", ["sleep1.2", "DPAD_CENTER:8.6", "DPAD_CENTER:6"],
                 resume={"Harbor Lights/1/2": 0}, played=("Harbor Lights/1/2",),
                 steps_by_lang={"de": ["sleep1.2", "DPAD_CENTER:19", "DPAD_CENTER:6"]}),
    "upnext": Take("open:Harbor Lights/1/2", ["sleep1.2", "DPAD_CENTER:28"],
                   resume={"Harbor Lights/1/2": 2566}),
    # The emulator cannot decode the German E-AC-3 track, so the server converts it and playback takes a while.
    "trickplay": Take("open:Glass Mountain", ["sleep1", "DPAD_CENTER:18", "DPAD_RIGHT*7:0.5", "sleep3"],
                      resume={"Glass Mountain": 4700}),
    # An album with the lyrics on, then the full screen: the first lyric line comes 12 s into the song.
    # The player takes up to 5 s to load; a key before that is lost and shifts every later one.
    # Back in the library, a music video plays in the music player and stays in the mini player.
    "music": Take("Music", ["sleep1", "DPAD_DOWN:0.7", "DPAD_RIGHT:0.9", "DPAD_CENTER:2.2", "DPAD_CENTER:6",
                            "DPAD_RIGHT*3:0.35", "sleep0.4", "DPAD_CENTER:5", "DPAD_RIGHT:0.6", "DPAD_CENTER:14",
                            "BACK:1.5", "BACK:2.5", "BACK:2", "DPAD_UP:0.6", "DPAD_UP:0.8", "DPAD_RIGHT*3:0.4",
                            "DPAD_CENTER:2.5", "DPAD_DOWN:1.2", "DPAD_RIGHT:0.8", "DPAD_RIGHT:1", "DPAD_CENTER:10",
                            "BACK:2", "DPAD_LEFT:1", "DPAD_LEFT:1.5"]),
    "livetv": Take("Live TV", ["sleep2.5", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4", "DPAD_DOWN:2.4",
                               "DPAD_UP*4:0.15", "sleep0.5", "DPAD_UP:0.6", "DPAD_RIGHT:0.5", "DPAD_CENTER:2.5",
                               "DPAD_DOWN:1.2", "DPAD_RIGHT*3:0.9", "DPAD_DOWN*2:0.9", "sleep1.5"]),
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
    "photos": Take("Photos", ["sleep1.5", "DPAD_CENTER:3", "DPAD_RIGHT*3:0.4", "DPAD_CENTER:1",
                              "DPAD_DOWN:0.6", "DPAD_CENTER:2.5", "DPAD_DOWN:1.6", "DPAD_LEFT*6:0.2", "sleep1",
                              "DPAD_RIGHT:1.4", "DPAD_RIGHT:1.4", "DPAD_RIGHT:1.6", "DPAD_CENTER:2.5",
                              "DPAD_RIGHT:2.2", "DPAD_RIGHT:2.2", "DPAD_CENTER:9"]),
}
# The demo server's own watch state for the titles the takes move, put back at the end.
RESTORE = {"Harbor Lights/1/2": (0, True), "Glass Mountain": (4700, False)}


class Capture:
    def __init__(self, device: Device, server: Server, takes: Path, lang: str):
        self.d = device
        self.server = server
        self.takes = takes
        self.lang = lang
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

    def capture(self, name: str, take: Take) -> Path:
        self.prepare(take)
        remote = f"/sdcard/glacier-{name}.mp4"
        self.d.record(remote, take.steps_by_lang.get(self.lang, take.steps))
        out = self.takes / f"{name}{self.suffix}.mp4"
        self.d.run("pull", remote, str(out))
        self.d.shell(f"rm -f {remote}")
        print(f"  {name}{self.suffix}")
        return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", help="adb serial of the emulator")
    parser.add_argument("--lang", default="en,de", help="comma-separated app languages, of " + ",".join(SNAPSHOTS))
    parser.add_argument("--only", help=f"comma-separated subset of {','.join(TAKES)}")
    parser.add_argument("--takes", type=Path, required=True, help="folder for the recordings")
    args = parser.parse_args()
    wanted = args.only.split(",") if args.only else list(TAKES)
    unknown = set(wanted) - set(TAKES)
    langs = args.lang.split(",")
    if unknown or set(langs) - set(SNAPSHOTS):
        sys.exit(f"Unknown: {', '.join(sorted(unknown | (set(langs) - set(SNAPSHOTS))))}")

    device = Device(args.serial)
    size = device.shell("wm size").strip()
    if "1920x1080" not in size:
        sys.exit(f"Expected a 1920x1080 screen, got '{size}'")
    server = Server()
    args.takes.mkdir(parents=True, exist_ok=True)
    for lang in langs:
        print(lang)
        capture = Capture(device, server, args.takes, lang)
        for name, take in TAKES.items():
            if name in wanted:
                capture.capture(name, take)
    for ref, (seconds, played) in RESTORE.items():
        server.resume_at(capture.item(ref), seconds, played)
    device.shell(f"am force-stop {PACKAGE}")


if __name__ == "__main__":
    main()
