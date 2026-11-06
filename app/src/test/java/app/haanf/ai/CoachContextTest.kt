package app.haanf.ai

import app.haanf.data.BenchmarkEntity
import app.haanf.data.CoachFactEntity
import app.haanf.data.TalkCheckEntity
import app.haanf.data.WorkoutEntity
import app.haanf.health.DayValue
import app.haanf.health.VitalTrend
import app.haanf.health.Vitals
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CoachContextTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 2)
    private val snapshot = CoachSnapshot(
        today = today,
        ageYears = 30,
        sex = Sex.MALE,
        weightKg = 75.0,
        zones = HeartRateZones(187),
        maxIsMeasured = false,
        facts = listOf(CoachFactEntity(createdAtMs = 0, kind = "INJURY", text = "Left knee sore", status = "ACTIVE")),
        benchmarks = listOf(
            BenchmarkEntity(
                workoutId = 1, protocol = "rockport-1-mile", takenAtMs = 0, timeSeconds = 900,
                heartRateBpm = 150, ageYears = 30, sex = "MALE", weightKg = 75.0, vo2Max = 41.2,
            ),
        ),
        workouts = listOf(
            WorkoutEntity(
                startedAtMs = 0, endedAtMs = 1_800_000, title = "Easy 30 minutes", activity = "TREADMILL",
                origin = "PROGRAM", deviceName = "SECRET-DEVICE-NAME", avgBpm = 128, maxBpm = 140, easySeconds = 1700,
            ),
        ),
        talkChecks = listOf(TalkCheckEntity(workoutId = 1, atMs = 0, bpm = 140, answer = "SOME_EFFORT")),
        vitals = Vitals(
            restingHeartRate = VitalTrend(DayValue(today, 60.0), 61.0, 63.0, 0),
            hrv = VitalTrend(DayValue(today.minusDays(4), 42.0), null, 45.0, 4),
            sleepHours = null,
        ),
        program = "Foundation 8, week 3 of 8.",
    )

    private val text = CoachContext.render(snapshot, ZoneOffset.UTC)

    @Test
    fun `gives the AI what the user asked it to have`() {
        listOf(
            "30 years old", "75.0 kg", "Left knee sore", "41.2", "Easy 30 minutes", "average 128",
            "Foundation 8", "Resting heart rate: latest 60.0", "with some effort at 140 bpm",
        ).forEach { assertTrue(it, text.contains(it)) }
    }

    @Test
    fun `flags old recovery data so it is not trusted`() {
        assertTrue(text.contains("HRV: latest 42.0 ms on 2026-10-29 NOT RECENT: 4 days old"))
    }

    @Test
    fun `says plainly when data is missing`() {
        assertTrue(text.contains("Sleep: no data"))
    }

    @Test
    fun `never includes device names`() {
        assertFalse(text.contains("SECRET-DEVICE-NAME"))
    }

    @Test
    fun `the snapshot has nowhere to put location or audio`() {
        val fields = CoachSnapshot::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { it.contains("location") || it.contains("latitude") || it.contains("audio") })
    }

    private fun workout(id: Long, seconds: Int, origin: String = "FREE", activity: String = "CYCLING", endReason: String? = null) = WorkoutEntity(
        id = id, startedAtMs = id * 10_000_000, endedAtMs = id * 10_000_000 + seconds * 1000L, title = "W$id",
        activity = activity, origin = origin, avgBpm = 140, maxBpm = 160, endReason = endReason,
    )

    @Test
    fun `starts lasting seconds are left out, and abandoned workouts are marked`() {
        val text = CoachContext.render(
            snapshot.copy(
                workouts = listOf(
                    workout(1, 5, origin = "BENCHMARK", activity = "OUTDOOR_RUN", endReason = "abandoned"),
                    workout(2, 600, origin = "BENCHMARK", activity = "OUTDOOR_RUN", endReason = "abandoned"),
                    workout(3, 67),
                ),
            ),
            ZoneOffset.UTC,
        )
        assertFalse(text.contains("W1"))
        assertFalse(text.contains("W3"))
        assertTrue(text, text.contains("W2 (benchmark), outdoor walk, 10:00."))
        assertTrue(text.contains("Stopped early."))
    }

    @Test
    fun `workouts say what kind they were, how they felt and what the phone measured`() {
        val ride = workout(4, 1_400).copy(
            effortRating = 3, distanceMetres = 4_230.0, movingSeconds = 1_300, climbMetres = 91.0, avgWatts = 120,
        )
        val run = workout(5, 1_800, origin = "AI", activity = "OUTDOOR_RUN").copy(
            distanceMetres = 5_000.0, movingSeconds = 1_700, gapSecondsPerKm = 320, hrDropBpm = 28,
        )
        val text = CoachContext.render(snapshot.copy(workouts = listOf(ride, run)), ZoneOffset.UTC)
        assertTrue(text, text.contains("W4 (free), ride, 23:20."))
        assertTrue(text, text.contains("4.23 km at 11.7 km/h moving, climbed 91 m, about 120 W."))
        assertTrue(text, text.contains("Rated effort 3 of 5."))
        assertTrue(text, text.contains("W5 (coach-planned), outdoor run"))
        assertTrue(text, text.contains("5.00 km at 5:40 /km, grade-adjusted 5:20 /km."))
        assertTrue(text, text.contains("Heart rate fell 28 bpm"))
    }
}
