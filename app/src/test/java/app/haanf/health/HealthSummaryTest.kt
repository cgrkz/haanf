package app.haanf.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthSummaryTest {
    private val early = Instant.parse("2026-10-20T08:00:00Z")
    private val late = Instant.parse("2026-11-01T08:00:00Z")

    @Test
    fun `groups by source with counts and the newest time`() {
        val summary = HealthSummary.bySource(
            listOf(
                RecordFact("com.fitbit.FitbitMobile", early, sampleCount = 10, deviceLabel = "Google Pixel Watch 2"),
                RecordFact("com.fitbit.FitbitMobile", late, sampleCount = 5, deviceLabel = "Google Pixel Watch 2"),
                RecordFact("com.huami.watch.hmwatchmanager", early),
            ),
        )

        assertEquals(2, summary.size)
        val fitbit = summary[0]
        assertEquals("Fitbit / Google Health", fitbit.sourceName)
        assertEquals(2, fitbit.recordCount)
        assertEquals(15, fitbit.sampleCount)
        assertEquals(late, fitbit.newest)
        assertEquals(listOf("Google Pixel Watch 2"), fitbit.devices)
        assertEquals("Zepp", summary[1].sourceName)
    }

    @Test
    fun `no records means no sources rather than a failure`() {
        assertTrue(HealthSummary.bySource(emptyList()).isEmpty())
    }

    @Test
    fun `an unknown app shows its package name`() {
        assertEquals("com.example.app", HealthSummary.sourceName("com.example.app"))
    }

    @Test
    fun `the text report distinguishes empty, denied and failed types`() {
        val report = HealthReport(
            windowDays = 14,
            readAt = late,
            types = listOf(
                TypeReport("Sleep", TypeResult.Read(emptyList())),
                TypeReport("HRV (RMSSD)", TypeResult.NotGranted),
                TypeReport("Exercise sessions", TypeResult.Error("boom")),
            ),
        )
        val text = HealthSummary.text(report)
        assertTrue("No data" in text)
        assertTrue("Not granted" in text)
        assertTrue("Could not read: boom" in text)
    }
}
