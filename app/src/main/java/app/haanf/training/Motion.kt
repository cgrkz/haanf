package app.haanf.training

import kotlin.math.roundToInt

enum class MotionKind { RUN, RIDE, WALK }

/** One kilometre, or the shorter last part. Holds totals only: never where it was. */
data class Split(
    val number: Int,
    val metres: Double,
    val seconds: Int,
    val endElapsedSeconds: Int,
    val avgBpm: Int?,
    val climbMetres: Double,
    val descentMetres: Double,
    val avgWatts: Int?,
    val gapSecondsPerKm: Int?,
)

/** What the meter knew at one second of the workout, for the coach's overview. */
data class MotionSample(
    val elapsedSeconds: Int,
    val distanceMetres: Double,
    val heightMetres: Double?,
    val speedMps: Double?,
    val watts: Double?,
)

data class MotionNow(
    val distanceMetres: Double = 0.0,
    val speedMps: Double? = null,
    val movingSeconds: Int = 0,
    val gradePercent: Double? = null,
    val climbing: Boolean = false,
    val climbSoFarMetres: Double? = null,
    val climbSeconds: Int? = null,
    val climbMetres: Double = 0.0,
    val descentMetres: Double = 0.0,
    val watts: Int? = null,
    val gapSpeedMps: Double? = null,
    val hasGps: Boolean = false,
    val hasBarometer: Boolean = false,
)

data class MotionTotals(
    val distanceMetres: Double,
    val movingSeconds: Int,
    val maxSpeedMps: Double,
    val climbMetres: Double,
    val descentMetres: Double,
    val avgWatts: Int?,
    val gapSecondsPerKm: Int?,
)

sealed interface MotionEvent {
    data class SplitDone(val split: Split) : MotionEvent
    data class Climb(val event: ClimbEvent) : MotionEvent
}

/**
 * Turns GPS fixes and air pressure into distance, speed, climb and effort, one tick a second.
 * Fixes are used and dropped. Only totals, splits and heights relative to the start remain.
 */
