package com.samplesnatcher.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Stream preview of interleaved S16 PCM through [AudioTrack] (one-shot or loop).
 *
 * **Playhead** uses wall-clock time vs clip duration ([PreviewPlayheadMath]) — independent of
 * [AudioTrack.getPlaybackHeadPosition], which is unreliable for MODE_STREAM on many devices.
 * Call [tickPlayhead] from the main thread (~60 Hz) while [isPlaying] is true.
 *
 * **One-shot** waits after the last [write] until [playbackHeadPosition] reaches the end (audible
 * drain) before releasing the track.
 */
class PreviewPlayer(
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private val playing = AtomicBoolean(false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadFraction = MutableStateFlow(0f)
    val playheadFraction: StateFlow<Float> = _playheadFraction.asStateFlow()

    @Volatile
    private var playStartUptimeMs: Long = 0L

    @Volatile
    private var clipFrameCount: Int = 0

    @Volatile
    private var clipLoop: Boolean = false

    @Volatile
    private var clipStartFraction: Float = 0f

    /** Update playhead from wall clock; safe to call every frame from the main thread. */
    fun tickPlayhead() {
        if (!_isPlaying.value || clipFrameCount <= 0) return
        val elapsed = SystemClock.uptimeMillis() - playStartUptimeMs
        _playheadFraction.value = PreviewPlayheadMath.fractionWithStartOffset(
            elapsed,
            clipFrameCount,
            sampleRate,
            clipLoop,
            clipStartFraction,
        )
    }

    /**
     * @param startFrameFraction 0 = from clip start; 0.75 = from ¾ through end then wrap when looping.
     * @return true when playback started, false when track initialization failed.
     */
    fun play(
        interleaved: ShortArray,
        loop: Boolean,
        startFrameFraction: Float = 0f,
    ): Boolean {
        stop()
        if (interleaved.isEmpty()) return false
        val fc = interleaved.size / channelCount
        if (fc <= 0) return false

        val outCh = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            outCh,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val quarterSecondBytes = (sampleRate * channelCount * 2 / 4).coerceAtLeast(minBuf)
        val targetBufferBytes = max(minBuf * 2, quarterSecondBytes).coerceAtMost(1_048_576)
        val at = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(outCh)
                        .build(),
                )
                .setBufferSizeInBytes(targetBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: UnsupportedOperationException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            try {
                at.release()
            } catch (_: Exception) {
            }
            return false
        }
        val startFrac = startFrameFraction.coerceIn(0f, 1f)
        val startFrame = (fc * startFrac).toInt().coerceIn(0, fc - 1)
        val startSample = startFrame * channelCount

        track = at
        clipFrameCount = fc
        clipLoop = loop
        clipStartFraction = startFrac
        _playheadFraction.value = startFrac
        playing.set(true)
        _isPlaying.value = true
        at.play()
        playStartUptimeMs = SystemClock.uptimeMillis()
        worker = Thread({
            val t = at
            try {
                var writeFrom = startSample
                do {
                    var offset = writeFrom
                    while (offset < interleaved.size && playing.get()) {
                        val w = t.write(interleaved, offset, interleaved.size - offset)
                        if (w < 0) break
                        offset += w
                    }
                    if (!playing.get()) break
                    writeFrom = 0

                    if (!loop) {
                        val tailFrames = fc - startFrame
                        val msPadding = 400L
                        val estMs = max(1L, tailFrames * 1000L / sampleRate + msPadding)
                        val deadline = SystemClock.uptimeMillis() + estMs
                        while (playing.get() && SystemClock.uptimeMillis() < deadline) {
                            val h = try {
                                t.playbackHeadPosition
                            } catch (_: Exception) {
                                break
                            }
                            if (h >= fc - 2) break
                            Thread.sleep(16)
                        }
                        break
                    }
                } while (playing.get() && !Thread.currentThread().isInterrupted)
            } finally {
                try {
                    t.pause()
                    t.flush()
                    t.stop()
                    t.release()
                } catch (_: Exception) {
                }
                playing.set(false)
                clipFrameCount = 0
                clipStartFraction = 0f
                _isPlaying.value = false
                _playheadFraction.value = 0f
            }
        }, "preview-audio").also { it.start() }
        return true
    }

    fun stop() {
        playing.set(false)
        clipFrameCount = 0
        clipStartFraction = 0f
        _isPlaying.value = false
        _playheadFraction.value = 0f
        track?.let { t ->
            try {
                t.pause()
                t.flush()
            } catch (_: Exception) {
            }
        }
        worker?.interrupt()
        worker?.join(2500)
        worker = null
        track = null
    }
}
