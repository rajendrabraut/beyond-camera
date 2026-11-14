package com.beyond.liveclips

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal const val MIN_CLIP_MS = 5_000L

internal data class Draft(
    val video: File? = null,
    val songId: String? = null,
    val lengthSeconds: String = "5",
    val videoStartSeconds: String = "0",
    val songStartSeconds: String = "0",
    val originalSound: Boolean = false,
    val caption: String = "",
    val monochrome: Boolean = false
)

internal fun clipDuration(
    requestedMs: Long?,
    videoRemainingMs: Long,
    songRemainingMs: Long?
): Long? {
    val maximum = minOf(videoRemainingMs, songRemainingMs ?: Long.MAX_VALUE)
    val duration = requestedMs ?: maximum
    return duration.takeIf { it in MIN_CLIP_MS..maximum }
}

internal class ClipStore(private val context: Context) {
    val draftDirectory = File(context.filesDir, "draft").apply { mkdirs() }
    val clips = File(context.filesDir, "clips").apply { mkdirs() }
    val received = File(context.filesDir, "received").apply { mkdirs() }
    private val draftFile = AtomicFile(File(draftDirectory, "project.json"))

    fun read(): Draft = runCatching {
        val json = JSONObject(draftFile.openRead().bufferedReader().use { it.readText() })
        Draft(
            video = json.optString("video").takeIf { VIDEO_NAME.matches(it) }
                ?.let { File(draftDirectory, it) }?.takeIf { it.isFile },
            songId = json.optString("song").ifEmpty { null },
            lengthSeconds = json.optString("duration", "5"),
            videoStartSeconds = json.optString("videoStart", "0"),
            songStartSeconds = json.optString("songStart", "0"),
            originalSound = json.optBoolean("originalSound"),
            caption = json.optString("caption").take(100),
            monochrome = json.optBoolean("monochrome")
        )
    }.getOrDefault(Draft())

    fun write(draft: Draft) {
        val json = JSONObject().apply {
            put("video", draft.video?.name.orEmpty())
            put("song", draft.songId.orEmpty())
            put("duration", draft.lengthSeconds)
            put("videoStart", draft.videoStartSeconds)
            put("songStart", draft.songStartSeconds)
            put("originalSound", draft.originalSound)
            put("caption", draft.caption)
            put("monochrome", draft.monochrome)
        }
        val stream = draftFile.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            draftFile.finishWrite(stream)
        } catch (e: Exception) {
            draftFile.failWrite(stream)
            throw e
        }
    }

    fun newTake(): File = File(draftDirectory, "${UUID.randomUUID()}.mp4")

    fun import(uri: Uri): File {
        val file = newTake()
        try {
            copyFromUri(context, uri, file, MAX_IMPORT_BYTES)
            require(mediaDuration(file, video = true) >= MIN_CLIP_MS) {
                "Choose a video at least 5 seconds long."
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun listClips(): List<File> = clips.listFiles { file ->
        file.isFile && file.extension == "mp4"
    }.orEmpty().sortedByDescending { it.lastModified() }

    fun listReceivedClips(): List<File> = received.listFiles { file ->
        file.isFile && file.extension.equals("mp4", ignoreCase = true)
    }.orEmpty().sortedByDescending { it.lastModified() }

    fun credit(file: File): String = if (file.parentFile == received) {
        ClipCreators.visibleCredit(ClipCreators.readCredit(file))
    } else File(clips, "${file.nameWithoutExtension}.txt").takeIf { it.isFile }?.readText().orEmpty()

    fun commitClip(temp: File, attribution: String): File {
        val name = "BeyondLiveClips_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val output = File(clips, "$name.mp4")
        val credit = File(clips, "$name.txt")
        try {
            if (attribution.isNotBlank()) credit.writeText(attribution)
            if (!temp.renameTo(output)) throw IOException("Couldn't save the finished clip.")
            return output
        } catch (e: Exception) {
            credit.delete()
            throw e
        }
    }

    fun saveToGallery(file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BeyondLiveClips")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't open the gallery.")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Couldn't write to the gallery.")
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        private val VIDEO_NAME = Regex("[a-f0-9-]{36}\\.mp4")
        private const val MAX_IMPORT_BYTES = 2_000_000_000L
    }
}

internal fun mediaDuration(file: File, video: Boolean = false): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val key = if (video) MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
            else MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
        if (retriever.extractMetadata(key) != "yes") return 0
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
    } finally {
        retriever.release()
    }
}

internal fun copyFromUri(context: Context, uri: Uri, file: File, limit: Long) {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't read this file.")
    input.use { source ->
        file.outputStream().use { target ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count == -1) break
                total += count
                require(total <= limit) { "This file is too large." }
                target.write(buffer, 0, count)
            }
        }
    }
}
