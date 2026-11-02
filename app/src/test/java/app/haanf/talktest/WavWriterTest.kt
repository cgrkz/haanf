package app.haanf.talktest

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `patches the header sizes on close`() {
        val file = folder.newFile("a.wav")
        val writer = WavWriter(file, sampleRateHz = 16_000)
        writer.write(ByteArray(32_000), 32_000)
        writer.write(ByteArray(100), 16)
        writer.close()

        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 32_016, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 32_016, header.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(32_016, header.getInt(40))
    }

    @Test
    fun `reports duration from the bytes written`() {
        val writer = WavWriter(folder.newFile("b.wav"), sampleRateHz = 44_100)
        writer.write(ByteArray(88_200), 88_200)
        assertEquals(1_000, writer.durationMs)
        writer.close()
    }

    @Test
    fun `measures peak level of signed little-endian samples`() {
        val silence = byteArrayOf(0, 0, 0, 0)
        val half = byteArrayOf(0, 0x40)
        val fullNegative = byteArrayOf(0, 0x80.toByte())
        assertEquals(0f, pcm16Peak(silence, silence.size), 0f)
        assertEquals(0.5f, pcm16Peak(half, half.size), 0f)
        assertEquals(1f, pcm16Peak(fullNegative, fullNegative.size), 0f)
    }
}
