package app.haanf.session

import app.haanf.session.DropoutDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropoutDetectorTest {
    private val detector = DropoutDetector(dropoutAfterMs = 10_000, reconnectAfterMs = 30_000)

    @Test
    fun `stays quiet before the first reading`() {
        assertNull(detector.onTick(nowMs = 60_000, linkStreaming = true))
        assertFalse(detector.active)
    }

    @Test
    fun `reports a dropout once after the threshold`() {
        detector.onReading(0)
        assertNull(detector.onTick(9_999, linkStreaming = true))
        assertEquals(Event.Started(10_000), detector.onTick(10_000, linkStreaming = true))
        assertTrue(detector.active)
        assertNull(detector.onTick(11_000, linkStreaming = true))
    }

    @Test
    fun `reports the gap when readings return`() {
        detector.onReading(0)
        detector.onTick(12_000, linkStreaming = true)
        assertEquals(Event.Ended(gapMs = 15_000), detector.onReading(15_000))
        assertFalse(detector.active)
    }

    @Test
    fun `asks for a reconnect when the link claims to be fine but stays silent`() {
        detector.onReading(0)
        detector.onTick(10_000, linkStreaming = true)
        assertNull(detector.onTick(29_000, linkStreaming = true))
        assertEquals(Event.RequestReconnect(30_000), detector.onTick(30_000, linkStreaming = true))
        assertNull(detector.onTick(45_000, linkStreaming = true))
        assertEquals(Event.RequestReconnect(60_000), detector.onTick(60_000, linkStreaming = true))
    }

    @Test
    fun `leaves recovery to the source while the link is already down`() {
        detector.onReading(0)
        detector.onTick(10_000, linkStreaming = false)
        assertNull(detector.onTick(120_000, linkStreaming = false))
    }
}
