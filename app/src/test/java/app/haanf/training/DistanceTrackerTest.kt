package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceTrackerTest {
    private val tracker = DistanceTracker()

    // About 11.1 metres per 0.0001 degrees of latitude.
    private fun fix(steps: Int, seconds: Int, accuracy: Float = 5f) =
        LocationFix(12.9 + steps * 0.0001, 77.6, accuracy, seconds * 1000L)

    @Test
    fun `adds up a straight walk`() {
        (0..100).forEach { tracker.add(fix(it, it * 8)) }
        assertEquals(1112.0, tracker.totalMetres, 5.0)
    }

    @Test
    fun `the first fix adds nothing`() {
        assertEquals(0.0, tracker.add(fix(0, 0)), 0.0)
    }

    @Test
    fun `ignores fixes with poor accuracy`() {
        tracker.add(fix(0, 0))
        tracker.add(fix(50, 10, accuracy = 80f))
        assertEquals(0.0, tracker.totalMetres, 0.0)
    }

    @Test
    fun `ignores a jump no walker could make`() {
        tracker.add(fix(0, 0))
        tracker.add(fix(100, 1))
        assertEquals(0.0, tracker.totalMetres, 0.0)
    }

    @Test
    fun `carries on after a jump instead of stalling`() {
        tracker.add(fix(0, 0))
        tracker.add(fix(100, 1))
        tracker.add(fix(101, 9))
        assertEquals(11.0, tracker.totalMetres, 1.0)
    }

    @Test
    fun `standing still does not build up distance from GPS noise`() {
        tracker.add(fix(0, 0, accuracy = 10f))
        repeat(200) { tracker.add(LocationFix(12.9 + (it % 2) * 0.00002, 77.6, 10f, (it + 1) * 1000L)) }
        assertEquals(0.0, tracker.totalMetres, 0.0)
    }

    @Test
    fun `small steps at walking pace still add up to the full distance`() {
        val metresPerFix = 1.8
        val degreesPerFix = metresPerFix / 111_195.0
        repeat(900) {
            tracker.add(LocationFix(12.9 + it * degreesPerFix, 77.6, 10f, it * 1000L))
        }
        assertEquals(1618.0, tracker.totalMetres, 12.0)
    }

    @Test
    fun `ignores a fix that goes backwards in time`() {
        tracker.add(fix(0, 10))
        tracker.add(fix(5, 5))
        assertEquals(0.0, tracker.totalMetres, 0.0)
    }
}
