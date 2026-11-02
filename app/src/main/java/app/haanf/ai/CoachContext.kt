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

        append("\nRecent workouts, newest first:\n")
        if (snapshot.workouts.isEmpty()) append("None yet.\n")
        snapshot.workouts.forEach { append("- ${describe(it, day(it.startedAtMs))}\n") }

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

    private fun describe(workout: WorkoutEntity, day: LocalDate): String {
        val seconds = ((workout.endedAtMs ?: workout.startedAtMs) - workout.startedAtMs) / 1000
        val activity = TrainingActivity.entries.firstOrNull { it.name == workout.activity }?.label ?: workout.activity
        val heartRate = workout.avgBpm?.let { "average $it, highest ${workout.maxBpm} bpm" } ?: "no heart rate"
        val zones = "easy ${workout.easySeconds / 60} min, hard ${workout.hardSeconds / 60} min, " +
            "very hard ${workout.veryHardSeconds / 60} min"
        return "$day: ${workout.title}, ${activity.lowercase()}, ${WorkoutText.clock(seconds.toInt())}, $heartRate, $zones."
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
}
