package app.haanf.ai

import app.haanf.ai.CoachContext.counts
import app.haanf.ai.CoachTools.text
import app.haanf.data.BenchmarkEntity
import app.haanf.data.WorkoutEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Lets chat look through saved workouts on demand, rather than receiving every detail with
 * each message. Nothing here can change a workout.
 */
class WorkoutLookup(
    private val between: suspend (fromMs: Long, toMs: Long) -> List<WorkoutEntity>,
    private val workout: suspend (Long) -> WorkoutEntity?,
    private val benchmarks: suspend () -> List<BenchmarkEntity>,
    private val detail: WorkoutDetail,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Lists matching workouts newest first, one line each, starting with the id. */
    suspend fun find(call: ToolCall): ToolOutcome {
        val input = call.input
        val from = date(input.text("from_date")) ?: return badDate(call)
        val to = date(input.text("to_date")) ?: return badDate(call)
        val fromMs = from.value?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        val toMs = to.value?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        val activity = input.text("activity") ?: ANY
        val kind = input.text("kind") ?: ANY
        val found = between(fromMs, toMs).filter { it.counts() && matches(it, activity, kind) }
        if (found.isEmpty()) return ToolOutcome(call.id, "No workouts match. Workouts under two minutes are never listed.")
        val text = buildString {
            append("${found.size} ${if (found.size == 1) "workout matches" else "workouts match"}, newest first:\n")
            found.take(MAX_FOUND).forEach { append("- ${CoachContext.line(it, zone)}\n") }
            if (found.size > MAX_FOUND) {
                append("${found.size - MAX_FOUND} older ones are not shown. Narrow the dates to see them.\n")
            }
        }
        return ToolOutcome(call.id, text.trimEnd())
    }

    /** Each workout in full: totals, kilometres or heart rate, and what was said during it. */
    suspend fun open(call: ToolCall): ToolOutcome {
        val ids = (call.input["workout_ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.toLong() }
            ?.distinct()
            .orEmpty()
        if (ids.isEmpty()) return ToolOutcome(call.id, "Give at least one workout id.", isError = true)
        val saved = benchmarks()
        val opened = mutableListOf<String>()
        for (id in ids.take(MAX_OPEN)) opened += describe(id, saved)
        val text = opened.joinToString("\n\n")
        val dropped = if (ids.size > MAX_OPEN) "\n\nOnly the first $MAX_OPEN were opened. Ask again for the rest." else ""
        return ToolOutcome(call.id, text + dropped)
    }

    private suspend fun describe(id: Long, saved: List<BenchmarkEntity>): String {
        val found = workout(id)?.takeIf { it.endedAtMs != null } ?: return "Workout #$id: there is no such workout."
        return buildString {
            append(detail.describe(found, saved.firstOrNull { it.workoutId == id }, "Workout #$id").trimEnd())
            found.review?.takeIf { it.isNotBlank() }?.let { append("\nThe review you wrote afterwards: $it") }
        }
    }

    private fun matches(workout: WorkoutEntity, activity: String, kind: String): Boolean {
        val name = CoachContext.activityName(workout)
        val activityMatches = when (activity) {
            "ride" -> name == "ride"
            "outdoor_run" -> name == "outdoor run"
            "outdoor_walk" -> name == "outdoor walk"
            "treadmill" -> name == "treadmill" || name == "treadmill walk"
            else -> true
        }
        val kindMatches = when (kind) {
            "program" -> workout.origin == "PROGRAM"
            "coach" -> workout.origin == "AI"
            "benchmark" -> workout.origin == "BENCHMARK"
            "free" -> workout.origin !in setOf("PROGRAM", "AI", "BENCHMARK")
            else -> true
        }
        return activityMatches && kindMatches
    }

    /** A date, or no limit when blank. Null means it could not be read. */
    private fun date(text: String?): Limit? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return Limit(null)
        return try {
            Limit(LocalDate.parse(trimmed))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private class Limit(val value: LocalDate?)

    private fun badDate(call: ToolCall) =
        ToolOutcome(call.id, "Dates must look like 2026-11-05, or be empty for no limit.", isError = true)

    companion object {
        private const val ANY = "any"
        const val MAX_FOUND = 20
        const val MAX_OPEN = 3

        val FIND_WORKOUTS = ToolSpec(
            name = "find_workouts",
            description = "List saved workouts, newest first, with their ids. Use it to find workouts older " +
                "than the recent ones you were given, or of one kind.",
            properties = buildJsonObject {
                put(
                    "activity",
                    CoachTools.choice("Which activity.", listOf(ANY, "ride", "outdoor_run", "outdoor_walk", "treadmill")),
                )
                put("kind", CoachTools.choice("Which kind of workout.", listOf(ANY, "program", "coach", "free", "benchmark")))
                put("from_date", CoachTools.string("The first day to include, such as 2026-10-01, or empty for no limit."))
                put("to_date", CoachTools.string("The last day to include, such as 2026-11-05, or empty for no limit."))
            },
        )

        val OPEN_WORKOUTS = ToolSpec(
            name = "open_workouts",
            description = "Open up to $MAX_OPEN saved workouts in full: totals, each kilometre or heart rate in " +
                "five-minute steps, climbs, and what the coach and athlete said during it, with times. Open " +
                "two together to compare them.",
            properties = buildJsonObject {
                putJsonObject("workout_ids") {
                    put("type", "array")
                    put("description", "Workout ids, such as 12, up to $MAX_OPEN.")
                    putJsonObject("items") { put("type", "integer") }
                }
            },
        )
    }
}
