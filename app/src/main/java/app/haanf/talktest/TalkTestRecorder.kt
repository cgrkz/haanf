package app.haanf.talktest

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import app.haanf.core.Clock
import app.haanf.data.TalkTestDao
import app.haanf.data.TalkTestEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId

sealed interface RecorderState {
    data object Idle : RecorderState
    data object Preparing : RecorderState
    data class Recording(val elapsedMs: Long, val peak: Float, val actualInput: String) : RecorderState
    data class Saved(val test: TalkTestEntity, val warning: String?) : RecorderState
    data class Failed(val message: String) : RecorderState
}

/**
 * Records lossless WAV, because breath sounds are what a lossy codec discards first.
 * Heart rate comes in through plain functions so this class knows nothing of wearables.
 */
@SuppressLint("MissingPermission")
class TalkTestRecorder(
    private val context: Context,
    private val dao: TalkTestDao,
    private val clock: Clock,
    private val library: TalkTestLibrary,
    private val currentBpm: () -> Int?,
    private val currentSessionId: () -> Long?,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    @Volatile
    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    fun fail(message: String) {
        _state.value = RecorderState.Failed(message)
    }

    suspend fun record(request: TalkTestRequest) = withContext(Dispatchers.IO) {
        stopRequested = false
        _state.value = RecorderState.Preparing
        val recordedAt = clock.nowEpochMs()
        val hrStart = currentBpm()
        val fileName = TalkTestScript.fileName(recordedAt, request, ZoneId.systemDefault())
        val target = library.audioFile(fileName)
        val temporary = File(target.path + TalkTestLibrary.TEMP_SUFFIX)
        var route: AudioRoute? = null
        try {
            route = AudioRoute.acquire(context, request.input)
            val capture = capture(request, route, temporary)
            if (!temporary.renameTo(target)) throw IOException("Could not save the recording")
            val test = TalkTestEntity(
                recordedAtMs = recordedAt,
                testType = request.type.name,
                effort = request.effort.name,
                activity = request.activity.name,
                requestedInput = request.input.name,
                actualInput = capture.actualInput,
                audioSource = capture.audioSource,
                sampleRateHz = capture.sampleRateHz,
                durationMs = capture.durationMs,
                hrStartBpm = hrStart,
                hrEndBpm = currentBpm(),
                hrSessionId = currentSessionId(),
                fileName = fileName,
            )
            val id = dao.insert(test)
            val warning = "Asked for Bluetooth earbuds, but this was recorded from ${capture.actualInput}."
                .takeIf { request.input == AudioInput.BLUETOOTH_EARBUDS && !capture.usedBluetooth }
            _state.value = RecorderState.Saved(test.copy(id = id), warning)
        } catch (e: NoBluetoothInputException) {
            _state.value = RecorderState.Failed(e.message.orEmpty())
        } catch (e: IOException) {
            _state.value = RecorderState.Failed("Recording failed: ${e.message}")
        } catch (e: IllegalStateException) {
            _state.value = RecorderState.Failed("Recording failed: ${e.message}")
        } catch (e: SecurityException) {
            _state.value = RecorderState.Failed("Microphone permission is missing")
        } finally {
            route?.release()
            temporary.delete()
        }
    }

    private data class Capture(
        val actualInput: String,
        val usedBluetooth: Boolean,
        val audioSource: String,
        val sampleRateHz: Int,
        val durationMs: Long,
    )

    private suspend fun capture(request: TalkTestRequest, route: AudioRoute, file: File): Capture {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val (source, sourceName) = audioSource(request.input, audioManager)
        val sampleRate = if (request.input == AudioInput.PHONE_MIC) PHONE_SAMPLE_RATE else BLUETOOTH_SAMPLE_RATE
        val minimum = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        if (minimum <= 0) throw IOException("This phone cannot record at $sampleRate Hz")

        val readBytes = sampleRate * WavWriter.BYTES_PER_SAMPLE / READS_PER_SECOND
        val recorder = AudioRecord(source, sampleRate, CHANNEL, ENCODING, maxOf(minimum, readBytes) * 2)
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("The microphone is busy")
            route.preferredDevice?.let { recorder.preferredDevice = it }
            recorder.startRecording()
            val buffer = ByteArray(readBytes)
            var routed: AudioDeviceInfo? = null
            val duration = WavWriter(file, sampleRate).use { writer ->
                while (!stopRequested && writer.durationMs < MAX_DURATION_MS) {
                    currentCoroutineContext().ensureActive()
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read < 0) throw IOException("Microphone read failed, code $read")
                    writer.write(buffer, read)
                    routed = recorder.routedDevice ?: routed
                    _state.value = RecorderState.Recording(
                        elapsedMs = writer.durationMs,
                        peak = pcm16Peak(buffer, read),
                        actualInput = inputLabel(routed),
                    )
                }
                writer.durationMs
            }
            recorder.stop()
            return Capture(inputLabel(routed), AudioRoute.isBluetooth(routed), sourceName, sampleRate, duration)
        } finally {
            recorder.release()
        }
    }

    /** Voice call processing suppresses breath noise, so the phone mic avoids it where it can. */
    private fun audioSource(input: AudioInput, audioManager: AudioManager): Pair<Int, String> {
        if (input == AudioInput.BLUETOOTH_EARBUDS) {
            return MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"
        }
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") {
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED"
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
        }
    }

    private fun inputLabel(device: AudioDeviceInfo?): String {
        val name = device?.productName?.toString().orEmpty()
        return when (device?.type) {
            null -> "unknown input"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "the built-in mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth ($name)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "a wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "a USB headset"
            else ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                ) {
                    "Bluetooth LE ($name)"
                } else {
                    "input type ${device.type}"
                }
        }
    }

    private companion object {
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val PHONE_SAMPLE_RATE = 44_100
        const val BLUETOOTH_SAMPLE_RATE = 16_000
        const val READS_PER_SECOND = 10
        const val MAX_DURATION_MS = 90_000L
    }
}
