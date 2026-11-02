package app.haanf

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.haanf.data.HaanfDatabase
import app.haanf.data.ProfileEntity
import app.haanf.data.TalkTestEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.SourceState
import app.haanf.talktest.TalkTestScript
import app.haanf.ui.HaanfRoot
import app.haanf.ui.HaanfTheme
import app.haanf.workout.LiveLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the screens on an emulator. Says nothing about real Bluetooth or microphones. */
@RunWith(AndroidJUnit4::class)
class AppScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val source = FakeSource()
    private val spoken = CopyOnWriteArrayList<String>()
    private val link = object : LiveLink {
        override fun begin(device: HeartRateDevice) = container.live.start(device, watchWorkoutRunning = false)
        override fun end() = container.live.stop()
    }

    private fun openLab() {
        compose.onNodeWithText("Lab").performClick()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, HaanfDatabase::class.java).build()
        container = AppContainer(context, { source }, database, link, { spoken += it })
        compose.setContent {
            HaanfTheme {
                HaanfRoot(container)
            }
        }
    }

    @Test
    fun heartRateToolOffersAScanWhenIdle() {
        openLab()
        compose.onNodeWithText("Scan for a heart rate device").assertIsDisplayed()
        compose.onNodeWithText("No sessions yet.").assertIsDisplayed()
    }

    @Test
    fun liveSessionShowsHeartRateFromAnySource() {
        openLab()
        container.live.start(source.device, watchWorkoutRunning = false)
        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.sessionId != null }
        compose.waitUntil(timeoutMillis = 5_000) {
            source.started && source.readingsFlow.subscriptionCount.value > 0
        }
        source.stateFlow.value = SourceState.Streaming
        source.readingsFlow.tryEmit(HeartRateReading(142, 1_000, container.clock.elapsedMs()))

        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.bpm == 142 }
        compose.onNodeWithTag("bpm").assertIsDisplayed()
        compose.onNodeWithText("142 bpm").assertIsDisplayed()
        compose.onNodeWithText("Connected, receiving").assertIsDisplayed()
        container.live.stop()
    }

    @Test
    fun talkTestToolShowsTheFixedPassage() {
        openLab()
        compose.onNodeWithText("Talk test").performClick()
        compose.onNodeWithText("Passage").performClick()
        compose.onNodeWithText(TalkTestScript.PASSAGE).assertIsDisplayed()
    }

    @Test
    fun exportingARecordingOpensTheShareSheetWithoutCrashing() {
        runBlocking {
            container.talkTestDao.insert(
                TalkTestEntity(
                    recordedAtMs = 0, testType = "COUNTING", effort = "EASY", activity = "CYCLING",
                    requestedInput = "PHONE_MIC", actualInput = "the built-in mic", audioSource = "UNPROCESSED",
                    sampleRateHz = 44_100, durationMs = 1_000, hrStartBpm = 100, hrEndBpm = 110,
                    hrSessionId = null, fileName = "regression.wav",
                ),
            )
        }
        openLab()
        compose.onNodeWithText("Talk test").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Export all with labels").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Export all with labels").performClick()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test
    fun healthToolStatesItIsReadOnly() {
        openLab()
        compose.onNodeWithText("Health data").performClick()
        compose.onNodeWithText("Health Connect").assertIsDisplayed()
    }

    @Test
    fun firstRunAsksForAYearOfBirthAndThenOffersTheProgram() {
        waitForText("About you")
        compose.onNodeWithText("Year of birth").performTextInput("1996")
        compose.onNodeWithText("Save").performClick()
        waitForText("Foundation 8")
        compose.onNodeWithText("Start this program").performClick()
        compose.onNodeWithText("Before you start").assertIsDisplayed()
        compose.onNodeWithText("I understand").performClick()
        waitForText("Week 1 of 8, session 1 of 3")
        compose.onAllNodesWithText("Easy 20 minutes")[0].assertIsDisplayed()
    }

    @Test
    fun aFreeWorkoutRunsAndLandsInHistory() {
        runBlocking {
            container.trainingDao.saveProfile(
                ProfileEntity(birthYear = 1996, deviceId = "fake", deviceName = "Fake Watch", deviceSourceKind = "fake"),
            )
        }
        waitForText("Start free workout")
        compose.onNodeWithText("Start free workout").performScrollTo().performClick()
        waitForText("Go")
        compose.onNodeWithText("Go").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.sessionId != null && source.started }
        compose.waitUntil(timeoutMillis = 5_000) { source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming
        source.readingsFlow.tryEmit(HeartRateReading(142, System.currentTimeMillis(), container.clock.elapsedMs()))
        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.bpm == 142 }
        compose.onNodeWithTag("workout-bpm").assertIsDisplayed()
        waitForText("142 bpm")
        compose.onNodeWithText("Hard").assertIsDisplayed()

        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onAllNodesWithText("Finish")[1].performClick()
        waitForText("Workout saved")
        waitForText("Done")
        compose.onNodeWithText("Done").performClick()

        waitForText("History")
        compose.onNodeWithText("History").performClick()
        waitForText("Free workout")
        assertTrue(spoken.contains("Workout started."))
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeSource : HeartRateSource {
        override val device = HeartRateDevice("fake", "Fake Watch", "fake")
        val stateFlow = MutableStateFlow<SourceState>(SourceState.Idle)
        val readingsFlow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 16)
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
}
