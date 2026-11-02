package app.haanf.workout

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log

/** Offline speech. If a phone has no engine, workouts simply run silently. */
class SpeechAnnouncer(context: Context) : Announcer {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null

    override fun say(text: String) {
        main.post {
            when {
                ready -> speak(text)
                engine != null -> waiting = text
                else -> {
                    waiting = text
                    engine = TextToSpeech(appContext, ::onReady)
                }
            }
        }
    }

    private fun onReady(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) {
            Log.w(TAG, "No speech engine, status $status")
            return
        }
        engine?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        waiting?.let(::speak)
        waiting = null
    }

    private fun speak(text: String) {
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    private companion object {
        const val TAG = "HaanfSpeech"
    }
}
