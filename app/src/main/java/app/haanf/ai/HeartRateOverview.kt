package app.haanf.ai

import app.haanf.training.HeartRateZones
import app.haanf.training.TimedBpm
import app.haanf.training.WorkoutSummaries
import app.haanf.training.Zone

/** Boils the heart rate stream down to a few lines, so a whole stream is never sent. */
object HeartRateOverview {
    private const val BUCKETS = 6

    fun describe(samples: List<TimedBpm>, nowMs: Long, lastMinutes: Int, zones: HeartRateZones): String {
        val fromMs = nowMs - lastMinutes * 60_000L
        val window = samples.filter { it.bpm > 0 && it.atMs in fromMs..nowMs }.sortedBy { it.atMs }
        if (window.isEmpty()) return "No heart rate readings in the last $lastMinutes minutes."

        val summary = WorkoutSummaries.summarize(window, window.first().atMs, nowMs, zones)
        val bucketMs = (nowMs - fromMs) / BUCKETS
        val trend = (0 until BUCKETS).mapNotNull { index ->
            val start = fromMs + index * bucketMs
            window.filter { it.atMs >= start && it.atMs < start + bucketMs }
                .takeIf { it.isNotEmpty() }
                ?.map { it.bpm }
                ?.average()
                ?.toInt()
        }
        val inZones = Zone.entries.mapNotNull { zone ->
            summary.zoneSeconds[zone]?.takeIf { it >= 30 }?.let { "${zone.label.lowercase()} ${it / 60} min ${it % 60} s" }
        }
        return buildString {
            append("Last $lastMinutes minutes: average ${summary.avgBpm}, highest ${summary.maxBpm}, ")
            append("lowest ${window.minOf { it.bpm }} bpm. ")
            append("Trend, oldest to newest: ${trend.joinToString(", ")}. ")
            if (inZones.isNotEmpty()) append("Time in zones: ${inZones.joinToString(", ")}.")
        }
    }
}
