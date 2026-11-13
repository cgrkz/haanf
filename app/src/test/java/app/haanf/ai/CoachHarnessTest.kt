package app.haanf.ai

import app.haanf.core.Clock
import app.haanf.data.BenchmarkEntity
import app.haanf.data.TalkAnswer
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.training.LocationFix
import app.haanf.training.Sex
import app.haanf.training.TimedBpm
import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import app.haanf.workout.AiWorkoutContext
import app.haanf.workout.DistanceSource
import app.haanf.workout.LiveLink
import app.haanf.workout.ProgramSlot
import app.haanf.workout.WorkoutController
import app.haanf.workout.WorkoutRequest
import app.haanf.workout.WorkoutState
import app.haanf.workout.WorkoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CoachHarnessTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val clock = object : Clock {
        override fun nowEpochMs() = START_MS + scheduler.currentTime
        override fun elapsedMs() = scheduler.currentTime
    }
    private val llm = FakeLlm()
    private val spoken = mutableListOf<String>()
    private var bpm: Int? = 130
    private var dropout = false
    private val savedTalk = mutableListOf<Pair<Int?, TalkAnswer>>()

    private val workouts = WorkoutController(
        scope = scope,
        store = object : WorkoutStore {
            override suspend fun open(workout: WorkoutEntity) = 1L
            override suspend fun close(workout: WorkoutEntity) = Unit
            override suspend fun attachHeartRateSession(workoutId: Long, sessionId: Long) = Unit
            override suspend fun unfinished() = emptyList<WorkoutEntity>()
            override suspend fun samples(sessionId: Long) = emptyList<TimedBpm>()
            override suspend fun creditProgram(programId: String) = Unit
            override suspend fun saveBenchmark(benchmark: BenchmarkEntity) = Unit
        },
        link = object : LiveLink {
            override fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean) = Unit
            override fun end() = Unit
        },
        clock = clock,
        announcer = { },
        distance = object : DistanceSource {
            override fun start(onFix: (LocationFix) -> Unit) = Unit
            override fun stop() = Unit
        },
        currentSessionId = { 1L },
        currentBpm = { bpm },
        inDropout = { dropout },
    )

    private val data = object : CoachDataSource {
        override suspend fun snapshot() = CoachSnapshot(
            LocalDate.of(2026, 11, 2), 30, Sex.MALE, 75.0, HeartRateZones(200), false,
            emptyList(), emptyList(), emptyList(), emptyList(), null, null,
        )

        override suspend fun ruleContext(minutesAvailable: Int, feeling: Int) = RuleContext(
            LocalDate.of(2026, 11, 2), minutesAvailable, feeling, 20, emptyList(), emptyList(), false,
        )

        override suspend fun lastBenchmarkDay(): LocalDate? = null
    }

    private val harness = CoachHarness(
        scope = scope,
        llm = llm,
        workouts = workouts,
        data = data,
        link = object : CoachWorkoutLink {
            override fun bpm() = this@CoachHarnessTest.bpm
            override fun inDropout() = dropout
            override fun sessionId() = 1L
            override suspend fun samples(sessionId: Long) = (0..120).map { TimedBpm(START_MS + it * 1000L, 130) }
            override suspend fun saveTalkCheck(workoutId: Long, bpm: Int?, answer: TalkAnswer) {
                savedTalk += bpm to answer
            }
        },
        clock = clock,
        announcer = { spoken += it },
        tone = { CoachTone.WARM },
    ).also { it.attach() }

    private val plan = WorkoutPlan(
        "Plan",
        listOf(
            WorkoutStep("Warm up", 600, Zone.ZONE_2),
            WorkoutStep("Hard", 600, Zone.ZONE_4),
            WorkoutStep("Cool down", 600, Zone.ZONE_2),
        ),
    )
    private val device = HeartRateDevice("id", "Watch", "fake")
    private val aiRequest = WorkoutRequest(
        title = "Plan",
        activity = TrainingActivity.TREADMILL,
        device = device,
        zones = HeartRateZones(200),
        plan = plan,
        ai = AiWorkoutContext(minutesAvailable = 40, feeling = 4),
        coached = true,
        talkChecks = true,
    )
    private val programRequest = aiRequest.copy(ai = null, slot = ProgramSlot("foundation-8", 1, 0))

    @After
    fun tearDown() = scope.cancel()

    private fun run(seconds: Long) {
        scheduler.advanceTimeBy(seconds * 1000)
        scheduler.runCurrent()
    }

    private fun start(request: WorkoutRequest = aiRequest) {
        workouts.start(request)
        scheduler.runCurrent()
    }

    /** The newest check-in in each request: the conversation grows, and each adds one below. */
    private fun userMessages() = llm.requests.map { request -> request.items.filterIsInstance<LlmItem.User>().last().text }

    private val runningPlan get() = (workouts.state.value as WorkoutState.Running).request.plan!!

    @Test
    fun `speaks when the workout starts`() {
        llm.then { FakeLlm.speak("Let's go") }
        start()
        assertEquals(listOf("Let's go"), spoken)
        assertTrue(userMessages().single().contains("The workout has just started"))
    }

    @Test
    fun `only the speak tool is ever heard, so reasoning cannot be read aloud`() {
        llm.then { LlmReply("The user seems fine. I should reassure them.", emptyList()) }
        start()
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `sleeps for as long as the AI chose, then wakes`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 4) }
        start()
        run(239)
        assertEquals(1, llm.requests.size)
        run(2)
        assertEquals(2, llm.requests.size)
        assertTrue(userMessages().last().contains("Your timer ran out"))
    }

    @Test
    fun `keeps the AI's sleep within 2 to 10 minutes`() {
        llm.then { FakeLlm.speak("Short", nextMinutes = 0) }
        start()
        run(119)
        assertEquals(1, llm.requests.size)
        run(2)
        assertEquals(2, llm.requests.size)

        val long = FakeLlm().then { FakeLlm.speak("Long", nextMinutes = 60) }
        assertEquals(10, CoachHarness.MAX_SLEEP_MINUTES)
        assertTrue(long.requests.isEmpty())
    }

    @Test
    fun `tells the AI the current heart rate and what it has said`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
        start()
        run(121)
        val message = userMessages().last()
        assertTrue(message.contains("Heart rate now: 130 bpm"))
        assertTrue(message.contains("Current step: Warm up"))
        val said = llm.requests.last().items.filterIsInstance<LlmItem.ToolTurn>().single()
        assertEquals("Spoken at 0:00.", said.outcomes.single().content)
    }

    @Test
    fun `a new step wakes it early`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
        val shortWarmUp = plan.copy(steps = listOf(plan.steps[0].copy(seconds = 300)) + plan.steps.drop(1))
        start(aiRequest.copy(plan = shortWarmUp))
        run(310)
        assertEquals(2, llm.requests.size)
        assertTrue(userMessages().last().contains("A new step just began: Hard"))
    }

    @Test
    fun `waits at least a minute between calls unless the athlete speaks`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
        start()
        run(10)
        dropout = true
        run(10)
        assertEquals(1, llm.requests.size)
        run(50)
        assertEquals(2, llm.requests.size)
    }

    @Test
    fun `answers the athlete straight away`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
        start()
        run(5)
        harness.userSaid("my knee hurts")
        scheduler.runCurrent()
        assertEquals(2, llm.requests.size)
        assertTrue(userMessages().last().contains("The athlete just said: \"my knee hurts\""))
    }

    @Test
    fun `a dropout wakes it`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
        start()
        run(70)
        dropout = true
        run(6)
        assertTrue(userMessages().last().contains("readings have stopped"))
    }

    @Test
    fun `a sustained spike wakes it, and a brief one does not`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
        start()
        run(70)
        bpm = 170
        run(30)
        assertEquals(1, llm.requests.size)
        bpm = 130
        run(30)
        assertEquals(1, llm.requests.size)
        bpm = 170
        run(55)
        assertEquals(2, llm.requests.size)
        assertTrue(userMessages().last().contains("Heart rate is 170"))
    }

    @Test
    fun `stops at the call limit but still answers the athlete`() {
        llm.then { FakeLlm.speak("Again", nextMinutes = 2) }
        start()
        run(3_600)
        assertEquals(CoachHarness.MAX_CALLS, llm.requests.size)
        harness.userSaid("hello?")
        scheduler.runCurrent()
        assertEquals(CoachHarness.MAX_CALLS + 1, llm.requests.size)
    }

    @Test
    fun `gives up politely after repeated failures, and the workout carries on`() {
        llm.then { throw LlmException(LlmFailure.NETWORK) }
        start()
        run(2_000)
        assertEquals(CoachHarness.MAX_FAILURES, llm.requests.size)
        assertEquals("The coach is offline. Your workout carries on.", spoken.single())
        assertTrue(harness.status.value.offline)
        assertTrue(workouts.state.value is WorkoutState.Running)
    }

    @Test
    fun `one failure does not stop the coach`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
            .then { throw LlmException(LlmFailure.RATE_LIMITED) }
            .then { FakeLlm.speak("Back", nextMinutes = 2) }
        start()
        run(500)
        assertTrue(spoken.contains("Back"))
        assertFalse(harness.status.value.offline)
    }

    @Test
    fun `can ease off on metrics alone`() {
        var round = 0
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
            .then { if (round++ == 0) FakeLlm.adjust(PlanChange.EASE) else FakeLlm.speak("Easing off", 5) }
        start()
        run(121)
        assertTrue(runningPlan.steps.none { it.target == Zone.ZONE_4 })
        assertTrue(spoken.contains("Easing off"))
    }

    @Test
    fun `can never increase because of metrics`() {
        var round = 0
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
            .then { if (round++ == 0) FakeLlm.adjust(PlanChange.EXTEND, 10) else FakeLlm.speak("Fine", 5) }
        start()
        val before = runningPlan
        run(121)
        assertEquals(before, runningPlan)
        val result = (llm.requests.last().items.last() as LlmItem.ToolTurn).outcomes.single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("just asked"))
    }

    @Test
    fun `intensifying on metrics is refused too`() {
        var round = 0
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
            .then { if (round++ == 0) FakeLlm.adjust(PlanChange.INTENSIFY) else FakeLlm.speak("Fine", 5) }
        start()
        val before = runningPlan
        run(121)
        assertEquals(before, runningPlan)
    }

    @Test
    fun `can increase when the athlete asks`() {
        var round = 0
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
            .then { if (round++ == 0) FakeLlm.adjust(PlanChange.EXTEND, 10) else FakeLlm.speak("Ten more minutes", 5) }
        start()
        run(5)
        harness.userSaid("give me ten more minutes")
        scheduler.runCurrent()
        assertEquals(plan.totalSeconds + 600, runningPlan.totalSeconds)
    }

    @Test
    fun `an increase the athlete asked for must still pass the rule check`() {
        val tight = aiRequest.copy(ai = AiWorkoutContext(minutesAvailable = 30, feeling = 4))
        var round = 0
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
            .then { if (round++ == 0) FakeLlm.adjust(PlanChange.EXTEND, 60) else FakeLlm.speak("Sorry", 5) }
        start(tight)
        run(5)
        harness.userSaid("add an hour")
        scheduler.runCurrent()
        assertEquals(plan.totalSeconds, runningPlan.totalSeconds)
    }

    @Test
    fun `a program workout offers no way to change it`() {
        llm.then { FakeLlm.speak("One") }
        start(programRequest)
        assertTrue(llm.requests.single().tools.none { it.name == "adjust_workout" })
        assertTrue(userMessages().single().contains("This workout is fixed"))
    }

    @Test
    fun `a program workout cannot be changed even if a model tries`() {
        var round = 0
        llm.then { if (round++ == 0) FakeLlm.adjust(PlanChange.EASE) else FakeLlm.speak("Fine", 5) }
        start(programRequest)
        assertEquals(plan, runningPlan)
    }

    @Test
    fun `an uncoached workout makes no AI calls`() {
        start(aiRequest.copy(coached = false))
        run(600)
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `no calls happen after the workout ends`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
        start()
        workouts.finish()
        scheduler.runCurrent()
        val calls = llm.requests.size
        run(600)
        assertEquals(calls, llm.requests.size)
        assertFalse(harness.status.value.active)
    }

    @Test
    fun `the coach can ask for a talk test, and a tap answers it`() {
        llm.then { FakeLlm.speak("Could you talk comfortably?", nextMinutes = 10, askTalkTest = true) }
            .then { FakeLlm.speak("Thanks", nextMinutes = 10) }
        start()
        assertTrue(harness.status.value.awaitingTalkAnswer)
        bpm = 155
        harness.answerTalkTest(TalkAnswer.SOME_EFFORT)
        scheduler.runCurrent()
        assertEquals(listOf(155 to TalkAnswer.SOME_EFFORT), savedTalk)
        assertFalse(harness.status.value.awaitingTalkAnswer)
        assertTrue(userMessages().last().contains("could talk with some effort"))
    }

    @Test
    fun `the heart rate overview is a summary, not the stream`() {
        var round = 0
        llm.then {
            if (round++ == 0) {
                FakeLlm.call("heart_rate_overview", buildJsonObject { put("last_minutes", 2) })
            } else {
                FakeLlm.speak("Steady", 5)
            }
        }
        start()
        run(120)
        val result = (llm.requests.first { it.items.size > 1 }.items.last() as LlmItem.ToolTurn).outcomes.single()
        assertTrue(result.content.contains("average 130"))
        assertTrue(result.content.length < 400)
    }

    @Test
    fun `no talk test when talk test checks are off`() {
        llm.then { FakeLlm.speak("Could you talk?", nextMinutes = 10, askTalkTest = true) }
        start(aiRequest.copy(talkChecks = false))
        assertFalse(harness.status.value.awaitingTalkAnswer)
        assertTrue(userMessages().single().contains("Talk test checks are off"))
    }

    @Test
    fun `never a talk test during a hard effort`() {
        llm.then { FakeLlm.speak("Steady", nextMinutes = 10) }
            .then { FakeLlm.speak("Could you talk?", nextMinutes = 10, askTalkTest = true) }
        start()
        run(605)
        assertTrue(userMessages().last().contains("This is a hard effort"))
        assertFalse(harness.status.value.awaitingTalkAnswer)
    }

    @Test
    fun `a talk test can be skipped`() {
        llm.then { FakeLlm.speak("Could you talk?", nextMinutes = 10, askTalkTest = true) }
        start()
        assertTrue(harness.status.value.awaitingTalkAnswer)
        harness.skipTalkTest()
        assertFalse(harness.status.value.awaitingTalkAnswer)
        assertTrue(savedTalk.isEmpty())
    }

    private companion object {
        const val START_MS = 1_700_000_000_000
    }

    @Test
    fun `the coach keeps one conversation, so it remembers what the athlete said`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 10) }
            .then { FakeLlm.speak("Noted", nextMinutes = 2) }
            .then { FakeLlm.speak("Later", nextMinutes = 2) }
        start()
        run(30)
        harness.userSaid("my knee hurts")
        scheduler.runCurrent()
        run(125)
        val later = llm.requests.last().items
        val users = later.filterIsInstance<LlmItem.User>().map { it.text }
        assertTrue(users.first().contains("Athlete:") || users.first().contains("years old"))
        assertTrue(users.any { it.contains("The athlete just said: \"my knee hurts\"") })
        assertTrue(users.last().contains("Your timer ran out"))
        // Earlier turns are sent back unchanged, in order.
        assertEquals(llm.requests[1].items, later.take(llm.requests[1].items.size))
    }

    @Test
    fun `about you and the plan are sent once, at the top`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
        start()
        run(125)
        val users = llm.requests.last().items.filterIsInstance<LlmItem.User>()
        assertTrue(users.first().text.contains("The plan:"))
        assertTrue(users.first().text.contains("[10:00] Hard"))
        assertFalse(users.last().text.contains("The plan:"))
        assertTrue(llm.requests.all { it.cacheable })
    }

    @Test
    fun `each check-in is timed and lists what happened since the last one`() {
        llm.then { FakeLlm.speak("One", nextMinutes = 2) }
        start()
        run(70)
        dropout = true
        run(6)
        assertTrue(userMessages().last(), userMessages().last().contains("Heart rate readings stopped."))
        dropout = false
        run(10)
        run(120)
        val message = userMessages().last()
        assertTrue(message, message.startsWith("[3:"))
        assertTrue(message, message.contains("Since your last check-in at 1:"))
        assertTrue(message, message.contains("Heart rate readings are back."))
        assertFalse(message, message.contains("readings stopped."))
    }

    @Test
    fun `what the coach says and hears goes on the workout timeline`() {
        llm.then { FakeLlm.speak("Easy does it", nextMinutes = 10) }
        start()
        harness.userSaid("feeling good")
        scheduler.runCurrent()
        val kinds = workouts.timeline.map { it.kind }
        assertTrue(kinds.contains(app.haanf.workout.TimelineKind.COACH))
        assertTrue(kinds.contains(app.haanf.workout.TimelineKind.ATHLETE))
        assertTrue(workouts.timeline.first { it.kind == app.haanf.workout.TimelineKind.COACH }.line().startsWith("[0:00] Coach: Easy does it"))
    }

    @Test
    fun `in a free workout, heart rate decides what counts as a hard effort`() {
        llm.then { FakeLlm.speak("Steady", nextMinutes = 2) }
            .then { FakeLlm.speak("Could you talk?", nextMinutes = 10, askTalkTest = true) }
        start(aiRequest.copy(plan = null, ai = null))
        bpm = 175
        run(125)
        assertTrue(userMessages().last().contains("This is a hard effort"))
        assertFalse(harness.status.value.awaitingTalkAnswer)
    }

    @Test
    fun `a treadmill workout tells the coach the phone cannot measure it`() {
        llm.then { FakeLlm.speak("One") }
        start()
        assertTrue(userMessages().single().contains("cannot measure speed, distance or incline"))
    }
}
