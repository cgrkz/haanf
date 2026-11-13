package app.haanf.ai

import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkoutPlannerTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 2)
    private var lastBenchmark: LocalDate? = today.minusDays(40)
    private var snapshot: CoachSnapshot? = CoachSnapshot(
        today, 30, Sex.MALE, 75.0, HeartRateZones(187), false,
        emptyList(), emptyList(), emptyList(), emptyList(), null, null,
    )
    private val data = object : CoachDataSource {
        override suspend fun snapshot() = this@WorkoutPlannerTest.snapshot
        override suspend fun ruleContext(minutesAvailable: Int, feeling: Int) = RuleContext(
            today, minutesAvailable, feeling, 20, emptyList(), emptyList(), false,
        )
        override suspend fun lastBenchmarkDay() = lastBenchmark
    }
    private val checkIn = CheckIn(30, 4, "legs a bit tired", TrainingActivity.TREADMILL)
    private val llm = FakeLlm()
    private val planner = WorkoutPlanner(llm, data, offersBenchmark = true) { CoachTone.CALM }

    private fun step(label: String, minutes: Double, target: String) = buildJsonObject {
        put("label", label)
        put("minutes", minutes)
        put("target", target)
    }

    private fun workout(minutes: Double, target: String = "zone_2"): JsonObject = buildJsonObject {
        put("title", "Workout")
        put("reason", "Because")
        putJsonArray("steps") { add(step("Main", minutes, target)) }
    }

    private fun propose(input: JsonObject) = FakeLlm.call("propose_workout", input)

    private fun plan() = runBlocking { planner.plan(checkIn) }

    @Test
    fun `approves a workout that passes the rules`() {
        llm.then { propose(workout(25.0)) }
        val outcome = plan() as PlanOutcome.Approved
        assertEquals("Workout", outcome.workout.title)
        assertEquals(1, llm.requests.size)
    }

    @Test
    fun `sends the check-in and the tone`() {
        llm.then { propose(workout(25.0)) }
        plan()
        val request = llm.requests.single()
        assertTrue((request.items.single() as LlmItem.User).text.contains("30 minutes available, feeling 4 out of 5"))
        assertTrue((request.items.single() as LlmItem.User).text.contains("legs a bit tired"))
        assertTrue(request.system.contains("calm and factual"))
    }

    @Test
    fun `tells the AI why a workout was rejected, and takes the fix`() {
        llm.then { propose(workout(60.0)) }.then { propose(workout(25.0)) }
        assertTrue(plan() is PlanOutcome.Approved)
        val retry = llm.requests[1].items.last() as LlmItem.ToolTurn
        assertTrue(retry.outcomes.single().isError)
        assertTrue(retry.outcomes.single().content.contains("only 30 are available"))
    }

    @Test
    fun `falls back to a safe workout when the AI keeps breaking the rules`() {
        llm.then { propose(workout(60.0)) }
        val outcome = plan() as PlanOutcome.Fallback
        assertEquals(30.0, outcome.workout.totalMinutes, 0.0)
        assertEquals(3, llm.requests.size)
    }

    @Test
    fun `falls back when the AI writes prose instead of a workout`() {
        llm.then { FakeLlm.text("How about a nice run?") }
        assertTrue(plan() is PlanOutcome.Fallback)
    }

    @Test
    fun `refuses a workout it cannot read`() {
        llm.then { propose(buildJsonObject { put("title", JsonPrimitive("No steps")) }) }
        assertTrue(plan() is PlanOutcome.Fallback)
    }

    @Test
    fun `allows a benchmark when one is due`() {
        llm.then { FakeLlm.call("suggest_benchmark", buildJsonObject { put("reason", "It has been 40 days") }) }
        assertEquals("It has been 40 days", (plan() as PlanOutcome.Benchmark).reason)
    }

    @Test
    fun `refuses a benchmark too soon after the last`() {
        lastBenchmark = today.minusDays(10)
        llm.then { FakeLlm.call("suggest_benchmark", buildJsonObject { put("reason", "Again") }) }
            .then { propose(workout(25.0)) }
        assertTrue(plan() is PlanOutcome.Approved)
    }

    @Test
    fun `offers no benchmark while benchmarks are switched off`() {
        val paused = WorkoutPlanner(llm, data, offersBenchmark = false) { CoachTone.CALM }
        llm.then { FakeLlm.call("suggest_benchmark", buildJsonObject { put("reason", "It has been 40 days") }) }
            .then { propose(workout(25.0)) }
        assertTrue(runBlocking { paused.plan(checkIn) } is PlanOutcome.Approved)
        val first = llm.requests.first()
        assertEquals(listOf("propose_workout"), first.tools.map { it.name })
        assertFalse(first.system.contains("benchmark is clearly due"))
        assertTrue((llm.requests[1].items.last() as LlmItem.ToolTurn).outcomes.single().isError)
    }

    @Test
    fun `reports a failed call in plain words`() {
        llm.then { throw LlmException(LlmFailure.BAD_KEY) }
        assertEquals("The API key was rejected.", (plan() as PlanOutcome.Failed).message)
    }

    @Test
    fun `asks for a key when there is none`() {
        val noKey = WorkoutPlanner({ null }, data) { CoachTone.WARM }
        assertTrue(runBlocking { noKey.plan(checkIn) } is PlanOutcome.Failed)
    }

    private val shown = ProposedWorkout(
        "Easy run",
        "Because",
        listOf(ProposedStep("Easy", 30.0, app.haanf.training.Zone.ZONE_2)),
    )

    @Test
    fun `make it easier tells the AI what it suggested and wants shorter or gentler`() {
        llm.then { propose(workout(20.0)) }
        assertTrue(runBlocking { planner.plan(checkIn, shown, easier = true) } is PlanOutcome.Approved)
        val text = (llm.requests.single().items.single() as LlmItem.User).text
        assertTrue(text.contains("You suggested: Easy run"))
        assertTrue(text.contains("something easier"))
    }

    @Test
    fun `make it easier rejects a workout that is not easier`() {
        llm.then { propose(workout(30.0)) }.then { propose(workout(20.0)) }
        assertTrue(runBlocking { planner.plan(checkIn, shown, easier = true) } is PlanOutcome.Approved)
        val retry = llm.requests[1].items.last() as LlmItem.ToolTurn
        assertTrue(retry.outcomes.single().content.contains("must be easier"))
    }

    @Test
    fun `something else rejects anything harder`() {
        llm.then { propose(workout(25.0, "zone_4")) }
        val outcome = runBlocking { planner.plan(checkIn, shown, easier = false) }
        assertTrue(outcome is PlanOutcome.Fallback)
    }

    @Test
    fun `the easier fallback is shorter than what was shown`() {
        llm.then { FakeLlm.text("No.") }
        val outcome = runBlocking { planner.plan(checkIn, shown, easier = true) } as PlanOutcome.Fallback
        assertEquals(20.0, outcome.workout.totalMinutes, 0.0)
    }

    @Test
    fun `the check-in feelings map so tired and sore rule out hard work`() {
        assertEquals(2, Feel.TIRED.score)
        assertEquals(2, Feel.SORE.score)
        assertTrue(Feel.OK.score >= 3)
        assertTrue(!Feel.UNWELL.canTrain)
    }
}
