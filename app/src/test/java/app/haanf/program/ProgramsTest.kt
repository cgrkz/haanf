package app.haanf.program

import app.haanf.training.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramsTest {
    private val program = Programs.FOUNDATION_8

    @Test
    fun `is eight weeks of three sessions`() {
        assertEquals(8, program.weeks.size)
        program.weeks.forEach { assertEquals(3, it.sessions.size) }
    }

    @Test
    fun `never places two hard sessions next to each other`() {
        val sessions = program.weeks.flatMap { it.sessions }
        sessions.zipWithNext().forEach { (first, second) ->
            assertFalse("${first.plan.title} then ${second.plan.title}", first.hard && second.hard)
        }
    }

    @Test
    fun `has no hard work in the first four weeks`() {
        assertTrue(program.weeks.take(4).flatMap { it.sessions }.none { it.hard })
    }

    @Test
    fun `saves very hard intervals for the final week`() {
        val veryHardWeeks = program.weeks.indices.filter { week ->
            program.weeks[week].sessions.any { session -> session.plan.steps.any { it.target == Zone.ZONE_5 } }
        }
        assertEquals(listOf(7), veryHardWeeks)
    }

    @Test
    fun `builds interval sessions with warm up, recoveries and cool down`() {
        val plan = program.session(week = 7, session = 1).plan
        assertEquals("4 x 4 minutes in Zone\u00A04", plan.title)
        assertEquals(Zone.ZONE_2, plan.steps.first { it.label == "Warm up" }.target)
        assertEquals(Zone.ZONE_1, plan.steps.last { it.label == "Cool down" }.target)
        assertEquals(3, plan.steps.count { it.label == "Recover" && it.target == Zone.ZONE_2 })
        assertEquals(4, plan.steps.count { it.target == Zone.ZONE_4 })
        assertEquals((10 + 16 + 9 + 5) * 60, plan.totalSeconds)
    }

    @Test
    fun `looks up programs by id`() {
        assertEquals(program, Programs.byId("foundation-8"))
    }
}
