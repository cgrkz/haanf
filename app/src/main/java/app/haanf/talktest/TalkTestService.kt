package app.haanf.talktest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.haanf.HaanfApplication
import app.haanf.MainActivity
import app.haanf.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android silences the microphone for background apps, so a talk test needs its own
 * foreground service to survive the screen turning off or the phone going in a pocket.
 */
class TalkTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recording: Job? = null

    private val recorder get() = (application as HaanfApplication).container.recorder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> recorder.requestStop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (recording?.isActive == true) return
        try {
            enterForeground()
        } catch (e: RuntimeException) {
            recorder.fail("Could not start the microphone service: ${e.message}")
            stopSelf()
            return
        }
        val request = TalkTestRequest(
            type = TalkTestType.valueOf(requireNotNull(intent.getStringExtra(EXTRA_TYPE))),
            effort = Effort.valueOf(requireNotNull(intent.getStringExtra(EXTRA_EFFORT))),
            activity = TrainingActivity.valueOf(requireNotNull(intent.getStringExtra(EXTRA_ACTIVITY))),
            input = AudioInput.valueOf(requireNotNull(intent.getStringExtra(EXTRA_INPUT))),
        )
        recording = scope.launch {
            recorder.record(request)
            ServiceCompat.stopForeground(this@TalkTestService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Talk test recording", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, TalkTestService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Haanf is recording a talk test")
            .setContentText("Audio stays on this phone unless you export it")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "app.haanf.action.START_TALK_TEST"
        private const val ACTION_STOP = "app.haanf.action.STOP_TALK_TEST"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_EFFORT = "effort"
        private const val EXTRA_ACTIVITY = "activity"
        private const val EXTRA_INPUT = "input"
        private const val CHANNEL_ID = "talk_test"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, request: TalkTestRequest) {
            val intent = Intent(context, TalkTestService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, request.type.name)
                .putExtra(EXTRA_EFFORT, request.effort.name)
                .putExtra(EXTRA_ACTIVITY, request.activity.name)
                .putExtra(EXTRA_INPUT, request.input.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TalkTestService::class.java).setAction(ACTION_STOP))
        }
    }
}
