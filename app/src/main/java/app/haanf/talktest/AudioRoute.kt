package app.haanf.talktest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class NoBluetoothInputException : Exception("No Bluetooth earbuds with a microphone are connected")

/** Points the system at the chosen microphone and restores audio settings on [release]. */
class AudioRoute private constructor(
    private val audioManager: AudioManager,
    val preferredDevice: AudioDeviceInfo?,
    private val onRelease: () -> Unit,
) {
    fun release() = onRelease()

    companion object {
        private const val ROUTE_WAIT_MS = 4_000L
        private const val ROUTE_POLL_MS = 100L

        private val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        fun isBluetooth(device: AudioDeviceInfo?): Boolean = device?.type in BLUETOOTH_TYPES

        suspend fun acquire(context: Context, input: AudioInput): AudioRoute {
            val audioManager = context.getSystemService(AudioManager::class.java)
            return when (input) {
                AudioInput.PHONE_MIC -> AudioRoute(audioManager, builtInMic(audioManager)) { }
                AudioInput.BLUETOOTH_EARBUDS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        communicationDeviceRoute(audioManager)
                    } else {
                        scoRoute(context, audioManager)
                    }
            }
        }

        private fun builtInMic(audioManager: AudioManager): AudioDeviceInfo? =
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        private suspend fun communicationDeviceRoute(audioManager: AudioManager): AudioRoute {
            val target = audioManager.availableCommunicationDevices
                .firstOrNull { it.type in BLUETOOTH_TYPES }
                ?: throw NoBluetoothInputException()
            val previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setCommunicationDevice(target)
            // Earbuds take a moment to leave music mode and open their microphone.
            withTimeoutOrNull(ROUTE_WAIT_MS) {
                while (audioManager.communicationDevice?.id != target.id) delay(ROUTE_POLL_MS)
            }
            return AudioRoute(audioManager, null) {
                audioManager.clearCommunicationDevice()
                audioManager.mode = previousMode
            }
        }

        @Suppress("DEPRECATION")
        private suspend fun scoRoute(context: Context, audioManager: AudioManager): AudioRoute {
            if (!audioManager.isBluetoothScoAvailableOffCall) throw NoBluetoothInputException()
            val previousMode = audioManager.mode
            val connected = CompletableDeferred<Unit>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) connected.complete(Unit)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            val routed = withTimeoutOrNull(ROUTE_WAIT_MS) { connected.await() } != null
            context.unregisterReceiver(receiver)
            if (!routed) {
                audioManager.stopBluetoothSco()
                audioManager.mode = previousMode
                throw NoBluetoothInputException()
            }
            return AudioRoute(audioManager, null) {
                audioManager.stopBluetoothSco()
                audioManager.mode = previousMode
            }
        }
    }
}
