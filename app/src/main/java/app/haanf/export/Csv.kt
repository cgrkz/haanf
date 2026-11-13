package app.haanf.export

import app.haanf.data.HrEventEntity
import app.haanf.data.HrSampleEntity
import app.haanf.data.TalkTestEntity
import app.haanf.data.WorkoutEntity
import java.time.Instant
import java.util.Locale

object Csv {
    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    fun field(value: Any?): String {
        val text = value?.toString().orEmpty()
        val needsQuotes = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    private fun table(header: List<String>, rows: List<List<Any?>>): String = buildString {
        append(header.joinToString(",", transform = ::field)).append('\n')
        rows.forEach { row -> append(row.joinToString(",", transform = ::field)).append('\n') }
    }

    fun samples(rows: List<HrSampleEntity>): String = table(
        listOf("timestamp_utc", "epoch_ms", "bpm", "sensor_contact", "rr_intervals_ms"),
        rows.map { listOf(iso(it.receivedAtMs), it.receivedAtMs, it.bpm, it.sensorContact, it.rrIntervalsMs) },
    )

    fun events(rows: List<HrEventEntity>): String = table(
        listOf("timestamp_utc", "epoch_ms", "type", "detail"),
        rows.map { listOf(iso(it.atMs), it.atMs, it.type, it.detail) },
    )

    fun talkTests(rows: List<TalkTestEntity>): String = table(
        listOf(
            "file", "recorded_utc", "test", "effort", "activity", "requested_input", "actual_input",
            "audio_source", "sample_rate_hz", "duration_ms", "hr_start_bpm", "hr_end_bpm", "hr_session_id",
        ),
        rows.map {
            listOf(
                it.fileName, iso(it.recordedAtMs), it.testType, it.effort, it.activity,
                it.requestedInput, it.actualInput, it.audioSource, it.sampleRateHz, it.durationMs,
                it.hrStartBpm, it.hrEndBpm, it.hrSessionId,
            )
        },
    )

    /** One row per workout. No device details, so the file is safe to pass around. */
    fun workouts(rows: List<WorkoutEntity>): String = table(
        listOf(
            "started_utc", "ended_utc", "title", "activity", "origin", "program", "program_week",
            "counted_for_program", "avg_bpm", "max_bpm", "zone1_s", "zone2_s", "zone3_s", "zone4_s", "zone5_s",
            "no_reading_s", "hr_drop_1min_bpm", "effort_rating", "distance_m", "moving_s", "max_speed_kmh",
            "climb_m", "descent_m", "avg_watts_estimated", "grade_adjusted_pace_s_per_km", "bike",
        ),
        rows.map {
            listOf(
                iso(it.startedAtMs), it.endedAtMs?.let(::iso), it.title, it.activity, it.origin, it.programId,
                it.programWeek, it.countedForProgram, it.avgBpm, it.maxBpm, it.zone1Seconds, it.zone2Seconds,
                it.zone3Seconds, it.zone4Seconds, it.zone5Seconds, it.noReadingSeconds, it.hrDropBpm, it.effortRating,
                it.distanceMetres?.let { metres -> "%.0f".format(Locale.ROOT, metres) }, it.movingSeconds,
                it.maxSpeedMps?.let { speed -> "%.1f".format(Locale.ROOT, speed * 3.6) },
                it.climbMetres?.let { metres -> "%.0f".format(Locale.ROOT, metres) }, it.descentMetres?.let { metres -> "%.0f".format(Locale.ROOT, metres) },
                it.avgWatts, it.gapSecondsPerKm, it.bikeType,
            )
        },
    )
}
