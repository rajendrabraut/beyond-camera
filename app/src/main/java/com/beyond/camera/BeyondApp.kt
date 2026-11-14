package com.beyond.camera

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.beyond.camera.settings.AppSettings

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
internal object VolumeKeyEvents {
    // Activity key dispatch and Compose both run on the main thread.
    var onPress: ((Int) -> Unit)? = null
}
