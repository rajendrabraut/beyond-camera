package com.beyond.camera.settings

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class PictureQuality(val label: String, val jpeg: Int) {
    HIGH("High", 97), STANDARD("Standard", 90), LOW("Low", 80)
}

enum class VolumeKeyAction(val label: String) {
    SHUTTER("Shutter"), COUNTDOWN("Shutter countdown"), ZOOM("Zoom"), VOLUME("Volume")
}

enum class HoldShutterAction(val label: String) {
    RECORD_VIDEO("Record video"), BURST("Burst shots"), OFF("Off")
}

enum class AntiBanding(val label: String) {
    AUTO("Auto"), HZ50("50 Hz"), HZ60("60 Hz"), OFF("Off")
}

enum class AspectRatioOption(val label: String) {
    R1_1("1:1"), R3_4("3:4"), R9_16("9:16"), FULL("Full")
}

enum class VideoResolution(val label: String, val sub: String) {
    HD("720P", "30FPS"), FHD("1080P", "30FPS")
}

/** Persistent camera settings, exposed as Compose state so the UI updates when they change. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
    private val resetters = mutableListOf<() -> Unit>()

    // Modes
    var timestamp by bool("timestamp", false)
    var scanQrInPhoto by bool("scan_qr", true)
    var pictureQuality by enumPref("picture_quality", PictureQuality.HIGH, PictureQuality.entries)
    var volumeKeys by enumPref("volume_keys", VolumeKeyAction.SHUTTER, VolumeKeyAction.entries)
    var focusAndShoot by bool("focus_shoot", false)
    var holdShutter by enumPref("hold_shutter", HoldShutterAction.RECORD_VIDEO, HoldShutterAction.entries)

    // General
    var saveLocation by bool("save_location", false)
    var shutterSound by bool("shutter_sound", true)
    var savePreviousMode by bool("save_previous_mode", false)
    var antiBanding by enumPref("anti_banding", AntiBanding.AUTO, AntiBanding.entries)

    // Quick panel
    var aspectRatio by enumPref("aspect_ratio", AspectRatioOption.R3_4, AspectRatioOption.entries)
    var timerSeconds by int("timer", 0)
    var gridlines by bool("gridlines", false)
    var videoResolution by enumPref("video_resolution", VideoResolution.FHD, VideoResolution.entries)
    var hdr by bool("hdr", false)
    var enhancedZoom by bool("enhanced_zoom", true)
    var timelapseIntervalMs by int("timelapse_interval", 1000)
    var shortVideoSeconds by int("short_video_seconds", 15)

    var lastMode by string("last_mode", "PHOTO")

    fun restoreDefaults() {
        prefs.edit().clear().apply()
        resetters.forEach { it() }
    }

    private fun <T> pref(
        def: T,
        read: () -> T,
        write: (T) -> Unit
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> = mutableStateOf(read())

        init {
            resetters += { state.value = def }
        }

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            write(value)
        }
    }

    private fun bool(key: String, def: Boolean) = pref(
        def,
        read = { prefs.getBoolean(key, def) },
        write = { prefs.edit().putBoolean(key, it).apply() }
    )

    private fun int(key: String, def: Int) = pref(
        def,
        read = { prefs.getInt(key, def) },
        write = { prefs.edit().putInt(key, it).apply() }
    )

    private fun string(key: String, def: String) = pref(
        def,
        read = { prefs.getString(key, def) ?: def },
        write = { prefs.edit().putString(key, it).apply() }
    )

    private fun <E : Enum<E>> enumPref(key: String, def: E, values: List<E>) = pref(
        def,
        read = { prefs.getString(key, null)?.let { name -> values.firstOrNull { it.name == name } } ?: def },
        write = { prefs.edit().putString(key, it.name).apply() }
    )
}
