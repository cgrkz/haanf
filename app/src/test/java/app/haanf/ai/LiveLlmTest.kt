package app.haanf.ai

import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Real calls with made-up data, for development only. Run with HAANF_LIVE_LLM=1. The key comes
 * from .env, which git ignores, and the Hinoki endpoint is the same Chat Completions path that
 * OpenRouter uses. Real health data never goes here.
 */
class LiveLlmTest {
    private val key: String? = File("../.env").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.trim().startsWith("LLM_KEY=") }
        ?.substringAfter("=")?.trim()?.trim('"', '\'')

    private val client by lazy {
        OpenAiCompatibleClient("https://inference.hinoki.security/v1", requireNotNull(key), "hinoki-offensive")
    }

    private val today: LocalDate = LocalDate.of(2026, 11, 2)
    private val data = object : CoachDataSource {
        override suspend fun snapshot() = CoachSnapshot(
            today, 30, Sex.MALE, 75.0, HeartRateZones(187), false,
            emptyList(), emptyList(), emptyList(), emptyList(), null, null,
        )
        override suspend fun ruleContext(minutesAvailable: Int, feeling: Int) = RuleContext(
            today, minutesAvailable, feeling, 3, emptyList(), emptyList(), false,
        )
        override suspend fun lastBenchmarkDay() = today.minusDays(2)
    }

    private fun live() = assumeTrue(System.getenv("HAANF_LIVE_LLM") == "1" && key != null)

    @Test
    fun `a real model plans a workout that passes the rule check`() = runBlocking {
        live()
        val planner = WorkoutPlanner({ client }, data) { CoachTone.WARM }
        val outcome = planner.plan(CheckIn(30, 4, "", TrainingActivity.TREADMILL))
        println("LIVE planner outcome: $outcome")
        assertTrue(outcome is PlanOutcome.Approved || outcome is PlanOutcome.Fallback)
    }

    @Test
    fun `a real model speaks through the speak tool`() = runBlocking {
        live()
        var spoken: String? = null
        val request = LlmRequest(
            system = CoachPrompts.coach(CoachTone.WARM),
            items = listOf(LlmItem.User("Why you are awake: Your timer ran out. Check in.\nHeart rate now: 142 bpm.")),
            tools = listOf(CoachHarness.SPEAK, CoachHarness.HEART_RATE_OVERVIEW),
        )
        val reply = runToolLoop(client, request, isDone = { spoken != null }) { call ->
            println("LIVE tool call: ${call.name} ${call.input}")
            if (call.name == "speak") spoken = call.input["message"].toString()
            ToolOutcome(call.id, if (call.name == "speak") "Spoken." else "Average 140, steady.")
        }
        println("LIVE stray text not spoken: ${reply.text?.take(80)}")
        println("LIVE spoken: $spoken")
        assertTrue(spoken != null)
    }
}
