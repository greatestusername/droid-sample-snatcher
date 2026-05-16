package com.samplesnatcher.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Peak-normalizes interleaved S16 PCM in place. */
object PcmNormalize {

    /** Target peak magnitude (just below full scale to avoid inter-sample overs on export). */
    const val TARGET_PEAK = 32_000

    enum class Result {
        /** Gain applied. */
        APPLIED,
        /** All samples were zero. */
        SILENT,
        /** No samples. */
        EMPTY,
    }

    fun peakNormalizeInPlace(interleaved: ShortArray): Result {
        if (interleaved.isEmpty()) return Result.EMPTY
        var maxAbs = 0
        for (s in interleaved) {
            maxAbs = maxOf(maxAbs, abs(s.toInt()))
        }
        if (maxAbs == 0) return Result.SILENT
        val scale = TARGET_PEAK.toDouble() / maxAbs.toDouble()
        for (i in interleaved.indices) {
            val v = (interleaved[i] * scale).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            interleaved[i] = v.toShort()
        }
        return Result.APPLIED
    }
}
