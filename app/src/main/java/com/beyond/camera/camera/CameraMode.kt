package com.beyond.camera.camera

import android.hardware.camera2.CameraMetadata
import com.beyond.camera.settings.VideoResolution

enum class CameraMode(val label: String, val isVideo: Boolean) {
    SCAN("Scan", false),
    PRO("Pro", false),
    VIDEO("Video", true),
    PHOTO("Photo", false),
    PORTRAIT("Portrait", false),
    NIGHT("Night", false),
    TIMELAPSE("Time-lapse", true),
    CLIP("Clip", true)
}

data class BindConfig(
    val mode: CameraMode,
    val front: Boolean,
    val wide: Boolean,
    val jpegQuality: Int,
    val hdr: Boolean,
    val videoRes: VideoResolution,
    val scanInPhoto: Boolean
)

/** What the current camera can do in Pro mode. */
class ProCaps(
    val manualSensor: Boolean,
    val isoRange: IntRange?,
    val exposureRangeNs: LongRange?,
    val minFocusDistance: Float,
    val awbModes: List<Int>,
    val evRange: IntRange,
    val evStep: Float
)

/** Manual values chosen in Pro mode. Null means automatic. */
data class ProState(
    val awb: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val focus: Float? = null,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val ev: Int = 0
)

/** One captured JPEG with the rotation needed to show it upright. */
class Frame(val jpeg: ByteArray, val rotation: Int)
