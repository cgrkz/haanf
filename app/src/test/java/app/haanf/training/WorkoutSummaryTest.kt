package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutSummaryTest {
    private val zones = HeartRateZones(maxBpm = 200)

    @Test
    fun `measures time in each zone from the readings`() {
        val summary = WorkoutSummaries.summarize(
            samples = listOf(TimedBpm(0, 120), TimedBpm(2_000, 120), TimedBpm(4_000, 150), TimedBpm(6_000, 150)),
            startedAtMs = 0,
            endedAtMs = 8_000,
            zones = zones,
        )
        assertEquals(8, summary.durationSeconds)
        assertEquals(4, summary.zoneSeconds[Zone.EASY])
        assertEquals(4, summary.zoneSeconds[Zone.HARD])
        assertEquals(0, summary.noReadingSeconds)
        assertEquals(135, summary.avgBpm)
        assertEquals(150, summary.maxBpm)
    }

    @Test
    fun `does not credit a long gap to the last reading`() {
        val summary = WorkoutSummaries.summarize(
            samples = listOf(TimedBpm(0, 120), TimedBpm(60_000, 120)),
            startedAtMs = 0,
            endedAtMs = 62_000,
            zones = zones,
        )
        assertEquals(12, summary.zoneSeconds[Zone.EASY])
        assertEquals(50, summary.noReadingSeconds)
    }

    @Test
    fun `ignores zero readings and those outside the workout`() {
        val summary = WorkoutSummaries.summarize(
            samples = listOf(TimedBpm(0, 0), TimedBpm(1_000, 100), TimedBpm(99_000, 190)),
            startedAtMs = 0,
            endedAtMs = 2_000,
            zones = zones,
        )
        assertEquals(100, summary.avgBpm)
        assertEquals(100, summary.maxBpm)
    }

    @Test
    fun `survives a workout with no heart rate at all`() {
        val summary = WorkoutSummaries.summarize(emptyList(), 0, 30_000, zones)
        assertNull(summary.avgBpm)
        assertNull(summary.maxBpm)
        assertEquals(30, summary.noReadingSeconds)
    }
}
