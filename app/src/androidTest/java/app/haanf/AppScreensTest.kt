package app.haanf

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import app.haanf.heartrate.SourceState
import app.haanf.talktest.TalkTestScript
import app.haanf.training.SafetyRules
import app.haanf.ui.HaanfRoot
import app.haanf.ui.LabTool
import app.haanf.ui.theme.HaanfTheme
import app.haanf.workout.LiveLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    private lateinit var context: Context
    private val source = FakeSource()
    private val spoken = CopyOnWriteArrayList<String>()
    private val link = object : LiveLink {
        override fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean) =
            container.live.start(device, watchWorkoutRunning = false)
        override fun end() = container.live.stop()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestSupport.grantBluetooth()
    }

    private fun launch(onboarded: Boolean) {
        // The container reads first-run progress when it is built, so reset it first.
        TestSupport.resetOnboarding(context, done = onboarded)
        val database = Room.inMemoryDatabaseBuilder(context, HaanfDatabase::class.java).build()
        container = AppContainer(context, { source }, database, link, { spoken += it }, noGps, { null })
    }

    private fun show() {
        compose.setContent {
            HaanfTheme {
                HaanfRoot(container)
            }
        }
    }

    private fun openLab(tool: LabTool) {
        launch(onboarded = true)
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        show()
        waitForText("Progress")
        compose.onNodeWithText("Progress").performClick()
        compose.onNodeWithContentDescription("Settings and Lab").performClick()
        waitForText(tool.title)
        compose.onNodeWithText(tool.title).performScrollTo().performClick()
    }

    @Test
    fun heartRateToolOffersAScanWhenIdle() {
        openLab(LabTool.HEART_RATE)
        waitForText("Scan for a heart rate device")
        compose.onNodeWithText("No sessions yet.").assertIsDisplayed()
    }

    @Test
    fun liveSessionShowsHeartRateFromAnySource() {
        openLab(LabTool.HEART_RATE)
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
        openLab(LabTool.TALK_TEST)
        waitForText("Passage")
        compose.onNodeWithText("Passage").performClick()
        compose.onNodeWithText(TalkTestScript.PASSAGE).assertIsDisplayed()
    }

    @Test
    fun exportingARecordingOpensTheShareSheetWithoutCrashing() {
        launch(onboarded = true)
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
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        show()
        compose.onNodeWithText("Progress").performClick()
        compose.onNodeWithContentDescription("Settings and Lab").performClick()
        waitForText(LabTool.TALK_TEST.title)
        compose.onNodeWithText(LabTool.TALK_TEST.title).performScrollTo().performClick()
        waitForText("Export all with labels")
        compose.onNodeWithText("Export all with labels").performClick()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test
    fun healthToolStatesItIsReadOnly() {
        openLab(LabTool.HEALTH_DATA)
        waitForText("Health Connect")
        compose.onNodeWithText("Health Connect").assertIsDisplayed()
    }

    @Test
    fun firstRunGoesFromWelcomeThroughTheHealthCheckToTheBenchmark() {
        launch(onboarded = false)
        show()
        waitForText("Get started")
        compose.onNodeWithText("Get started").performClick()

        waitForText("About you")
        compose.onNode(field("Year of birth")).performTextInput("1996")
        compose.onNode(field("Weight in kg")).performTextInput("75")
        compose.onNodeWithText("Male").performClick()
        compose.onNodeWithText("Neither applies to me").performScrollTo().performClick()

        waitForText("Connect your watch")
        compose.onNodeWithText("Continue without a watch").performScrollTo().performClick()

        waitForText("Your first step: a fitness benchmark")
        compose.onNodeWithText("Your program unlocks after this.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Start Foundation 8").fetchSemanticsNodes().size)
    }

    @Test
    fun aHealthConcernPointsToADoctorBeforeTheBenchmark() {
        launch(onboarded = false)
        runBlocking {
            container.trainingDao.saveProfile(ProfileEntity(birthYear = 1996, sex = "MALE", weightKg = 75.0))
        }
        show()
        waitForText("About you")
        compose.onNodeWithText("One or both apply to me").performScrollTo().performClick()
        compose.onNodeWithText(SafetyRules.HEALTH_CHECK_ADVICE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("My doctor has cleared me").performScrollTo().performClick()
        waitForText("Connect your watch")
    }

    @Test
    fun aProfileFromBeforeBenchmarksIsAskedForTheMissingDetails() {
        launch(onboarded = false)
        runBlocking { container.trainingDao.saveProfile(ProfileEntity(birthYear = 1996)) }
        show()
        waitForText("About you")
        compose.onNode(field("Weight in kg")).assertExists()
    }

    @Test
    fun aScoredBenchmarkUnlocksTheProgram() {
        launch(onboarded = true)
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        show()
        waitForText("Start Foundation 8")
        compose.onNodeWithText("Start Foundation 8").performScrollTo().performClick()
        compose.onNodeWithText("I understand").performClick()
        waitForText("Week 1 of 8")
        compose.onNodeWithText("Start session").assertExists()

        compose.onNodeWithText("Progress").performClick()
        waitForText("41.2")
        waitForText("Average for your age and sex", substring = true)
    }

    @Test
    fun anAbandonedBenchmarkGivesNoScoreAndKeepsTheProgramLocked() {
        launch(onboarded = false)
        TestSupport.seedProfile(container)
        show()
        waitForText("Your first step: a fitness benchmark")
        compose.onNodeWithText("Start benchmark").performScrollTo().performClick()
        waitForText("How it works")
        compose.onNodeWithText("Treadmill").performClick()
        compose.onNodeWithText("Start workout").performScrollTo().performClick()
        waitForText("Walk as fast as you can. No running.")
        compose.onNodeWithText("Finish").performClick()
        compose.onNodeWithText("No, stop without a score").performClick()
        waitForText("No score this time")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        waitForText("First, a fitness benchmark")
        assertEquals(0, compose.onAllNodesWithText("Start Foundation 8").fetchSemanticsNodes().size)
    }

    @Test
    fun aFreeWorkoutRunsAndLandsInProgress() {
        launch(onboarded = true)
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        show()
        waitForText("Coach")
        compose.onNodeWithText("Coach").performClick()
        waitForText("Start a free workout")
        compose.onNodeWithText("Start a free workout").performScrollTo().performClick()
        waitForText("Start workout")
        compose.onNodeWithText("Start workout").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.sessionId != null && source.started }
        compose.waitUntil(timeoutMillis = 5_000) { source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming
        source.readingsFlow.tryEmit(HeartRateReading(142, System.currentTimeMillis(), container.clock.elapsedMs()))
        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.bpm == 142 }
        compose.onNodeWithTag("workout-bpm").assertIsDisplayed()
        waitForText("142")
        compose.onNodeWithText("In your hard zone.").assertIsDisplayed()

        compose.onNodeWithText("Finish").performClick()
        compose.onAllNodesWithText("Finish")[1].performClick()
        waitForText("Workout done")
        compose.onNodeWithText("Done").performScrollTo().performClick()

        waitForText("Progress")
        compose.onNodeWithText("Progress").performClick()
        waitForText("Free workout")
        assertTrue(spoken.contains("Workout started."))
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 8_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
