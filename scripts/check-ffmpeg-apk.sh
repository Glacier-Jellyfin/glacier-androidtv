#!/usr/bin/env bash
# Fails unless the APK carries the FFmpeg decoder for every ABI, so no build
# from CI ships without it by accident.
# Usage: scripts/check-ffmpeg-apk.sh <apk>
set -euo pipefail
apk="$1"
missing=0
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    if ! unzip -l "$apk" | grep -q "lib/$abi/libffmpegJNI.so"; then
        echo "::error::$apk has no lib/$abi/libffmpegJNI.so"
        missing=1
    fi
done
exit "$missing"
