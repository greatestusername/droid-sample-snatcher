package com.samplesnatcher.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmNormalizeTest {

    @Test
    fun scalesQuietSignalToTargetPeak() {
        val pcm = shortArrayOf(1000, -500, 250, -250)
        val r = PcmNormalize.peakNormalizeInPlace(pcm)
        assertEquals(PcmNormalize.Result.APPLIED, r)
        var maxAbs = 0
        for (s in pcm) {
            maxAbs = maxOf(maxAbs, abs(s.toInt()))
        }
        assertEquals(PcmNormalize.TARGET_PEAK, maxAbs)
    }

    @Test
    fun silentReturnsSilent() {
        val pcm = ShortArray(4)
        assertEquals(PcmNormalize.Result.SILENT, PcmNormalize.peakNormalizeInPlace(pcm))
    }

    @Test
    fun loudSignalScaledDown() {
        val pcm = shortArrayOf(30_000, -28_000)
        PcmNormalize.peakNormalizeInPlace(pcm)
        var maxAbs = 0
        for (s in pcm) {
            maxAbs = maxOf(maxAbs, abs(s.toInt()))
        }
        assertTrue(maxAbs <= PcmNormalize.TARGET_PEAK)
        assertEquals(PcmNormalize.TARGET_PEAK, maxAbs)
    }
}
