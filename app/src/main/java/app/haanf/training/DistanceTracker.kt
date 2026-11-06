package app.haanf.training

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS reading. It is used and dropped: nothing stores it. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val elapsedMs: Long,
    /** The receiver's own speed reading, from the Doppler shift, when it has one. */
    val speedMps: Float? = null,
)

/**
 * Adds up distance from GPS fixes, then forgets them: the route is never kept, only the
 * running total. Poor fixes and impossible jumps are dropped so noise cannot add distance.
 */
class DistanceTracker(
    private val worstAccuracyMetres: Float = 25f,
    private val fastestMetresPerSecond: Double = 12.0,
) {
    private var last: LocationFix? = null

    var totalMetres: Double = 0.0
        private set

    fun add(fix: LocationFix): Double {
        if (fix.accuracyMetres > worstAccuracyMetres) return totalMetres
        val previous = last
        if (previous == null) {
            last = fix
            return totalMetres
        }
        val seconds = (fix.elapsedMs - previous.elapsedMs) / 1000.0
        if (seconds <= 0) return totalMetres
        val metres = metresBetween(previous, fix)
        // Staying put reports small wandering fixes, which must not count as walking.
        if (metres < maxOf(previous.accuracyMetres, fix.accuracyMetres) / 2) return totalMetres
        last = fix
        if (metres / seconds <= fastestMetresPerSecond) totalMetres += metres
        return totalMetres
    }

    private fun metresBetween(a: LocationFix, b: LocationFix): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(h))
    }

    private companion object {
        const val EARTH_RADIUS_METRES = 6_371_000.0
    }
}
