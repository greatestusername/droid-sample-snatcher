package com.samplesnatcher.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SampleSnapping {

    private const val ZERO_SEARCH_DEFAULT = 480

    /**
     * Snap a frame index to the nearest zero-crossing within ±[maxSearchFrames].
     * Uses sign change on mixed mono (L+R) or first channel.
     */
    fun snapZeroCrossing(
        buffer: PcmRingBuffer,
        channelCount: Int,
        frameIndex: Long,
        maxSearchFrames: Int = ZERO_SEARCH_DEFAULT,
    ): Long {
        val lo = max(buffer.oldestFrameIndex, frameIndex - maxSearchFrames)
        val hi = min(buffer.newestFrameIndex - 1, frameIndex + maxSearchFrames)
        if (hi < lo) return frameIndex

        val tmp = ShortArray(channelCount)
        fun monoAt(f: Long): Int {
            buffer.readFrameInterleaved(f, tmp, 0)
            var m = 0
            for (c in 0 until channelCount) m += tmp[c].toInt()
            return m / channelCount.coerceAtLeast(1)
        }

        val targetSign = sign(monoAt(frameIndex.coerceIn(lo, hi)))
        var best = frameIndex
        var bestDist = Long.MAX_VALUE
        for (f in lo..hi) {
            val m0 = monoAt(f)
            val m1 = monoAt(f + 1).coerceAtLeast(Int.MIN_VALUE / 4)
            val crossed = sign(m0) != sign(m1) || m0 == 0
            if (crossed) {
                val d = abs(f - frameIndex)
                if (d < bestDist) {
                    bestDist = d
                    best = f
                }
            }
        }
        return best
    }

    /**
     * Snap to nearest transient / onset in ±[maxSearchFrames] using rectified inter-frame RMS delta
     * (simple spectral-flux style cue). Adapts block size to the searchable span so short windows
     * still produce candidates (the old fixed block often yielded empty flux → no movement).
     */
    fun snapTransient(
        buffer: PcmRingBuffer,
        channelCount: Int,
        frameIndex: Long,
        maxSearchFrames: Int = 2048,
        blockSize: Int = 256,
    ): Long {
        val lo = max(buffer.oldestFrameIndex, frameIndex - maxSearchFrames)
        val hi = min(buffer.newestFrameIndex - 1, frameIndex + maxSearchFrames)
        if (hi <= lo) return frameIndex

        val span = (hi - lo).toInt().coerceAtLeast(1)
        // Need at least two staggered blocks to estimate onset; shrink block for narrow windows.
        val bs = min(blockSize, max(64, span / 6))
        if (span < bs + 16) return frameIndex

        val tmp = ShortArray(channelCount)
        fun energyAt(start: Long, len: Int): Float {
            var e = 0f
            var n = 0
            val last = min(buffer.newestFrameIndex - 1, start + len - 1)
            var f = start
            while (f <= last && n < len) {
                buffer.readFrameInterleaved(f, tmp, 0)
                for (c in 0 until channelCount) {
                    val x = tmp[c] / 32768f
                    e += x * x
                }
                n++
                f++
            }
            return if (n == 0) 0f else e / n
        }

        val stride = max(1, bs / 8)
        val flux = mutableListOf<Pair<Long, Float>>()
        var f = lo
        var prevE = energyAt(f, bs)
        f += stride
        while (f <= hi - bs) {
            val e = energyAt(f, bs)
            flux.add(f to max(0f, e - prevE))
            prevE = e
            f += stride
        }

        val target = frameIndex
        if (flux.isEmpty()) {
            return transientFallbackEnergyDelta(buffer, channelCount, target, lo, hi, bs, tmp)
        }

        var bestF = frameIndex
        var bestScore = Float.NEGATIVE_INFINITY
        val spanF = span.toFloat().coerceAtLeast(1f)
        for ((pos, sc) in flux) {
            val d = abs(pos - target).toFloat()
            val proximity = 1f - (d / spanF).coerceIn(0f, 1f)
            val combined = sc + proximity * (sc.coerceAtLeast(1e-6f) * 0.05f)
            if (combined > bestScore) {
                bestScore = combined
                bestF = pos
            }
        }
        if (bestScore <= 0f && flux.none { it.second > 1e-9f }) {
            return transientFallbackEnergyDelta(buffer, channelCount, target, lo, hi, bs, tmp)
        }
        return bestF
    }

    /** Max positive RMS delta between consecutive blocks — works when flux grid misses sharp onsets. */
    private fun transientFallbackEnergyDelta(
        buffer: PcmRingBuffer,
        channelCount: Int,
        frameIndex: Long,
        lo: Long,
        hi: Long,
        bs: Int,
        tmp: ShortArray,
    ): Long {
        fun energyAt(start: Long, len: Int): Float {
            var e = 0f
            var n = 0
            val last = min(buffer.newestFrameIndex - 1, start + len - 1)
            var f = start
            while (f <= last && n < len) {
                buffer.readFrameInterleaved(f, tmp, 0)
                for (c in 0 until channelCount) {
                    val x = tmp[c] / 32768f
                    e += x * x
                }
                n++
                f++
            }
            return if (n == 0) 0f else e / n
        }

        val step = max(32, bs / 4)
        var bestPos = frameIndex
        var bestDelta = -1f
        var f = lo
        while (f <= hi - bs - bs) {
            val e0 = energyAt(f, bs)
            val e1 = energyAt(f + bs, bs)
            val delta = max(0f, e1 - e0)
            val d = abs((f + bs) - frameIndex).toFloat()
            val combined = delta - d * 1e-6f
            if (combined > bestDelta) {
                bestDelta = combined
                bestPos = f + bs
            }
            f += step
        }
        return bestPos.coerceIn(lo, hi)
    }

    private fun sign(v: Int): Int = when {
        v > 0 -> 1
        v < 0 -> -1
        else -> 0
    }
}
