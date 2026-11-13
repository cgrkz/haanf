package app.haanf.training

import kotlin.math.roundToInt

/** How far heart rate falls in the first minute after the last hard step: a sign of fitness. */
object HeartRateRecovery {
    private const val WINDOW_MS = 10_000L
    private const val ONE_MINUTE_MS = 60_000L

    /** Seconds from the start of [plan] to the end of its last hard step, or null with none. */
    fun lastHardStepEndSeconds(plan: WorkoutPlan): Int? {
        val last = plan.steps.indexOfLast { it.target.isHard }
        if (last < 0) return null
        return plan.steps.take(last + 1).sumOf { it.seconds }
    }

    /**
     * Heart rate around the end of the hard step, less heart rate a minute later. Null when the
     * saved samples do not cover both moments, so a number is never guessed.
     */
    fun oneMinuteDrop(samples: List<TimedBpm>, hardEndMs: Long): Int? {
        val atEnd = samples.filter { it.bpm > 0 && it.atMs in (hardEndMs - WINDOW_MS)..hardEndMs }
        val laterMs = hardEndMs + ONE_MINUTE_MS
        val later = samples.filter { it.bpm > 0 && it.atMs in (laterMs - WINDOW_MS / 2)..(laterMs + WINDOW_MS / 2) }
        if (atEnd.isEmpty() || later.isEmpty()) return null
        return (atEnd.map { it.bpm }.average() - later.map { it.bpm }.average()).roundToInt()
    }
}
