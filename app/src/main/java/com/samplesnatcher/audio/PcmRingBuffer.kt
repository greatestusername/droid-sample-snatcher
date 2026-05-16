package com.samplesnatcher.audio

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Fixed-size multi-channel interleaved PCM S16LE ring. Frame index is monotonically increasing.
 * Reads used for visualization/export are synchronized against writes from the capture thread.
 */
class PcmRingBuffer(
    val capacityFrames: Int,
    val channelCount: Int,
) {
    private val bufferLock = Any()
    private val bytesPerFrame = channelCount * BYTES_PER_SAMPLE
    private val buffer = ShortArray(capacityFrames * channelCount)

    @Volatile
    private var framesCommitted: Long = 0L

    val sampleRate: Int
        get() = _sampleRate
    private var _sampleRate: Int = 48_000

    fun setSampleRate(rate: Int) {
        _sampleRate = rate
    }

    /** Oldest frame index still available; increases as ring advances. */
    val oldestFrameIndex: Long
        get() {
            val stored = min(framesCommitted.toDouble(), capacityFrames.toDouble()).toLong()
            return framesCommitted - stored
        }

    val newestFrameIndex: Long
        get() = framesCommitted

    fun frameCountAvailable(): Long = newestFrameIndex - oldestFrameIndex

    /**
     * Single consistent snapshot of the live frame window (for UI math). Prefer this over
     * separate calls to [oldestFrameIndex] / [newestFrameIndex] / [frameCountAvailable], which can
     * tear across threads and break range helpers like [kotlin.ranges.coerceIn].
     */
    fun getFrameWindow(): FrameWindow {
        synchronized(bufferLock) {
            val newest = framesCommitted
            val stored = min(newest.toDouble(), capacityFrames.toDouble()).toLong()
            val oldest = newest - stored
            return FrameWindow(oldest = oldest, newest = newest)
        }
    }

    data class FrameWindow(
        val oldest: Long,
        val newest: Long,
    ) {
        val available: Long
            get() = (newest - oldest).coerceAtLeast(0)
    }

    fun writeInterleavedS16(source: ShortArray, frameOffset: Int, frameCount: Int) {
        if (frameCount <= 0) return
        require(source.size >= frameOffset + frameCount * channelCount)
        synchronized(bufferLock) {
            for (f in 0 until frameCount) {
                val dstRing = (framesCommitted % capacityFrames).toInt()
                val dstBase = dstRing * channelCount
                val srcBase = (frameOffset + f * channelCount)
                for (c in 0 until channelCount) {
                    buffer[dstBase + c] = source[srcBase + c]
                }
                framesCommitted++
            }
        }
    }

    fun writeFromByteArrayInterleavedS16Le(bytes: ByteArray, byteLength: Int) {
        val frameCount = byteLength / bytesPerFrame
        var byteIdx = 0
        synchronized(bufferLock) {
            repeat(frameCount) {
                val dstRing = (framesCommitted % capacityFrames).toInt()
                val dstBase = dstRing * channelCount
                for (c in 0 until channelCount) {
                    val lo = bytes[byteIdx].toInt() and 0xff
                    val hi = bytes[byteIdx + 1].toInt() and 0xff
                    val s = (hi shl 8 or lo).toShort()
                    buffer[dstBase + c] = s
                    byteIdx += 2
                }
                framesCommitted++
            }
        }
    }

    fun readFrameInterleaved(absoluteFrame: Long, dst: ShortArray, dstOffset: Int) {
        require(dst.size >= dstOffset + channelCount)
        synchronized(bufferLock) {
            val newest = framesCommitted
            val stored = min(newest.toDouble(), capacityFrames.toDouble()).toLong()
            val oldest = newest - stored
            if (absoluteFrame < oldest || absoluteFrame >= newest) {
                Arrays.fill(dst, dstOffset, dstOffset + channelCount, 0)
                return
            }
            val ringIdx = (absoluteFrame % capacityFrames).toInt()
            val base = ringIdx * channelCount
            for (c in 0 until channelCount) {
                dst[dstOffset + c] = buffer[base + c]
            }
        }
    }

    fun copyRangeInterleaved(startFrame: Long, endFrameExclusive: Long): ShortArray {
        synchronized(bufferLock) {
            val newest = framesCommitted
            val stored = min(newest.toDouble(), capacityFrames.toDouble()).toLong()
            val oldest = newest - stored
            val start = maxOf(startFrame, oldest)
            val end = minOf(endFrameExclusive, newest)
            if (end <= start) return ShortArray(0)
            val outFrames = (end - start).toInt()
            val out = ShortArray(outFrames * channelCount)
            var o = 0
            var f = start
            while (f < end) {
                val ring = (f % capacityFrames).toInt()
                val base = ring * channelCount
                for (c in 0 until channelCount) {
                    out[o++] = buffer[base + c]
                }
                f++
            }
            return out
        }
    }

    /**
     * Overwrites [startFrame]..[endFrameExclusive) in the live window with [interleaved] PCM.
     * [interleaved] length must equal frame count × [channelCount].
     */
    fun replaceRangeInterleaved(
        startFrame: Long,
        endFrameExclusive: Long,
        interleaved: ShortArray,
    ): Boolean {
        synchronized(bufferLock) {
            val newest = framesCommitted
            val stored = min(newest.toDouble(), capacityFrames.toDouble()).toLong()
            val oldest = newest - stored
            val start = maxOf(startFrame, oldest)
            val end = minOf(endFrameExclusive, newest)
            if (end <= start) return false
            val frameCount = (end - start).toInt()
            if (interleaved.size != frameCount * channelCount) return false
            var o = 0
            var f = start
            while (f < end) {
                val ring = (f % capacityFrames).toInt()
                val base = ring * channelCount
                for (c in 0 until channelCount) {
                    buffer[base + c] = interleaved[o++]
                }
                f++
            }
            return true
        }
    }

    /**
     * Peak envelope for visualization: per-bin max absolute sample (mixed channels).
     * [binCount] columns across the whole rolling buffer (valid window).
     */
    fun computePeakEnvelope(binCount: Int): FloatArray {
        if (binCount <= 0) return FloatArray(0)
        synchronized(bufferLock) {
            val newestSnapshot = framesCommitted
            val stored = min(newestSnapshot.toDouble(), capacityFrames.toDouble()).toLong()
            val oldestSnapshot = newestSnapshot - stored
            val avail = (newestSnapshot - oldestSnapshot).toInt().coerceAtLeast(0)
            if (avail == 0) return FloatArray(binCount) { 0f }
            val peaks = FloatArray(binCount)
            val framesPerBin = avail / binCount.toFloat()
            for (b in 0 until binCount) {
                var startF = (oldestSnapshot + floor(b * framesPerBin).toLong())
                    .coerceIn(oldestSnapshot, newestSnapshot - 1)
                var endF = (oldestSnapshot + floor((b + 1) * framesPerBin).toLong())
                    .coerceIn(oldestSnapshot + 1, newestSnapshot)
                if (endF <= startF) {
                    endF = (startF + 1).coerceAtMost(newestSnapshot)
                }
                if (endF <= startF) {
                    peaks[b] = 0f
                    continue
                }
                var maxAbs = 0
                var f = startF
                var steps = 0
                // Safety cap: never iterate more than the live window (prevents runaway if indices drift)
                val maxSteps = avail + 16
                while (f < endF && steps < maxSteps) {
                    val ring = (f % capacityFrames).toInt()
                    val base = ring * channelCount
                    for (c in 0 until channelCount) {
                        maxAbs = maxOf(maxAbs, abs(buffer[base + c].toInt()))
                    }
                    f++
                    steps++
                }
                peaks[b] = (maxAbs / 32768f).coerceIn(0f, 1f)
            }
            return peaks
        }
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
    }
}
