package com.beyond.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

private enum class CaptureMode(val label: String) { PHOTO("PHOTO"), VIDEO("VIDEO") }

private enum class FlashSetting(val imageCaptureMode: Int, val icon: ImageVector, val description: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, Icons.Filled.FlashOff, "Flash off"),
    ON(ImageCapture.FLASH_MODE_ON, Icons.Filled.FlashOn, "Flash on"),
    AUTO(ImageCapture.FLASH_MODE_AUTO, Icons.Filled.FlashAuto, "Flash auto");

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

private data class Capture(val uri: Uri, val isVideo: Boolean)

private val RecordRed = Color(0xFFE53935)

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }

    var mode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var flash by remember { mutableStateOf(FlashSetting.OFF) }
    var torchOn by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordedSeconds by remember { mutableLongStateOf(0L) }
    var lastCapture by remember { mutableStateOf<Capture?>(null) }
    var shutterFlashTick by remember { mutableLongStateOf(0L) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        controller.initializationFuture.addListener({
            canSwitchCamera = runCatching {
                controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
                    controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            }.getOrDefault(false)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            recording?.close()
            controller.unbind()
        }
    }

    // Keep the controller in sync with the UI state.
    LaunchedEffect(mode) {
        controller.setEnabledUseCases(
            if (mode == CaptureMode.PHOTO) CameraController.IMAGE_CAPTURE else CameraController.VIDEO_CAPTURE
        )
        if (mode == CaptureMode.PHOTO && torchOn) {
            torchOn = false
            controller.enableTorch(false)
        }
    }
    LaunchedEffect(flash) { controller.imageCaptureFlashMode = flash.imageCaptureMode }
    LaunchedEffect(useFrontCamera) {
        controller.cameraSelector =
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        torchOn = false
    }

    val isRecording = recording != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Live preview. Pinch-to-zoom and tap-to-focus come from the controller.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                }
            }
        )

        ShutterFlash(tick = shutterFlashTick)

        // Top bar: flash / torch and the recording timer.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mode == CaptureMode.PHOTO) {
                RoundIconButton(icon = flash.icon, description = flash.description) {
                    flash = flash.next()
                }
            } else {
                RoundIconButton(
                    icon = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    description = if (torchOn) "Torch on" else "Torch off"
                ) {
                    torchOn = !torchOn
                    controller.enableTorch(torchOn)
                }
            }
            Spacer(Modifier.weight(1f))
            if (isRecording) RecordingTimer(seconds = recordedSeconds)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp)) // balances the flash button so the timer stays centered
        }

        // Bottom controls: mode selector, then thumbnail / shutter / camera switch.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ModeSelector(
                selected = mode,
                enabled = !isRecording,
                onSelect = { mode = it }
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Thumbnail(capture = lastCapture, onClick = { lastCapture?.let { openCapture(context, it) } })

                ShutterButton(mode = mode, isRecording = isRecording) {
                    when {
                        mode == CaptureMode.PHOTO -> takePhoto(
                            context = context,
                            controller = controller,
                            onSaved = { lastCapture = Capture(it, isVideo = false) }
                        ).also { shutterFlashTick = System.nanoTime() }

                        isRecording -> recording?.stop()

                        else -> {
                            recordedSeconds = 0L
                            recording = startRecording(
                                context = context,
                                controller = controller,
                                onProgress = { recordedSeconds = it },
                                onFinished = { uri ->
                                    recording = null
                                    if (uri != null) lastCapture = Capture(uri, isVideo = true)
                                }
                            )
                        }
                    }
                }

                if (canSwitchCamera) {
                    RoundIconButton(
                        icon = Icons.Filled.Cameraswitch,
                        description = "Switch camera",
                        enabled = !isRecording
                    ) { useFrontCamera = !useFrontCamera }
                } else {
                    Spacer(Modifier.size(52.dp))
                }
            }
        }
    }
}

private fun takePhoto(context: Context, controller: LifecycleCameraController, onSaved: (Uri) -> Unit) {
    val options = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStoreSaver.photoValues()
    ).build()

    controller.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let(onSaved)
            }

            override fun onError(exception: ImageCaptureException) {
                toast(context, "Couldn't save photo: ${exception.message}")
            }
        }
    )
}

// Audio is only enabled when RECORD_AUDIO is granted, which is checked right here.
@SuppressLint("MissingPermission")
private fun startRecording(
    context: Context,
    controller: LifecycleCameraController,
    onProgress: (Long) -> Unit,
    onFinished: (Uri?) -> Unit
): Recording {
    val output = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(MediaStoreSaver.videoValues()).build()

    val withAudio = context.hasPermission(Manifest.permission.RECORD_AUDIO)
    if (!withAudio) toast(context, "Recording without sound (microphone not allowed)")

    return controller.startRecording(
        output,
        AudioConfig.create(withAudio),
        ContextCompat.getMainExecutor(context)
    ) { event ->
        when (event) {
            is VideoRecordEvent.Status ->
                onProgress(TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos))

            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    toast(context, "Video failed (error ${event.error})")
                    onFinished(null)
                } else {
                    onFinished(event.outputResults.outputUri)
                }
            }
        }
    }
}

private fun openCapture(context: Context, capture: Capture) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(capture.uri, if (capture.isVideo) "video/*" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(context, "No app found to open this file")
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ShutterButton(mode: CaptureMode, isRecording: Boolean, onClick: () -> Unit) {
    val innerSize by animateDpAsState(if (isRecording) 30.dp else 62.dp, label = "shutterInner")
    val corner by animateDpAsState(if (isRecording) 8.dp else 31.dp, label = "shutterCorner")
    Box(
        modifier = Modifier
            .size(78.dp)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(corner))
                .background(if (mode == CaptureMode.VIDEO) RecordRed else Color.White)
        )
    }
}

@Composable
private fun Thumbnail(capture: Capture?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.15f))
            .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
            .clickable(enabled = capture != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            capture == null -> Unit
            capture.isVideo -> Icon(Icons.Filled.Videocam, contentDescription = "Last video", tint = Color.White)
            else -> AsyncImage(
                model = capture.uri,
                contentDescription = "Last photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun ModeSelector(selected: CaptureMode, enabled: Boolean, onSelect: (CaptureMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        CaptureMode.entries.forEach { option ->
            val isSelected = option == selected
            Text(
                text = option.label,
                color = if (isSelected) Color(0xFFFFD54F) else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier
                    .alpha(if (enabled || isSelected) 1f else 0.4f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && !isSelected) { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun RecordingTimer(seconds: Long) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(RecordRed)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%02d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
        )
    }
}

/** Brief white flash over the preview when a photo is taken. */
@Composable
private fun ShutterFlash(tick: Long) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == 0L) return@LaunchedEffect
        visible = true
        delay(60)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.7f else 0f,
        animationSpec = tween(if (visible) 40 else 180),
        label = "shutterFlash"
    )
    if (alpha > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(alpha)
                .background(Color.White)
        )
    }
}
