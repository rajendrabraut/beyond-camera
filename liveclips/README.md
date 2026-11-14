# Beyond Live Clips

Live Clips and Bitchat open from the Beyond home screen. The `liveclips` module is included in the same APK as Camera.

## Create a clip

1. Open **Music** and choose **Beyond Beat**, import local audio, or download a track from the catalog while connected. The catalog comes from this repository and has an offline copy. The local starter track is generated on the phone.
2. Open **Create**. Record video or import a file. Choose a duration of at least five seconds and a start point for the video and song. **Full song** uses the selected song from its start offset to the end and requires enough footage.
3. Add a text overlay or a black-and-white filter, then preview the edit or export it as a vertical H.264/AAC MP4. Choose **Compact 480p export** for smaller transfers. Export, drafts, downloaded songs and saved clips use local storage and work without internet.
4. Swipe up in **Clips** to play the next video. Likes, comments and saved clips are stored on this device. Share the export, save it to the gallery on Android 10 or later, or offer it to a nearby device in Bitchat. Keep the track's attribution with shared exports. The app shows the source and license and adds the credit when sharing through its share controls.

## Feed source review

- [TikTok-Clone](https://github.com/DaChelimo/TikTok-Clone/tree/a75e23c21e18adbf3307a730a5ebda32ebc338a7) uses MIT licensing and integrates Firebase authentication, databases, analytics and crash reporting plus Cloudflare R2 uploads.
- [TikTokCompose](https://github.com/oguzhaneksi/TikTokCompose/tree/b2a6207085ac0f4f5644e928465d856e6f44e7d5) uses Apache 2.0 licensing. Its sample calls Reddit and retrieves Mixkit media.
- [video-feed-lab](https://github.com/nikkiw/video-feed-lab/tree/db167cedf660e4d7c7537b0919d5f60f06ebb243) uses MIT licensing. Its sample streams from a configurable media server and measures playback quality.

Beyond's feed uses AndroidX Compose and Media3 and opens only app-local video files. Refreshing
the music catalog contacts GitHub. Explicit
downloads contact the HTTPS audio host listed in the catalog. Bitchat sends data to the nearby
device selected by the user. These checks are a source review, without a runtime audit of the clone apps.

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
