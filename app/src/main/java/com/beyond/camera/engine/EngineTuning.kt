package com.beyond.camera.engine

import com.beyond.camera.engine.BurstEngine.Kind
import com.beyond.camera.engine.BurstEngine.Params

/** Per-mode settings for [BurstEngine]. Kept together so the look is consistent across modes. */
object EngineTuning {
    /** At or above this ISO, Photo mode quietly merges extra frames. */
    const val LOW_LIGHT_ISO = 800

    /** Everyday photo: gentle shadow lift, held highlights, light colour-noise cleanup. */
    val photo = Params(Kind.DENOISE, shadows = 0.22f, highlights = 0.3f, chroma = 0.45f, sharpen = 0.25f, saturation = 1.04f)

    /** Photo mode in dim light: 3 merged frames, stronger cleanup. */
    val lowLight = Params(Kind.DENOISE, shadows = 0.3f, highlights = 0.3f, chroma = 0.7f, sharpen = 0.3f, saturation = 1.05f)

    /** Night: 5 merged brightened frames, strongest cleanup, highlights compressed rather than clipped. */
    val night = Params(Kind.DENOISE, shadows = 0.4f, highlights = 0.45f, chroma = 0.85f, sharpen = 0.3f, saturation = 1.08f, gain = 1.3f)

    /** HDR: normal + darker + brighter frames merged in linear light. */
    val hdr = Params(Kind.HDR, shadows = 0.3f, highlights = 0.5f, chroma = 0.5f, sharpen = 0.3f, saturation = 1.08f)

    /** Portrait: finished before the background blur; colour-noise cleanup kept moderate for skin texture. */
    val portrait = Params(Kind.DENOISE, shadows = 0.25f, highlights = 0.3f, chroma = 0.5f, sharpen = 0.25f, saturation = 1.04f)

    /** Zoom at 2x and above: 4 merged frames and stronger (noise-aware) sharpening. */
    val zoom = Params(Kind.DENOISE, shadows = 0.18f, highlights = 0.25f, chroma = 0.6f, sharpen = 0.6f, saturation = 1.04f)
}
