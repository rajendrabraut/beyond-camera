package com.beyond.camera

import android.Manifest
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beyond.camera.camera.BindConfig
import com.beyond.camera.camera.CameraEngine
import com.beyond.camera.camera.CameraMode
import com.beyond.camera.camera.Fusion
import com.beyond.camera.camera.PhotoProcessor
import com.beyond.camera.camera.ProState
import com.beyond.camera.camera.TimelapseRecorder
import com.beyond.camera.qr.QrDecoder
import com.beyond.camera.qr.QrHistory
import com.beyond.camera.qr.QrResult
import com.beyond.camera.qr.QrResultSheet
import com.beyond.camera.settings.AspectRatioOption
import com.beyond.camera.settings.HoldShutterAction
import com.beyond.camera.settings.VideoResolution
import com.beyond.camera.settings.VolumeKeyAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot

/** Remembers the mode and lens while the app is open, across trips to Settings or the viewer. */
object Session {
    var mode: CameraMode? = null
    var front = false
}

private enum class FlashSetting(val mode: Int, val icon: ImageVector, val label: String) {
    OFF(ImageCapture.FLASH_MODE_OFF, Icons.Filled.FlashOff, "Flash off"),
    AUTO(ImageCapture.FLASH_MODE_AUTO, Icons.Filled.FlashAuto, "Flash auto"),
    ON(ImageCapture.FLASH_MODE_ON, Icons.Filled.FlashOn, "Flash on");

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

/** Things that must stay reachable from callbacks and clean-up, outside Compose state. */
private class Live {
    var recording: Recording? = null
    val timelapse = AtomicReference<TimelapseRecorder?>(null)
    var lastPinchAt = 0L
    var zoomTarget = 1f
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

@Composable
fun CameraScreen(
    onOpenSettings: () -> Unit,
    onOpenViewer: () -> Unit,
    onOpenQrHistory: () -> Unit,
    onOpenQrCreate: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val settings = context.appSettings
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current

    val engine = remember { CameraEngine(app) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val sound = remember { MediaActionSound() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val live = remember { Live() }
    val scanPaused = remember { AtomicBoolean(false) }
    val lastScanAt = remember { AtomicLong(0L) }

    var mode by remember {
        mutableStateOf(
            Session.mode ?: if (settings.savePreviousMode) {
                runCatching { CameraMode.valueOf(settings.lastMode) }.getOrDefault(CameraMode.PHOTO)
            } else {
                CameraMode.PHOTO
            }
        )
    }
    var front by remember { mutableStateOf(Session.front) }
    var flash by remember { mutableStateOf(FlashSetting.OFF) }
    var torch by remember { mutableStateOf(false) }
    var pro by remember { mutableStateOf(ProState()) }
    var showPanel by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var countJob by remember { mutableStateOf<Job?>(null) }
    var burstJob by remember { mutableStateOf<Job?>(null) }
    var burstCount by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    var tlFrames by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<MediaItem?>(null) }
    var flashTick by remember { mutableLongStateOf(0L) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableLongStateOf(0L) }
    var qrSheet by remember { mutableStateOf<QrResult?>(null) }
    var qrChip by remember { mutableStateOf<QrResult?>(null) }
    var chipTick by remember { mutableLongStateOf(0L) }
    var autoRecord by remember { mutableStateOf(false) }

    val modeState = rememberUpdatedState(mode)

    // ---- Code scanning -------------------------------------------------------------------------
    val onQr = rememberUpdatedState<(QrResult) -> Unit> { result ->
        if (mode == CameraMode.SCAN) {
            if (qrSheet == null) {
                scanPaused.set(true)
                app.buzz()
                QrHistory.add(app, result)
                qrSheet = result
            }
        } else if (mode == CameraMode.PHOTO && qrSheet == null) {
            qrChip = result
            chipTick = SystemClock.elapsedRealtime()
        }
    }
    val analyzer = remember {
        ImageAnalysis.Analyzer { image ->
            try {
                if (modeState.value == CameraMode.TIMELAPSE) {
                    live.timelapse.get()?.onFrame(image)
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (!scanPaused.get() && now - lastScanAt.get() > 110) {
                        lastScanAt.set(now)
                        val found = QrDecoder.decode(image)
                        if (found != null) mainHandler.post { onQr.value(found) }
                    }
                }
            } catch (t: Throwable) {
                // A bad frame must never take the camera down.
            } finally {
                image.close()
            }
        }
    }
    LaunchedEffect(chipTick) {
        if (chipTick != 0L) {
            delay(3000)
            qrChip = null
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = "Reading picture…"
                val found = withContext(Dispatchers.IO) { QrDecoder.decode(app, uri) }
                busy = null
                if (found != null) {
                    scanPaused.set(true)
                    QrHistory.add(app, found)
                    qrSheet = found
                } else {
                    app.toast("No QR code or barcode found in that picture")
                }
            }
        }
    }

    // ---- Camera session ------------------------------------------------------------------------
    val ratio = when {
        mode == CameraMode.SCAN -> AspectRatioOption.FULL
        mode.isVideo -> if (settings.aspectRatio == AspectRatioOption.FULL) AspectRatioOption.FULL else AspectRatioOption.R9_16
        else -> settings.aspectRatio
    }
    val wide = ratio == AspectRatioOption.R9_16 || ratio == AspectRatioOption.FULL

    LaunchedEffect(
        mode, front, wide, settings.videoResolution, settings.hdr, settings.pictureQuality, settings.scanQrInPhoto
    ) {
        torch = false
        engine.bind(
            owner,
            previewView,
            BindConfig(
                mode = mode,
                front = front,
                wide = wide,
                jpegQuality = settings.pictureQuality.jpeg,
                hdr = settings.hdr,
                videoRes = settings.videoResolution,
                scanInPhoto = settings.scanQrInPhoto
            ),
            analyzer
        )
        engine.setFlashMode(flash.mode)
        engine.applyControls(if (mode == CameraMode.PRO) pro else null, settings.antiBanding)
        live.zoomTarget = 1f
    }
    LaunchedEffect(pro, settings.antiBanding) {
        if (engine.bound) engine.applyControls(if (mode == CameraMode.PRO) pro else null, settings.antiBanding)
    }
    LaunchedEffect(flash) { engine.setFlashMode(flash.mode) }
    LaunchedEffect(engine.zoomRatio) {
        if (SystemClock.elapsedRealtime() - live.lastPinchAt > 400) live.zoomTarget = engine.zoomRatio
    }
    LaunchedEffect(Unit) {
        last = withContext(Dispatchers.IO) { MediaStoreSaver.latest(app) }
    }

    DisposableEffect(Unit) {
        VolumeKeyEvents.cameraActive = true
        view.keepScreenOn = true
        onDispose {
            VolumeKeyEvents.cameraActive = false
            view.keepScreenOn = false
            runCatching { live.recording?.stop() }
            live.timelapse.getAndSet(null)?.let { recorder -> Thread { recorder.finish() }.start() }
            engine.release()
            runCatching { sound.release() }
        }
    }

    // ---- Actions -------------------------------------------------------------------------------
    suspend fun capturePhoto() {
        if (busy != null || !engine.photoReady) return
        val m = mode
        val square = settings.aspectRatio == AspectRatioOption.R1_1
        val stamp = settings.timestamp
        val quality = settings.pictureQuality.jpeg
        val location = if (settings.saveLocation) LocationHelper.lastKnown(app) else null
        busy = ""
        try {
            if (settings.shutterSound) runCatching { sound.play(MediaActionSound.SHUTTER_CLICK) }
            flashTick = System.nanoTime()
            val result: PhotoProcessor.Result = when {
                engine.usingExtension || m == CameraMode.PRO -> {
                    val frame = engine.capture()
                    withContext(Dispatchers.Default) {
                        PhotoProcessor.saveSingle(app, frame, square, stamp, quality, location)
                    }
                }

                m == CameraMode.NIGHT -> {
                    busy = "Hold still…"
                    engine.setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    val frames = engine.burst(4, 0.7f)
                    busy = "Enhancing…"
                    withContext(Dispatchers.Default) {
                        PhotoProcessor.saveFusion(
                            app, frames,
                            Fusion.Options(Fusion.Kind.DENOISE, gamma = 0.85f, saturation = 1.08f, sharpen = 0.3f),
                            square, stamp, quality, location
                        )
                    }
                }

                m == CameraMode.PHOTO && settings.hdr -> {
                    busy = "Hold still…"
                    engine.setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    val frames = engine.bracket()
                    busy = "Enhancing…"
                    withContext(Dispatchers.Default) {
                        PhotoProcessor.saveFusion(
                            app, frames,
                            Fusion.Options(Fusion.Kind.HDR, contrast = 0.25f, saturation = 1.1f),
                            square, stamp, quality, location
                        )
                    }
                }

                m == CameraMode.PORTRAIT -> {
                    val frame = engine.capture()
                    busy = "Enhancing…"
                    withContext(Dispatchers.Default) {
                        PhotoProcessor.savePortrait(app, frame, square, stamp, quality, location)
                    }
                }

                else -> {
                    val frame = engine.capture()
                    withContext(Dispatchers.Default) {
                        PhotoProcessor.saveSingle(app, frame, square, stamp, quality, location)
                    }
                }
            }
            result.note?.let { app.toast(it) }
            val uri = result.uri
            if (uri != null) last = MediaItem(uri, false) else app.toast("Couldn't save the photo")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.toast("Couldn't take the photo")
        } finally {
            busy = null
            engine.setFlashMode(flash.mode)
        }
    }

    fun shoot(delaySeconds: Int) {
        if (busy != null || !engine.photoReady) return
        countJob = scope.launch {
            if (delaySeconds > 0) {
                for (i in delaySeconds downTo 1) {
                    countdown = i
                    delay(1000)
                }
                countdown = null
            }
            scope.launch { capturePhoto() }
        }
    }

    fun toggleVideo() {
        val current = live.recording
        if (current != null) {
            current.stop()
            return
        }
        if (!engine.videoReady) return
        val withAudio = app.hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!withAudio) app.toast("Recording without sound (microphone not allowed)")
        val location = if (settings.saveLocation) LocationHelper.lastKnown(app) else null
        recordedMs = 0L
        if (settings.shutterSound) runCatching { sound.play(MediaActionSound.START_VIDEO_RECORDING) }
        val started = engine.startRecording(withAudio, location) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    recordedMs = TimeUnit.NANOSECONDS.toMillis(event.recordingStats.recordedDurationNanos)
                    if (modeState.value == CameraMode.CLIP && recordedMs >= settings.shortVideoSeconds * 1000L) {
                        live.recording?.stop()
                    }
                }

