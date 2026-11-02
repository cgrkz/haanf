package app.haanf.training

data class TimedBpm(val atMs: Long, val bpm: Int)

data class WorkoutSummary(
    val durationSeconds: Int,
    val avgBpm: Int?,
    val maxBpm: Int?,
    val zoneSeconds: Map<Zone, Int>,
    val noReadingSeconds: Int,
)

object WorkoutSummaries {
    /** A reading speaks for the time after it only until the next one, up to this limit. */
    private const val READING_HOLD_MS = 10_000L

    fun summarize(
        samples: List<TimedBpm>,
        startedAtMs: Long,
        endedAtMs: Long,
        zones: HeartRateZones?,
    ): WorkoutSummary {
        val valid = samples
            .filter { it.bpm > 0 && it.atMs in startedAtMs..endedAtMs }
            .sortedBy { it.atMs }
        val zoneMs = Zone.entries.associateWith { 0L }.toMutableMap()
        var coveredMs = 0L
        valid.forEachIndexed { index, sample ->
            val until = valid.getOrNull(index + 1)?.atMs ?: endedAtMs
            val heldMs = minOf(until - sample.atMs, READING_HOLD_MS)
            coveredMs += heldMs
            if (zones != null) {
                val zone = zones.zoneFor(sample.bpm)
                zoneMs[zone] = zoneMs.getValue(zone) + heldMs
            }
        }
        val durationMs = (endedAtMs - startedAtMs).coerceAtLeast(0)
        return WorkoutSummary(
            durationSeconds = (durationMs / 1000).toInt(),
            avgBpm = valid.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.toInt(),
            maxBpm = valid.maxOfOrNull { it.bpm },
            zoneSeconds = zoneMs.mapValues { (it.value / 1000).toInt() },
            noReadingSeconds = ((durationMs - coveredMs).coerceAtLeast(0) / 1000).toInt(),
        )
    }
}
