package com.samplesnatcher.audio

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight tempo estimate from PCM16 interleaved audio (downmixed to mono envelope).
 */
object BpmEstimator {

    data class Result(val bpm: Int, val confidence: Float)

    fun estimate(samples: ShortArray, sampleRate: Int, channelCount: Int): Result {
        val frames = samples.size / channelCount
        if (frames < 2048 || channelCount <= 0) return Result(0, 0f)

        val mono = FloatArray(frames)
        for (fi in 0 until frames) {
            var m = 0
            val base = fi * channelCount
            for (c in 0 until channelCount) m += samples[base + c].toInt()
            mono[fi] = (m / channelCount.toFloat()) / 32768f
        }

        val hop = 512
        val flux = ArrayList<Float>(frames / hop + 2)
        var prevMag = 0f
        var i = 0
        while (i + hop <= frames) {
            var e = 0f
            for (h in 0 until hop) {
                val x = mono[i + h]
                e += x * x
            }
            val env = sqrt(e / hop)
            flux.add(max(0f, env - prevMag))
            prevMag = env
            i += hop
        }
        if (flux.size < 8) return Result(0, 0f)

        val minBpm = 60
        val maxBpm = 200
        val minLag = (60f / maxBpm * sampleRate / hop).roundToInt().coerceAtLeast(2)
        val maxLag = (60f / minBpm * sampleRate / hop).roundToInt().coerceAtMost(flux.size - 1)

        var bestLag = minLag
        var bestCorr = 0f
        for (lag in minLag..maxLag) {
            var c = 0f
            val count = flux.size - lag
            if (count <= 0) continue
            for (t in 0 until flux.size - lag) {
                c += flux[t] * flux[t + lag]
            }
            c /= count
            if (c > bestCorr) {
                bestCorr = c
                bestLag = lag
            }
        }
        if (bestCorr <= 1e-8f) return Result(0, 0f)

        val bpm = (60f * sampleRate / hop / bestLag).roundToInt().coerceIn(minBpm, maxBpm)

        val neighborCorr = listOf(bestLag - 1, bestLag + 1)
            .filter { it in minLag..maxLag && it != bestLag }
            .maxOfOrNull { lag ->
                var c = 0f
                val cnt = flux.size - lag
                if (cnt <= 0) return@maxOfOrNull 0f
                for (t in 0 until flux.size - lag) c += flux[t] * flux[t + lag]
                c / cnt
            } ?: 0f

        val ratio = bestCorr / (bestCorr + neighborCorr + 1e-6f)
        val confidence = ratio.coerceIn(0f, 1f)

        return Result(bpm, confidence)
    }
}
