package app.haanf.ui

import app.haanf.data.ProfileEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.training.BenchmarkInputs
import app.haanf.training.HeartRateZones
import app.haanf.training.MaxHeartRate
import app.haanf.training.Sex
import app.haanf.training.WorkoutPlan
import app.haanf.training.Zone
import app.haanf.workout.AiWorkoutContext
import app.haanf.workout.ProgramSlot
import java.time.LocalDate

/** A workout chosen but not started yet. Get ready turns it into a request. */
data class PendingWorkout(
    val title: String,
    val plan: WorkoutPlan?,
    val slot: ProgramSlot?,
    val benchmark: Boolean = false,
    val ai: AiWorkoutContext? = null,
    val hard: Boolean = false,
    val eyebrow: String? = null,
)

val LOADING = ProfileEntity(birthYear = 0)
const val FREE_WORKOUT_TITLE = "Free workout"
const val BENCHMARK_TITLE = "1-mile walk benchmark"

fun ProfileEntity.zones(today: LocalDate): HeartRateZones =
    HeartRateZones.of(MaxHeartRate.resolve(today.year - birthYear, measuredMaxBpm), zoneStarts)

/** What the benchmark needs, or null until setup has collected it. */
fun ProfileEntity.benchmarkInputs(today: LocalDate): BenchmarkInputs? {
    val knownSex = sex?.let(Sex::valueOf) ?: return null
    val weight = weightKg ?: return null
    return BenchmarkInputs(today.year - birthYear, knownSex, weight)
}

fun ProfileEntity.device(): HeartRateDevice? {
    val id = deviceId ?: return null
    val kind = deviceSourceKind ?: return null
    return HeartRateDevice(id, deviceName, kind)
}

/** "Zone 2, 116 to 134 bpm" and the like, for a step's target. */
fun HeartRateZones.target(zone: Zone): String = "${zone.label}, ${bpmText(zone)}"

/** "under 116 bpm", "116 to 134 bpm" or "174 bpm and over". */
fun HeartRateZones.bpmText(zone: Zone): String {
    val range = rangeFor(zone)
    return when (zone) {
        Zone.ZONE_1 -> "under ${range.last + 1} bpm"
        Zone.ZONE_5 -> "${range.first} bpm and over"
        else -> "${range.first} to ${range.last} bpm"
    }
}

fun HeartRateZones.describe(zone: Zone): String {
    val range = rangeFor(zone)
    return "${zone.label}, ${range.first} to ${range.last} bpm"
}
