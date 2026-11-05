package app.haanf.ai

import app.haanf.training.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiWorkoutRulesTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 2)
    private val context = RuleContext(
        today = today,
        minutesAvailable = 45,
        feeling = 4,
        workoutsLogged = 20,
        hardDays = emptyList(),
        hardProgramDays = emptyList(),
        nextProgramSessionHard = false,
    )
    private val easy = ProposedWorkout("Easy", "", listOf(ProposedStep("Easy", 30.0, Zone.EASY)))
    private val intervals = ProposedWorkout(
        "Intervals",
        "",
        listOf(
            ProposedStep("Warm up", 10.0, Zone.EASY),
            ProposedStep("Hard", 4.0, Zone.HARD),
            ProposedStep("Recover", 3.0, Zone.EASY),
            ProposedStep("Hard", 4.0, Zone.HARD),
            ProposedStep("Cool down", 5.0, Zone.EASY),
        ),
    )
    private val veryHard = ProposedWorkout(
        "Very hard",
        "",
        listOf(
            ProposedStep("Warm up", 10.0, Zone.EASY),
            ProposedStep("Very hard", 4.0, Zone.VERY_HARD),
            ProposedStep("Cool down", 5.0, Zone.EASY),
        ),
    )

    private fun check(workout: ProposedWorkout, rules: RuleContext = context) = AiWorkoutRules.check(workout, rules)

    @Test
    fun `approves sound workouts`() {
        assertTrue(check(easy).isEmpty())
        assertTrue(check(intervals).isEmpty())
        assertTrue(check(veryHard).isEmpty())
    }

    @Test
    fun `must fit the time available`() {
        assertEquals(1, check(easy, context.copy(minutesAvailable = 20)).size)
    }

    @Test
    fun `must be at least 5 minutes and have steps`() {
        val short = ProposedWorkout("Short", "", listOf(ProposedStep("Easy", 3.0, Zone.EASY)))
        assertEquals(1, check(short).size)
        assertTrue(check(easy.copy(steps = emptyList())).isNotEmpty())
    }

    @Test
    fun `no hard work the day of or after a hard program session`() {
        assertEquals(1, check(intervals, context.copy(hardProgramDays = listOf(today.minusDays(1)))).size)
        assertEquals(1, check(intervals, context.copy(hardProgramDays = listOf(today))).size)
        assertTrue(check(intervals, context.copy(hardProgramDays = listOf(today.minusDays(2)))).isEmpty())
    }

    @Test
    fun `no hard work when the next program session is hard`() {
        assertEquals(1, check(intervals, context.copy(nextProgramSessionHard = true)).size)
    }

    @Test
    fun `easy workouts are unaffected by the hard session rules`() {
        val busy = context.copy(
            hardProgramDays = listOf(today),
            nextProgramSessionHard = true,
            feeling = 1,
            workoutsLogged = 0,
        )
        assertTrue(check(easy, busy).isEmpty())
    }

    @Test
    fun `at most 2 hard sessions in any 7 days`() {
        val two = context.copy(hardDays = listOf(today.minusDays(1), today.minusDays(6)))
        assertEquals(1, check(intervals, two).size)
        val oneInWindow = context.copy(hardDays = listOf(today.minusDays(1), today.minusDays(7)))
        assertTrue(check(intervals, oneInWindow).isEmpty())
    }

    @Test
    fun `no hard work when feeling poorly`() {
        assertEquals(1, check(intervals, context.copy(feeling = 2)).size)
        assertTrue(check(intervals, context.copy(feeling = 3)).isEmpty())
    }

    @Test
    fun `hard work needs a logged base first`() {
        assertEquals(1, check(intervals, context.copy(workoutsLogged = 5)).size)
        assertTrue(check(intervals, context.copy(workoutsLogged = 6)).isEmpty())
        assertEquals(1, check(veryHard, context.copy(workoutsLogged = 11)).size)
    }

    @Test
    fun `a very hard step lasts at most 4 minutes`() {
        val long = veryHard.copy(steps = veryHard.steps.map { if (it.target == Zone.VERY_HARD) it.copy(minutes = 5.0) else it })
        assertEquals(1, check(long).size)
    }

    @Test
    fun `hard work needs a warm up and a cool down`() {
        val cold = ProposedWorkout("Cold", "", listOf(ProposedStep("Hard", 10.0, Zone.HARD), ProposedStep("Easy", 5.0, Zone.EASY)))
        assertEquals(1, check(cold).size)
        val abrupt = ProposedWorkout("Abrupt", "", listOf(ProposedStep("Easy", 10.0, Zone.EASY), ProposedStep("Hard", 10.0, Zone.HARD)))
        assertEquals(1, check(abrupt).size)
    }

    @Test
    fun `injuries are not a rule, by the user's decision`() {
        assertTrue(RuleContext::class.java.declaredFields.none { it.name.contains("injur", ignoreCase = true) })
    }

    @Test
    fun `a benchmark repeat waits 28 days`() {
        assertNull(AiWorkoutRules.benchmarkBlockedBy(null, today))
        assertNull(AiWorkoutRules.benchmarkBlockedBy(today.minusDays(28), today))
        assertTrue(AiWorkoutRules.benchmarkBlockedBy(today.minusDays(27), today) != null)
    }

    @Test
    fun `the fallback is an easy workout that passes the rules`() {
        val fallback = AiWorkoutRules.fallback(30)
        assertTrue(check(fallback).isEmpty())
        assertTrue(AiWorkoutRules.check(AiWorkoutRules.fallback(5), context.copy(minutesAvailable = 5, feeling = 1)).isEmpty())
    }

    @Test
    fun `easing off is checked for really being easier`() {
        val shorter = intervals.copy(steps = intervals.steps.dropLast(1))
        assertTrue(AiWorkoutRules.checkChange(intervals, shorter, increase = false, context).isEmpty())
        assertEquals(1, AiWorkoutRules.checkChange(easy, intervals.copy(steps = listOf(ProposedStep("Hard", 30.0, Zone.HARD))), false, context).size)
        val longer = easy.copy(steps = listOf(ProposedStep("Easy", 40.0, Zone.EASY)))
        assertEquals(1, AiWorkoutRules.checkChange(easy, longer, increase = false, context).size)
    }

    @Test
    fun `an increase must pass the full rules`() {
        val hardDay = context.copy(hardProgramDays = listOf(today))
        assertTrue(AiWorkoutRules.checkChange(easy, intervals, increase = true, hardDay).isNotEmpty())
        assertTrue(AiWorkoutRules.checkChange(easy, intervals, increase = true, context).isEmpty())
    }

    @Test
    fun `make it easier must be easier, and never harder`() {
        val shorter = intervals.copy(steps = intervals.steps.dropLast(1) + ProposedStep("Cool down", 3.0, Zone.EASY))
        assertTrue(AiWorkoutRules.checkAlternative(intervals, shorter, easier = true).isEmpty())
        val gentler = intervals.copy(steps = intervals.steps.map { if (it.isHard) it.copy(target = Zone.EASY) else it })
        assertTrue(AiWorkoutRules.checkAlternative(intervals, gentler, easier = true).isEmpty())
        assertTrue(AiWorkoutRules.checkAlternative(intervals, intervals, easier = true).isNotEmpty())
        val harder = intervals.copy(steps = intervals.steps.map { if (it.isHard) it.copy(target = Zone.VERY_HARD) else it })
        assertTrue(AiWorkoutRules.checkAlternative(intervals, harder, easier = true).isNotEmpty())
        val longer = easy.copy(steps = listOf(ProposedStep("Easy", 40.0, Zone.EASY)))
        assertTrue(AiWorkoutRules.checkAlternative(easy, longer, easier = true).isNotEmpty())
    }

    @Test
    fun `something else may change shape but never add intensity`() {
        val different = easy.copy(steps = listOf(ProposedStep("Easy", 20.0, Zone.EASY), ProposedStep("Recovery", 10.0, Zone.RECOVERY)))
        assertTrue(AiWorkoutRules.checkAlternative(easy, different, easier = false).isEmpty())
        assertTrue(AiWorkoutRules.checkAlternative(easy, easy, easier = false).isNotEmpty())
        assertTrue(AiWorkoutRules.checkAlternative(easy, intervals, easier = false).isNotEmpty())
        val moreHard = intervals.copy(steps = intervals.steps + ProposedStep("Hard", 4.0, Zone.HARD))
        assertTrue(AiWorkoutRules.checkAlternative(intervals, moreHard, easier = false).isNotEmpty())
    }
}
