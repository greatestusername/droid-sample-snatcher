package com.samplesnatcher.audio

/**
 * Maps wall-clock elapsed time to a 0…1 playhead along a PCM clip of [frameCount] frames at [sampleRate].
 * Does not rely on [android.media.AudioTrack] APIs (which are unreliable for STREAM mode on some devices).
 */
object PreviewPlayheadMath {

    fun clipDurationMs(frameCount: Int, sampleRate: Int): Long {
        if (frameCount <= 0 || sampleRate <= 0) return 0L
        return frameCount * 1000L / sampleRate
    }

    /**
     * @param elapsedMs time since [android.media.AudioTrack.play] (or equivalent start).
     * @param loop if true, wraps within one clip length; if false, clamps to 1.
     */
    fun fraction(elapsedMs: Long, frameCount: Int, sampleRate: Int, loop: Boolean): Float {
        if (frameCount <= 0 || sampleRate <= 0) return 0f
        val dMs = clipDurationMs(frameCount, sampleRate)
        if (dMs <= 0L) return 0f
        return if (loop) {
            val e = elapsedMs.mod(dMs)
            e.toFloat() / dMs.toFloat()
        } else {
            (elapsedMs.toFloat() / dMs.toFloat()).coerceIn(0f, 1f)
        }
    }
}
