package app.haanf.workout

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.haanf.training.LocationFix

/**
 * Uses the phone's GPS directly, so no Google services are needed. Fixes are passed on and
 * dropped: nothing here keeps a position.
 */
@SuppressLint("MissingPermission")
class GpsDistanceSource(context: Context) : DistanceSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    override fun start(onFix: (LocationFix) -> Unit) {
        stop()
        val forward = LocationListener { location -> onFix(location.toFix()) }
        listener = forward
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_MS,
                0f,
                forward,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            // Location was refused or withdrawn. The workout carries on without distance.
            listener = null
        } catch (e: IllegalArgumentException) {
            // This phone has no GPS provider.
            listener = null
        }
    }

    override fun stop() {
        listener?.let(manager::removeUpdates)
        listener = null
    }

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        elapsedMs = elapsedRealtimeNanos / 1_000_000,
        speedMps = if (hasSpeed()) speed else null,
    )

    private companion object {
        const val UPDATE_MS = 1_000L
    }
}
