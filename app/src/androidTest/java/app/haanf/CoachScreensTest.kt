package app.haanf

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.haanf.ai.LlmClient
import app.haanf.ai.LlmItem
import app.haanf.ai.LlmReply
import app.haanf.ai.LlmRequest
import app.haanf.ai.ToolCall
import app.haanf.data.BenchmarkEntity
import app.haanf.data.CoachFactEntity
import app.haanf.data.FactStatus
import app.haanf.data.HaanfDatabase
import app.haanf.data.ProfileEntity
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.SourceState
import app.haanf.training.LocationFix
import app.haanf.ui.HaanfRoot
import app.haanf.ui.HaanfTheme
import app.haanf.workout.DistanceSource
import app.haanf.workout.LiveLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** The coach through its real screens, with a scripted AI and a fake wearable. */
@RunWith(AndroidJUnit4::class)
class CoachScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val spoken = CopyOnWriteArrayList<String>()
    private val source = FakeSource()
    private lateinit var container: AppContainer

    private val fakeAi = object : LlmClient {
        override suspend fun send(request: LlmRequest): LlmReply {
            val tools = request.tools.map { it.name }
            return when {
                "propose_workout" in tools -> call("propose_workout", workout())
                "speak" in tools -> call(
                    "speak",
                    buildJsonObject {
                        put("message", "Nice and steady, keep it easy.")
                        put("next_check_in_minutes", 5)
                        put("ask_talk_test", false)
                    },
                )
                "remember_fact" in tools -> if (request.items.size > 1 && request.items.last() is LlmItem.ToolTurn) {
                    LlmReply("I'll keep your knee in mind.", emptyList())
                } else {
                    call(
                        "remember_fact",
                        buildJsonObject {
                            put("kind", "INJURY")
                            put("fact", "Left knee sore")
                        },
                    )
                }
                else -> LlmReply("Solid session. Your heart rate stayed controlled.", emptyList())
            }
        }
    }

    private fun call(name: String, input: JsonObject) = LlmReply(null, listOf(ToolCall("id", name, input)))

    private fun workout() = buildJsonObject {
        put("title", "Coach easy run")
        put("reason", "A gentle session to build on your benchmark.")
        put(
            "steps",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("label", "Easy")
                        put("minutes", 20)
                        put("target", "easy")
                    },
                ),
            ),
        )
    }

    private val link = object : LiveLink {
        override fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean) =
            container.live.start(device, watchWorkoutRunning = false)

        override fun end() = container.live.stop()
    }

    private val noGps = object : DistanceSource {
        override fun start(onFix: (LocationFix) -> Unit) = Unit
        override fun stop() = Unit
    }

    private fun launch(withAi: Boolean = true, seed: Boolean = true) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, HaanfDatabase::class.java).build()
        container = AppContainer(context, { source }, database, link, { spoken += it }, noGps, { if (withAi) fakeAi else null })
        if (seed) seed()
        compose.setContent {
            HaanfTheme {
                HaanfRoot(container)
            }
        }
    }

    private fun seed() = runBlocking {
        container.trainingDao.saveProfile(
            ProfileEntity(
                birthYear = 1996,
                sex = "MALE",
                weightKg = 75.0,
                healthCheckClearedAtMs = 1,
                deviceId = "fake",
                deviceName = "Fake Watch",
                deviceSourceKind = "fake",
            ),
        )
        val id = container.trainingDao.insertWorkout(
            WorkoutEntity(startedAtMs = 0, endedAtMs = 900_000, title = "Walk", activity = "TREADMILL", origin = "BENCHMARK"),
        )
        container.trainingDao.insertBenchmark(
            BenchmarkEntity(
                workoutId = id, protocol = "rockport-1-mile", takenAtMs = 900_000, timeSeconds = 900,
                heartRateBpm = 150, ageYears = 30, sex = "MALE", weightKg = 75.0, vo2Max = 41.2,
            ),
        )
    }

    private fun waitForText(text: String, timeoutMs: Long = 8_000) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openCoach() {
        compose.onNodeWithText("Coach").performClick()
    }

    @Test
    fun withoutAKeyTheCoachTabExplainsWhatIsSentBeforeAnything() {
        launch(withAi = false)
        openCoach()
        waitForText("Set up your coach")
        compose.onNodeWithText("It never receives your location, your voice, or your device details.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tough and pushy").assertExists()
    }

    @Test
    fun theWholeLoopFromPlanToCoachToReview() {
        launch()
        openCoach()
        waitForText("Plan today's workout")
        compose.onNodeWithText("Plan my workout").performScrollTo().performClick()
        waitForText("Coach easy run")
        compose.onNodeWithText("Start this workout").performScrollTo().performClick()

        waitForText("Coach me during this workout")
        compose.onNodeWithText("Go").performScrollTo().performClick()

        waitForText("Nice and steady, keep it easy.")
        assertTrue(spoken.contains("Nice and steady, keep it easy."))

        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.sessionId != null && source.started }
        compose.waitUntil(timeoutMillis = 5_000) { source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming

        compose.onNodeWithText("Too hard").performScrollTo().performClick()

        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onAllNodesWithText("Finish")[1].performClick()
        waitForText("Workout saved")
        waitForText("Solid session.")
        compose.onNodeWithText("Done").performScrollTo().performClick()

        val saved = runBlocking { container.trainingDao.recentWorkouts(5).first { it.origin == "AI" } }
        assertTrue(saved.coached)
        assertEquals("Solid session. Your heart rate stayed controlled.", saved.review)
    }

    @Test
    fun theChatProposesAFactAndOnlyKeepsItAfterConfirmation() {
        launch()
        openCoach()
        waitForText("Your training log")
        compose.onNodeWithText("Message your coach").performTextInput("my left knee hurts")
        compose.onNodeWithText("Send").performClick()
        waitForText("I'll keep your knee in mind.")
        waitForText("Remember this? Left knee sore")

        assertEquals(0, runBlocking { container.coachDao.activeFacts() }.size)
        compose.onNodeWithText("Remember").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.coachDao.activeFacts() }.singleOrNull()?.text == "Left knee sore"
        }
    }

    @Test
    fun aKnownFactCanBeResolved() {
        launch()
        runBlocking {
            container.coachDao.insertFact(
                CoachFactEntity(createdAtMs = 0, kind = "INJURY", text = "Sore ankle", status = FactStatus.ACTIVE.name),
            )
        }
        openCoach()
        waitForText("Injury: Sore ankle")
        compose.onNodeWithText("Mark resolved").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { container.coachDao.activeFacts() }.isEmpty() }
    }

    @Test
    fun benchmarksRunWithoutTheCoach() {
        launch()
        waitForText("Your fitness")
        compose.onNodeWithText("Repeat benchmark").performScrollTo().performClick()
        waitForText("How it works")
        assertEquals(0, compose.onAllNodesWithText("Coach me during this workout").fetchSemanticsNodes().size)
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
