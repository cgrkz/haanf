package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MotionMeterTest {
    private val metresPerDegree = 111_195.0

    private fun hpaAt(heightMetres: Double) = 1_000.0 * (1 - heightMetres / 44_330.0).pow(1 / 0.1903)

    /** Drives the meter a second at a time along a straight line, with [speedAt] and [heightAt]. */
    private fun ride(
        meter: MotionMeter,
        seconds: Int,
        speedAt: (Int) -> Double,
        heightAt: (Double) -> Double,
        bpm: Int = 140,
    ): List<MotionEvent> {
        var metres = 0.0
        val events = mutableListOf<MotionEvent>()
        (0..seconds).forEach { t ->
            if (t > 0) metres += speedAt(t)
            meter.onFix(LocationFix(metres / metresPerDegree, 0.0, 5f, t * 1000L, speedAt(t).toFloat()))
            meter.onPressure(hpaAt(heightAt(metres)))
            events += meter.tick(t, bpm)
        }
        return events
    }

    @Test
    fun `a ride gives distance, speed, splits, climbs and watts`() {
        val meter = MotionMeter(MotionKind.RIDE, riderKg = 61.0, bike = BikeType.HYBRID)
        // 500 m at 6% then flat, at 5 m a second, for 2.5 km.
        val events = ride(meter, 500, { 5.0 }, { metres -> minOf(metres, 500.0) * 0.06 })
        val totals = meter.totals()
        assertEquals(2_500.0, totals.distanceMetres, 30.0)
        assertEquals(5.0, totals.maxSpeedMps, 0.01)
        assertTrue(totals.movingSeconds in 495..500)
        assertEquals(30.0, totals.climbMetres, 3.0)
        assertNotNull(totals.avgWatts)
        assertNull(totals.gapSecondsPerKm)

        val splits = events.filterIsInstance<MotionEvent.SplitDone>().map { it.split }
        assertEquals(listOf(1, 2), splits.map { it.number })
        assertTrue(splits[0].seconds in 199..202)
        assertEquals(140, splits[0].avgBpm)
        assertTrue(splits[0].climbMetres > 25)
        assertTrue(splits[1].climbMetres < 2)
        assertTrue(splits[0].avgWatts!! > splits[1].avgWatts!!)

        val climbs = events.filterIsInstance<MotionEvent.Climb>().map { it.event }
        assertTrue(climbs.first() is ClimbEvent.Started)
        assertTrue(climbs.last() is ClimbEvent.Ended)
        val last = meter.finish(500)
        assertEquals(3, last?.number)
        assertEquals(500.0, last!!.metres, 30.0)
    }

    @Test
    fun `a run gets grade-adjusted pace instead of watts`() {
        val meter = MotionMeter(MotionKind.RUN, riderKg = 61.0)
        ride(meter, 400, { 3.0 }, { metres -> metres * 0.05 })
        val totals = meter.totals()
        assertNull(totals.avgWatts)
        val pace = totals.gapSecondsPerKm!!
        assertTrue("$pace", pace < 333)
    }

    @Test
    fun `standing still is not moving time, and speed goes stale without fixes`() {
        val meter = MotionMeter(MotionKind.RIDE, riderKg = 61.0, bike = BikeType.ROAD)
        ride(meter, 60, { 0.0 }, { 0.0 })
        assertEquals(0, meter.totals().movingSeconds)
        (61..80).forEach { meter.tick(it, 120) }
        assertNull(meter.now().speedMps)
    }

    @Test
    fun `without a barometer there is distance but no climb or gradient`() {
        val meter = MotionMeter(MotionKind.RIDE)
        var metres = 0.0
        (0..100).forEach { t ->
            if (t > 0) metres += 6.0
            meter.onFix(LocationFix(metres / metresPerDegree, 0.0, 5f, t * 1000L, 6f))
            meter.tick(t, null)
        }
        assertEquals(600.0, meter.totals().distanceMetres, 15.0)
        assertNull(meter.now().gradePercent)
        assertEquals(false, meter.now().hasBarometer)
        assertNull(meter.totals().avgWatts)
    }
}
