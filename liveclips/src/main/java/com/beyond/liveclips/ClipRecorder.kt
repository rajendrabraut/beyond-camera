package com.beyond.liveclips

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.TimeUnit

internal class ClipRecorder {
    var capture by mutableStateOf<VideoCapture<Recorder>?>(null)
    private var recording: Recording? = null

    @SuppressLint("MissingPermission")
    fun record(file: File, duration: Long, mic: Boolean, onEvent: (VideoRecordEvent) -> Unit) {
        check(recording == null)
        val video = checkNotNull(capture) { "The camera is starting." }
        val context = appContext ?: error("Camera is unavailable.")
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(duration + 400)
            .setFileSizeLimit(512_000_000L)
            .build()
        var pending = video.output.prepareRecording(context, options)
        if (mic && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) recording = null
            if (event is VideoRecordEvent.Status &&
                TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos) >= duration + 120
            ) recording?.stop()
            onEvent(event)
        }
    }

    fun stop() { recording?.stop() }

    var appContext: android.content.Context? = null
}

@Composable
internal fun RecordingPreview(
    recorder: ClipRecorder,
    front: Boolean,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val error by rememberUpdatedState(onError)
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(lifecycle, front) {
        recorder.appContext = context.applicationContext
        var active = true
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (active) {
                runCatching {
                    val camera = future.get().also { provider = it }
                    val displayPreview = Preview.Builder().build().also { preview = it }
                    displayPreview.setSurfaceProvider(view.surfaceProvider)
                    val video = VideoCapture.withOutput(
                        Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.HD, Quality.SD))).build()
                    )
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    camera.bindToLifecycle(lifecycle, selector, displayPreview, video)
                    recorder.capture = video
                }.onFailure { error("Couldn't start this camera. Try the other lens or import a video.") }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active = false
            recorder.stop()
            preview?.let { provider?.unbind(it) }
            recorder.capture?.let { provider?.unbind(it) }
            recorder.capture = null
        }
    }
}
