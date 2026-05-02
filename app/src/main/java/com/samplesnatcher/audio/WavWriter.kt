package com.samplesnatcher.audio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {

    fun writePcm16BitLeWav(
        out: OutputStream,
        sampleRate: Int,
        channelCount: Int,
        interleavedS16: ShortArray,
    ) {
        val byteRate = sampleRate * channelCount * 2
        val blockAlign = channelCount * 2
        val dataBytes = interleavedS16.size * 2
        val riffChunkSize = 36 + dataBytes

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffChunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())

        val bb = ByteBuffer.allocate(interleavedS16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in interleavedS16) {
            bb.putShort(s)
        }
        out.write(bb.array())
    }
}
