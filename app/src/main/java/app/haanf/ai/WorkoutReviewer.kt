package app.haanf.ai

import app.haanf.data.BenchmarkEntity
import app.haanf.data.WorkoutEntity
import app.haanf.training.HeartRateZones
import app.haanf.training.TimedBpm
import app.haanf.workout.WorkoutText

/** Writes the short review shown after a workout. */
class WorkoutReviewer(
    private val llm: LlmProvider,
    private val data: CoachDataSource,
    private val tone: () -> CoachTone,
    private val samples: suspend (Long) -> List<TimedBpm>,
) {
    /** Null when no key is set. Failures surface as [LlmException], so the screen can offer a retry. */
    suspend fun review(workout: WorkoutEntity, benchmark: BenchmarkEntity?): String? {
        val client = llm.client() ?: return null
        val snapshot = data.snapshot() ?: return null
        val request = LlmRequest(
            system = CoachPrompts.reviewer(tone()),
            items = listOf(LlmItem.User(CoachContext.render(snapshot) + "\n\n" + workoutText(workout, benchmark))),
            effort = LlmEffort.LOW,
        )
        return client.send(request).text?.trim()
    }

    private suspend fun workoutText(workout: WorkoutEntity, benchmark: BenchmarkEntity?): String = buildString {
        val endMs = workout.endedAtMs ?: workout.startedAtMs
        val seconds = ((endMs - workout.startedAtMs) / 1000).toInt()
        append("The workout to review: ${workout.title}, ${WorkoutText.clock(seconds)}. ")
        append("Average ${workout.avgBpm ?: "unknown"}, highest ${workout.maxBpm ?: "unknown"} bpm.\n")
        benchmark?.let { append("It was a benchmark. Estimated VO2max ${"%.1f".format(it.vo2Max)}.\n") }
        val sessionId = workout.hrSessionId
        val maxBpm = workout.zoneMaxBpm
        if (sessionId != null && maxBpm != null) {
            val minutes = (seconds / 60 + 1).coerceAtLeast(1)
            append(HeartRateOverview.describe(samples(sessionId), endMs, minutes, HeartRateZones(maxBpm)))
        }
    }
}
