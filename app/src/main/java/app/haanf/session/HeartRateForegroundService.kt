package app.haanf.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.haanf.HaanfApplication
import app.haanf.MainActivity
import app.haanf.R
import app.haanf.heartrate.HeartRateDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and working with the screen off. The session itself lives
 * in [LiveSessionController]; this class only holds foreground priority and a wake lock.
 */
class HeartRateForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var notifier: Job? = null

    private val container get() = (application as HaanfApplication).container
    private val live get() = container.live

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_TALK -> container.speech.listen(container.coach::userSaid) { }
            ACTION_STOP ->
                if (container.workouts.isRunning) {
                    // Finishing the workout comes back here once it has been saved.
                    container.workouts.finish(reason = "stopped_from_notification")
                } else {
                    live.stop()
                    finish()
                }
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Connecting"),
                foregroundTypes(
                    usesLocation = intent.getBooleanExtra(EXTRA_USES_LOCATION, false),
                    usesMicrophone = intent.getBooleanExtra(EXTRA_USES_MICROPHONE, false),
                ),
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not enter the foreground", e)
            stopSelf()
            return
        }
        acquireWakeLock()
        val device = HeartRateDevice(
            id = requireNotNull(intent.getStringExtra(EXTRA_DEVICE_ID)),
            name = intent.getStringExtra(EXTRA_DEVICE_NAME),
            sourceKind = requireNotNull(intent.getStringExtra(EXTRA_SOURCE_KIND)),
        )
        live.start(device, intent.getBooleanExtra(EXTRA_WATCH_WORKOUT, false))
        startNotifier()
    }

    private fun foregroundTypes(usesLocation: Boolean, usesMicrophone: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val location = if (usesLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val microphone = if (usesMicrophone && hasMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or location or microphone
    }

    private fun startNotifier() {
        notifier?.cancel()
        notifier = scope.launch {
            var shown = ""
            val manager = getSystemService(NotificationManager::class.java)
            while (isActive) {
                val text = container.workouts.statusLine() ?: LiveText.notification(live.live.value)
                if (text != shown) {
                    manager.notify(NOTIFICATION_ID, notification(text))
                    shown = text
                }
                delay(NOTIFICATION_REFRESH_MS)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haanf:live-session")
            .apply { acquire(WAKE_LOCK_LIMIT_MS) }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val talk = PendingIntent.getService(
            this,
            1,
            Intent(this, HeartRateForegroundService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Haanf live heart rate")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (container.coach.status.value.active) addAction(0, "Talk to coach", talk) }
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Live heart rate", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "HaanfService"
        private const val ACTION_START = "app.haanf.action.START_LIVE"
        private const val ACTION_STOP = "app.haanf.action.STOP_LIVE"
        private const val ACTION_TALK = "app.haanf.action.TALK_TO_COACH"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_DEVICE_NAME = "device_name"
        private const val EXTRA_SOURCE_KIND = "source_kind"
        private const val EXTRA_WATCH_WORKOUT = "watch_workout"
        private const val EXTRA_USES_LOCATION = "uses_location"
        private const val EXTRA_USES_MICROPHONE = "uses_microphone"
        private const val CHANNEL_ID = "live_heart_rate"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_REFRESH_MS = 2_000L
        private const val WAKE_LOCK_LIMIT_MS = 8 * 60 * 60 * 1000L

        fun start(
            context: Context,
            device: HeartRateDevice,
            watchWorkoutRunning: Boolean,
            usesLocation: Boolean = false,
            usesMicrophone: Boolean = false,
        ) {
            val intent = Intent(context, HeartRateForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DEVICE_ID, device.id)
                .putExtra(EXTRA_DEVICE_NAME, device.name)
                .putExtra(EXTRA_SOURCE_KIND, device.sourceKind)
                .putExtra(EXTRA_WATCH_WORKOUT, watchWorkoutRunning)
                .putExtra(EXTRA_USES_LOCATION, usesLocation)
                .putExtra(EXTRA_USES_MICROPHONE, usesMicrophone)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HeartRateForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
