package com.beyond.camera

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.beyond.camera.settings.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow

class BeyondApp : Application(), ImageLoaderFactory {
    val settings: AppSettings by lazy { AppSettings(this) }

    // Coil loader that can also draw video thumbnails.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}

val Context.appSettings: AppSettings
    get() = (applicationContext as BeyondApp).settings

/** Volume-key presses forwarded from the Activity to the camera screen. */
object VolumeKeyEvents {
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    @Volatile
    var cameraActive = false
}
