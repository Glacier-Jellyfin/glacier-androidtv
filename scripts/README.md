# Scripts

Helper scripts for building, releasing and publishing Glacier. Each script
finds the repository root from its own location, so you can run it from any
working directory.

The release scripts are called by `.github/workflows/release.yml` and
`.github/workflows/stores.yml`. The release workflow calls the stores
workflow, so both run as part of a release.

## ffmpeg

| Script | What it does | Run by |
|---|---|---|
| `build-ffmpeg.sh` | Builds FFmpeg's audio decoders for Android and installs their headers and static libraries where `core/ffmpeg` expects them. | CI and release workflows, through the FFmpeg action (`.github/actions/ffmpeg`); by hand on Linux, macOS or WSL |
| `check-ffmpeg-apk.sh` | Fails unless the APK contains the FFmpeg decoder for every ABI. | `ci.yml` and `release.yml` |

## release

| Script | What it does | Run by |
|---|---|---|
| `release-notes.sh` | Prints the changelog section of one version, with the German and Spanish sections below it, for the in-app update dialog. | `release.yml` |
| `store-notes.sh` | Copies the short store release notes of a stable version to an output folder, and fails if one is missing or longer than 500 characters. | `release.yml` and `stores.yml` |
| `play-listing.sh` | Updates the Google Play store listing of each language from `fastlane/metadata/android`. | `stores.yml` |
| `amazon-submit.sh` | Submits the Amazon APK with its store texts, graphics, screenshots and video through the Amazon App Submission API. | `stores.yml` |

## media

| Script | What it does | Run by |
|---|---|---|
| `capture-website-media.py` | Records the takes for the website and the promo video from the running app on an Android TV emulator. | By hand (needs an emulator and the demo server) |
| `make-promo-video.py` | Writes the website's section pictures (`--only stills`) and the Google Play screenshots (`--only store`) from the recorded takes. Its older film renderer is superseded by `promo/film.py`. | By hand |
| `promo/film.py` | Renders the promo film (about 2 minutes) and the highlight cut (about 52 seconds) in English, German and Spanish from the takes, the 3D plates and the music, and with `--website` writes the website's video media. | By hand (see below) |
| `promo/compositor.py` | The library behind `film.py`: frames, 3D stage, type, sound and mastering. | Imported by `film.py` |
| `promo/fetch_posters.py` | Downloads the posters of the demo server, the artwork of the poster wall. | By hand (needs the demo server) |
| `promo/posterwall.py` | Blender script: the curved wall of posters (Cycles). | By hand, in Blender |
| `promo/assemble.py` | Blender script: layers of a screen fly through 3D space and assemble into the flat screen (moves `WALL2LIB`, `WALL2LIB_LONG`, `LIB2LOGO`). | By hand, in Blender |
| `promo/layers/` | `build_lib.py` cuts the library key frame into layers, `bg.py` is its background model, `drive_lang.py` puts the emulator on that screen and dumps it. | By hand |
| `promo/contact_sheet.py` | Contact sheet of a PNG sequence, and the flat-frame check of a Blender plate. | By hand |
| `make-playback-samples.py` | Makes the sample files for the playback check and for the Dolby Vision and IEC 61937 tests. | By hand (needs ffmpeg and dovi_tool) |
| `make-readme-badges.py` | Writes the README's wordmark and buttons as SVG files in `docs/badges/`, in light and dark. | By hand |
| `make-social-preview.py` | Writes the website's social preview image (`website/assets/social-preview.png`) from the home screen still (`website/assets/screenshots/home.jpg`). | By hand |

## website

| Script | What it does | Run by |
|---|---|---|
| `website-languages.py` | Writes the language menu into every page of the website from one table. With `--check` it only fails if a page is out of date. | By hand |

### The promo film

The film is made in five steps. Everything is written under `build/promo/`, which is git-ignored; only the finished media are
committed.

1. **Record the takes.** `capture-website-media.py --takes build/promo/cache/film-takes` records the app on the emulator against
   the demo server, once per language (`hero`, `family`, `library`, `detail`, `skip`, `upnext`, `trickplay`, `music`, `livetv`,
   `photos`, with `-de` and `-es` for the other languages). The recorder writes a frame only when the screen changes.
2. **Layers.** The 3D opening and ending build the library screen out of its parts. For each language, `promo/layers/drive_lang.py`
   dumps the screen (`lib.xml`) and `build_lib.py` cuts the key frame (frame 0 of that language's library take) into layers in
   `build/promo/layers/LIB[_de|_es]/`.
3. **Posters.** `promo/fetch_posters.py` downloads the demo server's posters into `build/promo/cache/posters`.
4. **Blender plates** (Blender 5.3, Cycles on a GPU; hours per language). `promo/posterwall.py` builds the poster wall,
   `promo/assemble.py --move WALL2LIB_LONG | WALL2LIB | LIB2LOGO --layout LIB[_de|_es] --suffix [_de|_es]` renders the three plates
   into `build/promo/blender/assemble/<move>[_de|_es]/`. The last frame of a plate is pixel-exact the key frame, so the film
   continues in the take without a seam. `promo/contact_sheet.py` checks a plate.
5. **The film.** Copy the music to `build/promo/cache/film-music.flac` (or pass `--music FILE`) and run
   `python scripts/media/promo/film.py --lang en,de,es --cut full,highlight --sheet --website`. It measures the tempo of the music,
   places every scene on its bars, renders the frames in parallel and the sound track, masters it to -14 LUFS with a true peak of at
   most -1 dBTP (measured on the encoded AAC), and writes `build/promo/film/` (1080p masters for YouTube, the silent highlight, the
   README GIF, contact sheets). `--website` replaces `website/assets/promo/glacier-promo[-de|-es].mp4` and `.jpg` (720p, under 16 MB;
   Amazon's `amazon-submit.sh` takes the same file), `website/assets/screenshots/demo[-de|-es].mp4` and `.webm`, `hero[-de|-es].jpg`
   and `demo.gif`. `--preview` renders at half size, `--frames 12.5,30` writes single frames to check.

The section pictures and the store screenshots are separate: `make-promo-video.py --takes DIR --only stills,store`.

Needs: Python 3.9+ with numpy, Pillow, opencv-python-headless, fontTools and brotli; ffmpeg and ffprobe; Blender 5.3 for the plates;
ACE-Step for new music.

Licences of what the film contains: the music was generated with ACE-Step 1.5 (MIT; the generated music may be used
commercially), the track used has the sha256 `a2fa4e197c88b85e30f52c7309ecb7686d1682d812d901a1c6719fd594ef970f`; the posters are
the artwork of the Glacier demo library (glacier-demo, CC0); the font is Bricolage Grotesque (SIL Open Font License,
`website/assets/fonts/`). The 3D scenes are made from our own layers; no third-party models or textures are used.
