package app.haanf.wearprobe

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lists the watch's raw sensors, and listens briefly to the ones that might give Haanf
 * something Health Services does not. Only counts, rates and short summaries are reported.
 */
class SensorProbe(private val manager: SensorManager, private val line: (String) -> Unit) {

    fun list() {
        val sensors = manager.getSensorList(Sensor.TYPE_ALL).sortedWith(compareBy({ it.type }, { it.name }))
        line("== All sensors (${sensors.size}) ==")
        sensors.forEach { line(describe(it)) }
        val dynamic = manager.getDynamicSensorList(Sensor.TYPE_ALL)
        line("Dynamic sensors: ${if (dynamic.isEmpty()) "none" else dynamic.joinToString { it.name }}")
        line("")
    }

    /** Listens to each interesting sensor at once for [seconds], then reports what arrived. */
    suspend fun listen(seconds: Int) {
        val targets = manager.getSensorList(Sensor.TYPE_ALL).filter { it.type in STANDARD_TARGETS || it.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE }
        line("== Listening to ${targets.size} sensors for $seconds s ==")
        val tallies = targets.associateWith { Tally() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                tallies[event.sensor]?.add(event)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                tallies[sensor]?.accuracies?.add(accuracy)
            }
        }
        val registered = targets.associateWith { sensor ->
            try {
                if (manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) "registered" else "refused"
            } catch (e: SecurityException) {
                "not allowed: ${e.message}"
            }
        }
        delay(seconds * 1_000L)
        manager.unregisterListener(listener)

        targets.forEach { sensor ->
            val tally = tallies.getValue(sensor)
            line("-- ${sensor.stringType}: ${sensor.name} --")
            line("Registration: ${registered[sensor]}. Events: ${tally.count}, about ${tally.perSecond()} a second.")
            tally.first?.let { line("First event: ${it.size} values, ${it.take(MAX_VALUES).joinToString { v -> "%.2f".format(v) }}") }
            if (tally.count > 0) line("First value ranged ${"%.2f".format(tally.low)} to ${"%.2f".format(tally.high)}.")
            if (tally.accuracies.isNotEmpty()) line("Accuracy reports: ${tally.accuracies.distinct().joinToString()}")
            if (sensor.type == Sensor.TYPE_HEART_BEAT) line(beats(tally))
        }
        line("")
    }

    /**
     * Logs every event from the readable Google sensors in full, with seconds since the start,
     * to see what their extra values hold.
     */
    suspend fun detail(seconds: Int) {
        val targets = manager.getSensorList(Sensor.TYPE_ALL)
            .filter { !it.isWakeUpSensor && (it.stringType in DETAIL_TYPES || it.type == Sensor.TYPE_HEART_RATE) }
        line("== Every event from ${targets.size} sensors for $seconds s ==")
        val startNs = android.os.SystemClock.elapsedRealtimeNanos()
        val lines = mutableListOf<String>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (lines.size >= MAX_DETAIL_LINES) return
                val at = (event.timestamp - startNs) / 1e9
                lines += "%.1f s %s [%d]: %s".format(at, event.sensor.name, event.accuracy, event.values.joinToString { "%.2f".format(it) })
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        targets.forEach { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        delay(seconds * 1_000L)
        manager.unregisterListener(listener)
        lines.forEach(line)
        line("")
    }

    /** Beat-to-beat intervals from heart beat events, the raw material of HRV. */
    private fun beats(tally: Tally): String {
        val confident = tally.events.filter { it.second >= CONFIDENT }
        val intervals = confident.zipWithNext { a, b -> (b.first - a.first) / 1_000_000.0 }
            .filter { it in 250.0..2_000.0 }
        if (intervals.size < 2) return "Beat intervals: too few confident beats (${confident.size} of ${tally.events.size})."
        val sorted = intervals.sorted()
        val rmssd = sqrt(intervals.zipWithNext { a, b -> (b - a) * (b - a) }.average())
        return "Beat intervals: ${intervals.size} from ${confident.size} confident beats of ${tally.events.size}; " +
            "median ${sorted[sorted.size / 2].roundToInt()} ms, ${sorted.first().roundToInt()} to ${sorted.last().roundToInt()} ms; " +
            "RMSSD ${rmssd.roundToInt()} ms."
    }

    private fun describe(sensor: Sensor): String {
        val rate = when {
            sensor.minDelay > 0 -> "up to ${(1_000_000.0 / sensor.minDelay).roundToInt()} Hz"
            sensor.minDelay == 0 -> "on change"
            else -> "one-shot"
        }
        val mode = when (sensor.reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
            Sensor.REPORTING_MODE_ON_CHANGE -> "on change"
            Sensor.REPORTING_MODE_ONE_SHOT -> "one shot"
            else -> "special"
        }
        val wake = if (sensor.isWakeUpSensor) ", wake-up" else ""
        return "${sensor.stringType} (type ${sensor.type}): ${sensor.name}, ${sensor.vendor}; $rate, $mode$wake; " +
            "batches up to ${sensor.fifoMaxEventCount} events; range ${sensor.maximumRange}, ${sensor.power} mA"
    }

    private class Tally {
        var count = 0
        var first: FloatArray? = null
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        var startNs = 0L
        var endNs = 0L
        val accuracies = mutableListOf<Int>()
        val events = mutableListOf<Pair<Long, Float>>()

        fun add(event: SensorEvent) {
            if (count == 0) {
                first = event.values.copyOf()
                startNs = event.timestamp
            }
            count++
            endNs = event.timestamp
            event.values.firstOrNull()?.let {
                low = minOf(low, it)
                high = maxOf(high, it)
            }
            if (event.sensor.type == Sensor.TYPE_HEART_BEAT) events += event.timestamp to (event.values.firstOrNull() ?: 0f)
        }

        fun perSecond(): String {
            val seconds = (endNs - startNs) / 1e9
            return if (count < 2 || seconds <= 0) "-" else "%.1f".format((count - 1) / seconds)
        }
    }

    private companion object {
        const val MAX_VALUES = 6
        const val MAX_DETAIL_LINES = 400
        val DETAIL_TYPES = setOf("com.google.sensor.heart_rate_extended", "com.google.sensor.step_cadence", "android.sensor.step_detector")
        const val CONFIDENT = 0.5f

        val STANDARD_TARGETS = setOf(
            Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_HEART_BEAT,
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,
            Sensor.TYPE_STEP_DETECTOR,
            Sensor.TYPE_PRESSURE,
        )
    }
}
