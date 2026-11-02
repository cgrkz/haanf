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
}
