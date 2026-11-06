package app.haanf.training

import kotlin.math.pow

/**
 * Height relative to where the workout started, from air pressure. Height above sea level
 * is never worked out, so it can never be stored or sent. Readings are smoothed, and climb
 * and descent only count once a change passes [countFromMetres], so noise adds nothing.
 */
class AltitudeTracker(
    private val countFromMetres: Double = 1.5,
    private val smoothingReadings: Int = 5,
) {
    private var startHpa: Double? = null
    private val recent = ArrayDeque<Double>()
    private var counted: Double? = null

    /** The latest pressure, kept only to estimate air density for watts. */
    var lastHpa: Double? = null
        private set

    var heightMetres: Double = 0.0
        private set
    var climbMetres: Double = 0.0
        private set
    var descentMetres: Double = 0.0
        private set

    val hasReadings: Boolean get() = startHpa != null

    fun add(hpa: Double): Double {
        if (hpa !in PLAUSIBLE_HPA) return heightMetres
        lastHpa = hpa
        recent.addLast(hpa)
        while (recent.size > smoothingReadings) recent.removeFirst()
        val smoothed = recent.average()
        val start = startHpa ?: smoothed.also { startHpa = it }
        heightMetres = heightBetween(start, smoothed)
        val from = counted ?: heightMetres.also { counted = it }
        val change = heightMetres - from
        if (change >= countFromMetres) {
            climbMetres += change
            counted = heightMetres
        } else if (change <= -countFromMetres) {
            descentMetres -= change
            counted = heightMetres
        }
        return heightMetres
    }

    companion object {
        private val PLAUSIBLE_HPA = 300.0..1_100.0

        private const val SEA_LEVEL_HPA = 1_013.25

        /**
         * The standard atmosphere's height difference between two pressures, in metres. Only
         * the difference is returned: height above sea level is never kept.
         */
        fun heightBetween(fromHpa: Double, toHpa: Double): Double =
            44_330.0 * ((fromHpa / SEA_LEVEL_HPA).pow(0.1903) - (toHpa / SEA_LEVEL_HPA).pow(0.1903))
    }
}

data class TerrainPoint(val elapsedSeconds: Int, val distanceMetres: Double, val heightMetres: Double)

sealed interface ClimbEvent {
    val elapsedSeconds: Int

    data class Started(override val elapsedSeconds: Int, val gradePercent: Double) : ClimbEvent

    data class Ended(
        override val elapsedSeconds: Int,
        val gainMetres: Double,
        val metres: Double,
        val seconds: Int,
    ) : ClimbEvent {
        val averageGradePercent: Double get() = if (metres > 0) gainMetres / metres * 100 else 0.0
    }
}

/**
 * Gradient over the last [windowMetres] of distance, and when a climb starts and ends.
 * A climb starts at [startGrade] with at least [minGainMetres] gained, and ends below [endGrade].
 */
class ClimbDetector(
    private val windowMetres: Double = 100.0,
    private val startGrade: Double = 0.03,
    private val endGrade: Double = 0.01,
    private val minGainMetres: Double = 3.0,
    private val minMetres: Double = 30.0,
) {
    private val points = ArrayDeque<TerrainPoint>()
    private var climbFrom: TerrainPoint? = null

    var gradePercent: Double? = null
        private set
    val climbing: Boolean get() = climbFrom != null

    /** Height gained since the current climb began, or null when not climbing. */
    fun climbSoFar(now: TerrainPoint): Double? = climbFrom?.let { now.heightMetres - it.heightMetres }

    fun climbSeconds(now: TerrainPoint): Int? = climbFrom?.let { now.elapsedSeconds - it.elapsedSeconds }

    fun add(point: TerrainPoint): ClimbEvent? {
        points.addLast(point)
        // Keep one point at least a window behind, so the gradient always spans the window.
        while (points.size > 2 && point.distanceMetres - points[1].distanceMetres >= windowMetres) points.removeFirst()
        val first = points.first()
        val metres = point.distanceMetres - first.distanceMetres
        if (metres < minMetres) {
            gradePercent = null
            return null
        }
        val gain = point.heightMetres - first.heightMetres
        val grade = gain / metres
        gradePercent = grade * 100
        val from = climbFrom
        return when {
            from == null && grade >= startGrade && gain >= minGainMetres -> {
                climbFrom = first
                ClimbEvent.Started(point.elapsedSeconds, grade * 100)
            }
            from != null && grade < endGrade -> {
                climbFrom = null
                ClimbEvent.Ended(
                    elapsedSeconds = point.elapsedSeconds,
                    gainMetres = point.heightMetres - from.heightMetres,
                    metres = point.distanceMetres - from.distanceMetres,
                    seconds = point.elapsedSeconds - from.elapsedSeconds,
                )
            }
            else -> null
        }
    }
}
