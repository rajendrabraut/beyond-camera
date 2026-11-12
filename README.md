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

Photos are captured at the sensor's highest resolution. Plain Photo mode uses zero shutter lag when
the camera supports it; every other mode, and phones without it, use maximum-quality capture.

The app's own engine (`engine/BurstEngine.kt`) was written from scratch, following openly published
methods (no code copied):

- **Burst alignment** after Google's HDR+ paper and its open analysis by Monod, Delon and Veit
  (IPOL 2021): coarse-to-fine tile matching on an image pyramid (each tile tries its parent's motion
  and two neighbours'), then a pixel-accurate refinement at full resolution. Each tile can move on its
  own, so hand shake and small subject movement are handled.
- **Robust merge**: tiles that don't match the reference, and single pixels that changed (people,
  leaves), are left out, with thresholds set from the noise the engine measures in each photo. The
  sharpest frame is used as the reference.
- **HDR** after Mertens et al., "Exposure Fusion": a normal, a darker and a brighter frame are merged
  in linear light with well-exposedness weights, then compressed so recovered highlights fit.
- **Finishing**: local tone mapping on a smooth base layer (shadows lifted, highlights held, fine
  detail kept), edge-aware colour-noise reduction, and sharpening that skips noise.

| Mode | What the engine does |
|---|---|
| Photo (Smart processing on) | Finishing on every shot. In dim light (ISO 800+) it adds 2 frames and merges them |
| Night | 5 brightened frames merged, strongest cleanup |
| HDR | 3 exposures merged |
| Zoom 2x and above | 4 frames merged, stronger noise-aware sharpening |
| Portrait (Smart processing on) | Finishing first, then the background blur |
| Pro, or the phone maker's own HDR / Night / Bokeh | Untouched camera output |

Finishing runs in the background, so you can keep shooting. Everything works in strips, so memory
stays low at full resolution. It works on the JPEGs the phone gives apps, not raw sensor data, so it
complements the phone's built-in processing rather than replacing it.

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

## Camera updates

Regular Photo mode uses CameraX zero-shutter-lag capture when the device supports it and falls back
to low-latency capture otherwise. Night, HDR, and Portrait remain separate multi-frame or image
processing modes, and can take longer than a regular photo.

## Versioning

Four-part versions `a.b.c.d` (current: `1.0.0.3`). Each update raises the last part (`1.0.0.4`, …).
The Android version code is `a*10000000 + b*100000 + c*1000 + d` (`1.0.0.3` = `10000003`), which is
always higher than earlier builds, so updates install over the previous version.

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
