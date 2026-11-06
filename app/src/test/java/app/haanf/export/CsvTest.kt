package app.haanf.export

import app.haanf.data.HrEventEntity
import app.haanf.data.HrSampleEntity
import app.haanf.data.TalkTestEntity
import app.haanf.data.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test
    fun `quotes only the fields that need it`() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"two\nlines\"", Csv.field("two\nlines"))
        assertEquals("", Csv.field(null))
    }

    @Test
    fun `writes samples with UTC timestamps`() {
        val csv = Csv.samples(
            listOf(HrSampleEntity(sessionId = 1, receivedAtMs = 1_000, bpm = 80, sensorContact = "DETECTED", rrIntervalsMs = "750 760")),
        )
        assertEquals(
            "timestamp_utc,epoch_ms,bpm,sensor_contact,rr_intervals_ms\n" +
                "1970-01-01T00:00:01Z,1000,80,DETECTED,750 760\n",
            csv,
        )
    }

    @Test
    fun `escapes event details`() {
        val csv = Csv.events(listOf(HrEventEntity(sessionId = 1, atMs = 0, type = "note", detail = "a, b")))
        assertEquals("timestamp_utc,epoch_ms,type,detail\n1970-01-01T00:00:00Z,0,note,\"a, b\"\n", csv)
    }

    @Test
    fun `leaves heart rate blank when there was no live session`() {
        val csv = Csv.talkTests(
            listOf(
                TalkTestEntity(
                    recordedAtMs = 0, testType = "COUNTING", effort = "HARD", activity = "TREADMILL",
                    requestedInput = "PHONE_MIC", actualInput = "the built-in mic", audioSource = "UNPROCESSED",
                    sampleRateHz = 44_100, durationMs = 12_000, hrStartBpm = null, hrEndBpm = null,
                    hrSessionId = null, fileName = "a.wav",
                ),
            ),
        )
        assertEquals(
            "a.wav,1970-01-01T00:00:00Z,COUNTING,HARD,TREADMILL,PHONE_MIC,the built-in mic," +
                "UNPROCESSED,44100,12000,,,",
            csv.lines()[1],
        )
    }

    @Test
    fun `writes workouts without device details`() {
        val csv = Csv.workouts(
            listOf(
                WorkoutEntity(
                    startedAtMs = 0,
                    endedAtMs = 60_000,
                    title = "Easy, 30 minutes",
                    activity = "OUTDOOR_RUN",
                    origin = "AI",
                    deviceName = "Pixel Watch 2",
                    avgBpm = 120,
                    easySeconds = 60,
                    hrDropBpm = 25,
                    effortRating = 3,
                ),
            ),
        )
        val lines = csv.trimEnd().lines()
        assertEquals(2, lines.size)
        assertEquals(
            "1970-01-01T00:00:00Z,1970-01-01T00:01:00Z,\"Easy, 30 minutes\",OUTDOOR_RUN,AI,,,false,120,,0,60,0,0,0,25,3,,,,,,,,",
            lines[1],
        )
        assertEquals(false, csv.contains("Pixel"))
    }
}
