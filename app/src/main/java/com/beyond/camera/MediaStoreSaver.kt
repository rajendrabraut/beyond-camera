package com.beyond.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One photo or video taken with this app. */
data class MediaItem(
    val uri: Uri,
    val isVideo: Boolean,
    val dateAdded: Long = 0L,
    val name: String = "",
    val size: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)

/** Saves captures into the gallery under a "BeyondCamera" album and lists them back. */
object MediaStoreSaver {
    private const val ALBUM = "BeyondCamera"
    private const val PREFIX = "BeyondCamera_"

    fun photoValues(mimeType: String = "image/jpeg"): ContentValues =
        values(mimeType, Environment.DIRECTORY_PICTURES)

    fun videoValues(): ContentValues = values("video/mp4", Environment.DIRECTORY_MOVIES)

    private fun values(mimeType: String, directory: String): ContentValues {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$PREFIX$stamp")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$ALBUM")
            }
        }
    }

    /** Copies a finished temp file into the gallery, then deletes the temp file. */
    fun publish(context: Context, file: File, isVideo: Boolean, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val values = if (isVideo) videoValues() else photoValues(mimeType)
        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (pending) values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        var uri: Uri? = null
        return try {
            val inserted = resolver.insert(collection, values) ?: return null
            uri = inserted
            val out = resolver.openOutputStream(inserted) ?: throw IllegalStateException("No output stream")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            if (pending) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(inserted, done, null, null)
            }
            inserted
        } catch (e: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        } finally {
            file.delete()
        }
    }

    fun tempFile(context: Context, extension: String): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "tmp_${System.nanoTime()}.$extension")
    }

    /** Replaces the bytes at the existing URI, restoring the original if the write fails. */
    fun replacePhoto(context: Context, uri: Uri, finished: File, original: ByteArray): Boolean {
        val resolver = context.contentResolver
        return try {
            finished.inputStream().use { input ->
                val output = resolver.openOutputStream(uri, "wt") ?: return false
                output.use { input.copyTo(it) }
            }
            true
        } catch (_: Exception) {
            runCatching { resolver.openOutputStream(uri, "wt")?.use { it.write(original) } }
            false
        }
    }

    /** Leftover temp files from a crash or a killed process. */
    fun clearTemp(context: Context) {
        runCatching { File(context.cacheDir, "captures").listFiles()?.forEach { it.delete() } }
    }

    /** Captures made by this app, newest first. */
    fun query(context: Context, limit: Int = 500): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        items.addAll(queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false))
        items.addAll(queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
        items.sortByDescending { it.dateAdded }
        return if (items.size > limit) items.subList(0, limit) else items
    }

    fun latest(context: Context): MediaItem? = query(context).firstOrNull()

    private fun queryCollection(context: Context, collection: Uri, isVideo: Boolean): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$PREFIX%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val date = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                while (cursor.moveToNext()) {
                    result.add(
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(id)),
                            isVideo = isVideo,
                            dateAdded = cursor.getLong(date),
                            name = cursor.getString(name) ?: "",
                            size = cursor.getLong(size),
                            width = cursor.getInt(width),
                            height = cursor.getInt(height)
                        )
                    )
                }
            }
        }
        return result
    }

    fun delete(context: Context, item: MediaItem): Boolean =
        runCatching { context.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)
}
