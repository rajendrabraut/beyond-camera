package com.beyond.camera

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds MediaStore entries so captures land in the gallery under a "BeyondCamera" album. */
object MediaStoreSaver {
    private const val ALBUM = "BeyondCamera"

    fun photoValues(): ContentValues = values("image/jpeg", Environment.DIRECTORY_PICTURES)

    fun videoValues(): ContentValues = values("video/mp4", Environment.DIRECTORY_MOVIES)

    private fun values(mimeType: String, directory: String): ContentValues {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${ALBUM}_$stamp")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$ALBUM")
            }
        }
    }
}
