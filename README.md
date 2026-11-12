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

### Release build

```bash
./gradlew assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. Release builds are minified and resource-shrunk with R8.

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

## Tech stack

| Part | Version |
|---|---|
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.14.3 |
| Kotlin | 2.0.21 |
| CameraX | 1.6.2 |
| Compose BOM | 2024.10.01 |
