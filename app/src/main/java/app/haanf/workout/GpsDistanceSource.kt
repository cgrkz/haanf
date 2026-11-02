package app.haanf.workout

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import app.haanf.training.LocationFix

/** Uses the phone's GPS directly, so no Google services are needed. Callers hold the permission. */
@SuppressLint("MissingPermission")
class GpsDistanceSource(context: Context) : DistanceSource {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    override fun start(onFix: (LocationFix) -> Unit) {
        stop()
        val forward = LocationListener { location -> onFix(location.toFix()) }
        listener = forward
        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            UPDATE_MS,
            0f,
            forward,
            Looper.getMainLooper(),
        )
    }

    override fun stop() {
        listener?.let(manager::removeUpdates)
        listener = null
    }

    fun isEnabled(): Boolean = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    private fun Location.toFix() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        elapsedMs = elapsedRealtimeNanos / 1_000_000,
    )

    private companion object {
        const val UPDATE_MS = 1_000L
    }
}
