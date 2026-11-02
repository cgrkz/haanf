package app.haanf.training

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyRulesTest {
    @Test
    fun `a workout counts at 80 percent of the plan`() {
        assertFalse(SafetyRules.countsAsDone(elapsedSeconds = 959, plannedSeconds = 1200))
        assertTrue(SafetyRules.countsAsDone(elapsedSeconds = 960, plannedSeconds = 1200))
    }

    @Test
    fun `ignores a brief spike over the maximum`() {
        val monitor = OverMaxMonitor(maxBpm = 180)
        assertFalse(monitor.onBpm(190, 0))
        assertFalse(monitor.onBpm(190, 29_000))
        assertFalse(monitor.onBpm(170, 30_000))
        assertFalse(monitor.onBpm(190, 31_000))
    }

    @Test
    fun `alerts after a sustained period, then not again straight away`() {
        val monitor = OverMaxMonitor(maxBpm = 180)
        monitor.onBpm(190, 0)
        assertTrue(monitor.onBpm(190, 30_000))
        assertFalse(monitor.onBpm(190, 60_000))
        assertTrue(monitor.onBpm(190, 150_000))
    }

    @Test
    fun `sitting exactly at the maximum is not over it`() {
        val monitor = OverMaxMonitor(maxBpm = 180)
        monitor.onBpm(180, 0)
        assertFalse(monitor.onBpm(180, 60_000))
    }
}
