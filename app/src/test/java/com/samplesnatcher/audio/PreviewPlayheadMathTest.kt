package com.samplesnatcher.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlayheadMathTest {

    @Test
    fun clipDuration_oneSecond_at48k() {
        val d = PreviewPlayheadMath.clipDurationMs(48_000, 48_000)
        assertEquals(1000L, d)
    }

    @Test
    fun oneShot_halfElapsed() {
        val f = PreviewPlayheadMath.fraction(500L, 48_000, 48_000, loop = false)
        assertEquals(0.5f, f, 0.001f)
    }

    @Test
    fun oneShot_clampsPastEnd() {
        val f = PreviewPlayheadMath.fraction(10_000L, 48_000, 48_000, loop = false)
        assertEquals(1f, f, 0.001f)
    }

    @Test
    fun loop_wraps() {
        val d = PreviewPlayheadMath.clipDurationMs(1000, 48_000) // ~20ms
        assertTrue(d > 0L)
        val f0 = PreviewPlayheadMath.fraction(0L, 1000, 48_000, loop = true)
        val fHalf = PreviewPlayheadMath.fraction(d / 2, 1000, 48_000, loop = true)
        val fWrap = PreviewPlayheadMath.fraction(d + d / 2, 1000, 48_000, loop = true)
        assertEquals(0f, f0, 0.02f)
        assertEquals(0.5f, fHalf, 0.02f)
        assertEquals(0.5f, fWrap, 0.02f)
    }
}
