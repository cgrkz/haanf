package app.haanf.training

import kotlin.math.roundToInt

enum class Zone(val label: String) {
    RECOVERY("Recovery"),
    EASY("Easy"),
    HARD("Hard"),
    VERY_HARD("Very hard"),
}

/** Zones as shares of maximum heart rate. See docs/phase-1-plan.md for their sources. */
data class HeartRateZones(val maxBpm: Int) {
    fun zoneFor(bpm: Int): Zone {
        val percent = bpm * 100 / maxBpm
        return when {
            percent < EASY_FROM -> Zone.RECOVERY
            percent < HARD_FROM -> Zone.EASY
            percent < VERY_HARD_FROM -> Zone.HARD
            else -> Zone.VERY_HARD
        }
    }

    /** Heart rates that fall in [zone], for showing a target during a step. */
    fun rangeFor(zone: Zone): IntRange = when (zone) {
        Zone.RECOVERY -> 0 until bpmAt(EASY_FROM)
        Zone.EASY -> bpmAt(EASY_FROM) until bpmAt(HARD_FROM)
        Zone.HARD -> bpmAt(HARD_FROM) until bpmAt(VERY_HARD_FROM)
        Zone.VERY_HARD -> bpmAt(VERY_HARD_FROM)..maxBpm
    }

    private fun bpmAt(percent: Int): Int = (maxBpm * percent + 99) / 100

    private companion object {
        const val EASY_FROM = 50
        const val HARD_FROM = 70
        const val VERY_HARD_FROM = 85
    }
}

object MaxHeartRate {
    /** Tanaka 2001, an estimate that individuals can differ from. */
    fun estimate(age: Int): Int = (208 - 0.7 * age).roundToInt()

    fun resolve(age: Int, measured: Int?): Int = measured ?: estimate(age)
}
