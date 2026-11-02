package app.haanf

import android.content.Context
import android.os.Build
import app.haanf.core.Clock
import app.haanf.core.SystemTimeClock
import app.haanf.data.HaanfDatabase
import app.haanf.data.RoomSessionStore
import app.haanf.health.HealthConnectReader
import app.haanf.heartrate.HeartRateSourceFactory
import app.haanf.heartrate.ble.BleDiscovery
import app.haanf.heartrate.ble.BleHeartRateSourceFactory
import app.haanf.session.LiveSessionController
import app.haanf.talktest.TalkTestLibrary
import app.haanf.talktest.TalkTestRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Wires the app together by hand. One instance lives as long as the process. */
class AppContainer(
    context: Context,
    sourceFactory: HeartRateSourceFactory = BleHeartRateSourceFactory(context),
    database: HaanfDatabase = HaanfDatabase.create(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val clock: Clock = SystemTimeClock
    val hrDao = database.hrDao()
    val talkTestDao = database.talkTestDao()

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

    init {
        sessionScope.launch {
            hrDao.closeOpenSessions(reason = "app_process_ended")
            talkTests.removeUnfinished()
        }
    }
}
