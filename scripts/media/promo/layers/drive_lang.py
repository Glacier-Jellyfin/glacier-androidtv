"""Puts the app on the library screen of an emulator and saves what the layer tools need: a screenshot and the uiautomator dump.

What:   Uses the recording helpers of capture-website-media.py (the same demo server, profile and language setup) to bring the
        Android TV emulator to the library screen, waits for it to settle and writes <OUT>/lib.png and <OUT>/lib.xml.
        lib.png is a screenshot for checking; the key frame itself is frame 0 (pts 0) of the language's library take, so that the
        3D plates land exactly on the take: copy it to <OUT>/keyframe.png (ffmpeg -i library.mp4 -frames:v 1 keyframe.png).
How:    python scripts/media/promo/layers/drive_lang.py lib OUT_DIR LANG [--serial emulator-5560]
Needs:  the emulator and the demo server running as for capture-website-media.py, adb.
"""
import argparse
import importlib.util
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location("cwm", ROOT / "scripts" / "media" / "capture-website-media.py")
m = importlib.util.module_from_spec(spec)
sys.modules["cwm"] = m
spec.loader.exec_module(m)

ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
ap.add_argument("what", choices=["lib"])
ap.add_argument("out", type=Path)
ap.add_argument("lang", choices=["en", "de", "es"])
ap.add_argument("--serial", default="emulator-5560")
args = ap.parse_args()
d = m.Device(args.serial)
srv = m.Server()
cap = m.Capture(d, srv, Path("."), args.lang)
args.out.mkdir(exist_ok=True, parents=True)


def shot(name):
    png = d.run("exec-out", "screencap", "-p", binary=True)
    (args.out / f"{name}.png").write_bytes(png)


def dump(name):
    x = d.run("exec-out", "uiautomator", "dump", "/dev/tty")
    x = x[x.find("<?xml"):x.rfind(">") + 1]
    (args.out / f"{name}.xml").write_text(x, encoding="utf-8")


if args.what == "lib":
    cap.prepare(m.TAKES["library"])
    time.sleep(4)
    shot("lib")
    dump("lib")
