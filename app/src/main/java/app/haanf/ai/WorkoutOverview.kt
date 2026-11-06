package app.haanf.ai

import app.haanf.training.HeartRateZones
import app.haanf.training.MotionSample
import app.haanf.training.TimedBpm
import app.haanf.training.WorkoutSummaries
import app.haanf.training.Zone
import app.haanf.workout.WorkoutText
import kotlin.math.roundToInt

/**
 * Boils a stretch of the workout down to a few timed lines: heart rate, and speed and climb
 * when the phone measures them. A whole stream is never sent.
 */
object WorkoutOverview {
    private const val MAX_BUCKETS = 10

    fun describe(
        samples: List<TimedBpm>,
        motion: List<MotionSample>,
        workoutStartMs: Long,
        nowSeconds: Int,
        lastMinutes: Int,
        zones: HeartRateZones,
        ride: Boolean,
        bucketSeconds: Int = maxOf(60, lastMinutes * 60 / MAX_BUCKETS),
    ): String {
        val fromSeconds = (nowSeconds - lastMinutes * 60).coerceAtLeast(0)
        fun at(seconds: Int) = workoutStartMs + seconds * 1000L
        val window = samples.filter { it.bpm > 0 && it.atMs in at(fromSeconds)..at(nowSeconds) }.sortedBy { it.atMs }
        if (window.isEmpty() && motion.none { it.elapsedSeconds in fromSeconds..nowSeconds }) {
            return "No readings between ${WorkoutText.clock(fromSeconds)} and ${WorkoutText.clock(nowSeconds)}."
        }
        val lines = mutableListOf("From ${WorkoutText.clock(fromSeconds)} to ${WorkoutText.clock(nowSeconds)}:")
        var start = fromSeconds
        while (start < nowSeconds) {
            val end = minOf(start + bucketSeconds, nowSeconds)
            val bpm = window.filter { it.atMs >= at(start) && it.atMs < at(end) }.map { it.bpm }
            val parts = mutableListOf<String>()
            parts += if (bpm.isEmpty()) "no heart rate" else "heart rate ${bpm.average().roundToInt()} average, ${bpm.min()} to ${bpm.max()}"
            motionPart(motion, start, end, ride)?.let(parts::add)
            lines += "[${WorkoutText.clock(start)}-${WorkoutText.clock(end)}] ${parts.joinToString("; ")}."
            start = end
        }
        if (window.isNotEmpty()) {
            val summary = WorkoutSummaries.summarize(window, window.first().atMs, at(nowSeconds), zones)
            val inZones = Zone.entries.mapNotNull { zone ->
                summary.zoneSeconds[zone]?.takeIf { it >= 30 }?.let { "${zone.label.lowercase()} ${WorkoutText.clock(it)}" }
            }
            lines += "Overall: average ${summary.avgBpm}, highest ${summary.maxBpm}, lowest ${window.minOf { it.bpm }} bpm." +
                if (inZones.isNotEmpty()) " Time in zones: ${inZones.joinToString(", ")}." else ""
        }
        return lines.joinToString("\n")
    }

    private fun motionPart(motion: List<MotionSample>, start: Int, end: Int, ride: Boolean): String? {
        val first = motion.lastOrNull { it.elapsedSeconds <= start } ?: motion.firstOrNull { it.elapsedSeconds in start..end } ?: return null
        val last = motion.lastOrNull { it.elapsedSeconds <= end } ?: return null
        val seconds = last.elapsedSeconds - first.elapsedSeconds
        if (seconds <= 0) return null
        val parts = mutableListOf<String>()
        val metres = last.distanceMetres - first.distanceMetres
        if (metres > 0) {
            val speed = metres / seconds
            parts += if (ride) "${WorkoutText.kmh(speed)} km/h" else "${WorkoutText.paceOf(speed) ?: "-"} /km"
        }
        val heights = motion.filter { it.elapsedSeconds in first.elapsedSeconds..last.elapsedSeconds }.mapNotNull { it.heightMetres }
        if (heights.size >= 2) {
            val change = heights.last() - heights.first()
            parts += "${if (change >= 0) "+" else ""}${change.roundToInt()} m"
        }
        val watts = motion.filter { it.elapsedSeconds in first.elapsedSeconds..last.elapsedSeconds }.mapNotNull { it.watts }
        if (watts.isNotEmpty()) parts += "about ${watts.average().roundToInt()} W"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
