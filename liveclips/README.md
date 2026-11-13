# Beyond Live Clips

Beyond Live Clips is the companion app to Beyond Camera. It installs as `com.beyond.liveclips` with its own signing identity. Beyond Camera keeps its existing package and release certificate.

## Create a clip

1. Open **Music** and choose **Beyond Beat**, import local audio, or download a track from the catalog while connected. The catalog comes from this repository and has an offline copy. The local starter track is generated on the phone.
2. Open **Create**. Record video or import a file. Choose a duration of at least five seconds and a start point for the video and song. **Full song** uses the selected song from its start offset to the end and requires enough footage.
3. Preview the edited result or export it as a vertical H.264/AAC MP4. Choose **Compact 480p export** for smaller transfers. Export, drafts, downloaded songs and saved clips use local storage and work without internet.
4. Share the export, save it to the gallery on Android 10 or later, or offer it to a nearby device in Bitchat. Keep the track's attribution with shared exports. The app shows the source and license and adds the credit when sharing through its share controls.

The Kotlin editor owns trimming, song timing, local drafts, and export options. CameraX records video and AndroidX Media3 decodes, mixes, and renders it using Android codecs.

## Bitchat

Pair two phones in Android Bluetooth settings and open Bitchat in Beyond Live Clips on both phones. Tap **Listen** on the receiving phone. On the sending phone, tap **Refresh**, then select the paired phone. You can send messages or offer files up to 32 MB. The receiver approves each file and the app verifies its SHA-256 checksum before making it available.

The current transport is authenticated Bluetooth Classic RFCOMM. It uses a Beyond-specific protocol and works between Beyond Live Clips installations. The design is inspired by [Jack Dorsey's bitchat project](https://github.com/permissionlesstech/bitchat), whose Bluetooth LE mesh uses a separate wire protocol. Beyond uses Android's pairing and link encryption. Direct links require the peers to remain nearby.

## Build

```shell
./gradlew :liveclips:testDebugUnitTest :liveclips:lintDebug :liveclips:assembleDebug
```

For a signed release, provide `liveclips-keystore.properties` in the project root or `~/.android/beyond-liveclips-keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Run `./gradlew :liveclips:assembleRelease`. Store the key and properties outside Git so future releases can use the same certificate.

The bundled catalog is `src/main/assets/music-catalog.json`. Each entry needs an HTTPS attribution source, an HTTPS audio download and a CC BY 4.0 or CC0 1.0 license. Verify the license and the download URL before adding an entry.
