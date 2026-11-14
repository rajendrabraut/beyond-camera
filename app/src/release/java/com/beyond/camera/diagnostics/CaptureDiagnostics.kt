package com.beyond.camera.diagnostics

import android.content.Context
import androidx.compose.runtime.Composable

@Suppress("UNUSED_PARAMETER")
internal object CaptureDiagnostics {
    fun photo(context: Context, jpeg: ByteArray, rotation: Int, mode: String, timing: PhotoTiming,
        frameAgeMs: Long?, earlier: Boolean) = Unit

    fun video(context: Context, mode: String, durationMs: Long, startMs: Long? = null, finishMs: Long? = null) = Unit

    @Composable
    fun Settings() = Unit
}
