package com.beyond.camera.engine

import android.content.Context
import android.location.Location
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import com.beyond.camera.camera.PhotoProcessor
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finishes published photos one at a time, even after the user leaves the camera screen. */
object ProcessingQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val revisions = mutableStateMapOf<String, Int>()
    private val activeCaptures = MutableStateFlow(0)

    init {
        scope.launch {
            for (job in jobs) {
                do {
                    activeCaptures.first { it == 0 }
                    delay(250)
                } while (activeCaptures.value != 0)
                job()
            }
        }
    }

    fun captureStarted() = activeCaptures.update { it + 1 }

    fun captureFinished() = activeCaptures.update { it - 1 }

    // MediaStore can return a volume-specific URI for the same image ID.
    fun revision(uri: Uri): Int = uri.lastPathSegment?.let { revisions[it] } ?: 0

    fun submit(
        context: Context,
        uri: Uri,
        rotation: Int,
        edit: PhotoProcessor.Edit,
        location: Location?,
        extraFrames: List<File>
    ) {
        jobs.trySend {
            val replaced = try {
                PhotoProcessor.finish(context, uri, rotation, edit, location, extraFrames)
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            } finally {
                extraFrames.forEach { it.delete() }
            }
            if (replaced) {
                val id = uri.lastPathSegment ?: return@trySend
                withContext(Dispatchers.Main) { revisions[id] = revision(uri) + 1 }
            }
        }
    }
}
