# FFmpeg audio decoder

Plays audio formats the device has no decoder for (DTS, Dolby TrueHD, and
E-AC-3 or others on older devices) by decoding them with FFmpeg to PCM.
Without it the server has to transcode such audio tracks.

## Origin

The Java sources, `src/main/jni/ffmpeg_jni.cc` and `src/main/jni/CMakeLists.txt`
are Media3's `decoder_ffmpeg` module, copied unchanged from
[androidx/media](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg)
at tag `1.11.1` (Apache License 2.0), except for the include path in
`CMakeLists.txt`. Media3 does not publish this module as an artifact. Keep it
at the same version as the Media3 dependency in `gradle/libs.versions.toml`;
the package name must stay `androidx.media3.decoder.ffmpeg` so that
`DefaultRenderersFactory` finds `FfmpegAudioRenderer`.

## Building FFmpeg

`scripts/build-ffmpeg.sh` downloads FFmpeg, builds its audio decoders for
`armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, and installs headers and static
libraries into `src/main/jni/ffmpeg/` (not committed). Gradle then builds the
JNI wrapper `libffmpegJNI.so` with the NDK and CMake versions set in
`build.gradle.kts`.

CI runs the script and caches its output. Locally it needs Linux, macOS or
WSL; on Windows without WSL, download the `ffmpeg-android` artifact of a CI run
and unpack it into `src/main/jni/ffmpeg/`. Without that directory the app
builds and runs as before, just without the FFmpeg decoder.

## Testing

`FfmpegDecoderTest` plays one second of DTS, TrueHD, AC-3 and E-AC-3 through
the FFmpeg renderer alone. It needs FFmpeg built into the module (or the
`ffmpeg-android` artifact of a CI run unpacked into `src/main/jni/ffmpeg/`)
and a device or emulator:

```sh
./gradlew :core:ffmpeg:connectedDebugAndroidTest
```

Run it after raising `FFMPEG_VERSION`.

## License

FFmpeg is built with LGPL components only (no `--enable-gpl` or
`--enable-nonfree`) and linked statically into `libffmpegJNI.so`. FFmpeg is
licensed under the LGPL 2.1 or later; see https://ffmpeg.org/legal.html. Its
source for the version used is at https://ffmpeg.org/releases/ (version in
`scripts/build-ffmpeg.sh`).
