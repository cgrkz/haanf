package app.haanf.training

import kotlin.math.roundToInt

enum class Zone(val number: Int) {
    ZONE_1(1),
    ZONE_2(2),
    ZONE_3(3),
    ZONE_4(4),
    ZONE_5(5),
    ;

    val label: String get() = "Zone $number"

    /** Zone 3 and above, from 70% of maximum by default: the AHA's vigorous activity. */
    val isHard: Boolean get() = number >= 3

    fun easier(): Zone = entries[maxOf(ordinal - 1, 0)]

    fun harder(): Zone = entries[minOf(ordinal + 1, entries.lastIndex)]
}

/**
 * Five zones. Zones 2 to 5 each start at a heart rate in [starts], so Zone 1 is everything below
 * Zone 2, and Zone 5 everything from its start up. By default they start at 60, 70, 80 and 90%
 * of maximum, and the athlete can set their own in Settings. See docs/phase-1-plan.md.
 */
data class HeartRateZones(val maxBpm: Int, val starts: List<Int> = defaultStarts(maxBpm)) {
    init {
        require(isValid(starts)) { "Zone starts must be four rising heart rates: $starts" }
    }

    /** True when the athlete set these zones, rather than taking them from their maximum. */
    val isCustom: Boolean get() = starts != defaultStarts(maxBpm)

    fun zoneFor(bpm: Int): Zone = Zone.entries[starts.count { bpm >= it }]

    /** Heart rates that fall in [zone], for showing a target during a step. */
    fun rangeFor(zone: Zone): IntRange = when (zone) {
        Zone.ZONE_1 -> 0 until starts[0]
        Zone.ZONE_5 -> starts[3]..maxOf(maxBpm, starts[3])
        else -> starts[zone.ordinal - 1] until starts[zone.ordinal]
    }

    companion object {
        /** Where Zones 2 to 5 start, as shares of maximum heart rate. */
        val DEFAULT_PERCENTS = listOf(60, 70, 80, 90)
        const val LOWEST_START = 40
        const val HIGHEST_START = 240

        fun defaultStarts(maxBpm: Int): List<Int> = DEFAULT_PERCENTS.map { (maxBpm * it + 99) / 100 }

        fun isValid(starts: List<Int>): Boolean =
            starts.size == 4 && starts.all { it in LOWEST_START..HIGHEST_START } &&
                starts.zipWithNext().all { (lower, higher) -> lower < higher }

        /** As saved with a profile or a workout: "116,135,155,174". */
        fun encode(starts: List<Int>): String = starts.joinToString(",")

        fun decode(saved: String?): List<Int>? =
            saved?.split(",")?.map { it.trim().toIntOrNull() ?: return null }?.takeIf(::isValid)

        /** Zones from a maximum and the starts saved beside it, or the defaults if none were. */
        fun of(maxBpm: Int, savedStarts: String?): HeartRateZones =
            HeartRateZones(maxBpm, decode(savedStarts) ?: defaultStarts(maxBpm))
    }
}

object MaxHeartRate {
    /** Tanaka 2001, an estimate that individuals can differ from. */
    fun estimate(age: Int): Int = (208 - 0.7 * age).roundToInt()

    fun resolve(age: Int, measured: Int?): Int = measured ?: estimate(age)
}