class MotionMeter(
    private val kind: MotionKind,
    private val riderKg: Double? = null,
    private val bike: BikeType? = null,
    private val movingFromMps: Double = 0.8,
    private val splitMetres: Double = 1_000.0,
) {
    private val distance = DistanceTracker(fastestMetresPerSecond = if (kind == MotionKind.RIDE) 25.0 else 12.0)
    private val altitude = AltitudeTracker()
    private val climbs = ClimbDetector()
    private var latestHpa: Double? = null
    private val recentSpeeds = ArrayDeque<Double>()
    private var lastTotal = 0.0
    private var lastAcceptedMs: Long? = null
    private var ticksSinceFix = Int.MAX_VALUE
    private var hadFix = false

    private var lastElapsed = -1
    private var moving = 0
    private var maxSpeed = 0.0
    private var wattsSeconds = 0.0
    private var wattsCount = 0
    private var gapMetres = 0.0
    private var gapSeconds = 0
    private var now = MotionNow()

    private var splitCount = 0
    private var split = SplitStart(0, 0.0, 0.0, 0.0)
    private var splitBpm = Tally()
    private var splitWatts = Tally()
    private var splitGapMetres = 0.0
    private var splitGapSeconds = 0

    private val _samples = mutableListOf<MotionSample>()
    val samples: List<MotionSample> get() = _samples

    val distanceMetres: Double get() = distance.totalMetres

    fun onFix(fix: LocationFix) {
        hadFix = true
        ticksSinceFix = 0
        val total = distance.add(fix)
        val doppler = fix.speedMps?.takeIf { fix.accuracyMetres <= GOOD_ACCURACY_METRES }?.toDouble()
        val measured = doppler ?: lastAcceptedMs?.let { from ->
            val seconds = (fix.elapsedMs - from) / 1000.0
            if (total > lastTotal && seconds > 0) (total - lastTotal) / seconds else null
        }
        if (total > lastTotal || lastAcceptedMs == null) {
            lastTotal = total
            lastAcceptedMs = fix.elapsedMs
        }
        measured?.let {
            recentSpeeds.addLast(it)
            while (recentSpeeds.size > SPEED_READINGS) recentSpeeds.removeFirst()
        }
    }

    fun onPressure(hpa: Double) {
        latestHpa = hpa
    }

    fun now(): MotionNow = now

    /** Call once a second. Returns kilometres and climbs that just finished or began. */
    fun tick(elapsedSeconds: Int, bpm: Int?): List<MotionEvent> {
        if (elapsedSeconds <= lastElapsed) return emptyList()
        val seconds = if (lastElapsed < 0) 0 else elapsedSeconds - lastElapsed
        lastElapsed = elapsedSeconds
        if (ticksSinceFix != Int.MAX_VALUE) ticksSinceFix++
        val speed = recentSpeeds.takeIf { it.isNotEmpty() && ticksSinceFix <= STALE_TICKS }?.average()
        if (ticksSinceFix > STALE_TICKS) recentSpeeds.clear()

        latestHpa?.let { altitude.add(it) }
        val height = if (altitude.hasReadings) altitude.heightMetres else null
        val metres = distance.totalMetres
        val events = mutableListOf<MotionEvent>()
        val point = height?.let { TerrainPoint(elapsedSeconds, metres, it) }
        point?.let { climbs.add(it)?.let { event -> events += MotionEvent.Climb(event) } }
        val grade = (climbs.gradePercent ?: 0.0) / 100

        val isMoving = speed != null && speed >= movingFromMps
        if (isMoving) moving += seconds
        if (speed != null && speed > maxSpeed) maxSpeed = speed
        val watts = if (kind == MotionKind.RIDE && isMoving && riderKg != null && bike != null) {
            CyclingPower.watts(requireNotNull(speed), grade, riderKg, bike, CyclingPower.airDensity(altitude.lastHpa))
        } else {
            null
        }
        if (watts != null && seconds > 0) {
            wattsSeconds += watts * seconds
            wattsCount += seconds
            splitWatts = splitWatts.add(watts, seconds)
        }
        val gap = if (kind == MotionKind.RUN && isMoving) GradeAdjustedPace.adjustedSpeed(requireNotNull(speed), grade) else null
        if (gap != null && seconds > 0) {
            gapMetres += gap * seconds
            gapSeconds += seconds
            splitGapMetres += gap * seconds
            splitGapSeconds += seconds
        }
        if (bpm != null && bpm > 0 && seconds > 0) splitBpm = splitBpm.add(bpm.toDouble(), seconds)

        while (metres - split.distanceMetres >= splitMetres) {
            events += MotionEvent.SplitDone(closeSplit(elapsedSeconds, splitMetres))
        }

        now = MotionNow(
            distanceMetres = metres,
            speedMps = speed,
            movingSeconds = moving,
            gradePercent = climbs.gradePercent,
            climbing = climbs.climbing,
            climbSoFarMetres = point?.let(climbs::climbSoFar),
            climbSeconds = point?.let(climbs::climbSeconds),
            climbMetres = altitude.climbMetres,
            descentMetres = altitude.descentMetres,
            watts = watts?.roundToInt(),
            gapSpeedMps = gap,
            hasGps = hadFix,
            hasBarometer = altitude.hasReadings,
        )
        _samples += MotionSample(elapsedSeconds, metres, height, speed, watts)
        return events
    }

    /** The shorter last part, if it is long enough to say anything. */
    fun finish(elapsedSeconds: Int): Split? {
        val metres = distance.totalMetres - split.distanceMetres
        if (metres < MIN_LAST_SPLIT_METRES) return null
        return closeSplit(elapsedSeconds, metres)
    }

    fun totals(): MotionTotals = MotionTotals(
        distanceMetres = distance.totalMetres,
        movingSeconds = moving,
        maxSpeedMps = maxSpeed,
        climbMetres = altitude.climbMetres,
        descentMetres = altitude.descentMetres,
        avgWatts = if (wattsCount > 0) (wattsSeconds / wattsCount).roundToInt() else null,
        gapSecondsPerKm = paceOf(gapMetres, gapSeconds),
    )

    private fun closeSplit(elapsedSeconds: Int, metres: Double): Split {
        splitCount++
        val done = Split(
            number = splitCount,
            metres = metres,
            seconds = elapsedSeconds - split.elapsedSeconds,
            endElapsedSeconds = elapsedSeconds,
            avgBpm = splitBpm.average()?.roundToInt(),
            climbMetres = altitude.climbMetres - split.climbMetres,
            descentMetres = altitude.descentMetres - split.descentMetres,
            avgWatts = splitWatts.average()?.roundToInt(),
            gapSecondsPerKm = paceOf(splitGapMetres, splitGapSeconds),
        )
        split = SplitStart(elapsedSeconds, split.distanceMetres + metres, altitude.climbMetres, altitude.descentMetres)
        splitBpm = Tally()
        splitWatts = Tally()
        splitGapMetres = 0.0
        splitGapSeconds = 0
        return done
    }

    private fun paceOf(metres: Double, seconds: Int): Int? =
        if (metres >= MIN_PACE_METRES && seconds > 0) (seconds / (metres / 1000)).roundToInt() else null

    private data class SplitStart(val elapsedSeconds: Int, val distanceMetres: Double, val climbMetres: Double, val descentMetres: Double)

    private data class Tally(val sum: Double = 0.0, val seconds: Int = 0) {
        fun add(value: Double, weight: Int) = Tally(sum + value * weight, seconds + weight)
        fun average(): Double? = if (seconds > 0) sum / seconds else null
    }

    private companion object {
        const val GOOD_ACCURACY_METRES = 25f
        const val SPEED_READINGS = 5
        const val STALE_TICKS = 10
        const val MIN_LAST_SPLIT_METRES = 100.0
        const val MIN_PACE_METRES = 100.0
    }
}
