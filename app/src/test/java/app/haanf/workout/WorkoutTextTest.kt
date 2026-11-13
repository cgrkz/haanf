package app.haanf.workout

import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutTextTest {
    @Test
    fun `formats a running clock`() {
        assertEquals("0:05", WorkoutText.clock(5))
        assertEquals("12:03", WorkoutText.clock(723))
        assertEquals("1:02:03", WorkoutText.clock(3_723))
    }

    @Test
    fun `speaks singular and plural minutes`() {
        assertEquals("Warm up. 1 minute.", WorkoutText.stepLine(WorkoutStep("Warm up", 60, Zone.ZONE_2)))
        assertEquals("Recover. 3 minutes.", WorkoutText.stepLine(WorkoutStep("Recover", 180, Zone.ZONE_2)))
    }
}
