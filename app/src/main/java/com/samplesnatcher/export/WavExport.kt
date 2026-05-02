package com.samplesnatcher.export

import android.content.Context
import android.net.Uri
import com.samplesnatcher.audio.WavWriter
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object WavExport {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun sanitizeLabel(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9._-]+"), "_").trim('_').take(48)
            .ifEmpty { "sample" }

    /**
     * Default filename shown in the system “save” dialog: sanitized label + optional BPM segment + date + `.wav`.
     */
    fun suggestedDisplayName(userLabel: String, includeBpmInName: Boolean, bpm: Int?): String {
        val safe = sanitizeLabel(userLabel)
        val dateStr = LocalDate.now().format(DATE_FMT)
        return buildFilename(safe, dateStr, includeBpmInName, bpm)
    }

    /**
     * Writes WAV bytes to a URI obtained from [androidx.activity.result.contract.ActivityResultContracts.CreateDocument].
     */
    fun writeWavToUri(
        context: Context,
        uri: Uri,
        interleavedS16: ShortArray,
        sampleRate: Int,
        channelCount: Int,
    ): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                WavWriter.writePcm16BitLeWav(out, sampleRate, channelCount, interleavedS16)
                out.flush()
            } != null
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    internal fun buildFilename(
        safeLabel: String,
        yyyymmdd: String,
        includeBpm: Boolean,
        bpm: Int?,
    ): String {
        return if (includeBpm && bpm != null && bpm in 40..300) {
            "${safeLabel}_BPM${bpm}_$yyyymmdd.wav"
        } else {
            "${safeLabel}_$yyyymmdd.wav"
        }
    }
}
