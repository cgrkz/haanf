package app.haanf.ai

import app.haanf.data.BenchmarkEntity
import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEntity
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BikeType
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
 * One saved workout in full, as the AI reads it: totals, each kilometre or heart rate in
 * five-minute steps, and the timeline word for word. Used by the review and by chat.
 */
class WorkoutDetail(
    private val samples: suspend (Long) -> List<TimedBpm>,
    private val timeline: suspend (Long) -> List<TimelineEntry>,
    private val splits: suspend (Long) -> List<SplitEntity>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun describe(workout: WorkoutEntity, benchmark: BenchmarkEntity?, label: String): String = buildString {
        val endMs = workout.endedAtMs ?: workout.startedAtMs
        val seconds = ((endMs - workout.startedAtMs) / 1000).toInt()
        val ride = workout.activity == TrainingActivity.CYCLING.name
        val started = Instant.ofEpochMilli(workout.startedAtMs).atZone(zone)
        append("$label: ${workout.title} (${CoachContext.kindName(workout)}), ${CoachContext.activityName(workout)}, ")
        append("${started.toLocalDate()} at ${TIME_OF_DAY.format(started)}, ${WorkoutText.clock(seconds)} long")
        if (workout.coached) append(", coached")
        if (workout.endReason == "abandoned") append(", stopped early")
        append(". Times in square brackets are minutes and seconds into the workout.\n")
        append("Heart rate: average ${workout.avgBpm ?: "unknown"}, highest ${workout.maxBpm ?: "unknown"} bpm. ")
        append("Time in zones: easy ${WorkoutText.clock(workout.easySeconds)}, hard ${WorkoutText.clock(workout.hardSeconds)}, ")
        append("very hard ${WorkoutText.clock(workout.veryHardSeconds)}")
        if (workout.noReadingSeconds >= 30) append(", no reading ${WorkoutText.clock(workout.noReadingSeconds)}")
        append(".\n")
        workout.hrDropBpm?.let { append("Heart rate fell $it bpm in the minute after the last hard step.\n") }
        motionText(workout, ride)?.let { append(it).append("\n") }
        benchmark?.let { append("It was a benchmark. Estimated VO2max ${"%.1f".format(it.vo2Max)}.\n") }
        workout.effortRating?.let { append("The athlete rated the effort $it of 5.\n") }

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
            val bike = workout.bikeType?.let { name -> BikeType.entries.firstOrNull { it.name == name } }
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
        const val FIVE_MINUTES = 300
        val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
