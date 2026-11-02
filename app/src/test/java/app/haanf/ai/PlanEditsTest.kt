package app.haanf.ai

import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanEditsTest {
    private val plan = WorkoutPlan(
        "Intervals",
        listOf(
            WorkoutStep("Warm up", 600, Zone.EASY),
            WorkoutStep("Hard 1", 240, Zone.HARD),
            WorkoutStep("Recover", 180, Zone.EASY),
            WorkoutStep("Hard 2", 240, Zone.HARD),
            WorkoutStep("Cool down", 300, Zone.EASY),
        ),
    )

    @Test
    fun `time already done is never changed`() {
        val edited = PlanEdits.apply(plan, 700, PlanChange.EASE, 0)
        assertEquals(plan.positionAt(699)!!.step.label, edited.positionAt(699)!!.step.label)
        assertEquals(600, edited.steps[0].seconds)
        assertEquals(100, edited.steps[1].seconds)
        assertEquals(Zone.HARD, edited.steps[1].target)
    }

    @Test
    fun `easing lowers what is left by one zone`() {
        val edited = PlanEdits.apply(plan, 700, PlanChange.EASE, 0)
        assertTrue(edited.steps.drop(2).none { it.target == Zone.HARD })
        assertEquals(plan.totalSeconds, edited.totalSeconds)
    }

    @Test
    fun `easing very hard work leaves it hard`() {
        val veryHard = WorkoutPlan("v", listOf(WorkoutStep("Very hard", 240, Zone.VERY_HARD)))
        assertEquals(Zone.HARD, PlanEdits.apply(veryHard, 0, PlanChange.EASE, 0).steps.single().target)
    }

    @Test
    fun `shortening keeps the cool down`() {
        val edited = PlanEdits.apply(plan, 0, PlanChange.SHORTEN, 10)
        assertEquals(plan.totalSeconds - 600, edited.totalSeconds)
        assertEquals("Cool down", edited.steps.last().label)
        assertTrue(edited.steps.last().seconds >= 180)
    }

    @Test
    fun `shortening cannot remove the rest of the workout`() {
        val edited = PlanEdits.apply(plan, 1000, PlanChange.SHORTEN, 120)
        assertTrue(edited.totalSeconds >= 1060)
    }

    @Test
    fun `extending adds easy time before the cool down`() {
        val edited = PlanEdits.apply(plan, 700, PlanChange.EXTEND, 10)
        assertEquals(plan.totalSeconds + 600, edited.totalSeconds)
        assertEquals("Cool down", edited.steps.last().label)
        assertEquals("Extra easy", edited.steps[edited.steps.lastIndex - 1].label)
    }

    @Test
    fun `intensifying raises what is left but keeps the cool down easy`() {
        val edited = PlanEdits.apply(plan, 650, PlanChange.INTENSIFY, 0)
        assertEquals(Zone.EASY, edited.steps.last().target)
        assertEquals(Zone.HARD, edited.steps.first { it.label == "Recover" }.target)
        assertEquals(Zone.EASY, edited.steps[0].target)
    }

    @Test
    fun `the running position survives an edit`() {
        val edited = PlanEdits.apply(plan, 650, PlanChange.EASE, 0)
        assertEquals(40 + 0, 650 - 610)
        assertEquals("Hard 1", edited.positionAt(650)!!.step.label)
        assertEquals(190, edited.positionAt(650)!!.remainingSeconds)
    }
}
