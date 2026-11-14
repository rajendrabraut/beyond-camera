package com.beyond.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.beyond.camera.MediaStoreSaver
import com.beyond.camera.await
import com.beyond.camera.settings.AntiBanding
import com.beyond.camera.settings.VideoResolution
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the CameraX session: binds the right use cases for each mode and exposes capture controls. */
@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class CameraEngine(private val context: Context) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var zoomLiveData: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        zoomRatio = z.zoomRatio
        minZoom = z.minZoomRatio
        maxZoom = z.maxZoomRatio
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var photoFrames: PhotoFrames? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var minZoom by mutableFloatStateOf(1f)
        private set
    var maxZoom by mutableFloatStateOf(1f)
        private set
    var hasFlash by mutableStateOf(false)
        private set
    var canSwitch by mutableStateOf(false)
        private set
    var bound by mutableStateOf(false)
        private set

    /** True when the phone maker's own HDR / Night / Bokeh processing is active for this session. */
    var usingExtension by mutableStateOf(false)
        private set
    var stabilized by mutableStateOf(false)
        private set
    var videoReady by mutableStateOf(false)
        private set
    var photoReady by mutableStateOf(false)
        private set
    internal fun photoSnapshot(): PhotoFrames.Snapshot? =
        photoFrames?.snapshot(SystemClock.elapsedRealtimeNanos())
    var proCaps by mutableStateOf<ProCaps?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        cfg: BindConfig,
        analyzer: ImageAnalysis.Analyzer
    ) {
        bound = false
        videoReady = false
        photoReady = false
        photoFrames = null
        error = null
        try {
            val p = provider ?: ProcessCameraProvider.getInstance(context).await(mainExecutor).also { provider = it }
            val hasBack = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            val hasFront = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            canSwitch = hasBack && hasFront
            val base = if ((cfg.front && hasFront) || !hasBack) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            var selector = base
            var extensionOn = false
            val extMode = when {
                cfg.mode == CameraMode.NIGHT -> ExtensionMode.NIGHT
                cfg.mode == CameraMode.PORTRAIT -> ExtensionMode.BOKEH
                else -> null
            }
            if (extMode != null) {
                val manager = extensions ?: runCatching {
                    ExtensionsManager.getInstanceAsync(context, p).await(mainExecutor)
                }.getOrNull()?.also { extensions = it }
                if (manager != null && runCatching { manager.isExtensionAvailable(base, extMode) }.getOrDefault(false)) {
                    selector = manager.getExtensionEnabledCameraSelector(base, extMode)
                    extensionOn = true
                }
            }

            p.unbindAll()
            zoomLiveData?.removeObserver(zoomObserver)
            imageCapture = null
            videoCapture = null
            stabilized = false

            val ratio = if (cfg.wide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            val aspect = AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()

            val cases = ArrayList<UseCase>()
            cases.add(preview)
            var optionalAnalysis: ImageAnalysis? = null

            when (cfg.mode) {
                CameraMode.PHOTO, CameraMode.PRO, CameraMode.PORTRAIT, CameraMode.NIGHT -> {
                    // Standard Photo uses software HDR after capture so OEM HDR can't delay the shutter.
                    val captureMode = when {
                        cfg.mode == CameraMode.PHOTO -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                        else -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(captureMode)
                        .setJpegQuality(cfg.jpegQuality)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(aspect)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build()
                        )
                        .build()
                    imageCapture = capture
                    cases.add(capture)
                    if (cfg.mode == CameraMode.PHOTO) {
                        val info = Camera2CameraInfo.from(p.getCameraInfo(base))
                        val elapsedClock = info.getCameraCharacteristic(
                            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE
                        ) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                        val frames = PhotoFrames(elapsedClock)
                        photoFrames = frames
                        val scan = cfg.scanInPhoto && base == CameraSelector.DEFAULT_BACK_CAMERA
                        optionalAnalysis = buildAnalysis(aspect, Size(1920, 1440), ImageAnalysis.Analyzer { image ->
                            try {
                                frames.update(image)
                            } catch (_: RuntimeException) {
                                // Some vendor buffers are unavailable during camera reconfiguration.
                            } finally {
                                if (scan) analyzer.analyze(image) else image.close()
                            }
                        }, ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                        cases.add(optionalAnalysis)
                    }
                }

                CameraMode.SCAN -> cases.add(buildAnalysis(aspect, Size(1280, 720), analyzer))

                CameraMode.TIMELAPSE -> cases.add(buildAnalysis(aspect, Size(1920, 1080), analyzer))

                CameraMode.VIDEO, CameraMode.CLIP -> {
                    val fhd = cfg.videoRes == VideoResolution.FHD
                    val quality = if (fhd) Quality.FHD else Quality.HD
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality))
                        )
                        .setTargetVideoEncodingBitRate(if (fhd) 20_000_000 else 12_000_000)
                        .build()
                    val builder = VideoCapture.Builder(recorder)
                    val canStabilize = runCatching {
                        Recorder.getVideoCapabilities(p.getCameraInfo(selector)).isStabilizationSupported
                    }.getOrDefault(false)
                    if (canStabilize) builder.setVideoStabilizationEnabled(true)
                    val video = builder.build()
                    videoCapture = video
                    stabilized = canStabilize
                    cases.add(video)
                }
            }

            val cam = try {
                p.bindToLifecycle(owner, selector, *cases.toTypedArray())
            } catch (first: Exception) {
                // Some phones can't run every stream together: retry with the essentials only.
                p.unbindAll()
                val extra = optionalAnalysis
                if (extra == null && !extensionOn) throw first
                if (extra != null) {
                    cases.remove(extra)
                    photoFrames = null
                }
                extensionOn = false
                p.bindToLifecycle(owner, base, *cases.toTypedArray())
            }
            preview.setSurfaceProvider(previewView.surfaceProvider)

            camera = cam
            usingExtension = extensionOn
            hasFlash = cam.cameraInfo.hasFlashUnit()
            cam.cameraInfo.zoomState.also { zoomLiveData = it }.observe(owner, zoomObserver)
            proCaps = readCaps(cam, extensionOn)
            videoReady = videoCapture != null
            photoReady = imageCapture != null
            bound = true
        } catch (e: Exception) {
            camera = null
            error = e.message ?: "Camera could not start"
        }
    }

    private fun buildAnalysis(
        aspect: AspectRatioStrategy, target: Size, analyzer: ImageAnalysis.Analyzer,
        format: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
    ): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(format)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(
                        ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor, analyzer)
        return analysis
    }

    private fun readCaps(cam: Camera, extensionOn: Boolean): ProCaps {
        val exposure = cam.cameraInfo.exposureState
        val evRange = if (exposure.isExposureCompensationSupported) {
            exposure.exposureCompensationRange.lower..exposure.exposureCompensationRange.upper
        } else {
            0..0
        }
        val evStep = exposure.exposureCompensationStep.toFloat()
        var manual = false
        var iso: IntRange? = null
        var shutter: LongRange? = null
        var minFocus = 0f
        var awb: List<Int> = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (!extensionOn) {
            runCatching {
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                manual = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    iso = it.lower..it.upper
                }
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    shutter = it.lower..it.upper
                }
                minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.let {
                    awb = it.toList()
                }
            }
        }
        return ProCaps(manual, iso, shutter, minFocus, awb, evRange, evStep)
    }

    /** Applies anti-banding and, in Pro mode, the manual values. */
    fun applyControls(pro: ProState?, antiBanding: AntiBanding) {
        val cam = camera ?: return
        runCatching { cam.cameraControl.setExposureCompensationIndex(pro?.ev ?: 0) }
        if (usingExtension) return
        runCatching {
            val options = CaptureRequestOptions.Builder()
            if (photoFrames != null) {
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                val fps = Range(30, 30)
                // Prefer a short frame period for handheld Photo when the HAL offers it.
                if (info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.contains(fps) == true
                ) {
                    options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                }
                if (info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                ) {
                    options.setCaptureRequestOption(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    )
                }
            }
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                when (antiBanding) {
                    AntiBanding.AUTO -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                    AntiBanding.HZ50 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
                    AntiBanding.HZ60 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
                    AntiBanding.OFF -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
                }
            )
            val caps = proCaps
            if (pro != null && caps != null) {
                if (pro.awb != CameraMetadata.CONTROL_AWB_MODE_AUTO && pro.awb in caps.awbModes) {
                    options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, pro.awb)
                }
                if (pro.focus != null && caps.minFocusDistance > 0f) {
                    options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, pro.focus)
                }
                if ((pro.iso != null || pro.shutterNs != null) && caps.manualSensor) {
                    options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, pro.iso ?: 400)
                    options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, pro.shutterNs ?: 20_000_000L)
                }
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        }
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun setTorch(on: Boolean) {
        runCatching { camera?.cameraControl?.enableTorch(on) }
    }

    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        runCatching { cam.cameraControl.setZoomRatio(ratio.coerceIn(minZoom, maxZoom)) }
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        runCatching {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        }
    }

    /** Takes one full-resolution JPEG into memory. */
    suspend fun capture(): Frame {
        val capture = imageCapture ?: throw IllegalStateException("Camera not ready")
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = try {
                        if (!cont.isActive) return
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        Frame(bytes, image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                        return
                    } finally {
                        image.close()
                    }
                    cont.resume(frame)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean, location: Location?, onEvent: (VideoRecordEvent) -> Unit): Recording? {
        val video = videoCapture ?: return null
        val builder = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(MediaStoreSaver.videoValues())
        if (location != null) builder.setLocation(location)
        var pending = video.output.prepareRecording(context, builder.build())
        if (withAudio) pending = pending.withAudioEnabled()
        return pending.start(mainExecutor) { event -> onEvent(event) }
    }

    fun release() {
        zoomLiveData?.removeObserver(zoomObserver)
        runCatching { provider?.unbindAll() }
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        photoFrames = null
        bound = false
    }
}
