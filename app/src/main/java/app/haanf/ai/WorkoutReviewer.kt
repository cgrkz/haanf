package app.haanf.ai

import app.haanf.ai.CoachTools.text
import app.haanf.data.BenchmarkEntity
import app.haanf.data.FactKind
import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEntity
import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.training.Split
import app.haanf.training.TimedBpm
import app.haanf.workout.TimelineEntry
import app.haanf.workout.WorkoutText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    /** Null when no key is set. Failures surface as [LlmException], so the screen can offer a retry. */
    suspend fun review(workout: WorkoutEntity, benchmark: BenchmarkEntity?): String? {
        val client = llm.client() ?: return null
        val snapshot = data.snapshot() ?: return null
        // The workout under review is described below, so it is left out of the list.
        val others = snapshot.copy(workouts = snapshot.workouts.filter { it.id != workout.id })
        val request = LlmRequest(
            system = CoachPrompts.reviewer(tone()),
            items = listOf(LlmItem.User(CoachContext.render(others, zone) + "\n\n" + workoutText(workout, benchmark))),
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

    private suspend fun workoutText(workout: WorkoutEntity, benchmark: BenchmarkEntity?): String = buildString {
        val endMs = workout.endedAtMs ?: workout.startedAtMs
        val seconds = ((endMs - workout.startedAtMs) / 1000).toInt()
        val ride = workout.activity == TrainingActivity.CYCLING.name
        val activity = TrainingActivity.entries.firstOrNull { it.name == workout.activity }?.label?.lowercase() ?: workout.activity
        val startedAt = TIME_OF_DAY.format(Instant.ofEpochMilli(workout.startedAtMs).atZone(zone))
        append("The workout to review: ${workout.title}, $activity, started at $startedAt, ${WorkoutText.clock(seconds)} long")
        if (workout.coached) append(", coached")
        append(". Times in square brackets are minutes and seconds into the workout.\n")
        append("Heart rate: average ${workout.avgBpm ?: "unknown"}, highest ${workout.maxBpm ?: "unknown"} bpm. ")
        append("Time in zones: easy ${WorkoutText.clock(workout.easySeconds)}, hard ${WorkoutText.clock(workout.hardSeconds)}, ")
        append("very hard ${WorkoutText.clock(workout.veryHardSeconds)}")
        if (workout.noReadingSeconds >= 30) append(", no reading ${WorkoutText.clock(workout.noReadingSeconds)}")
        append(".\n")
        workout.hrDropBpm?.let { append("Heart rate fell $it bpm in the minute after the last hard step.\n") }
        motionText(workout, ride)?.let { append(it).append("\n") }
        benchmark?.let { append("It was a benchmark. Estimated VO2max ${"%.1f".format(it.vo2Max)}.\n") }

        val kilometres = splits(workout.id)
        val sessionId = workout.hrSessionId
        val maxBpm = workout.zoneMaxBpm
        if (kilometres.isNotEmpty()) {
            append("\nEach kilometre:\n")
            kilometres.forEach { append(WorkoutText.splitLine(it.toSplit(), ride)).append("\n") }
        } else if (sessionId != null && maxBpm != null && seconds > 0) {
            append("\nHeart rate in five-minute steps:\n")
            append(
                WorkoutOverview.describe(
                    samples = samples(sessionId),
                    motion = emptyList(),
                    workoutStartMs = workout.startedAtMs,
                    nowSeconds = seconds,
                    lastMinutes = (seconds + 59) / 60,
                    zones = HeartRateZones(maxBpm),
                    ride = ride,
                    bucketSeconds = FIVE_MINUTES,
                ),
            )
            append("\n")
        }

        val lines = timeline(workout.id)
        append("\nWhat was said and what happened, in order:\n")
        if (lines.isEmpty()) append("Nothing was recorded.\n")
        lines.forEach { append(it.line()).append("\n") }
    }

    private fun motionText(workout: WorkoutEntity, ride: Boolean): String? {
        val parts = mutableListOf<String>()
        val metres = workout.distanceMetres
        if (metres != null && metres > 0) {
            val moving = workout.movingSeconds
            parts += "Distance ${WorkoutText.km(metres)} km" + (moving?.let { ", moving ${WorkoutText.clock(it)}" } ?: "")
            val speed = moving?.takeIf { it > 0 }?.let { metres / it }
            if (speed != null) {
                parts += if (ride) {
                    "average ${WorkoutText.kmh(speed)} km/h" + (workout.maxSpeedMps?.let { ", top ${WorkoutText.kmh(it)} km/h" } ?: "")
                } else {
                    "average pace ${WorkoutText.paceOf(speed)} /km"
                }
            }
        }
        workout.gapSecondsPerKm?.let { parts += "grade-adjusted pace ${WorkoutText.pace(it)} /km" }
        if (workout.climbMetres != null || workout.descentMetres != null) {
            parts += "climbed ${(workout.climbMetres ?: 0.0).roundToInt()} m, descended ${(workout.descentMetres ?: 0.0).roundToInt()} m"
        }
        workout.avgWatts?.let { watts ->
            val bike = workout.bikeType?.let { name -> app.haanf.training.BikeType.entries.firstOrNull { it.name == name } }
            parts += "estimated average power $watts W" + (bike?.let { " on a ${it.label.lowercase()}" } ?: "")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".")?.replaceFirstChar { it.uppercase() }
    }

    private fun SplitEntity.toSplit() = Split(
        number = number,
        metres = metres,
        seconds = seconds,
        endElapsedSeconds = endElapsedSeconds,
        avgBpm = avgBpm,
        climbMetres = climbMetres,
        descentMetres = descentMetres,
        avgWatts = avgWatts,
        gapSecondsPerKm = gapSecondsPerKm,
    )

    private companion object {
        const val MAX_ROUNDS = 3
        const val FIVE_MINUTES = 300
        val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
