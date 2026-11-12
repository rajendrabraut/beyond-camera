# Beyond Camera

An offline Android camera app built with Kotlin, Jetpack Compose and CameraX.

## Modes

| Mode | What it does |
|---|---|
| Photo | Full-resolution photos, optional HDR, optional live QR / barcode hint |
| Portrait | Keeps the person sharp and blurs the background (on-device segmentation) |
| Night | Merges 4 shots to cut noise and brighten dark scenes |
| Pro | White balance, focus, shutter speed, ISO and exposure, where the phone's camera supports them |
| Video | 720p / 1080p with stabilisation when the phone supports it |
| Clip | Short video that stops by itself after 10 / 15 / 30 / 60 seconds |
| Time-lapse | One frame every 0.5 – 5 seconds, encoded as a 30 fps video |
| Scan | QR codes and barcodes, live or from a picture already on the phone |

## Image engine

Photos are captured at the sensor's highest resolution in maximum-quality mode. On top of that the
app has its own multi-frame engine (`camera/Fusion.kt`), based on the same idea modern phone cameras
use: take several frames and combine them.

- **Alignment**: frames are lined up with a median-threshold-bitmap search, first on small copies, then
  to the pixel on a full-resolution crop. This also works between different exposures.
- **Night and Enhanced zoom**: same-exposure frames are averaged, leaving out pixels that moved, then
  sharpened. Averaging 3–4 frames roughly halves visible noise.
- **HDR**: a normal, a darker and a brighter frame are blended with smooth per-area weights, so
  highlights come from the dark frame and shadows from the bright one.
- **Phone maker's processing first**: if the phone exposes its own HDR / Night / Bokeh processing to
  apps (CameraX Extensions), that is used instead.

It runs in strips, so memory stays low at full resolution. It cannot replace a phone's built-in
image signal processor, which works on raw sensor data.

## Other features

- Pinch, quick stops (1x / 2x / 4x …) and a fine slider for zoom; tap to focus
- In-app viewer: swipe left for older captures, swipe right from the newest to return to the camera,
  pinch or double-tap to zoom at full resolution, share, delete, details
- Aspect ratios 1:1, 3:4, 9:16, full screen; timer; grid lines; time stamp; flash and video light
- Volume buttons as shutter, countdown or zoom; hold the shutter for video or burst
- Scan history, QR code creation, scan from a gallery picture
- Saves to `Pictures/BeyondCamera` and `Movies/BeyondCamera`

## Privacy and security

- **No internet permission.** The manifest strips `INTERNET`, so nothing can leave the phone unless
  you share it yourself. The analytics uploader bundled with the segmentation library is removed.
- Scanned links are never opened automatically. Only `http` / `https` links can be opened, and
  suspicious ones (no encryption, raw number address, look-alike characters, hidden `@` address,
  unusual port) show a warning first. Scanned text is cleaned of control characters and length-capped.
- Location tagging is off by default and asks for permission only when you turn it on.
- App data is excluded from cloud backup and device transfer.
- QR and barcode reading uses ZXing, fully on-device.
- Release builds are minified with R8 and signed.

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17 and the Android SDK (compileSdk 36)

## Build

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug` instead. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Release build

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

Signing reads the first of these that exists (neither is committed):

1. `keystore.properties` in the project root
2. `~/.android/beyond-camera-keystore.properties`

The file needs these keys:

```properties
storeFile=C:/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

If neither file exists, the release build is signed with the debug key, so it still installs but can't be published.

## Versioning

Four-part versions: the last part goes up for small updates (`1.1.0.1`, `1.1.0.2`), the second part
for big ones (`1.1.0.0`, `1.2.0.0`).

## Tech stack

| Part | Version |
|---|---|
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.14.3 |
| Kotlin | 2.0.21 |
| CameraX (core, video, view, extensions) | 1.6.2 |
| Compose BOM | 2024.10.01 |
| ZXing core | 3.5.3 |
| ML Kit selfie segmentation | 16.0.0-beta6 |

## Credits

The interface, icons and layout are original to this project. It is not affiliated with any phone maker.
