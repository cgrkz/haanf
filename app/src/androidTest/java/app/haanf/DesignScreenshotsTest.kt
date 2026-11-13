package app.haanf

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.haanf.ai.LlmClient
import app.haanf.ai.LlmReply
import app.haanf.ai.LlmRequest
import app.haanf.ai.ToolCall
import app.haanf.data.CoachFactEntity
import app.haanf.data.CoachMessageEntity
import app.haanf.data.EnrolmentEntity
import app.haanf.data.FactStatus
import app.haanf.data.HaanfDatabase
import app.haanf.data.TalkAnswer
import app.haanf.data.SplitEntity
import app.haanf.data.TalkCheckEntity
import app.haanf.data.WorkoutEventEntity
import app.haanf.data.WorkoutEntity
import app.haanf.health.DayValue
import app.haanf.health.VitalTrends
import app.haanf.health.Vitals
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.SourceState
import app.haanf.program.Programs
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BenchmarkInputs
import app.haanf.training.BikeType
import app.haanf.training.LocationFix
import app.haanf.training.HeartRateZones
import app.haanf.training.Sex
import app.haanf.training.WalkOutcome
import app.haanf.ui.FinishedSummary
import app.haanf.ui.HaanfRoot
import app.haanf.ui.LiveWorkoutScreen
import app.haanf.ui.TalkTestPanel
import app.haanf.ui.VitalsHolder
import app.haanf.ui.VitalsStatus
import app.haanf.ui.theme.HaanfTheme
import app.haanf.workout.BenchmarkRequest
import app.haanf.workout.DistanceSource
import app.haanf.workout.PressureSource
import app.haanf.workout.TimelineKind
import app.haanf.workout.LiveLink
import app.haanf.workout.WorkoutOrigin
import app.haanf.workout.WorkoutRequest
import app.haanf.workout.WorkoutState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

/**
 * Renders each designed screen with sample data and saves it, for comparing with the designs
 * in docs/design/screens. The pictures land in the app's external files, under design-screens.
 * Sample data only: nothing here says how the app behaves with a real watch.
 */
