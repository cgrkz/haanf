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

    /**
     * Zone 3 and above. By default Zone 3 starts at 70% of heart rate reserve, inside the ACSM's
     * vigorous band, or at 70% of maximum without a resting rate, where the AHA's vigorous band starts.
     */
    val isHard: Boolean get() = number >= 3

    fun easier(): Zone = entries[maxOf(ordinal - 1, 0)]

    fun harder(): Zone = entries[minOf(ordinal + 1, entries.lastIndex)]
}

/**
 * Five zones. Zones 2 to 5 each start at a heart rate in [starts], so Zone 1 is everything below
 * Zone 2, and Zone 5 everything from its start up. By default each starts 60, 70, 80 or 90% of the
 * way from resting to maximum heart rate, the heart rate reserve. Without a usable resting rate they
 * fall back to the same shares of maximum. The athlete can set their own in Settings. See
 * docs/phase-1-plan.md.
 */
data class HeartRateZones(
    val maxBpm: Int,
    val restingBpm: Int? = null,
    val starts: List<Int> = defaultStarts(maxBpm, restingBpm),
) {
    init {
        require(isValid(starts)) { "Zone starts must be four rising heart rates: $starts" }
    }

    /** True when the athlete set these zones, rather than taking the defaults. */
    val isCustom: Boolean get() = starts != defaultStarts(maxBpm, restingBpm)

    /** True when the defaults count resting heart rate, rather than maximum alone. */
    val countsResting: Boolean get() = usableResting(maxBpm, restingBpm) != null

    fun zoneFor(bpm: Int): Zone = Zone.entries[starts.count { bpm >= it }]

    /** Heart rates that fall in [zone], for showing a target during a step. */
    fun rangeFor(zone: Zone): IntRange = when (zone) {
        Zone.ZONE_1 -> 0 until starts[0]
        Zone.ZONE_5 -> starts[3]..maxOf(maxBpm, starts[3])
        else -> starts[zone.ordinal - 1] until starts[zone.ordinal]
    }

    companion object {
        /** Where Zones 2 to 5 start, as shares of heart rate reserve, or of maximum without a resting rate. */
        val DEFAULT_PERCENTS = listOf(60, 70, 80, 90)
        const val LOWEST_START = 40
        const val HIGHEST_START = 240
        val RESTING_RANGE = 30..110

        /** Too little room between resting and maximum leaves zones a beat or two wide. */
        private const val MIN_RESERVE = 40

        fun usableResting(maxBpm: Int, restingBpm: Int?): Int? =
            restingBpm?.takeIf { it in RESTING_RANGE && maxBpm - it >= MIN_RESERVE }

        fun defaultStarts(maxBpm: Int, restingBpm: Int? = null): List<Int> {
            val resting = usableResting(maxBpm, restingBpm)
                ?: return DEFAULT_PERCENTS.map { (maxBpm * it + 99) / 100 }
            return DEFAULT_PERCENTS.map { resting + ((maxBpm - resting) * it + 99) / 100 }
        }

        fun isValid(starts: List<Int>): Boolean =
            starts.size == 4 && starts.all { it in LOWEST_START..HIGHEST_START } &&
                starts.zipWithNext().all { (lower, higher) -> lower < higher }

        /** As saved with a profile or a workout: "151,164,177,190". */
        fun encode(starts: List<Int>): String = starts.joinToString(",")

        fun decode(saved: String?): List<Int>? =
            saved?.split(",")?.map { it.trim().toIntOrNull() ?: return null }?.takeIf(::isValid)

        /** Zones from a maximum and the starts saved beside it, or the defaults if none were. */
        fun of(maxBpm: Int, savedStarts: String?, restingBpm: Int? = null): HeartRateZones =
            HeartRateZones(maxBpm, restingBpm, decode(savedStarts) ?: defaultStarts(maxBpm, restingBpm))
    }
}

object MaxHeartRate {
    /** Tanaka 2001, an estimate that individuals can differ from. */
    fun estimate(age: Int): Int = (208 - 0.7 * age).roundToInt()

    fun resolve(age: Int, measured: Int?): Int = measured ?: estimate(age)
}
