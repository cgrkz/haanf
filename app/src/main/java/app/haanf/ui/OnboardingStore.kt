package app.haanf.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remembers which first-run steps are behind the user. Nothing here is health data. */
class OnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    private val _watchStepDone = MutableStateFlow(prefs.getBoolean(KEY_WATCH, false))
    private val _finished = MutableStateFlow(prefs.getBoolean(KEY_FINISHED, false))

    val watchStepDone: StateFlow<Boolean> = _watchStepDone.asStateFlow()
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    fun markWatchStepDone() {
        prefs.edit().putBoolean(KEY_WATCH, true).apply()
        _watchStepDone.value = true
    }

    fun markFinished() {
        prefs.edit().putBoolean(KEY_FINISHED, true).apply()
        _finished.value = true
    }

    private companion object {
        const val KEY_WATCH = "watch_step_done"
        const val KEY_FINISHED = "finished"
    }
}
