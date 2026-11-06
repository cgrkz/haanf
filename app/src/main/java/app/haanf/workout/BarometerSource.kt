package app.haanf.workout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Reports air pressure while a workout runs. Height above sea level is never worked out from it. */
interface PressureSource {
    /** False when the phone has no barometer. */
    val available: Boolean
    fun start(onHpa: (Double) -> Unit)
    fun stop()
}

object NoPressure : PressureSource {
    override val available = false
    override fun start(onHpa: (Double) -> Unit) = Unit
    override fun stop() = Unit
}

/** The phone's barometer. It needs no permission. */
class BarometerSource(context: Context) : PressureSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var listener: SensorEventListener? = null

    override val available: Boolean get() = sensor != null

    override fun start(onHpa: (Double) -> Unit) {
        stop()
        val barometer = sensor ?: return
        val forward = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.let { onHpa(it.toDouble()) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = forward
        manager?.registerListener(forward, barometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
    }
}
