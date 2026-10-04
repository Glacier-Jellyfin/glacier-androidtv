#!/usr/bin/env bash
#
# Builds FFmpeg's audio decoders for Android and installs the headers and
# static libraries where core/ffmpeg's CMake build expects them:
#
#   core/ffmpeg/src/main/jni/ffmpeg/include/
#   core/ffmpeg/src/main/jni/ffmpeg/android-libs/<abi>/lib{avcodec,avutil,swresample}.a
#
# Gradle then links them into libffmpegJNI.so. CI runs this (cached); locally
# it needs Linux or macOS (or WSL) with make. Without its output the app still
# builds, just without the FFmpeg audio decoder.
#
# Usage: scripts/build-ffmpeg.sh [path to Android NDK]
#        (defaults to $ANDROID_NDK_HOME, then $ANDROID_NDK_ROOT)
#
# Based on build_ffmpeg.sh from androidx/media (Apache License 2.0),
# Copyright (C) 2019 The Android Open Source Project.
set -euo pipefail

# Media3's decoder module recommends FFmpeg 6.0, but its JNI wrapper only uses
# APIs that 8.1 still has (ch_layout, swr_alloc_set_opts2). The newest stable
# branch receives security fixes the longest.
FFMPEG_VERSION=8.1.3
FFMPEG_SHA256=7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3
# minSdk of the app.
API_LEVEL=28
# FFmpeg decoder names for everything FfmpegLibrary maps that TV platforms
# commonly lack (DTS, TrueHD, older devices without E-AC-3 …). LGPL only:
# no --enable-gpl or --enable-nonfree.
DECODERS=(aac ac3 eac3 truehd dca flac alac vorbis opus mp3 pcm_mulaw pcm_alaw)
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/core/ffmpeg/src/main/jni/ffmpeg"

ndk="${1:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$ndk" || ! -d "$ndk" ]]; then
    echo "Android NDK not found; pass its path or set ANDROID_NDK_HOME" >&2
    exit 1
fi
case "$(uname -s)" in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo "Unsupported host $(uname -s); use Linux, macOS or WSL" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
[[ -d "$toolchain" ]] || { echo "No NDK toolchain at $toolchain" >&2; exit 1; }
jobs="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Downloading FFmpeg $FFMPEG_VERSION"
curl -fsSL -o "$work/ffmpeg.tar.xz" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
if command -v sha256sum >/dev/null; then
    echo "$FFMPEG_SHA256  $work/ffmpeg.tar.xz" | sha256sum -c -
else
    echo "$FFMPEG_SHA256  $work/ffmpeg.tar.xz" | shasum -a 256 -c -
fi
tar -xJf "$work/ffmpeg.tar.xz" -C "$work"
cd "$work/ffmpeg-$FFMPEG_VERSION"

options=(
    --target-os=android
    --enable-static
    --disable-shared
    --enable-pic
    --disable-doc
    --disable-programs
    --disable-everything
    --disable-avdevice
    --disable-avformat
    --disable-swscale
    --disable-avfilter
    --disable-symver
    --enable-swresample
    --extra-ldexeflags=-pie
    --disable-v4l2-m2m
    --disable-vulkan
    --nm="$toolchain/llvm-nm"
    --ar="$toolchain/llvm-ar"
    --ranlib="$toolchain/llvm-ranlib"
    --strip="$toolchain/llvm-strip"
    --prefix="$out"
)
for decoder in "${DECODERS[@]}"; do
    options+=("--enable-decoder=$decoder")
done

rm -rf "$out"
for abi in "${ABIS[@]}"; do
    echo "Building FFmpeg for $abi"
    case "$abi" in
        armeabi-v7a) abi_options=(--arch=arm --cpu=armv7-a
            --cross-prefix="$toolchain/armv7a-linux-androideabi$API_LEVEL-"
            --extra-cflags="-march=armv7-a -mfloat-abi=softfp" --extra-ldflags="-Wl,--fix-cortex-a8") ;;
        arm64-v8a) abi_options=(--arch=aarch64 --cpu=armv8-a
            --cross-prefix="$toolchain/aarch64-linux-android$API_LEVEL-") ;;
        # x86 assembly needs nasm and brings text relocations; C is plenty for audio.
        x86) abi_options=(--arch=x86 --cpu=i686 --disable-asm
            --cross-prefix="$toolchain/i686-linux-android$API_LEVEL-") ;;
        x86_64) abi_options=(--arch=x86_64 --cpu=x86-64 --disable-asm
            --cross-prefix="$toolchain/x86_64-linux-android$API_LEVEL-") ;;
    esac
    ./configure "${options[@]}" "${abi_options[@]}" --libdir="$out/android-libs/$abi"
    make -j"$jobs"
    make install-libs
    # The public headers are the same for every ABI.
    [[ -d "$out/include" ]] || make install-headers
    make distclean
done

# What was built, for CI's cache key and for reference.
printf 'ffmpeg %s\ndecoders %s\nabis %s\napi %s\n' \
    "$FFMPEG_VERSION" "${DECODERS[*]}" "${ABIS[*]}" "$API_LEVEL" > "$out/BUILD_INFO"
echo "FFmpeg installed in $out"
