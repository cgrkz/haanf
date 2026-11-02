package app.haanf.export

import app.haanf.data.HrEventEntity
import app.haanf.data.HrSampleEntity
import app.haanf.data.TalkTestEntity
import java.time.Instant

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
}
