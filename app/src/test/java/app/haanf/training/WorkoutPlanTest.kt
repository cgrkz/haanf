package app.haanf.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutPlanTest {
    private val plan = WorkoutPlan(
        title = "Test",
        steps = listOf(
            WorkoutStep("Warm up", 60, Zone.EASY),
            WorkoutStep("Hard", 30, Zone.HARD),
        ),
    )

    @Test
    fun `adds up the planned time`() {
        assertEquals(90, plan.totalSeconds)
    }

    @Test
    fun `tracks the current step and time left`() {
        val start = plan.positionAt(0)!!
        assertEquals("Warm up", start.step.label)
        assertEquals(60, start.remainingSeconds)
        assertEquals("Hard", start.next?.label)

        assertEquals(1, plan.positionAt(59)!!.remainingSeconds)
        val second = plan.positionAt(60)!!
        assertEquals("Hard", second.step.label)
        assertEquals(30, second.remainingSeconds)
        assertNull(second.next)
    }

    @Test
    fun `has no position once the plan is over`() {
        assertEquals(1, plan.positionAt(89)!!.remainingSeconds)
        assertNull(plan.positionAt(90))
    }
}
