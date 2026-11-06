package app.haanf.ai

import app.haanf.data.BenchmarkEntity
import app.haanf.data.CoachFactEntity
import app.haanf.data.TalkAnswer
import app.haanf.data.TalkCheckEntity
import app.haanf.data.WorkoutEntity
import app.haanf.health.VitalTrend
import app.haanf.health.Vitals
import app.haanf.talktest.TrainingActivity
import app.haanf.training.FitnessNorms
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import app.haanf.training.Zone
import app.haanf.workout.WorkoutText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Everything the AI may know. Location, device identifiers and audio have no place here. */
data class CoachSnapshot(
    val today: LocalDate,
    val ageYears: Int,
    val sex: Sex,
    val weightKg: Double,
    val zones: HeartRateZones,
    val maxIsMeasured: Boolean,
    val facts: List<CoachFactEntity>,
    val benchmarks: List<BenchmarkEntity>,
    val workouts: List<WorkoutEntity>,
    val talkChecks: List<TalkCheckEntity>,
    val vitals: Vitals?,
    val program: String?,
)

/** Turns a snapshot into the plain text every AI feature starts from. */
object CoachContext {
    fun render(snapshot: CoachSnapshot, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        fun day(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

        append("Today is ${snapshot.today}.\n")
        append("Athlete: ${snapshot.ageYears} years old, ${snapshot.sex.label.lowercase()}, ")
        append("${snapshot.weightKg} kg.\n")
        val source = if (snapshot.maxIsMeasured) "measured" else "estimated from age"
        append("Maximum heart rate ${snapshot.zones.maxBpm} bpm ($source).\n")
        val zones = listOf(Zone.EASY, Zone.HARD, Zone.VERY_HARD).joinToString {
            val range = snapshot.zones.rangeFor(it)
            "${it.label} ${range.first}-${range.last}"
        }
        append("Heart rate zones: $zones.\n")

        append("\nInjuries, conditions and notes:\n")
        if (snapshot.facts.isEmpty()) append("None recorded.\n")
        snapshot.facts.forEach { append("- ${it.kind.lowercase()}: ${it.text}\n") }

        append("\nFitness benchmarks, newest first:\n")
        if (snapshot.benchmarks.isEmpty()) append("None yet.\n")
        snapshot.benchmarks.take(MAX_BENCHMARKS).forEach { benchmark ->
            val level = FitnessNorms.level(benchmark.vo2Max, benchmark.ageYears, Sex.valueOf(benchmark.sex))
            append("- ${day(benchmark.takenAtMs)}: estimated VO2max ${"%.1f".format(benchmark.vo2Max)} ")
            append("(${level.label.lowercase()}), mile in ${WorkoutText.clock(benchmark.timeSeconds)} ")
            append("at ${benchmark.heartRateBpm} bpm.\n")
        }

        // Starts that lasted seconds say nothing about fitness, and would mislead.
        val workouts = snapshot.workouts.filter { it.durationSeconds() >= MIN_WORKOUT_SECONDS }.take(MAX_WORKOUTS)
        append("\nRecent workouts, newest first:\n")
        if (workouts.isEmpty()) append("None yet.\n")
        workouts.forEach { append("- ${describe(it, day(it.startedAtMs))}\n") }

        snapshot.program?.let { append("\nProgram: $it\n") }

        append("\nRecovery:\n")
        append(vitalLine("Resting heart rate", "bpm", snapshot.vitals?.restingHeartRate))
        append(vitalLine("HRV", "ms", snapshot.vitals?.hrv))
        append(vitalLine("Sleep", "hours", snapshot.vitals?.sleepHours))

        if (snapshot.talkChecks.isNotEmpty()) {
            append("\nRecent talk tests:\n")
            snapshot.talkChecks.forEach { check ->
                val answer = TalkAnswer.valueOf(check.answer).summary
                append("- ${day(check.atMs)}: $answer at ${check.bpm ?: "unknown"} bpm.\n")
            }
        }
    }

    private fun WorkoutEntity.durationSeconds(): Int = (((endedAtMs ?: startedAtMs) - startedAtMs) / 1000).toInt()

    private fun describe(workout: WorkoutEntity, day: LocalDate): String {
        val seconds = workout.durationSeconds()
        val benchmark = workout.origin == "BENCHMARK"
        val activity = when (workout.activity) {
            TrainingActivity.CYCLING.name -> "ride"
            TrainingActivity.TREADMILL.name -> if (benchmark) "treadmill walk" else "treadmill"
            else -> if (benchmark) "outdoor walk" else "outdoor run"
        }
        val kind = when (workout.origin) {
            "PROGRAM" -> "program" + (workout.programWeek?.let { ", week $it" } ?: "")
            "AI" -> "coach-planned"
            "BENCHMARK" -> "benchmark"
            else -> "free"
        }
        val heartRate = workout.avgBpm?.let { "Heart rate average $it, highest ${workout.maxBpm} bpm" } ?: "No heart rate"
        val zones = "easy ${workout.easySeconds / 60} min, hard ${workout.hardSeconds / 60} min, " +
            "very hard ${workout.veryHardSeconds / 60} min"
        return buildString {
            append("$day: ${workout.title} ($kind), $activity, ${WorkoutText.clock(seconds)}. $heartRate; $zones.")
            motion(workout, activity == "ride")?.let { append(" $it") }
            workout.hrDropBpm?.let { append(" Heart rate fell $it bpm in the minute after the last hard step.") }
            workout.effortRating?.let { append(" Rated effort $it of 5.") }
            if (workout.endReason == "abandoned") append(" Stopped early.")
        }
    }

    private fun motion(workout: WorkoutEntity, ride: Boolean): String? {
        val parts = mutableListOf<String>()
        val metres = workout.distanceMetres
        val moving = workout.movingSeconds
        if (metres != null && metres > 0) {
            val speed = if (moving != null && moving > 0) metres / moving else null
            parts += when {
                speed == null -> "${WorkoutText.km(metres)} km"
                ride -> "${WorkoutText.km(metres)} km at ${WorkoutText.kmh(speed)} km/h moving"
                else -> "${WorkoutText.km(metres)} km at ${WorkoutText.paceOf(speed)} /km"
            }
        }
        workout.climbMetres?.takeIf { it >= 1 }?.let { parts += "climbed ${it.roundToInt()} m" }
        workout.avgWatts?.let { parts += "about $it W" }
        workout.gapSecondsPerKm?.let { parts += "grade-adjusted ${WorkoutText.pace(it)} /km" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".")?.replaceFirstChar { it.uppercase() }
    }

    private fun vitalLine(name: String, unit: String, trend: VitalTrend?): String {
        if (trend == null) return "$name: no data.\n"
        val latest = "%.1f".format(trend.latest.value)
        val age = if (trend.stale) " NOT RECENT: ${trend.daysSinceLatest} days old" else ""
        val averages = listOfNotNull(
            trend.recentAverage?.let { "last 7 days ${"%.1f".format(it)}" },
            trend.previousAverage?.let { "7 days before ${"%.1f".format(it)}" },
        ).joinToString(", ")
        return "$name: latest $latest $unit on ${trend.latest.date}$age. $averages\n"
    }

    private const val MAX_BENCHMARKS = 5
    private const val MAX_WORKOUTS = 14
    private const val MIN_WORKOUT_SECONDS = 120
}
