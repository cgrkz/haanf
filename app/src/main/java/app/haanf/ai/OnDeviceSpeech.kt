package app.haanf.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns speech into text on the phone, which keeps the promise that audio never leaves it.
 * Only the on-device recogniser is used: there is no fallback to a cloud one.
 */
class OnDeviceSpeech(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    @SuppressLint("NewApi")
    fun listen(onText: (String) -> Unit, onProblem: (String) -> Unit) {
        main.post {
            if (!available) {
                onProblem("On-device speech is not available on this phone.")
                return@post
            }
            stop()
            val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            recognizer = speech
            speech.setRecognitionListener(listener(onText, onProblem))
            _listening.value = true
            speech.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
            )
        }
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        _listening.value = false
    }

    private fun listener(onText: (String) -> Unit, onProblem: (String) -> Unit) = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            stop()
            if (text.isNullOrBlank()) onProblem("Nothing was heard.") else onText(text)
        }

        override fun onError(error: Int) {
            stop()
            onProblem(
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    "Nothing was heard."
                } else {
                    "Speech failed, code $error."
                },
            )
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
