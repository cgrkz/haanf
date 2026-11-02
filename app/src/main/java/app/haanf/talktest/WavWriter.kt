package app.haanf.talktest

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Writes 16-bit mono PCM. The header's sizes are unknown until [close] patches them. */
class WavWriter(file: File, private val sampleRateHz: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")

    var dataBytes: Long = 0
        private set

    val durationMs: Long get() = dataBytes * 1000 / (sampleRateHz * BYTES_PER_SAMPLE)

    init {
        out.setLength(0)
        out.write(header(dataSize = 0))
    }

    fun write(buffer: ByteArray, count: Int) {
        out.write(buffer, 0, count)
        dataBytes += count
    }

    override fun close() {
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    private fun header(dataSize: Long): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((HEADER_BYTES - 8 + dataSize).toInt())
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRateHz)
            putInt(sampleRateHz * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize.toInt())
        }.array()

    companion object {
        const val HEADER_BYTES = 44
        const val BYTES_PER_SAMPLE = 2
    }
}

/** Loudest sample in a little-endian 16-bit PCM buffer, from 0 to 1, for the level meter. */
fun pcm16Peak(buffer: ByteArray, count: Int): Float {
    var peak = 0
    var index = 0
    while (index + 1 < count) {
        val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
        peak = maxOf(peak, abs(sample))
        index += 2
    }
    return (peak / 32768f).coerceIn(0f, 1f)
}
