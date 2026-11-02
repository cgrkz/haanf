package app.haanf.session

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTextTest {
    @Test
    fun `shows a placeholder until there is a reading`() {
        assertEquals("-- bpm", LiveText.bpm(LiveSnapshot()))
        assertEquals("no reading yet", LiveText.age(LiveSnapshot(), nowElapsedMs = 5_000))
    }

    @Test
    fun `counts the age of the last reading`() {
        val snapshot = LiveSnapshot(bpm = 90, lastValidReadingElapsedMs = 1_000)
        assertEquals("90 bpm", LiveText.bpm(snapshot))
        assertEquals("4s ago", LiveText.age(snapshot, nowElapsedMs = 5_000))
        assertEquals("1m 5s ago", LiveText.age(snapshot, nowElapsedMs = 66_000))
    }
}
