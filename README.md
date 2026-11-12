# Beyond Camera

A simple Android camera app built with Kotlin, Jetpack Compose and CameraX.

## Features

- Photo and video modes
- Front/back camera switch
- Flash off / on / auto for photos, and a torch for video
- Pinch to zoom and tap to focus
- Video recording with sound and an on-screen timer
- Thumbnail of the last capture; tap it to open the file
- Saves to the gallery in `Pictures/BeyondCamera` and `Movies/BeyondCamera`

## Requirements

- Android 8.0 (API 26) or newer
- JDK 17 and the Android SDK (compileSdk 36)

## Build

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug` instead. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

You can also open the folder in Android Studio and press Run.

## Tech stack

| Part | Version |
|---|---|
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.14.3 |
| Kotlin | 2.0.21 |
| CameraX | 1.6.2 |
| Compose BOM | 2024.10.01 |
