package app.haanf.ai

import app.haanf.ai.CoachTools.text
import app.haanf.data.BenchmarkEntity
import app.haanf.data.FactKind
import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEntity
import app.haanf.training.TimedBpm
import app.haanf.workout.TimelineEntry
import java.time.ZoneId

/**
 * Writes the short review shown after a workout. It reads the workout's timeline word for
 * word, its kilometres, and its heart rate, so it can explain what happened and why.
 */
class WorkoutReviewer(
    private val llm: LlmProvider,
    private val data: CoachDataSource,
    private val tone: () -> CoachTone,
    private val samples: suspend (Long) -> List<TimedBpm>,
    private val timeline: suspend (Long) -> List<TimelineEntry> = { emptyList() },
    private val splits: suspend (Long) -> List<SplitEntity> = { emptyList() },
    private val proposeFact: suspend (kind: String, text: String) -> Unit = { _, _ -> },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val detail = WorkoutDetail(samples, timeline, splits, zone)

    /** Null when no key is set. Failures surface as [LlmException], so the screen can offer a retry. */
    suspend fun review(workout: WorkoutEntity, benchmark: BenchmarkEntity?): String? {
        val client = llm.client() ?: return null
        val snapshot = data.snapshot() ?: return null
        // The workout under review is described below, so it is left out of the list.
        val others = snapshot.copy(workouts = snapshot.workouts.filter { it.id != workout.id })
        val request = LlmRequest(
            system = CoachPrompts.reviewer(tone()),
            items = listOf(LlmItem.User(CoachContext.render(others, zone) + "\n\n" + detail.describe(workout, benchmark, "The workout to review"))),
            tools = listOf(CoachTools.REMEMBER_FACT),
            effort = LlmEffort.LOW,
        )
        val reply = runToolLoop(client, request, maxRounds = MAX_ROUNDS) { call ->
            when (call.name) {
                CoachTools.REMEMBER_FACT.name -> {
                    val kind = FactKind.entries.firstOrNull { it.name == call.input.text("kind") } ?: FactKind.NOTE
                    val fact = call.input.text("fact")?.trim().orEmpty()
                    if (fact.isEmpty()) {
                        ToolOutcome(call.id, "The fact was empty.", isError = true)
                    } else {
                        proposeFact(kind.name, fact)
                        ToolOutcome(call.id, "Proposed. The athlete will confirm it in the app. Now write the review.")
                    }
                }
                else -> ToolOutcome(call.id, "Unknown tool.", isError = true)
            }
        }
        return reply.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val MAX_ROUNDS = 3
    }
}
