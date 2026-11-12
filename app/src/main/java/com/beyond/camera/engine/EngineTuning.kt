package com.beyond.camera.engine

import com.beyond.camera.engine.BurstEngine.Kind
import com.beyond.camera.engine.BurstEngine.Params

/** Per-mode settings for [BurstEngine]. Kept together so the look is consistent across modes. */
object EngineTuning {
    /** Everyday photo: gentle shadow lift, held highlights, light colour-noise cleanup. */
    val photo = Params(Kind.DENOISE, shadows = 0.22f, highlights = 0.3f, chroma = 0.45f, sharpen = 0.25f, saturation = 1.04f)

    /** Night: 3 merged frames, strongest cleanup, highlights compressed rather than clipped. */
    val night = Params(Kind.DENOISE, shadows = 0.4f, highlights = 0.45f, chroma = 0.85f, sharpen = 0.3f, saturation = 1.08f, gain = 1.3f)

    /** HDR: stronger local tone mapping on a single frame. */
    val hdr = Params(Kind.DENOISE, shadows = 0.48f, highlights = 0.62f, chroma = 0.5f, sharpen = 0.3f, saturation = 1.08f)

    /** Portrait: finished before the background blur; colour-noise cleanup kept moderate for skin texture. */
    val portrait = Params(Kind.DENOISE, shadows = 0.25f, highlights = 0.3f, chroma = 0.5f, sharpen = 0.25f, saturation = 1.04f)

    /** Zoom at 2x and above: stronger noise-aware sharpening on a single frame. */
    val zoom = Params(Kind.DENOISE, shadows = 0.18f, highlights = 0.25f, chroma = 0.6f, sharpen = 0.6f, saturation = 1.04f)
}
