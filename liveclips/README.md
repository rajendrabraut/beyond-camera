# Beyond Live Clips

Live Clips and Bitchat open from the Beyond home screen. The `liveclips` module is included in the same APK as Camera.

## Create a clip

1. Open **Music** and choose **Beyond Beat**, import local audio, or download a track from the catalog while connected. The catalog comes from this repository and has an offline copy. The local starter track is generated on the phone.
2. Open **Create**. Record video or import a file. Choose a duration of at least five seconds and a start point for the video and song. **Full song** uses the selected song from its start offset to the end and requires enough footage.
3. Preview the edited result or export it as a vertical H.264/AAC MP4. Choose **Compact 480p export** for smaller transfers. Export, drafts, downloaded songs and saved clips use local storage and work without internet.
4. Share the export, save it to the gallery on Android 10 or later, or offer it to a nearby device in Bitchat. Keep the track's attribution with shared exports. The app shows the source and license and adds the credit when sharing through its share controls.

The Kotlin editor owns trimming, song timing, local drafts, and export options. CameraX records video and AndroidX Media3 decodes, mixes, and renders it using Android codecs.

## Bitchat

For a file under 32 MB, pair two phones in Android Bluetooth settings and open Bitchat on both phones. Tap **Listen** on the receiving phone. On the sending phone, tap **Refresh**, then select the paired phone.

For files of 32 MB or more, or when Bluetooth is unavailable, enable Wi-Fi and tap **Find Wi-Fi Direct devices** on both phones. Choose the recipient and accept Android's connection prompt. Wi-Fi Direct works without internet and supports files up to the available storage on the devices. The recipient approves each file. Beyond compresses blocks with fast lossless DEFLATE when they shrink and verifies the restored file with SHA-256.

The feature is inspired by [Jack Dorsey's bitchat project](https://github.com/permissionlesstech/bitchat). Beyond uses its own direct-transfer protocol on Bluetooth Classic or Wi-Fi Direct. Both devices need the same Beyond version.

## Build

```shell
./gradlew :liveclips:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease
```

The root README describes APK locations and release signing.

The bundled catalog is `src/main/assets/music-catalog.json`. Each entry needs an HTTPS attribution source, an HTTPS audio download and a CC BY 4.0 or CC0 1.0 license. Verify the license and the download URL before adding an entry.
