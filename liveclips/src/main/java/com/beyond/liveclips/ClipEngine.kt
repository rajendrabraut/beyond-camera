package com.beyond.liveclips

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ClipEdit(
    val video: File,
    val videoStartMs: Long,
    val durationMs: Long,
    val song: File?,
    val songStartMs: Long,
    val originalSound: Boolean,
    val height: Int
)

internal fun secondsToMs(value: String): Long? {
    val seconds = value.trim().toDoubleOrNull() ?: return null
    if (!seconds.isFinite() || seconds < 0 || seconds > 24 * 3600) return null
    return (seconds * 1000).toLong()
}

@OptIn(UnstableApi::class)
internal class ClipEngine(private val context: Context) {
    fun clipped(file: File, startMs: Long, endMs: Long): MediaItem =
        MediaItem.Builder().setUri(Uri.fromFile(file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            ).build()

    suspend fun export(edit: ClipEdit, output: File) {
        val video = EditedMediaItem.Builder(clipped(edit.video, edit.videoStartMs, edit.videoStartMs + edit.durationMs))
            .setRemoveAudio(!edit.originalSound)
            .setEffects(
                Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                    edit.height * 9 / 16 / 2 * 2, edit.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )))
            ).build()
        val videoTracks = if (edit.originalSound) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
            else setOf(C.TRACK_TYPE_VIDEO)
        val sequences = mutableListOf(
            EditedMediaItemSequence.Builder(videoTracks).addItem(video).build()
        )
        edit.song?.let { song ->
            val audio = EditedMediaItem.Builder(clipped(song, edit.songStartMs, edit.songStartMs + edit.durationMs))
                .setRemoveVideo(true).build()
            sequences.add(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(audio).build())
        }
        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }).build()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            continuation.invokeOnCancellation { handler.post { transformer.cancel() } }
            transformer.start(Composition.Builder(sequences).build(), output.absolutePath)
        }
    }
}
