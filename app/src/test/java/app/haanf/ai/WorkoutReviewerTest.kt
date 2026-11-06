package app.haanf.ai

import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEntity
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import app.haanf.training.TimedBpm
import app.haanf.workout.TimelineEntry
import app.haanf.workout.TimelineKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WorkoutReviewerTest {
    private val start = 1_793_897_649_850L
    private val ride = WorkoutEntity(
        id = 5, startedAtMs = start, endedAtMs = start + 1_400_000, title = "Free workout", activity = "CYCLING",
        origin = "FREE", zoneMaxBpm = 193, hrSessionId = 7, avgBpm = 149, maxBpm = 185, easySeconds = 447,
        hardSeconds = 421, veryHardSeconds = 525, coached = true, distanceMetres = 4_200.0, movingSeconds = 1_300,
        maxSpeedMps = 8.4, climbMetres = 91.0, descentMetres = 88.0, avgWatts = 120, bikeType = "HYBRID",
    )
    private val earlier = ride.copy(id = 4, startedAtMs = start - 86_400_000, endedAtMs = start - 86_400_000 + 1_800_000, title = "Yesterday's ride")
    private val data = object : CoachDataSource {
        override suspend fun snapshot() = CoachSnapshot(
            LocalDate.of(2026, 11, 5), 22, Sex.MALE, 61.0, HeartRateZones(193), false,
            emptyList(), emptyList(), listOf(ride, earlier), emptyList(), null, null,
        )
        override suspend fun ruleContext(minutesAvailable: Int, feeling: Int) =
            RuleContext(LocalDate.of(2026, 11, 5), minutesAvailable, feeling, 5, emptyList(), emptyList(), false)
        override suspend fun lastBenchmarkDay(): LocalDate? = null
    }
    private val timeline = listOf(
        TimelineEntry(0, TimelineKind.COACH, "Nice start. Keep it easy.", 117),
        TimelineEntry(250, TimelineKind.ATHLETE, "Big hill coming up.", 147),
        TimelineEntry(260, TimelineKind.CLIMB, "Climb started, about 7% gradient.", 148),
        TimelineEntry(1_025, TimelineKind.ATHLETE, "Knee feels a bit tight on the descent.", 157),
    )
    private val splits = listOf(
        SplitEntity(workoutId = 5, number = 1, metres = 1_000.0, seconds = 372, endElapsedSeconds = 372, avgBpm = 141,
            climbMetres = 32.0, descentMetres = 0.0, avgWatts = 158, gapSecondsPerKm = null),
    )

    private fun reviewer(llm: FakeLlm, proposed: MutableList<Pair<String, String>> = mutableListOf()) = WorkoutReviewer(
        llm = llm,
        data = data,
        tone = { CoachTone.WARM },
        samples = { (0 until 1_400 step 2).map { TimedBpm(start + it * 1000L, 120 + it / 20) } },
        timeline = { timeline },
        splits = { splits },
        proposeFact = { kind, text -> proposed += kind to text },
        zone = ZoneOffset.UTC,
    )

    @Test
    fun `the review reads the timeline word for word, the kilometres and the terrain`() = runBlocking {
        val llm = FakeLlm().then { FakeLlm.text("Good ride.") }
        assertEquals("Good ride.", reviewer(llm).review(ride, null))
        val text = (llm.requests.single().items.single() as LlmItem.User).text
        assertTrue(text, text.contains("[4:10] Athlete: Big hill coming up. (147 bpm)"))
        assertTrue(text, text.contains("[4:20] Climb started, about 7% gradient. (148 bpm)"))
        assertTrue(text, text.contains("Kilometre 1 in 6:12, 9.7 km/h, +32 m -0 m, about 158 W."))
        assertTrue(text, text.contains("Distance 4.20 km, moving 21:40, average 11.6 km/h, top 30.2 km/h"))
        assertTrue(text, text.contains("climbed 91 m, descended 88 m, estimated average power 120 W on a hybrid."))
    }

    @Test
    fun `the workout under review is not also in the list of recent workouts`() = runBlocking {
        val llm = FakeLlm().then { FakeLlm.text("ok") }
        reviewer(llm).review(ride, null)
        val text = (llm.requests.single().items.single() as LlmItem.User).text
        assertTrue(text.contains("Yesterday's ride"))
        assertEquals(1, Regex("Free workout").findAll(text).count())
    }

    @Test
    fun `a pain mentioned during the workout can be proposed as a fact, then the review is written`() = runBlocking {
        val proposed = mutableListOf<Pair<String, String>>()
        val llm = FakeLlm()
            .then {
                FakeLlm.call(
                    "remember_fact",
                    buildJsonObject {
                        put("kind", "INJURY")
                        put("fact", "Knee tight on descents")
                    },
                )
            }
            .then { FakeLlm.text("Strong climb. Keep an eye on that knee.") }
        val review = reviewer(llm, proposed).review(ride, null)
        assertEquals(listOf("INJURY" to "Knee tight on descents"), proposed)
        assertEquals("Strong climb. Keep an eye on that knee.", review)
    }

    @Test
    fun `without GPS the review gets heart rate in timed five-minute steps`() = runBlocking {
        val llm = FakeLlm().then { FakeLlm.text("ok") }
        val indoor = ride.copy(activity = "TREADMILL", distanceMetres = null, movingSeconds = null, climbMetres = null, descentMetres = null, avgWatts = null)
        WorkoutReviewer(llm, data, { CoachTone.WARM }, { (0 until 1_400 step 2).map { TimedBpm(start + it * 1000L, 130) } }, zone = ZoneOffset.UTC)
            .review(indoor, null)
        val text = (llm.requests.single().items.single() as LlmItem.User).text
        assertTrue(text, text.contains("[0:00-5:00] heart rate 130 average"))
        assertTrue(text, text.contains("[20:00-23:20]"))
        assertFalse(text.contains("Each kilometre"))
    }
}
