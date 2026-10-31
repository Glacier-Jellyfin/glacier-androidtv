#!/usr/bin/env python3
"""
Makes the short samples the playback check plays (core/player/src/main/assets/
playback-check) and the Dolby Vision samples of DolbyVisionBaseLayerTest
(core/player/src/androidTest/assets). Needs ffmpeg with libx264, libx265,
libsvtav1 and libvpx.

The Dolby Vision samples are HEVC that declares a profile through its
configuration record (dvcC), as Media3 and Jellyfin read it. They carry no
RPU data or enhancement layer, so they show which decoder takes a profile
and whether it plays, not the Dolby Vision picture itself.

    python3 scripts/make-playback-samples.py
"""
import os
import struct
import subprocess
import tempfile

ROOT = os.path.join(os.path.dirname(__file__), "..", "core", "player", "src")
CHECK = os.path.join(ROOT, "main", "assets", "playback-check")
TEST = os.path.join(ROOT, "androidTest", "assets")

SIZE = "640x360"
HDR10 = (
    "log-level=error:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:range=limited:hdr10=1"
    ":master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=1000,400"
)
HLG = "log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:range=limited"


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def picture(pixels, seconds):
    return ["-f", "lavfi", "-i", f"testsrc2=size={SIZE}:rate=24,format={pixels}", "-t", str(seconds)]


def silence(layout, seconds):
    return ["-f", "lavfi", "-i", f"anullsrc=channel_layout={layout}:sample_rate=48000", "-t", str(seconds)]


def hevc(out, pixels, params, seconds=1, dolby_vision=None):
    """HEVC in Matroska; with dolby_vision = (profile, compatibility, enhancement layer) it declares that profile."""
    with tempfile.TemporaryDirectory() as tmp:
        mp4 = os.path.join(tmp, "hevc.mp4")
        ffmpeg(*picture(pixels, seconds), "-c:v", "libx265", "-preset", "ultrafast", "-crf", "32",
               "-x265-params", params, "-tag:v", "hvc1", "-pix_fmt", pixels, mp4)
        if dolby_vision:
            add_dolby_vision(mp4, *dolby_vision)
        # ffmpeg carries the record over as the Matroska block addition mapping, as in real remuxes.
        ffmpeg("-i", mp4, "-c", "copy", out)


def boxes(data, start, end):
    pos = start
    while pos < end:
        size, kind = struct.unpack(">I4s", data[pos:pos + 8])
        yield pos, size, kind.decode("latin-1")
        pos += size


def child(data, parent, kind, skip=8):
    pos, size = parent
    return next((p, s) for p, s, k in boxes(data, pos + skip, pos + size) if k == kind)


def add_dolby_vision(mp4, profile, compatibility, enhancement_layer):
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
    # Version 1.0, level 6, RPU and base layer present.
    flags = (profile << 9) | (6 << 3) | 0b100 | (0b010 if enhancement_layer else 0) | 0b001
    record = bytes([1, 0]) + struct.pack(">H", flags) + bytes([compatibility << 4]) + bytes(19)
    box = struct.pack(">I4s", 8 + len(record), b"dvcC") + record
    data[sum(hvc1):sum(hvc1)] = box
    for pos, _ in (moov, trak, mdia, minf, stbl, stsd, hvc1):
        data[pos:pos + 4] = struct.pack(">I", struct.unpack(">I", data[pos:pos + 4])[0] + len(box))
    open(mp4, "wb").write(data)


def audio(out, codec, layout, bitrate, *extra):
    # Three seconds: a receiver that refuses a bitstream usually drops the output after a second or two.
    ffmpeg(*silence(layout, 3), "-c:a", codec, "-b:a", bitrate, *extra, out)


os.makedirs(CHECK, exist_ok=True)
os.makedirs(TEST, exist_ok=True)

# The playback check, in the order it plays them.
ffmpeg(*picture("yuv420p", 1), "-c:v", "libx264", "-preset", "veryfast", "-crf", "32", "-profile:v", "high", os.path.join(CHECK, "h264.mkv"))
hevc(os.path.join(CHECK, "hevc.mkv"), "yuv420p", "log-level=error")
hevc(os.path.join(CHECK, "hevc-hdr10.mkv"), "yuv420p10le", HDR10)
hevc(os.path.join(CHECK, "hevc-hlg.mkv"), "yuv420p10le", HLG)
hevc(os.path.join(CHECK, "dv-p5.mkv"), "yuv420p10le", HDR10, dolby_vision=(5, 0, False))
hevc(os.path.join(CHECK, "dv-p7.mkv"), "yuv420p10le", HDR10, dolby_vision=(7, 6, True))
hevc(os.path.join(CHECK, "dv-p81.mkv"), "yuv420p10le", HDR10, dolby_vision=(8, 1, False))
# SVT-AV1 takes the colours from the frames, not from -color_trc.
ffmpeg(*picture("yuv420p10le,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv", 1),
       "-c:v", "libsvtav1", "-preset", "12", "-crf", "50", os.path.join(CHECK, "av1-hdr10.mkv"))
ffmpeg(*picture("yuv420p10le", 1), "-c:v", "libvpx-vp9", "-profile:v", "2", "-deadline", "realtime", "-crf", "50", "-b:v", "0",
       os.path.join(CHECK, "vp9-profile2.webm"))
audio(os.path.join(CHECK, "aac.mka"), "aac", "stereo", "64k")
audio(os.path.join(CHECK, "ac3.mka"), "ac3", "5.1", "192k")
audio(os.path.join(CHECK, "eac3.mka"), "eac3", "5.1", "96k")
audio(os.path.join(CHECK, "truehd.mka"), "truehd", "5.1", "0", "-strict", "-2")
audio(os.path.join(CHECK, "dts.mka"), "dca", "5.1", "768k", "-strict", "-2")

# DolbyVisionBaseLayerTest: Main 10 like the real thing, and Main for decoders without Main 10 such as the emulator's.
hevc(os.path.join(TEST, "dv-profile7.mkv"), "yuv420p10le", HDR10, seconds=2, dolby_vision=(7, 6, True))
hevc(os.path.join(TEST, "dv-profile7-8bit.mkv"), "yuv420p", HDR10, seconds=2, dolby_vision=(7, 6, True))

for folder in (CHECK, TEST):
    for name in sorted(os.listdir(folder)):
        print(f"{os.path.getsize(os.path.join(folder, name)):>8}  {os.path.normpath(os.path.join(folder, name))}")
