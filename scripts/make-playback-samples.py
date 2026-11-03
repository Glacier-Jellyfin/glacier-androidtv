#!/usr/bin/env python3
"""
Makes the short samples the playback check plays (core/player/src/main/assets/
playback-check), the Dolby Vision samples of DolbyVisionBaseLayerTest
(core/player/src/androidTest/assets) and the bitstreams of Iec61937PackerTest
(core/player/src/test/resources/iec61937) with the SHA-256 of what ffmpeg's
spdif muxer makes of them. Needs ffmpeg with libx264, libx265, libsvtav1 and
libvpx, and dovi_tool (https://github.com/quietvoid/dovi_tool; DOVI_TOOL or
on the PATH).

The Dolby Vision samples of the check carry real RPUs from dovi_tool's test
assets (core/player/src/test/resources/dolby-vision), profile 7 also an
enhancement layer, single track as on UHD Blu-ray remuxes; the pictures
themselves are test patterns. The samples of DolbyVisionProfile7Test only
declare their profile through the configuration record (dvcC), as Media3 and
Jellyfin read it.

    python3 scripts/make-playback-samples.py
"""
import hashlib
import os
import struct
import subprocess
import tempfile

ROOT = os.path.join(os.path.dirname(__file__), "..", "core", "player", "src")
CHECK = os.path.join(ROOT, "main", "assets", "playback-check")
TEST = os.path.join(ROOT, "androidTest", "assets")
IEC = os.path.join(ROOT, "test", "resources", "iec61937")
DOVI = os.path.join(ROOT, "test", "resources", "dolby-vision")
DOVI_TOOL = os.environ.get("DOVI_TOOL", "dovi_tool")

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


def quiet_noise(layout, seconds):
    """Pink noise at -66 dBFS: a faint hiss at most."""
    return ["-f", "lavfi", "-i", f"anoisesrc=color=pink:amplitude=0.0005:sample_rate=48000,aformat=channel_layouts={layout}",
            "-t", str(seconds)]


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


def dovi_tool(*args):
    subprocess.run([DOVI_TOOL, *args], check=True, stdout=subprocess.DEVNULL)


def dolby_vision_stream(out, profile, rpu, seconds=1):
    """
    Raw HEVC with an RPU (dovi_tool's [rpu] fixture) in every frame; profile 7
    also with an enhancement layer at half the size, interleaved as on discs.
    """
    frames = 24 * seconds
    with tempfile.TemporaryDirectory() as tmp:
        bl = os.path.join(tmp, "bl.hevc")
        ffmpeg(*picture("yuv420p10le", seconds), "-c:v", "libx265", "-preset", "ultrafast", "-crf", "32",
               "-x265-params", HDR10 + ":repeat-headers=1", "-pix_fmt", "yuv420p10le", bl)
        rpus = os.path.join(tmp, "rpu.bin")
        with open(rpus, "wb") as f:
            f.write(open(os.path.join(DOVI, rpu), "rb").read() * frames)
        if profile == 7:
            el = os.path.join(tmp, "el.hevc")
            ffmpeg("-f", "lavfi", "-i", f"testsrc2=size=320x180:rate=24,format=yuv420p10le", "-t", str(seconds),
                   "-c:v", "libx265", "-preset", "ultrafast", "-crf", "40", "-x265-params", "log-level=error:repeat-headers=1",
                   "-pix_fmt", "yuv420p10le", el)
            el_rpu = os.path.join(tmp, "el-rpu.hevc")
            dovi_tool("inject-rpu", "-i", el, "--rpu-in", rpus, "-o", el_rpu)
            dovi_tool("mux", "--bl", bl, "--el", el_rpu, "-o", out)
        else:
            dovi_tool("inject-rpu", "-i", bl, "--rpu-in", rpus, "-o", out)


def dolby_vision(out, profile, compatibility, rpu):
    """A check sample: [dolby_vision_stream] in Matroska, declaring its profile like a remux."""
    with tempfile.TemporaryDirectory() as tmp:
        stream = os.path.join(tmp, "dv.hevc")
        dolby_vision_stream(stream, profile, rpu)
        mp4 = os.path.join(tmp, "dv.mp4")
        ffmpeg("-framerate", "24", "-f", "hevc", "-i", stream, "-c", "copy", "-tag:v", "hvc1", mp4)
        add_dolby_vision(mp4, profile, compatibility, profile == 7)
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
    # Five seconds of barely audible noise rather than silence: lossless formats then reach a real
    # bitrate, and a receiver that refuses a bitstream drops the output well within the time.
    ffmpeg(*quiet_noise(layout, 5), "-c:a", codec, "-b:a", bitrate, *extra, out)


def iec61937(name, codec, bitrate, *spdif):
    """A raw bitstream and the SHA-256 of its IEC 61937 bursts as ffmpeg packs them."""
    out = os.path.join(IEC, name)
    ffmpeg("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000,aformat=channel_layouts=5.1", "-t", "1",
           "-c:a", codec, "-b:a", bitrate, "-strict", "-2", out)
    with tempfile.TemporaryDirectory() as tmp:
        bursts = os.path.join(tmp, "bursts.spdif")
        ffmpeg("-i", out, "-c", "copy", "-f", "spdif", *spdif, bursts)
        return f"{name} {hashlib.sha256(open(bursts, 'rb').read()).hexdigest()}\n"


os.makedirs(CHECK, exist_ok=True)
os.makedirs(TEST, exist_ok=True)
os.makedirs(IEC, exist_ok=True)

# The playback check, in the order it plays them.
ffmpeg(*picture("yuv420p", 1), "-c:v", "libx264", "-preset", "veryfast", "-crf", "32", "-profile:v", "high", os.path.join(CHECK, "h264.mkv"))
hevc(os.path.join(CHECK, "hevc.mkv"), "yuv420p", "log-level=error")
hevc(os.path.join(CHECK, "hevc-hdr10.mkv"), "yuv420p10le", HDR10)
hevc(os.path.join(CHECK, "hevc-hlg.mkv"), "yuv420p10le", HLG)
dolby_vision(os.path.join(CHECK, "dv-p5.mkv"), 5, 0, "profile5.bin")
dolby_vision(os.path.join(CHECK, "dv-p7.mkv"), 7, 6, "fel_orig.bin")
dolby_vision(os.path.join(CHECK, "dv-p81.mkv"), 8, 1, "mel_to_81.bin")
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

# DolbyVisionSampleTest: a profile 7 stream with enhancement layer and what dovi_tool's mode 2 makes of it.
dolby_vision_stream(os.path.join(DOVI, "profile7_fel.hevc"), 7, "fel_orig.bin")
dovi_tool("-m", "2", "convert", "--discard", "-i", os.path.join(DOVI, "profile7_fel.hevc"), "-o", os.path.join(DOVI, "profile7_fel_to_81.hevc"))

# Iec61937PackerTest: TrueHD in MAT frames, DTS as type IV bursts at the HDMI high bitrate rate.
with open(os.path.join(IEC, "expected.txt"), "w", newline="\n") as expected:
    expected.write(iec61937("truehd.thd", "truehd", "0"))
    expected.write(iec61937("dts.dts", "dca", "768k", "-dtshd_rate", "768000"))

for folder in (CHECK, TEST, IEC, DOVI):
    for name in sorted(os.listdir(folder)):
        print(f"{os.path.getsize(os.path.join(folder, name)):>8}  {os.path.normpath(os.path.join(folder, name))}")