                is VideoRecordEvent.Finalize -> {
                    live.recording = null
                    isRecording = false
                    if (settings.shutterSound) runCatching { sound.play(MediaActionSound.STOP_VIDEO_RECORDING) }
                    if (event.hasError()) {
                        app.toast("The video couldn't be saved")
                    } else {
                        last = MediaItem(event.outputResults.outputUri, true)
                    }
                }
            }
        }
        live.recording = started
        isRecording = started != null
    }

    fun toggleTimelapse() {
        val running = live.timelapse.getAndSet(null)
        if (running != null) {
            isRecording = false
            busy = "Saving…"
            scope.launch {
                val uri = withContext(Dispatchers.IO) { running.finish() }
                busy = null
                if (uri != null) last = MediaItem(uri, true) else app.toast("Too short: keep recording a little longer")
            }
        } else if (engine.bound && busy == null) {
            tlFrames = 0
            live.timelapse.set(
                TimelapseRecorder(app, settings.timelapseIntervalMs.toLong()) { count ->
                    mainHandler.post { tlFrames = count }
                }
            )
            isRecording = true
        }
    }

    fun selectMode(target: CameraMode) {
        if (isRecording || busy != null || target == mode) return
        countJob?.cancel()
        countdown = null
        mode = target
        Session.mode = target
        settings.lastMode = target.name
        showPanel = false
        qrChip = null
        scanPaused.set(false)
    }

    fun onShutterTap() {
        when {
            countJob?.isActive == true -> {
                countJob?.cancel()
                countdown = null
            }

            mode == CameraMode.VIDEO || mode == CameraMode.CLIP -> toggleVideo()
            mode == CameraMode.TIMELAPSE -> toggleTimelapse()
            mode == CameraMode.SCAN -> Unit
            else -> shoot(settings.timerSeconds)
        }
    }

    fun onShutterHold() {
        if (mode.isVideo || mode == CameraMode.SCAN || busy != null) return
        when (settings.holdShutter) {
            HoldShutterAction.RECORD_VIDEO -> {
                app.buzz()
                autoRecord = true
                selectMode(CameraMode.VIDEO)
            }

            HoldShutterAction.BURST -> {
                if (!engine.photoReady) return
                app.buzz()
                burstJob = scope.launch {
                    busy = ""
                    try {
                        var count = 0
                        while (isActive && count < 20) {
                            val frame = engine.capture()
                            count++
                            burstCount = count
                            val saved = withContext(Dispatchers.Default) {
                                PhotoProcessor.saveSingle(app, frame, false, false, settings.pictureQuality.jpeg, null)
                            }
                            saved.uri?.let { last = MediaItem(it, false) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.toast("Burst stopped")
                    } finally {
                        busy = null
                        burstCount = 0
                    }
                }
            }

            HoldShutterAction.OFF -> Unit
        }
    }

    fun onShutterRelease() {
        burstJob?.cancel()
        burstJob = null
    }

    // Hold-to-record: start as soon as the video session is ready.
    LaunchedEffect(autoRecord, engine.videoReady) {
        if (autoRecord && engine.videoReady && mode == CameraMode.VIDEO) {
            autoRecord = false
            if (live.recording == null) toggleVideo()
        }
    }

    val shutterTap = rememberUpdatedState<() -> Unit> { onShutterTap() }
    val shootNow = rememberUpdatedState<(Int) -> Unit> { seconds -> shoot(seconds) }
    LaunchedEffect(Unit) {
        VolumeKeyEvents.events.collect { key ->
            val up = key == KeyEvent.KEYCODE_VOLUME_UP
            when (settings.volumeKeys) {
                VolumeKeyAction.SHUTTER -> shutterTap.value()
                VolumeKeyAction.COUNTDOWN -> {
                    if (modeState.value.isVideo || modeState.value == CameraMode.SCAN) {
                        shutterTap.value()
                    } else {
                        shootNow.value(if (settings.timerSeconds > 0) settings.timerSeconds else 3)
                    }
                }

                VolumeKeyAction.ZOOM -> engine.setZoom(engine.zoomRatio * if (up) 1.25f else 0.8f)
                VolumeKeyAction.VOLUME -> Unit
            }
        }
    }

    val onPreviewTap = rememberUpdatedState<(Offset) -> Unit> { position ->
        val previous = focusPoint
        val near = previous != null && hypot(position.x - previous.x, position.y - previous.y) < with(density) { 60.dp.toPx() }
        if (settings.focusAndShoot && near && !mode.isVideo && mode != CameraMode.SCAN) {
            onShutterTap()
        } else {
            engine.focusAt(previewView, position.x, position.y)
            focusPoint = position
            focusTick = System.nanoTime()
        }
        showPanel = false
    }

    // ---- Layout --------------------------------------------------------------------------------
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val previewModifier = when (ratio) {
        AspectRatioOption.FULL -> Modifier.fillMaxSize()
        AspectRatioOption.R9_16 -> Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)

        AspectRatioOption.R3_4 -> Modifier
            .padding(top = statusTop + 62.dp)
            .fillMaxWidth()
            .aspectRatio(3f / 4f)

        AspectRatioOption.R1_1 -> Modifier
            .padding(top = statusTop + 110.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
    }
    val photoMode = !mode.isVideo && mode != CameraMode.SCAN

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(previewModifier.clipToBounds()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onPreviewTap.value(it) } }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) {
                                live.lastPinchAt = SystemClock.elapsedRealtime()
                                live.zoomTarget = (live.zoomTarget * zoom).coerceIn(engine.minZoom, engine.maxZoom)
                                engine.setZoom(live.zoomTarget)
                            }
                        }
                    }
            )
            if (settings.gridlines && mode != CameraMode.SCAN) GridOverlay()
            if (mode == CameraMode.SCAN) ScanFrame()
            focusPoint?.let { point -> FocusRing(point, focusTick) { focusPoint = null } }
            ShutterFlash(flashTick)
            countdown?.let { seconds ->
                Text(
                    seconds.toString(),
                    color = Color.White,
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (mode == CameraMode.PRO) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    ProPanel(engine.proCaps, pro) { pro = it }
                }
            }
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (engine.hasFlash) {
                if (photoMode) {
                    CircleIcon(flash.icon, flash.label, active = flash != FlashSetting.OFF) { flash = flash.next() }
                } else {
                    CircleIcon(
                        if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        if (torch) "Light on" else "Light off",
                        active = torch
                    ) {
                        torch = !torch
                        engine.setTorch(torch)
                    }
                }
            }
            if (mode == CameraMode.PHOTO) {
                TextPill("HDR", settings.hdr) { if (busy == null) settings.hdr = !settings.hdr }
            }
            if (photoMode && settings.timerSeconds > 0) TextPill("${settings.timerSeconds}s", false)
            Spacer(Modifier.weight(1f))
            if (mode == CameraMode.SCAN) {
                CircleIcon(Icons.Filled.Image, "Scan a picture from your phone") { pickImage.launch("image/*") }
                CircleIcon(Icons.Filled.History, "Scan history", onClick = onOpenQrHistory)
                CircleIcon(Icons.Filled.QrCode2, "Create a QR code", onClick = onOpenQrCreate)
            } else {
                CircleIcon(Icons.Filled.Tune, "Quick options", active = showPanel, enabled = !isRecording) {
                    showPanel = !showPanel
                }
            }
            CircleIcon(Icons.Filled.Settings, "Settings", enabled = !isRecording && busy == null, onClick = onOpenSettings)
        }

        // Recording / processing status
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isRecording) {
                val text = when (mode) {
                    CameraMode.TIMELAPSE -> "$tlFrames frames  ·  ${"%.1f".format(tlFrames / 30f)}s video"
                    CameraMode.CLIP -> "${clock(recordedMs)} / ${clock(settings.shortVideoSeconds * 1000L)}"
                    else -> clock(recordedMs)
                }
                StatusPill(text, dot = true)
            }
            val message = busy
            if (!message.isNullOrEmpty()) StatusPill(message, dot = false)
            if (burstCount > 0) StatusPill("Burst $burstCount", dot = false)
            if (mode == CameraMode.SCAN && qrSheet == null) StatusPill("Point at a QR code or barcode", dot = false)
            engine.error?.let { StatusPill("Camera problem: $it", dot = false) }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            qrChip?.let { chip ->
                Text(
                    text = "Code found: ${chip.text}",
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Ui.Accent)
                        .clickable {
                            scanPaused.set(true)
                            QrHistory.add(app, chip)
                            qrSheet = chip
                            qrChip = null
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                )
                Spacer(Modifier.height(10.dp))
            }

            if (showPanel) {
                QuickPanel(mode, settings)
                Spacer(Modifier.height(10.dp))
            } else if (mode != CameraMode.SCAN) {
                ZoomBar(engine.zoomRatio, engine.minZoom, engine.maxZoom) {
                    live.zoomTarget = it
                    engine.setZoom(it)
                }
                Spacer(Modifier.height(10.dp))
            }

            ModeStrip(mode, enabled = !isRecording && busy == null) { selectMode(it) }
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Thumb(last, onClick = { if (!isRecording && busy == null) onOpenViewer() })

                if (mode == CameraMode.SCAN) {
                    Text(
                        "Scan from picture",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ui.Accent)
                            .clickable { pickImage.launch("image/*") }
                            .padding(horizontal = 22.dp, vertical = 16.dp)
                    )
                } else {
                    ShutterButton(
                        video = mode.isVideo,
                        recording = isRecording,
                        progress = if (mode == CameraMode.CLIP && isRecording) {
                            recordedMs / (settings.shortVideoSeconds * 1000f)
                        } else {
                            null
                        },
                        enabled = engine.bound && (busy == null || burstJob != null),
                        onTap = { onShutterTap() },
                        onLongPress = { onShutterHold() },
                        onRelease = { onShutterRelease() }
                    )
                }

                if (engine.canSwitch) {
                    CircleIcon(Icons.Filled.Cameraswitch, "Switch camera", enabled = !isRecording && busy == null) {
                        front = !front
                        Session.front = front
                    }
                } else {
                    Spacer(Modifier.size(42.dp))
                }
            }
        }

        qrSheet?.let { result ->
            QrResultSheet(result) {
                qrSheet = null
                scanPaused.set(false)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, dot: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(Ui.Record)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Options that change often, shown just above the mode strip. */
@Composable
private fun QuickPanel(mode: CameraMode, settings: com.beyond.camera.settings.AppSettings) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ui.Panel)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        when {
            mode == CameraMode.TIMELAPSE -> {
                PanelLabel("Take one frame every")
                Segmented(
                    options = listOf(500, 1000, 2000, 5000),
                    selected = settings.timelapseIntervalMs,
                    label = { if (it < 1000) "0.5s" else "${it / 1000}s" },
                    sub = { "${it * 30 / 1000}x speed" }
                ) { settings.timelapseIntervalMs = it }
            }

            mode.isVideo -> {
                PanelLabel("Video quality")
                Segmented(
                    options = VideoResolution.entries,
                    selected = settings.videoResolution,
                    label = { it.label },
                    sub = { it.sub }
                ) { settings.videoResolution = it }
                if (mode == CameraMode.CLIP) {
                    PanelLabel("Clip length")
                    Segmented(
                        options = listOf(10, 15, 30, 60),
                        selected = settings.shortVideoSeconds,
                        label = { "${it}s" }
                    ) { settings.shortVideoSeconds = it }
                }
            }

            else -> {
                PanelLabel("Shape")
                Segmented(
                    options = AspectRatioOption.entries,
                    selected = settings.aspectRatio,
                    label = { it.label }
                ) { settings.aspectRatio = it }
                PanelLabel("Timer")
                Segmented(
                    options = listOf(0, 3, 5, 10),
                    selected = settings.timerSeconds,
                    label = { if (it == 0) "Off" else "${it}s" }
                ) { settings.timerSeconds = it }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.Filled.GridOn, "Grid lines", active = settings.gridlines) {
                settings.gridlines = !settings.gridlines
            }
            Text("Grid lines", color = Color.White, fontSize = 13.sp)
        }
    }
}
