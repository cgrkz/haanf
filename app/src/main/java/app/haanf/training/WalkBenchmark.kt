package app.haanf.training

enum class Sex(val label: String) {
    MALE("Male"),
    FEMALE("Female"),
}

data class BenchmarkInputs(
    val ageYears: Int,
    val sex: Sex,
    val weightKg: Double,
)

sealed interface WalkOutcome {
    data class Scored(val vo2Max: Double, val timeSeconds: Int, val heartRateBpm: Int) : WalkOutcome
    data class NotScored(val reason: String) : WalkOutcome
}

/**
 * The Rockport 1-mile walk test, a fixed protocol: the AI may choose when to run it but
 * never how. Kline 1987, the abstract's equation, which gives litres per minute.
 * docs/benchmarks.md covers its limits.
 */
object RockportWalkTest {
    const val ID = "rockport-1-mile"
    const val MILE_METRES = 1609.34

    private const val POUNDS_PER_KG = 2.20462
    private const val MIN_SECONDS = 8 * 60
    private const val MAX_SECONDS = 40 * 60
    private const val MIN_LAST_QUARTER_READINGS = 3

    fun vo2Max(inputs: BenchmarkInputs, timeMinutes: Double, fourthQuarterBpm: Double): Double {
        val pounds = inputs.weightKg * POUNDS_PER_KG
        val male = if (inputs.sex == Sex.MALE) 1.0 else 0.0
        val litresPerMinute = 6.9652 +
            0.0091 * pounds -
            0.0257 * inputs.ageYears +
            0.5955 * male -
            0.2240 * timeMinutes -
            0.0115 * fourthQuarterBpm
        return litresPerMinute * 1000 / inputs.weightKg
    }

    fun evaluate(
        inputs: BenchmarkInputs,
        samples: List<TimedBpm>,
        startedAtMs: Long,
        endedAtMs: Long,
    ): WalkOutcome {
        val seconds = ((endedAtMs - startedAtMs) / 1000).toInt()
        if (seconds < MIN_SECONDS) {
            return WalkOutcome.NotScored("That is quicker than a mile can be walked, so it was not scored.")
        }
        if (seconds > MAX_SECONDS) {
            return WalkOutcome.NotScored("The walk took too long for the test to be scored.")
        }
        val lastQuarterFrom = endedAtMs - (endedAtMs - startedAtMs) / 4
        val lastQuarter = samples.filter { it.bpm > 0 && it.atMs in lastQuarterFrom..endedAtMs }
        if (lastQuarter.size < MIN_LAST_QUARTER_READINGS) {
            return WalkOutcome.NotScored("Not enough heart rate readings near the end to score the walk.")
        }
        val bpm = lastQuarter.map { it.bpm }.average()
        return WalkOutcome.Scored(vo2Max(inputs, seconds / 60.0, bpm), seconds, bpm.toInt())
    }
}

enum class FitnessLevel(val label: String) {
    WELL_BELOW("Well below average"),
    BELOW("Below average"),
    AVERAGE("Average"),
    ABOVE("Above average"),
    WELL_ABOVE("Well above average"),
}

/** Treadmill VO2max percentiles from the FRIEND registry. docs/benchmarks.md gives the source. */
object FitnessNorms {
    private data class Row(
        val fromAge: Int,
        val p10: Double,
        val p25: Double,
        val p50: Double,
        val p75: Double,
        val p90: Double,
    )

    private val men = listOf(
        Row(0, 32.1, 40.1, 48.0, 55.2, 61.8),
        Row(30, 30.2, 35.9, 42.4, 49.2, 56.5),
        Row(40, 26.8, 31.9, 37.8, 45.0, 52.1),
        Row(50, 22.8, 27.1, 32.6, 39.7, 45.6),
        Row(60, 19.8, 23.7, 28.2, 34.5, 40.3),
        Row(70, 17.1, 20.4, 24.4, 30.4, 36.6),
    )

    private val women = listOf(
        Row(0, 23.9, 30.5, 37.6, 44.7, 51.3),
        Row(30, 20.9, 25.3, 30.2, 36.1, 41.4),
        Row(40, 18.8, 22.1, 26.7, 32.4, 38.4),
        Row(50, 17.3, 19.9, 23.4, 27.6, 32.0),
        Row(60, 14.6, 17.2, 20.0, 23.8, 27.0),
        Row(70, 13.6, 15.6, 18.3, 20.8, 23.1),
    )

    private fun row(age: Int, sex: Sex): Row =
        (if (sex == Sex.MALE) men else women).last { age >= it.fromAge }

    fun median(age: Int, sex: Sex): Double = row(age, sex).p50

    fun level(vo2Max: Double, age: Int, sex: Sex): FitnessLevel {
        val norms = row(age, sex)
        return when {
            vo2Max < norms.p10 -> FitnessLevel.WELL_BELOW
            vo2Max < norms.p25 -> FitnessLevel.BELOW
            vo2Max < norms.p75 -> FitnessLevel.AVERAGE
            vo2Max < norms.p90 -> FitnessLevel.ABOVE
            else -> FitnessLevel.WELL_ABOVE
        }
    }
}
