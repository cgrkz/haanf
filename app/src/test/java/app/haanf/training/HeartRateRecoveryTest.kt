package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateRecoveryTest {
    private val plan = WorkoutPlan(
        "Intervals",
        listOf(
            WorkoutStep("Warm up", 600, Zone.ZONE_2),
            WorkoutStep("Hard 1", 120, Zone.ZONE_4),
            WorkoutStep("Recover", 180, Zone.ZONE_2),
            WorkoutStep("Hard 2", 120, Zone.ZONE_5),
            WorkoutStep("Cool down", 300, Zone.ZONE_2),
        ),
    )

    @Test
    fun `finds the end of the last hard step`() {
        assertEquals(1020, HeartRateRecovery.lastHardStepEndSeconds(plan))
    }

    @Test
    fun `an easy plan has no hard step`() {
        assertNull(HeartRateRecovery.lastHardStepEndSeconds(WorkoutPlan("Easy", listOf(WorkoutStep("Easy", 600, Zone.ZONE_2)))))
    }

    @Test
    fun `measures the drop over one minute`() {
        val end = 1_000_000L
        val samples = listOf(
            TimedBpm(end - 4_000, 168),
            TimedBpm(end, 170),
            TimedBpm(end + 30_000, 155),
            TimedBpm(end + 58_000, 141),
            TimedBpm(end + 62_000, 139),
        )
        assertEquals(29, HeartRateRecovery.oneMinuteDrop(samples, end))
    }

    @Test
    fun `gives no number when the minute after was not recorded`() {
        val end = 1_000_000L
        assertNull(HeartRateRecovery.oneMinuteDrop(listOf(TimedBpm(end, 170), TimedBpm(end + 30_000, 150)), end))
    }

    @Test
    fun `gives no number without a reading at the end of the effort`() {
        val end = 1_000_000L
        assertNull(HeartRateRecovery.oneMinuteDrop(listOf(TimedBpm(end - 30_000, 170), TimedBpm(end + 60_000, 140)), end))
    }

    @Test
    fun `ignores zero readings`() {
        val end = 1_000_000L
        val samples = listOf(TimedBpm(end, 0), TimedBpm(end - 2_000, 160), TimedBpm(end + 60_000, 0), TimedBpm(end + 61_000, 130))
        assertEquals(30, HeartRateRecovery.oneMinuteDrop(samples, end))
    }
}
