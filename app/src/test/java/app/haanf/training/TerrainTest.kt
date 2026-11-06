package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class TerrainTest {
    private fun hpaAt(heightMetres: Double, startHpa: Double = 1_013.25) =
        startHpa * (1 - heightMetres / 44_330.0).pow(1 / 0.1903)

    @Test
    fun `12 hectopascals near sea level is about 100 metres`() {
        assertEquals(100.0, AltitudeTracker.heightBetween(1_013.25, 1_001.29), 0.5)
    }

    @Test
    fun `height is relative to the start, wherever the start is`() {
        val tracker = AltitudeTracker(smoothingReadings = 1)
        tracker.add(hpaAt(1_300.0))
        tracker.add(hpaAt(1_330.0))
        assertEquals(30.0, tracker.heightMetres, 0.5)
    }

    @Test
    fun `sensor noise adds no climb or descent`() {
        val tracker = AltitudeTracker()
        repeat(600) { tracker.add(1_000.0 + if (it % 2 == 0) 0.1 else -0.1) }
        assertEquals(0.0, tracker.climbMetres, 0.001)
        assertEquals(0.0, tracker.descentMetres, 0.001)
    }

    @Test
    fun `a steady climb and descent are counted`() {
        val tracker = AltitudeTracker()
        (0..100).forEach { tracker.add(hpaAt(it * 0.5)) }
        (100 downTo 40).forEach { tracker.add(hpaAt(it * 0.5)) }
        repeat(10) { tracker.add(hpaAt(20.0)) }
        assertEquals(50.0, tracker.climbMetres, 1.6)
        assertEquals(30.0, tracker.descentMetres, 1.6)
    }

    @Test
    fun `impossible pressures are ignored`() {
        val tracker = AltitudeTracker()
        tracker.add(0.0)
        assertFalse(tracker.hasReadings)
    }

    @Test
    fun `a climb starts on a steady gradient and ends when it levels off`() {
        val detector = ClimbDetector()
        val events = mutableListOf<ClimbEvent>()
        // 5 m a second at 6% for 120 seconds, then flat.
        (0..120).forEach { t -> detector.add(TerrainPoint(t, t * 5.0, t * 5.0 * 0.06))?.let(events::add) }
        assertTrue(detector.climbing)
        assertEquals(6.0, detector.gradePercent!!, 0.1)
        (121..200).forEach { t -> detector.add(TerrainPoint(t, t * 5.0, 120 * 5.0 * 0.06))?.let(events::add) }
        assertFalse(detector.climbing)
        val started = events.first() as ClimbEvent.Started
        val ended = events.last() as ClimbEvent.Ended
        assertTrue(started.elapsedSeconds in 5..15)
        assertTrue(ended.gainMetres in 30.0..37.0)
        assertTrue(ended.averageGradePercent in 4.0..6.5)
    }

    @Test
    fun `gentle rises and standing still are not climbs`() {
        val detector = ClimbDetector()
        (0..200).forEach { t -> assertNull(detector.add(TerrainPoint(t, t * 5.0, t * 5.0 * 0.015))) }
        val still = ClimbDetector()
        (0..60).forEach { t -> still.add(TerrainPoint(t, 10.0, t * 0.1)) }
        assertNull(still.gradePercent)
        assertFalse(still.climbing)
    }
}
