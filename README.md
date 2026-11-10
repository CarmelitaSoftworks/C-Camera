# C Camera

**English** · [Español](README.es.md) · [Português](README.pt.md)

An Android camera app by **Carmelita Softworks** that processes photos the way an iPhone does: it keeps
the frames from **before** you press the shutter (zero shutter lag), **aligns and merges them in RAW**,
recovers highlights with a short frame (**Smart HDR**) and saves in **Ultra HDR**. It has **Photo,
Portrait, Night, Pro, Panorama, 4K Video, Slow motion and Time-lapse** modes, its own stabilization,
**Live Photos**, a **QR reader** and an export designed so **Instagram** shows your photos at their best.

Kotlin · Camera2 · Jetpack Compose · C++ image engine (NDK) · OpenGL ES · ML Kit · minSdk 26

## Modes

| Mode | What it does |
|---|---|
| **Photo** | 4–6 RAW frames from the ZSL buffer merged together; the shutter is released instantly and the merge continues in the background. In low light it switches to **Auto Night** on its own |
| **Portrait** | Aperture-shaped blur (bokeh balls) that grows with distance from the subject; ƒ1.4–ƒ16 aperture and 6 lighting styles (natural, studio, contour, stage, stage mono, high-key mono); uses the telephoto lens if there is one |
| **Night** | 12 long exposures merged, with noise reduction tuned to the remaining noise |
| **Pro** | Manual ISO, shutter speed, white balance and focus |
| **Panorama** | Turn the phone to the right; frames are stitched on the phone itself |
| **Video** | 4K or 1080p, 30/60 fps, HEVC when available, stereo audio |
| **Slow motion** | 120/240 fps using the Camera2 high-speed session |
| **Time-lapse** | 2 frames per second played back at 30 (15× faster) |

## Image processing

| Feature | How |
|---|---|
| **Multi-frame merge** (Deep Fusion / HDR+) | Hierarchical sub-pixel alignment and robust merging based on the sensor noise profile; the sharpest frame is used as reference |
| **Zero shutter lag** | A ring buffer keeps the latest frames: the photo is the moment you press |
| **Smart HDR** | An underexposed frame recovers highlights; local tone mapping (Mertens) with a guided filter and bilateral upsampling to avoid halos |
| **Automatic brightness and color** | Auto brightness after the merge, correction of the tint left by the phone's white balance and a black point that adapts to dark scenes. Calibrated against the stock camera |
| **Ultra HDR** (Android 14+) | Gain map: highlights glow on HDR screens; other screens show the SDR version |
| **Live Photos** | ~1.5 s before and after the shot, saved as a *Motion Photo* |
| **Phone processing** | If the manufacturer offers it (Camera2 Extensions), Photo, Night and Portrait use its own processing; otherwise the C Camera engine |

## Controls and settings

- Lens zoom (0.5× / 1× / 2× / 3× depending on the phone), tap to focus, **long-press to lock AE/AF**
  and swipe to change exposure.
- 3/10 s timer, **burst** by holding the shutter, macro, flash and volume buttons as shutter.
- Grid, level, 4:3 / 16:9 / 1:1 aspect ratio, front camera mirroring and photographic styles.
- **Stabilization**: detects the phone's optical and electronic stabilization; if missing, C Camera
  stabilizes video with the gyroscope (OpenGL) and picks the sharpest frame for photos.
- **Privacy**: location is off by default, and sharing a photo that has it sends a copy without GPS.
- Optionally save the RAW as **DNG**.
- **Battery saving**: after 15 s without touching the screen the camera goes idle.
- The **Camera info** screen shows everything the phone allows (RAW, ZSL, OIS, ISO, slow motion,
  manufacturer processing…).

### Instagram

From the last-photo viewer, **Instagram** prepares a copy and opens the app:

- **Exact size** at 1080 px wide, so Instagram doesn't rescale it: **3:4 (1080×1440)**, the tallest
  feed format and the only one the profile grid doesn't crop, plus 4:5, 1:1 and 9:16 Story (on a
  blurred background).
- **Lanczos downscaling in linear light** + gentle output sharpening that compensates for recompression.
- **Quality-95 JPEG**, **Ultra HDR preserved** and no location.

## Architecture

```
app/src/main/
├── cpp/                      native engine (C++17, multithreaded)
│   ├── core/image.*          planes, filters, Lanczos, guided filter, sRGB
│   ├── core/burst.*          sub-pixel alignment + robust merge (Bayer or YUV)
│   ├── core/finish.*         RAW development, Smart HDR, auto brightness and color, Ultra HDR
│   ├── core/outputs.cpp      portrait, Instagram export, story
│   ├── jni_bridge.cpp
│   └── tests/                host tests (no Android needed)
└── java/com/carmelita/ccamera/
    ├── camera/               Camera2, capabilities, ZSL, stabilization, video, extensions
    ├── processing/           NativeEngine (JNI), PhotoJob, Portrait, Panorama
    ├── storage/              saving, EXIF (with GPS), Motion Photo, DNG
    ├── share/                Instagram export and intent
    ├── qr/                   QR reader and parser
    └── ui/                   Compose: camera, settings, viewer
```

The app's interface is in Spanish.

## Building

The GitHub Actions workflow **does not run automatically**: start it from *Actions → Build → Run
workflow* (or by pushing a `v*` tag, which also publishes the APK in a Release). Pushes and PRs don't
trigger anything.

Locally:

```bash
# Native engine tests (only needs cmake and a C++ compiler)
cmake -S app/src/main/cpp/tests -B build-tests && cmake --build build-tests && ./build-tests/foton_tests

./gradlew testDebugUnitTest   # Kotlin tests (EXIF, Motion Photo, QR, panorama, stabilization)
./gradlew assembleRelease     # APK in app/build/outputs/apk/release/
```

Requires JDK 17 and the Android SDK with the NDK (Android Studio installs them).

> The release APK is signed with the debug key so it can be installed directly. Before publishing it
> to a store, configure your own `signingConfig`.

## Status and limitations

- It builds and the native and Kotlin tests pass. It is being tested on a vivo V30; some modes (slow
  motion, time-lapse, panorama, Pro) have not been tested on a phone yet.
- Every manufacturer exposes different things through Camera2. If a phone doesn't provide RAW, ZSL or
  manufacturer processing, the app automatically falls back to what does work (see *Camera info*).
- Live Photos have no audio.
- Portrait mode separates people, not pets or objects.

## License

GPL-3.0 (see `LICENSE`). Uses Google's ML Kit (free of charge but not free software) for QR and portrait.
