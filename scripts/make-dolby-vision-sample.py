#!/usr/bin/env python3
"""
Makes the Dolby Vision samples in core/player/src/androidTest/assets: two
seconds of HDR10 HEVC that declares Dolby Vision profile 7 (dvhe.07.06), as
UHD Blu-ray remuxes do. dv-profile7.mkv is Main 10 like the real thing;
dv-profile7-8bit.mkv is Main, for decoders without Main 10 such as the
emulator's. Neither has an enhancement layer or RPU data, only the
configuration record Media3 and Jellyfin read. Needs ffmpeg with libx265.

    python3 scripts/make-dolby-vision-sample.py
"""
import os
import struct
import subprocess
import tempfile

ASSETS = os.path.join(os.path.dirname(__file__), "..", "core", "player", "src", "androidTest", "assets")
SAMPLES = {"dv-profile7.mkv": "yuv420p10le", "dv-profile7-8bit.mkv": "yuv420p"}
HDR10 = (
    "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited:hdr10=1"
    ":master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=1000,400"
)


def boxes(data, start, end):
    pos = start
    while pos < end:
        size, kind = struct.unpack(">I4s", data[pos:pos + 8])
        yield pos, size, kind.decode("latin-1")
        pos += size


def child(data, parent, kind, skip=8):
    pos, size = parent
    return next((p, s) for p, s, k in boxes(data, pos + skip, pos + size) if k == kind)


def add_profile7(mp4):
    """Appends a dvcC box to the hvc1 sample entry; ffmpeg writes moov last, so no offsets move."""
    data = bytearray(open(mp4, "rb").read())
    top = list(boxes(data, 0, len(data)))
    assert top[-1][2] == "moov", "moov must come last"
    moov = top[-1][:2]
    trak = child(data, moov, "trak")
    mdia = child(data, trak, "mdia")
    minf = child(data, mdia, "minf")
    stbl = child(data, minf, "stbl")
    stsd = child(data, stbl, "stsd")
    hvc1 = child(data, stsd, "hvc1", skip=16)  # stsd: version, flags and entry count first
    # Version 1.0, profile 7, level 6, RPU, enhancement and base layer present, base layer compatibility 6.
    record = bytes([1, 0]) + struct.pack(">H", (7 << 9) | (6 << 3) | 0b111) + bytes([6 << 4]) + bytes(19)
    box = struct.pack(">I4s", 8 + len(record), b"dvcC") + record
    data[sum(hvc1):sum(hvc1)] = box
    for pos, _ in (moov, trak, mdia, minf, stbl, stsd, hvc1):
        data[pos:pos + 4] = struct.pack(">I", struct.unpack(">I", data[pos:pos + 4])[0] + len(box))
    open(mp4, "wb").write(data)


for name, pixels in SAMPLES.items():
    out = os.path.join(ASSETS, name)
    with tempfile.TemporaryDirectory() as tmp:
        mp4 = os.path.join(tmp, "hdr10.mp4")
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"testsrc2=size=640x360:rate=24,format={pixels}", "-t", "2",
            "-c:v", "libx265", "-preset", "ultrafast", "-crf", "32", "-x265-params", HDR10,
            "-tag:v", "hvc1", "-pix_fmt", pixels, mp4,
        ], check=True)
        add_profile7(mp4)
        # ffmpeg carries the record over as the Matroska block addition mapping, as in real remuxes.
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", mp4, "-c", "copy", out], check=True)
    print("Wrote", os.path.normpath(out))
