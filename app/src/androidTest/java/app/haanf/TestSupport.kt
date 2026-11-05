package app.haanf

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.platform.app.InstrumentationRegistry
import app.haanf.data.BenchmarkEntity
import app.haanf.data.ProfileEntity
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.SourceState
import app.haanf.training.LocationFix
import app.haanf.workout.DistanceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/** A text field, found by the name a screen reader gives it. */
fun field(label: String): SemanticsMatcher = hasSetTextAction() and hasContentDescription(label)

/** A wearable that emits whatever a test tells it to. */
class FakeSource : HeartRateSource {
    override val device = HeartRateDevice("fake", "Fake Watch", "fake")
    val stateFlow = MutableStateFlow<SourceState>(SourceState.Idle)
    val readingsFlow = MutableSharedFlow<HeartRateReading>(replay = 1, extraBufferCapacity = 16)
    var started = false

    override val state: StateFlow<SourceState> = stateFlow
    override val readings: Flow<HeartRateReading> = readingsFlow
    override val notes: Flow<String> = MutableSharedFlow()

    override fun start() {
        started = true
    }

    override fun stop() = Unit
    override fun reconnect(reason: String) = Unit
}

val noGps = object : DistanceSource {
    override fun start(onFix: (LocationFix) -> Unit) = Unit
    override fun stop() = Unit
}

object TestSupport {
    /** First-run progress lives in preferences, so each test starts from a clean first run. */
    fun resetOnboarding(context: Context, done: Boolean = false) {
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("watch_step_done", done)
            .putBoolean("finished", done)
            .commit()
    }

    /** Only Haanf's own Bluetooth permissions, on the emulator. */
    fun grantBluetooth() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val app = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).forEach {
            automation.grantRuntimePermission(app, it)
        }
    }

    fun seedProfile(container: AppContainer, withDevice: Boolean = true) = runBlocking {
        container.trainingDao.saveProfile(
            ProfileEntity(
                birthYear = 1996,
                sex = "MALE",
                weightKg = 75.0,
                healthCheckClearedAtMs = 1,
                deviceId = if (withDevice) "fake" else null,
                deviceName = if (withDevice) "Fake Watch" else null,
                deviceSourceKind = if (withDevice) "fake" else null,
            ),
        )
    }

    fun seedBenchmark(container: AppContainer, vo2Max: Double = 41.2, takenAtMs: Long = 900_000) = runBlocking {
        val id = container.trainingDao.insertWorkout(
            WorkoutEntity(
                startedAtMs = takenAtMs - 900_000,
                endedAtMs = takenAtMs,
                title = "1-mile walk benchmark",
                activity = "TREADMILL",
                origin = "BENCHMARK",
            ),
        )
        container.trainingDao.insertBenchmark(
            BenchmarkEntity(
                workoutId = id, protocol = "rockport-1-mile", takenAtMs = takenAtMs, timeSeconds = 900,
                heartRateBpm = 150, ageYears = 30, sex = "MALE", weightKg = 75.0, vo2Max = vo2Max,
            ),
        )
    }
}