@RunWith(AndroidJUnit4::class)
class DesignScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private val source = FakeSource()
    private val link = object : LiveLink {
        override fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean) =
            container.live.start(device, watchWorkoutRunning = false)

        override fun end() = container.live.stop()
    }

    private val sampleAi = object : LlmClient {
        override suspend fun send(request: LlmRequest): LlmReply {
            val tools = request.tools.map { it.name }
            return when {
                "propose_workout" in tools -> LlmReply(
                    null,
                    listOf(
                        ToolCall(
                            "id",
                            "propose_workout",
                            buildJsonObject {
                                put("title", "Easy run, 30 min")
                                put("reason", "You did intervals yesterday and slept under 7 hours, so today stays easy.")
                                put(
                                    "steps",
                                    JsonArray(
                                        listOf(
                                            step("Warm-up", 5, "zone_1"),
                                            step("Easy", 20, "zone_2"),
                                            step("Cool-down", 5, "zone_1"),
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                )
                "speak" in tools -> LlmReply(
                    null,
                    listOf(
                        ToolCall(
                            "id",
                            "speak",
                            buildJsonObject {
                                put("message", "Nice and steady. Keep it conversational.")
                                put("next_check_in_minutes", 5)
                                put("ask_talk_test", false)
                            },
                        ),
                    ),
                )
                else -> LlmReply("Good control today.", emptyList())
            }
        }
    }

    private fun step(label: String, minutes: Int, target: String) = buildJsonObject {
        put("label", label)
        put("minutes", minutes)
        put("target", target)
    }

    private val today: LocalDate get() = container.programs.today()
    private fun daysAgoMs(days: Long, hour: Int = 7): Long =
        today.minusDays(days).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestSupport.grantBluetooth()
    }

    private fun build(onboarded: Boolean, withAi: Boolean = true) {
        TestSupport.resetOnboarding(context, done = onboarded)
        val database = Room.inMemoryDatabaseBuilder(context, HaanfDatabase::class.java).build()
        container = AppContainer(context, { source }, database, link, {}, noGps, { if (withAi) sampleAi else null })
    }

    private fun sampleVitals(): VitalsHolder {
        fun series(days: Int, value: (Int) -> Double) = (days downTo 0).map { DayValue(today.minusDays(it.toLong()), value(it)) }
        val resting = series(27) { 58.0 + (it % 5) * 0.8 + it / 9.0 }
        val hrv = series(27) { 46.0 - (it % 4) * 1.5 - it / 7.0 }.filter { it.date <= today.minusDays(3) }
        val sleep = series(27) { 7.1 - (it % 3) * 0.3 }.map { if (it.date == today) it.copy(value = 6 + 40 / 60.0) else it }
        val vitals = Vitals(
            restingHeartRate = VitalTrends.trend(resting, today),
            hrv = VitalTrends.trend(hrv, today),
            sleepHours = VitalTrends.trend(sleep, today),
        )
        return VitalsHolder(vitals, VitalsStatus.READ, emptySet()) {}
    }

    private fun seedTraining() = runBlocking {
        TestSupport.seedProfile(container)
        container.trainingDao.saveProfile(
            requireNotNull(container.trainingDao.profile()).copy(deviceName = "Pixel Watch 2"),
        )
        TestSupport.seedBenchmark(container, vo2Max = 36.8, takenAtMs = daysAgoMs(42))
        TestSupport.seedBenchmark(container, vo2Max = 38.4, takenAtMs = daysAgoMs(8))
        container.trainingDao.saveEnrolment(
            EnrolmentEntity(
                programId = Programs.FOUNDATION_8.id,
                enrolledAtMs = daysAgoMs(30),
                week = 5,
                session = 1,
                lastWeekTrained = 5,
                lastWorkoutEpochDay = today.minusDays(5).toEpochDay(),
            ),
        )
        listOf(
            Triple("Easy 30 minutes", WorkoutOrigin.PROGRAM, 1L),
            Triple("Easy run, 30 min", WorkoutOrigin.AI, 0L),
        ).forEach { (title, origin, days) ->
            container.trainingDao.insertWorkout(
                WorkoutEntity(
                    startedAtMs = daysAgoMs(days),
                    endedAtMs = daysAgoMs(days) + 30 * 60_000,
                    title = title,
                    activity = TrainingActivity.OUTDOOR_RUN.name,
                    origin = origin.name,
                    avgBpm = 128,
                    zone1Seconds = 60,
                    zone2Seconds = 27 * 60,
                    zone4Seconds = 2 * 60,
                ),
            )
        }
    }

    private fun showRoot(vitals: VitalsHolder? = sampleVitals()) {
        compose.setContent { HaanfTheme { HaanfRoot(container, vitals) } }
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "design-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun firstRun() {
        build(onboarded = false)
        showRoot()
        waitForText("Get started")
        shot("01-welcome")
        compose.onNodeWithText("Get started").performClick()
        waitForText("About you")
        compose.onNode(field("Year of birth")).performTextInput("1994")
        compose.onNode(field("Weight in kg")).performTextInput("72")
        compose.onNodeWithText("Male").performClick()
        shot("02-about-you")
        compose.onNodeWithText("Neither applies to me").performScrollTo().performClick()
        waitForText("Connect your watch")
        shot("03-connect-watch")
        compose.onNodeWithText("Continue without a watch").performScrollTo().performClick()
        if (Features.BENCHMARK) {
            waitForText("Your first step: a fitness benchmark")
            shot("04-first-benchmark")
        }
    }

    @Test
    fun programNotEnrolled() {
        build(onboarded = true)
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        showRoot()
        waitForText("Start Foundation 8")
        shot("06-program-detail")
        compose.onNodeWithText("Start Foundation 8").performScrollTo()
        shot("06-program-detail-bottom")
    }

    @Test
    fun programAndGetReadyAndLive() {
        build(onboarded = true)
        seedTraining()
        showRoot()
        waitForText("Week 5 of 8")
        shot("05-program-home")
        compose.onNodeWithText("Start session").performClick()
        waitForText("Coach me during this workout")
        compose.waitUntil(timeoutMillis = 5_000) { source.started && source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming
        source.readingsFlow.tryEmit(HeartRateReading(78, System.currentTimeMillis(), container.clock.elapsedMs()))
        waitForText("78")
        shot("09-get-ready")
        compose.onNodeWithText("Cycling").performClick()
        waitForText("Your bike")
        shot("09-get-ready-ride")
        compose.onNodeWithText("Outdoor run").performClick()

        compose.onNodeWithText("Start workout").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.sessionId != null }
        compose.waitUntil(timeoutMillis = 5_000) { source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming
        source.readingsFlow.tryEmit(HeartRateReading(118, System.currentTimeMillis(), container.clock.elapsedMs()))
        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.bpm == 118 }
        waitForText("Nice and steady", substring = true)
        shot("10-live-steady")

        // No readings for ten seconds is a dropout.
        Thread.sleep(12_000)
        compose.waitUntil(timeoutMillis = 10_000) { container.live.live.value.inDropout }
        shot("17-live-dropout")
        container.workouts.finish()
    }

    @Test
    fun coachCheckInPlanAndChat() {
        build(onboarded = true)
        seedTraining()
        runBlocking {
            container.coachDao.insertFact(CoachFactEntity(createdAtMs = 0, kind = "INJURY", text = "Left knee sore since October", status = FactStatus.ACTIVE.name))
            container.coachDao.insertMessage(CoachMessageEntity(atMs = 0, fromCoach = false, text = "My left knee is a bit sore after Tuesday."))
            container.coachDao.insertMessage(
                CoachMessageEntity(atMs = 1, fromCoach = true, text = "Thanks for telling me. I'll keep runs on flat ground and easy this week."),
            )
        }
        showRoot()
        waitForText("Coach")
        compose.onNodeWithText("Coach").performClick()
        waitForText("Let's find today's workout")
        shot("07-coach-check-in")
        compose.onNodeWithText("Find my workout").performScrollTo().performClick()
        waitForText("Easy run, 30 min")
        shot("08-coach-plan")
        compose.onNodeWithText("Change my answers").performScrollTo().performClick()
        compose.onNodeWithText("Unwell").performClick()
        waitForText("Rest is the right call today.", substring = true)
        shot("07-coach-check-in-unwell")
    }

    @Test
    fun chatAndSettingsAndHistory() {
        build(onboarded = true)
        seedTraining()
        runBlocking {
            container.coachDao.insertFact(CoachFactEntity(createdAtMs = 0, kind = "INJURY", text = "Left knee sore since October", status = FactStatus.ACTIVE.name))
            container.coachDao.insertMessage(CoachMessageEntity(atMs = 0, fromCoach = false, text = "My left knee is a bit sore after Tuesday."))
            container.coachDao.insertMessage(
                CoachMessageEntity(atMs = 1, fromCoach = true, text = "Thanks for telling me. I'll keep runs on flat ground and easy this week."),
            )
        }
        showRoot()
        waitForText("Progress")
        compose.onNodeWithText("Progress").performClick()
        waitForText(if (Features.BENCHMARK) "Estimated VO2max" else "Vitals, last 4 weeks")
        shot("14-progress")
        compose.onNodeWithText("See all workouts").performScrollTo()
        shot("14-progress-bottom")
        compose.onNodeWithText("See all workouts").performClick()
        waitForText("All workouts")
        shot("18-history")
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithContentDescription("Settings and Lab").performClick()
        waitForText("Settings")
        shot("15-settings")
        compose.onNodeWithText("Talk test recorder").performScrollTo()
        shot("15-settings-bottom")
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()

        compose.onNodeWithText("Coach").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Chat with your coach").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Chat with your coach").performClick()
        waitForText("What the coach knows")
        shot("21-chat")
    }

    @Test
    fun liveInterval() {
        build(onboarded = true)
        val plan = Programs.FOUNDATION_8.session(5, 1).plan
        val zones = HeartRateZones(187)
        val running = WorkoutState.Running(
            workout = WorkoutEntity(id = 1, startedAtMs = 0, title = plan.title, activity = "OUTDOOR_RUN", origin = "PROGRAM"),
            request = WorkoutRequest(plan.title, TrainingActivity.OUTDOOR_RUN, source.device, zones, plan = plan),
            startedElapsedMs = 0,
            elapsedSeconds = 15 * 60 + 48,
            stepIndex = 3,
            stepRemainingSeconds = 72,
        )
        startLive(151)
        compose.setContent { HaanfTheme { LiveWorkoutScreen(container, running) } }
        waitForText("1:12")
        shot("11-live-interval")
        container.live.stop()
    }

    @Test
    fun liveBenchmark() {
        build(onboarded = true)
        val running = WorkoutState.Running(
            workout = WorkoutEntity(id = 1, startedAtMs = 0, title = "1-mile walk benchmark", activity = "OUTDOOR_RUN", origin = "BENCHMARK"),
            request = WorkoutRequest(
                "1-mile walk benchmark",
                TrainingActivity.OUTDOOR_RUN,
                source.device,
                HeartRateZones(187),
                benchmark = BenchmarkRequest(BenchmarkInputs(32, Sex.MALE, 72.0), usesGps = true),
            ),
            startedElapsedMs = 0,
            elapsedSeconds = 8 * 60 + 40,
            distanceMetres = 840.0,
        )
        startLive(132)
        compose.setContent { HaanfTheme { LiveWorkoutScreen(container, running) } }
        waitForText("0.84")
        shot("19-live-benchmark")
        container.live.stop()
    }

    @Test
    fun talkTest() {
        build(onboarded = true)
        compose.setContent { HaanfTheme { TalkTestPanel(container, 126) } }
        waitForText("Talk test")
        shot("12-talk-test")
    }

    @Test
    fun summary() {
        build(onboarded = true)
        val workout = runBlocking {
            TestSupport.seedProfile(container)
            container.trainingDao.saveEnrolment(
                EnrolmentEntity(programId = Programs.FOUNDATION_8.id, enrolledAtMs = 0, week = 5, session = 2, lastWeekTrained = 5),
            )
            val started = System.currentTimeMillis() - (32 * 60 + 10) * 1000L
            val entity = WorkoutEntity(
                startedAtMs = started,
                endedAtMs = started + (32 * 60 + 10) * 1000L,
                title = "4 x 2 minutes in Zone\u00A04",
                activity = "OUTDOOR_RUN",
                origin = "PROGRAM",
                programId = Programs.FOUNDATION_8.id,
                programWeek = 5,
                programSession = 1,
                avgBpm = 139,
                maxBpm = 166,
                zone1Seconds = 2 * 60,
                zone2Seconds = 21 * 60,
                zone4Seconds = 8 * 60,
                zone5Seconds = 60,
                countedForProgram = true,
                review = "Good control today. You stayed in your target zone on all four efforts, and your heart rate settled quickly afterwards.",
                hrDropBpm = 28,
                effortRating = 3,
            )
            val id = container.trainingDao.insertWorkout(entity)
            container.coachDao.insertTalkCheck(
                TalkCheckEntity(workoutId = id, atMs = started + 9 * 60_000, bpm = 126, answer = TalkAnswer.COMFORTABLE.name),
            )
            entity.copy(id = id)
        }
        compose.setContent { HaanfTheme { FinishedSummary(container, WorkoutState.Finished(workout)) {} } }
        waitForText("Workout done")
        shot("13-summary")
        compose.onNodeWithText("Done").performScrollTo()
        shot("13-summary-bottom")
    }

    @Test
    fun benchmarkSummary() {
        build(onboarded = true)
        val workout = runBlocking {
            TestSupport.seedProfile(container)
            val started = System.currentTimeMillis() - 15 * 60_000L
            val entity = WorkoutEntity(
                startedAtMs = started,
                endedAtMs = started + 14 * 60_000L + 32_000,
                title = "1-mile walk benchmark",
                activity = "OUTDOOR_RUN",
                origin = "BENCHMARK",
                avgBpm = 128,
                maxBpm = 141,
                zone2Seconds = 11 * 60,
                zone4Seconds = 3 * 60,
            )
            entity.copy(id = container.trainingDao.insertWorkout(entity))
        }
        val scored = WalkOutcome.Scored(vo2Max = 41.2, timeSeconds = 14 * 60 + 32, heartRateBpm = 138)
        compose.setContent { HaanfTheme { FinishedSummary(container, WorkoutState.Finished(workout, scored)) {} } }
        waitForText("Benchmark done")
        shot("20-benchmark-result")
    }

    @Test
    fun coachSetupWithoutAKey() {
        build(onboarded = true, withAi = false)
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        showRoot()
        waitForText("Coach")
        compose.onNodeWithText("Coach").performClick()
        waitForText("Set up your coach")
        shot("16-coach-setup")
    }

    /** Opened from the Coach tab's settings button, with a key already saved. */
    @Test
    fun coachSetupWithAKeySaved() {
        build(onboarded = true)
        TestSupport.seedProfile(container)
        TestSupport.seedBenchmark(container)
        // A made-up key, saved only if the emulator has none, and removed afterwards.
        val madeUp = container.aiSettings.settings.value == null
        if (madeUp) {
            container.aiSettings.save(
                app.haanf.ai.AiSettings(app.haanf.ai.AiProvider.HINOKI, app.haanf.ai.AiProvider.HINOKI.defaultModel, app.haanf.ai.CoachTone.WARM),
                "made-up-key-for-a-screenshot-only",
            )
        }
        try {
            showRoot()
            waitForText("Coach")
            compose.onNodeWithText("Coach").performClick()
            waitForText("Let's find today's workout")
            compose.onNodeWithContentDescription("Coach settings").performClick()
            waitForText("key is saved", substring = true)
            shot("16-coach-setup-saved")
        } finally {
            if (madeUp) container.aiSettings.clear()
        }
    }

    /** A GPS the test moves by hand. Fixes are pushed, never stored. */
    private class PushGps : DistanceSource {
        private var onFix: ((LocationFix) -> Unit)? = null
        private var metres = 0.0
        private var elapsedMs = 0L

        override fun start(onFix: (LocationFix) -> Unit) {
            this.onFix = onFix
        }

        override fun stop() {
            onFix = null
        }

        fun ride(totalMetres: Double, speedMps: Float) {
            while (metres < totalMetres) {
                metres += 5
                elapsedMs += (5_000 / speedMps).toLong()
                onFix?.invoke(LocationFix(12.9 + metres / 111_195.0, 77.6, 5f, elapsedMs, speedMps))
            }
        }
    }

    private class PushPressure : PressureSource {
        override val available = true
        private var onHpa: ((Double) -> Unit)? = null

        override fun start(onHpa: (Double) -> Unit) {
            this.onHpa = onHpa
        }

        override fun stop() {
            onHpa = null
        }

        fun height(metres: Double) {
            onHpa?.invoke(1_000.0 * (1 - metres / 44_330.0).pow(1 / 0.1903))
        }
    }

    @Test
    fun liveRideOnAClimb() {
        TestSupport.resetOnboarding(context, done = true)
        val gps = PushGps()
        val air = PushPressure()
        val database = Room.inMemoryDatabaseBuilder(context, HaanfDatabase::class.java).build()
        container = AppContainer(context, { source }, database, link, {}, gps, { null }, pressure = air)
        TestSupport.seedProfile(container)
        showRoot()
        container.workouts.start(
            WorkoutRequest(
                title = "Free workout", activity = TrainingActivity.CYCLING, device = source.device,
                zones = HeartRateZones(193), usesGps = true, riderKg = 61.0, bike = BikeType.HYBRID, spokenCues = false,
            ),
        )
        compose.waitUntil(timeoutMillis = 5_000) { container.workouts.state.value is WorkoutState.Running }
        compose.waitUntil(timeoutMillis = 5_000) { source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming
        air.height(0.0)
        gps.ride(2_000.0, 6f)
        Thread.sleep(2_500)
        repeat(8) {
            air.height(8.0)
            gps.ride(2_000.0 + (it + 1) * 15.0, 2.3f)
            source.readingsFlow.tryEmit(HeartRateReading(172, System.currentTimeMillis(), container.clock.elapsedMs()))
            Thread.sleep(1_000)
        }
        waitForText("Climbing", substring = true)
        shot("22-live-ride-climb")
        container.workouts.finish()
    }

    @Test
    fun rideSummary() {
        build(onboarded = true)
        val workout = runBlocking {
            TestSupport.seedProfile(container)
            val started = System.currentTimeMillis() - 1_400_000L
            val entity = WorkoutEntity(
                startedAtMs = started, endedAtMs = started + 1_400_000L, title = "Free workout", activity = "CYCLING",
                origin = "FREE", avgBpm = 149, maxBpm = 185, zone1Seconds = 5, zone2Seconds = 447, zone4Seconds = 421,
                zone5Seconds = 525, coached = true, effortRating = 3, distanceMetres = 4_230.0, movingSeconds = 1_310,
                maxSpeedMps = 8.4, climbMetres = 91.0, descentMetres = 88.0, avgWatts = 118, bikeType = BikeType.HYBRID.name,
                review = "A strong climb: heart rate rose with the 7% gradient and peaked at 185 near the top, then dropped " +
                    "52 bpm on the descent. Next time, shift down early on the climb to keep it under 165.",
            )
            val id = container.trainingDao.insertWorkout(entity)
            listOf(
                SplitEntity(workoutId = id, number = 1, metres = 1_000.0, seconds = 372, endElapsedSeconds = 372, avgBpm = 141, climbMetres = 32.0, descentMetres = 0.0, avgWatts = 158, gapSecondsPerKm = null),
                SplitEntity(workoutId = id, number = 2, metres = 1_000.0, seconds = 431, endElapsedSeconds = 803, avgBpm = 168, climbMetres = 41.0, descentMetres = 2.0, avgWatts = 171, gapSecondsPerKm = null),
                SplitEntity(workoutId = id, number = 3, metres = 1_000.0, seconds = 214, endElapsedSeconds = 1_017, avgBpm = 174, climbMetres = 18.0, descentMetres = 21.0, avgWatts = 96, gapSecondsPerKm = null),
                SplitEntity(workoutId = id, number = 4, metres = 1_000.0, seconds = 128, endElapsedSeconds = 1_145, avgBpm = 142, climbMetres = 0.0, descentMetres = 58.0, avgWatts = 12, gapSecondsPerKm = null),
                SplitEntity(workoutId = id, number = 5, metres = 230.0, seconds = 255, endElapsedSeconds = 1_400, avgBpm = 121, climbMetres = 0.0, descentMetres = 7.0, avgWatts = 40, gapSecondsPerKm = null),
            ).forEach { container.trainingDao.insertSplit(it) }
            listOf(
                Triple(0, TimelineKind.COACH, "Nice start. Keep it easy for the first few minutes.") to 117,
                Triple(250, TimelineKind.ATHLETE, "Big hill coming up.") to 147,
                Triple(262, TimelineKind.COACH, "Shift down early and let your speed drop on the climb.") to 148,
                Triple(720, TimelineKind.COACH, "You're in Zone 4 now. Ease off if you want this steady.") to 172,
                Triple(1_025, TimelineKind.ATHLETE, "Knee feels a bit tight on the descent.") to 157,
                Triple(1_260, TimelineKind.TALK_TEST, "Could talk comfortably.") to 121,
            ).forEach { (line, bpm) ->
                container.trainingDao.insertEvent(
                    WorkoutEventEntity(workoutId = id, atMs = started + line.first * 1000L, elapsedSeconds = line.first, kind = line.second.name, text = line.third, bpm = bpm),
                )
            }
            entity.copy(id = id)
        }
        compose.setContent { HaanfTheme { FinishedSummary(container, WorkoutState.Finished(workout)) {} } }
        waitForText("Workout done")
        shot("23-ride-summary")
        compose.onNodeWithText("Done").performScrollTo()
        shot("23-ride-summary-bottom")
    }

    private fun startLive(bpm: Int) {
        container.live.start(source.device, watchWorkoutRunning = false)
        compose.waitUntil(timeoutMillis = 5_000) { source.readingsFlow.subscriptionCount.value > 0 }
        source.stateFlow.value = SourceState.Streaming
        source.readingsFlow.tryEmit(HeartRateReading(bpm, System.currentTimeMillis(), container.clock.elapsedMs()))
        compose.waitUntil(timeoutMillis = 5_000) { container.live.live.value.bpm == bpm }
    }
}
