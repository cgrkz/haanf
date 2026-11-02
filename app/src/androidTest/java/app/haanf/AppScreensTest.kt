package app.haanf

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.haanf.data.HaanfDatabase
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.SourceState
import app.haanf.talktest.TalkTestScript
import app.haanf.ui.HaanfRoot
import app.haanf.ui.HaanfTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the screens on an emulator. Says nothing about real Bluetooth or microphones. */
@RunWith(AndroidJUnit4::class)
class AppScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val source = FakeSource()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, HaanfDatabase::class.java).build()
        container = AppContainer(context, { source }, database)
        compose.setContent {
            HaanfTheme {
                HaanfRoot(container)
            }
        }
    }

    @Test
    fun heartRateTabOffersAScanWhenIdle() {
        compose.onNodeWithText("Scan for a heart rate device").assertIsDisplayed()
        compose.onNodeWithText("No sessions yet.").assertIsDisplayed()
    }

    @Test
    fun liveSessionShowsHeartRateFromAnySource() {
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
    fun talkTestTabShowsTheFixedPassage() {
        compose.onNodeWithText("Talk test").performClick()
        compose.onNodeWithText("Passage").performClick()
        compose.onNodeWithText(TalkTestScript.PASSAGE).assertIsDisplayed()
    }

    @Test
    fun healthTabStatesItIsReadOnly() {
        compose.onNodeWithText("Health data").performClick()
        compose.onNodeWithText("Health Connect").assertIsDisplayed()
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
