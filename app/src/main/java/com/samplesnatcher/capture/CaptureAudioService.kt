package com.samplesnatcher.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.samplesnatcher.MainActivity
import com.samplesnatcher.R
import com.samplesnatcher.audio.PcmRingBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class CaptureAudioService : Service() {

    private val binder = LocalBinder()
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private val captureRunning = AtomicBoolean(false)
    /** When true, AudioRecord is still read (driver backlog cleared) but PCM is not written — ring stays frozen (e.g. editor open). */
    private val capturePaused = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private val stopInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCaptureSetup: Runnable? = null

    private lateinit var ring: PcmRingBuffer

    /** Matches the device playback mix rate (often 48 kHz; many TVs use 44.1 kHz). */
    private fun mixerOutputSampleRateHz(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val raw = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: return 48_000
        return raw.toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: 48_000
    }

    private fun newRingBuffer(): PcmRingBuffer {
        val seconds = AppBufferConfig.readBufferSeconds(this)
        val hz = mixerOutputSampleRateHz()
        return PcmRingBuffer(
            capacityFrames = hz * seconds,
            channelCount = 2,
        ).also { it.setSampleRate(hz) }
    }

    private val _state = MutableStateFlow(CaptureUiState())
    val captureState: StateFlow<CaptureUiState> = _state.asStateFlow()

    data class CaptureUiState(
        val isRunning: Boolean = false,
        val lastError: String? = null,
    )

    inner class LocalBinder : Binder() {
        fun getService(): CaptureAudioService = this@CaptureAudioService
    }

    override fun onCreate() {
        super.onCreate()
        ring = newRingBuffer()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getRingBuffer(): PcmRingBuffer = ring

    fun currentBufferSeconds(): Int = AppBufferConfig.readBufferSeconds(this)

    /** Pause writing new samples to the ring while keeping capture alive (discard PCM until resumed). */
    fun setCapturePaused(paused: Boolean) {
        capturePaused.set(paused)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureInternal(releaseForegroundNotification = true)
                stopSelf()
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data != null && code != 0) {
                    startCapturePipeline(code, data)
                } else {
                    _state.value = _state.value.copy(lastError = "Missing projection grant")
                }
            }
        }
        // Do not auto-restart with a stale MediaProjection intent after a crash — that triggers
        // SecurityException on startForeground (grant is one-shot per process) on some OEMs (e.g. Samsung).
        return START_NOT_STICKY
    }

    private fun startCapturePipeline(resultCode: Int, resultData: Intent) {
        // Must enter foreground immediately after startForegroundService(); also do not call
        // stopForeground before this — Android will crash the app / kill the service if the
        // foreground requirement isn't satisfied quickly enough.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        stopCaptureInternal(releaseForegroundNotification = false)
        ring = newRingBuffer()
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(resultCode, resultData)
        if (mp == null) {
            _state.value = _state.value.copy(lastError = "Could not start capture (projection is null)")
            stopForegroundCompat()
            stopSelf()
            return
        }
        mediaProjection = mp
        mp.registerCallback(projectionCallback, null)
        val projection = mp

        // Keep matchers minimal: some devices (notably Samsung) fail AudioPolicy registration
        // ("could not register audio policy") when too many usages are listed.
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val sampleRate = mixerOutputSampleRateHz()
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf =
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf <= 0) {
            _state.value = _state.value.copy(lastError = "AudioRecord min buffer invalid")
            releaseProjection()
            stopForegroundCompat()
            stopSelf()
            return
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        pendingCaptureSetup?.let { mainHandler.removeCallbacks(it) }
        val setup = Runnable {
            pendingCaptureSetup = null
            if (stopInProgress.get() || mediaProjection !== projection) return@Runnable
            val record = try {
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (e: UnsupportedOperationException) {
                _state.value = _state.value.copy(
                    lastError = "Playback capture not available (${e.message ?: "audio policy"})",
                )
                releaseProjection()
                stopForegroundCompat()
                stopSelf()
                return@Runnable
            } catch (e: SecurityException) {
                _state.value = _state.value.copy(lastError = "Playback capture denied (${e.message})")
                releaseProjection()
                stopForegroundCompat()
                stopSelf()
                return@Runnable
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                _state.value = _state.value.copy(lastError = "AudioRecord init failed (playback capture unavailable?)")
                releaseProjection()
                stopForegroundCompat()
                stopSelf()
                return@Runnable
            }

            ring.setSampleRate(sampleRate)
            audioRecord = record
            captureRunning.set(true)

            captureThread = Thread(
                {
                    val bytes = ByteArray(minBuf)
                    val ar = audioRecord
                    if (ar != null) {
                        ar.startRecording()
                        while (captureRunning.get()) {
                            val n = ar.read(bytes, 0, bytes.size)
                            if (n > 0) {
                                if (!capturePaused.get()) {
                                    ring.writeFromByteArrayInterleavedS16Le(bytes, n)
                                }
                            } else if (n < 0) {
                                _state.value = _state.value.copy(lastError = "read error $n")
                                break
                            }
                        }
                        try {
                            ar.stop()
                        } catch (_: Exception) {
                        }
                    }
                },
                "sample-snatcher-capture",
            ).also { it.start() }

            _state.value = CaptureUiState(isRunning = true, lastError = null)
        }
        pendingCaptureSetup = setup
        mainHandler.postDelayed(setup, AUDIO_POLICY_SETUP_DELAY_MS)
    }

    /**
     * @param releaseForegroundNotification When false, only tears down capture (used while
     *   restarting so we never briefly leave foreground — that used to crash the process).
     */
    private fun stopCaptureInternal(releaseForegroundNotification: Boolean = true) {
        if (!stopInProgress.compareAndSet(false, true)) return
        try {
            pendingCaptureSetup?.let { mainHandler.removeCallbacks(it) }
            pendingCaptureSetup = null
            captureRunning.set(false)
            capturePaused.set(false)
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = null
            captureThread?.join(2000)
            captureThread = null
            releaseProjection()
            if (releaseForegroundNotification) {
                stopForegroundCompat()
            }
            _state.value = CaptureUiState(isRunning = false, lastError = _state.value.lastError)
        } finally {
            stopInProgress.set(false)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // System revoked projection; tear down without re-entering from releaseProjection.
            stopCaptureInternal(releaseForegroundNotification = true)
        }
    }

    private fun releaseProjection() {
        val mp = mediaProjection ?: return
        mediaProjection = null
        try {
            mp.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            mp.stop()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopCaptureInternal()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(0, getString(R.string.capture_notification_action_stop), stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.samplesnatcher.CAPTURE_START"
        const val ACTION_STOP = "com.samplesnatcher.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "sample_snatcher_capture"
        private const val NOTIFICATION_ID = 1001
        /** Brief delay after MediaProjection grant so AudioPolicy can register (avoids OEM race). */
        private const val AUDIO_POLICY_SETUP_DELAY_MS = 200L
    }
}

/** Buffer length 60–90 s, default 75 (from product spec). */
object AppBufferConfig {
    private const val PREF = "snatcher_prefs"
    private const val KEY = "buffer_seconds"
    private const val DEFAULT = 75
    private const val MIN = 60
    private const val MAX = 90

    fun readBufferSeconds(ctx: android.content.Context): Int {
        val p = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
        return p.getInt(KEY, DEFAULT).coerceIn(MIN, MAX)
    }

    fun writeBufferSeconds(ctx: android.content.Context, seconds: Int) {
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY, seconds.coerceIn(MIN, MAX))
            .apply()
    }
}
