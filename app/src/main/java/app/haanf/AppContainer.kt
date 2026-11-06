package app.haanf

import android.content.Context
import android.os.Build
import app.haanf.ai.AiSettingsStore
import app.haanf.ai.CoachChat
import app.haanf.ai.CoachData
import app.haanf.ai.CoachHarness
import app.haanf.ai.CoachTone
import app.haanf.ai.CoachWorkoutLink
import app.haanf.ai.LlmProvider
import app.haanf.ai.OnDeviceSpeech
import app.haanf.ai.ReviewRunner
import app.haanf.ai.WorkoutPlanner
import app.haanf.ai.WorkoutDetail
import app.haanf.ai.WorkoutLookup
import app.haanf.ai.WorkoutReviewer
import app.haanf.core.Clock
import app.haanf.core.SystemTimeClock
import app.haanf.data.HaanfDatabase
import app.haanf.data.RoomSessionStore
import app.haanf.data.TalkAnswer
import app.haanf.data.TalkCheckEntity
import app.haanf.health.HealthConnectReader
import app.haanf.heartrate.HeartRateSourceFactory
import app.haanf.heartrate.ble.BleDiscovery
import app.haanf.heartrate.ble.BleHeartRateSourceFactory
import app.haanf.session.HeartRatePreview
import app.haanf.session.LiveSessionController
import app.haanf.program.ProgramTracker
import app.haanf.talktest.TalkTestLibrary
import app.haanf.talktest.TalkTestRecorder
import app.haanf.training.TimedBpm
import app.haanf.ui.OnboardingStore
import app.haanf.workout.Announcer
import app.haanf.workout.BarometerSource
import app.haanf.workout.DistanceSource
import app.haanf.workout.GpsDistanceSource
import app.haanf.workout.LiveLink
import app.haanf.workout.PressureSource
import app.haanf.workout.RoomWorkoutStore
import app.haanf.workout.ServiceLiveLink
import app.haanf.workout.SpeechAnnouncer
import app.haanf.workout.TimelineEntry
import app.haanf.workout.WorkoutController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** Wires the app together by hand. One instance lives as long as the process. */
class AppContainer(
    context: Context,
    sourceFactory: HeartRateSourceFactory = BleHeartRateSourceFactory(context),
    database: HaanfDatabase = HaanfDatabase.create(context.applicationContext),
    link: LiveLink = ServiceLiveLink(context.applicationContext),
    announcer: Announcer = SpeechAnnouncer(context.applicationContext),
    distance: DistanceSource = GpsDistanceSource(context.applicationContext),
    llmOverride: LlmProvider? = null,
    pressure: PressureSource = BarometerSource(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val clock: Clock = SystemTimeClock
    val hrDao = database.hrDao()
    val talkTestDao = database.talkTestDao()
    val trainingDao = database.trainingDao()
    val coachDao = database.coachDao()

    val live = LiveSessionController(
        scope = sessionScope,
        store = RoomSessionStore(hrDao),
        sources = sourceFactory,
        clock = clock,
        environment = "phone=${Build.MANUFACTURER} ${Build.MODEL}; android_sdk=${Build.VERSION.SDK_INT}; " +
            "app=${BuildConfig.VERSION_NAME}",
    )
    val discovery = BleDiscovery(appContext, clock)
    val talkTests = TalkTestLibrary(appContext, talkTestDao)
    val recorder = TalkTestRecorder(
        context = appContext,
        dao = talkTestDao,
        clock = clock,
        library = talkTests,
        currentBpm = { live.freshBpm() },
        currentSessionId = { live.live.value.sessionId },
    )
    val health = HealthConnectReader(appContext)
    val preview = HeartRatePreview(CoroutineScope(SupervisorJob() + Dispatchers.Default), sourceFactory)
    val onboarding = OnboardingStore(appContext)
    val programs = ProgramTracker(trainingDao, clock)
    val workouts = WorkoutController(
        scope = sessionScope,
        store = RoomWorkoutStore(trainingDao, hrDao, programs),
        link = link,
        clock = clock,
        announcer = announcer,
        distance = distance,
        currentSessionId = { live.live.value.sessionId },
        currentBpm = { live.freshBpm() },
        pressure = pressure,
        inDropout = { live.live.value.inDropout },
    )

    val aiSettings = AiSettingsStore(appContext)
    val llm: LlmProvider = llmOverride ?: aiSettings
    private val tone: () -> CoachTone = { aiSettings.settings.value?.tone ?: CoachTone.WARM }
    val speech = OnDeviceSpeech(appContext)
    val coachData = CoachData(trainingDao, coachDao, programs, { health.vitals(Instant.now()) }, clock)
    val planner = WorkoutPlanner(llm, coachData, tone)
    private val workoutDetail = WorkoutDetail(
        samples = { samples(it) },
        timeline = { id -> trainingDao.events(id).mapNotNull(TimelineEntry::from) },
        splits = { id -> trainingDao.splits(id) },
    )
    val chat = CoachChat(
        llm, coachDao, coachData, clock, tone,
        lookup = WorkoutLookup(
            between = { from, to -> trainingDao.workoutsBetween(from, to) },
            workout = { id -> trainingDao.workout(id) },
            benchmarks = { trainingDao.benchmarks() },
            detail = workoutDetail,
        ),
    )
    val reviews = ReviewRunner(
        scope = sessionScope,
        workouts = workouts,
        training = trainingDao,
        reviewer = WorkoutReviewer(
            llm = llm,
            data = coachData,
            tone = tone,
            samples = { samples(it) },
            timeline = { id -> trainingDao.events(id).mapNotNull(TimelineEntry::from) },
            splits = { id -> trainingDao.splits(id) },
            proposeFact = { kind, text -> coachData.proposeFact(kind, text) },
        ),
    )
    val coach = CoachHarness(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        llm = llm,
        workouts = workouts,
        data = coachData,
        link = object : CoachWorkoutLink {
            override fun bpm(): Int? = live.freshBpm()
            override fun inDropout(): Boolean = live.live.value.inDropout
            override fun sessionId(): Long? = live.live.value.sessionId
            override suspend fun samples(sessionId: Long): List<TimedBpm> = this@AppContainer.samples(sessionId)
            override suspend fun saveTalkCheck(workoutId: Long, bpm: Int?, answer: TalkAnswer) {
                coachDao.insertTalkCheck(
                    TalkCheckEntity(workoutId = workoutId, atMs = clock.nowEpochMs(), bpm = bpm, answer = answer.name),
                )
            }
        },
        clock = clock,
        announcer = announcer,
        tone = tone,
    )

    private suspend fun samples(sessionId: Long): List<TimedBpm> =
        hrDao.samples(sessionId).map { TimedBpm(it.receivedAtMs, it.bpm) }

    init {
        if (BuildConfig.DEBUG) aiSettings.importDevKey(File(appContext.filesDir, AiSettingsStore.DEV_KEY_FILE))
        coach.attach()
        reviews.attach()
        sessionScope.launch {
            hrDao.closeOpenSessions(reason = "app_process_ended")
            workouts.recoverInterrupted()
            talkTests.removeUnfinished()
        }
    }
}
