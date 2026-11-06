package app.haanf.wearprobe

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.health.services.client.HealthServices
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Lists what Health Services offers on this watch, once without permissions and once with
 * them, on screen, in the log and in a private file. It reads no readings at all.
 */
class ProbeActivity : Activity() {
    private val scope = MainScope()
    private lateinit var output: TextView
    private val report = StringBuilder()

    private val permissions: List<String>
        get() = buildList {
            add(if (Build.VERSION.SDK_INT >= 36) HEART_RATE_PERMISSION else android.Manifest.permission.BODY_SENSORS)
            add(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inset = (resources.displayMetrics.widthPixels * 0.12).toInt()
        output = TextView(this).apply {
            textSize = 12f
            setPadding(inset, inset, inset, inset * 2)
        }
        setContentView(ScrollView(this).apply { addView(output) })
        scope.launch {
            dump("Before asking for permissions")
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                line("All permissions were already granted.")
                save()
            } else {
                requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != PERMISSION_REQUEST) return
        scope.launch {
            dump("After asking for permissions")
            save()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun dump(heading: String) {
        line("== $heading ==")
        line("Wear OS API ${Build.VERSION.SDK_INT}, Health Services app ${healthServicesVersion()}")
        permissions.forEach { line("$it: ${if (checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) "granted" else "not granted"}") }
        val client = HealthServices.getClient(this)

        attempt("Passive") {
            val passive = client.passiveMonitoringClient.getCapabilities()
            line("Passive data: ${names(passive.supportedDataTypesPassiveMonitoring)}")
            line("Passive goals: ${names(passive.supportedDataTypesPassiveGoals)}")
            line("Health events: ${passive.supportedHealthEventTypes.joinToString { it.toString() }}")
            line("Activity states: ${passive.supportedUserActivityStates.joinToString { it.toString() }}")
        }

        attempt("Measure") {
            val measure = client.measureClient.getCapabilities()
            line("Measure data: ${names(measure.supportedDataTypesMeasure)}")
        }

        attempt("Exercise") {
            val exercise = client.exerciseClient.getCapabilities()
            val batching = exercise.supportedBatchingModeOverrides
            line("Batching overrides: ${if (batching.isEmpty()) "none" else batching.joinToString { batchingName(it) }}")
            line("Exercise types: ${exercise.supportedExerciseTypes.map { it.name }.sorted().joinToString()}")
            WATCHED_TYPES.forEach { type ->
                val caps = exercise.typeToCapabilities[type]
                if (caps == null) {
                    line("${type.name}: not supported")
                    return@forEach
                }
                line("-- ${type.name} --")
                line("Data: ${names(caps.supportedDataTypes)}")
                line("Goals: ${names(caps.supportedGoals.keys)}")
                line("Goals that must hold: ${names(caps.supportedDebouncedGoals.keys)}")
                line("Milestones: ${names(caps.supportedMilestones.keys)}")
                line("Events: ${caps.supportedExerciseEvents.joinToString { it.toString() }.ifEmpty { "none" }}")
                line("Auto pause: ${caps.supportsAutoPauseAndResume}")
            }
        }
        line("")
    }

    private suspend fun attempt(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            line("$label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun names(types: Collection<DataType<*, *>>): String =
        types.map { it.name }.sorted().joinToString().ifEmpty { "none" }

    private fun batchingName(mode: BatchingMode): String =
        if (mode == BatchingMode.HEART_RATE_5_SECONDS) "HEART_RATE_5_SECONDS" else "another mode"

    private fun healthServicesVersion(): String = try {
        packageManager.getPackageInfo(HEALTH_SERVICES_PACKAGE, 0).versionName ?: "unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "not installed"
    }

    private fun line(text: String) {
        Log.i(TAG, text)
        report.append(text).append('\n')
        output.text = report
    }

    private fun save() {
        File(filesDir, "probe.txt").writeText(report.toString())
        line("Saved to files/probe.txt.")
    }

    private companion object {
        const val TAG = "HaanfProbe"
        const val PERMISSION_REQUEST = 1
        const val HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"
        const val HEALTH_SERVICES_PACKAGE = "com.google.android.wearable.healthservices"

        /** The workouts Haanf coaches, plus the general one. */
        val WATCHED_TYPES = listOf(
            ExerciseType.RUNNING,
            ExerciseType.RUNNING_TREADMILL,
            ExerciseType.BIKING,
            ExerciseType.BIKING_STATIONARY,
            ExerciseType.WALKING,
            ExerciseType.WORKOUT,
        )
    }
}
