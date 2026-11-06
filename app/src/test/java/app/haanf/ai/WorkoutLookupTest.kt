package app.haanf.ai

import app.haanf.data.BenchmarkEntity
import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEntity
import app.haanf.training.TimedBpm
import app.haanf.workout.TimelineEntry
import app.haanf.workout.TimelineKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WorkoutLookupTest {
    private fun at(day: String, hour: Int = 7): Long =
        LocalDate.parse(day).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun workout(id: Long, day: String, activity: String, origin: String = "FREE", minutes: Int = 30) = WorkoutEntity(
        id = id, startedAtMs = at(day), endedAtMs = at(day) + minutes * 60_000L, title = "Workout $id",
        activity = activity, origin = origin, zoneMaxBpm = 193, hrSessionId = id, avgBpm = 140, maxBpm = 170,
    )

    private val ride = workout(5, "2026-11-05", "CYCLING").copy(
        distanceMetres = 4_200.0, movingSeconds = 1_300, climbMetres = 91.0, descentMetres = 88.0, avgWatts = 120,
        bikeType = "HYBRID", effortRating = 4, review = "Strong climb, steady descent.",
    )
    private val earlierRide = workout(3, "2026-10-20", "CYCLING")
    private val run = workout(4, "2026-11-01", "OUTDOOR_RUN", origin = "PROGRAM")
    private val walk = workout(2, "2026-10-10", "OUTDOOR_RUN", origin = "BENCHMARK", minutes = 15)
    private val falseStart = workout(6, "2026-11-04", "CYCLING", minutes = 0)
    private val all = listOf(falseStart, ride, run, earlierRide, walk).sortedByDescending { it.startedAtMs }

    private val ranges = mutableListOf<Pair<Long, Long>>()

    private fun lookup(workouts: List<WorkoutEntity> = all) = WorkoutLookup(
        between = { from, to ->
            ranges += from to to
            workouts.filter { it.startedAtMs in from until to }
        },
        workout = { id -> workouts.firstOrNull { it.id == id } },
        benchmarks = {
            listOf(BenchmarkEntity(workoutId = 2, protocol = "ROCKPORT", takenAtMs = at("2026-10-10"), timeSeconds = 900,
                heartRateBpm = 150, ageYears = 22, sex = "MALE", weightKg = 61.0, vo2Max = 48.3))
        },
        detail = WorkoutDetail(
            samples = { (0 until 1_800 step 5).map { TimedBpm(at("2026-11-05") + it * 1000L, 130) } },
            timeline = { id ->
                if (id == 5L) {
                    listOf(
                        TimelineEntry(250, TimelineKind.ATHLETE, "Big hill coming up.", 147),
                        TimelineEntry(600, TimelineKind.CLIMB, "Climb ended: 36 m up over 0.60 km in 3:10, 6% average.", 162),
                    )
                } else {
                    emptyList()
                }
            },
            splits = { id ->
                if (id == 5L) {
                    listOf(SplitEntity(workoutId = 5, number = 1, metres = 1_000.0, seconds = 372, endElapsedSeconds = 372,
                        avgBpm = 141, climbMetres = 32.0, descentMetres = 0.0, avgWatts = 158, gapSecondsPerKm = null))
                } else {
                    emptyList()
                }
            },
            zone = ZoneOffset.UTC,
        ),
        zone = ZoneOffset.UTC,
    )

    private fun find(activity: String = "any", kind: String = "any", from: String = "", to: String = "") =
        ToolCall("f", "find_workouts", buildJsonObject {
            put("activity", activity)
            put("kind", kind)
            put("from_date", from)
            put("to_date", to)
        })

    private fun open(vararg ids: Long) =
        ToolCall("o", "open_workouts", buildJsonObject { put("workout_ids", JsonArray(ids.map { JsonPrimitive(it) })) })

    @Test
    fun `find lists rides newest first with their ids, and leaves out false starts`() = runBlocking {
        val result = lookup().find(find(activity = "ride"))
        assertFalse(result.isError)
        assertTrue(result.content, result.content.startsWith("2 workouts match, newest first:"))
        assertTrue(result.content, result.content.contains("- #5 2026-11-05: Workout 5 (free), ride, 30:00."))
        assertTrue(result.content, result.content.contains("- #3 2026-10-20: Workout 3 (free), ride"))
        assertTrue(result.content.indexOf("#5 ") < result.content.indexOf("#3 "))
        assertFalse(result.content, result.content.contains("#6 "))
        assertFalse(result.content, result.content.contains("#4 "))
    }

    @Test
    fun `find narrows by kind and by dates, both days included`() = runBlocking {
        val programs = lookup().find(find(kind = "program"))
        assertTrue(programs.content, programs.content.startsWith("1 workout matches"))
        assertTrue(programs.content, programs.content.contains("#4 2026-11-01: Workout 4 (program), outdoor run"))

        val october = lookup().find(find(from = "2026-10-10", to = "2026-10-20"))
        assertTrue(october.content, october.content.contains("#3 "))
        assertTrue(october.content, october.content.contains("#2 2026-10-10: Workout 2 (benchmark), outdoor walk"))
        assertFalse(october.content, october.content.contains("#5 "))
        assertEquals(at("2026-10-10", 0) to at("2026-10-21", 0), ranges.last())
    }

    @Test
    fun `find shows twenty at most and says how many more there are`() = runBlocking {
        val many = (1L..25L).map { workout(100 + it, LocalDate.parse("2026-09-01").plusDays(it).toString(), "CYCLING") }
            .sortedByDescending { it.startedAtMs }
        val result = lookup(many).find(find())
        assertTrue(result.content, result.content.startsWith("25 workouts match"))
        assertEquals(WorkoutLookup.MAX_FOUND, Regex("(?m)^- #").findAll(result.content).count())
        assertTrue(result.content, result.content.endsWith("5 older ones are not shown. Narrow the dates to see them."))
    }

    @Test
    fun `find refuses a date it cannot read, and says when nothing matches`() = runBlocking {
        val bad = lookup().find(find(from = "last week"))
        assertTrue(bad.isError)
        assertTrue(bad.content.contains("2026-11-05"))
        val none = lookup().find(find(activity = "treadmill"))
        assertFalse(none.isError)
        assertTrue(none.content, none.content.startsWith("No workouts match."))
    }

    @Test
    fun `open gives the kilometres, the timeline word for word, the rating and the review`() = runBlocking {
        val result = lookup().open(open(5))
        val text = result.content
        assertTrue(text, text.startsWith("Workout #5: Workout 5 (free), ride, 2026-11-05 at 07:00, 30:00 long."))
        assertTrue(text, text.contains("Kilometre 1 in 6:12, 9.7 km/h, +32 m -0 m, about 158 W."))
        assertTrue(text, text.contains("[4:10] Athlete: Big hill coming up. (147 bpm)"))
        assertTrue(text, text.contains("[10:00] Climb ended: 36 m up over 0.60 km in 3:10, 6% average. (162 bpm)"))
        assertTrue(text, text.contains("The athlete rated the effort 4 of 5."))
        assertTrue(text, text.contains("The review you wrote afterwards: Strong climb, steady descent."))
    }

    @Test
    fun `open compares workouts side by side, and without kilometres gives timed heart rate`() = runBlocking {
        val text = lookup().open(open(5, 2)).content
        assertTrue(text, text.contains("Workout #5: "))
        assertTrue(text, text.contains("Workout #2: Workout 2 (benchmark), outdoor walk"))
        assertTrue(text, text.contains("It was a benchmark. Estimated VO2max 48.3."))
        assertTrue(text, text.contains("Heart rate in five-minute steps:"))
    }

    @Test
    fun `open says which ids do not exist and opens three at most`() = runBlocking {
        val missing = lookup().open(open(99)).content
        assertEquals("Workout #99: there is no such workout.", missing)

        val text = lookup().open(open(5, 4, 3, 2)).content
        assertEquals(3, Regex("(?m)^Workout #\\d+: ").findAll(text).count())
        assertFalse(text, text.contains("Workout #2: "))
        assertTrue(text, text.endsWith("Only the first 3 were opened. Ask again for the rest."))

        assertTrue(lookup().open(ToolCall("o", "open_workouts", buildJsonObject {})).isError)
    }
}
