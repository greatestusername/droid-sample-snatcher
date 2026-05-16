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

    /**
     * Playhead along clip when playback begins at [startFraction] of the clip (e.g. 0.75 to hear
     * tail → head loop wrap). [startFraction] is 0 for normal preview from the beginning.
     */
    fun fractionWithStartOffset(
        elapsedMs: Long,
        frameCount: Int,
        sampleRate: Int,
        loop: Boolean,
        startFraction: Float,
    ): Float {
        if (frameCount <= 0 || sampleRate <= 0) return 0f
        val start = startFraction.coerceIn(0f, 1f)
        val startFrame = (start * frameCount).toInt().coerceIn(0, frameCount - 1)
        val elapsedFrames = (elapsedMs * sampleRate / 1000).toInt().coerceAtLeast(0)
        val pos = if (loop) {
            (startFrame + elapsedFrames) % frameCount
        } else {
            (startFrame + elapsedFrames).coerceAtMost(frameCount - 1)
        }
        return pos.toFloat() / frameCount.toFloat()
    }
}
