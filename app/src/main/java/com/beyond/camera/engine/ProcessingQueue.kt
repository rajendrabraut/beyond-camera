package com.beyond.camera.engine

import android.net.Uri
import com.beyond.camera.camera.PhotoProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finishes photos in the background, one at a time, so the shutter is free again as soon as the
 * frames are captured. Keeps running when the user leaves the camera screen.
 */
object ProcessingQueue {
    const val MAX_PENDING = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    val pending = MutableStateFlow(0)
    val saved = MutableSharedFlow<Uri>(extraBufferCapacity = 8)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun isFull(): Boolean = pending.value >= MAX_PENDING

    fun submit(job: () -> PhotoProcessor.Result) {
        pending.update { it + 1 }
        scope.launch {
            mutex.withLock {
                try {
                    val result = job()
                    val uri = result.uri
                    if (uri != null) saved.emit(uri) else messages.emit("Couldn't save the photo")
                    result.note?.let { messages.emit(it) }
                } catch (t: Throwable) {
                    messages.emit("Couldn't process the photo")
                } finally {
                    pending.update { it - 1 }
                }
            }
        }
    }
}
