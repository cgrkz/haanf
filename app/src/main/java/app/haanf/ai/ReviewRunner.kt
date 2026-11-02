package app.haanf.ai

import android.util.Log
import app.haanf.data.BenchmarkEntity
import app.haanf.data.TrainingDao
import app.haanf.data.WorkoutEntity
import app.haanf.workout.WorkoutController
import app.haanf.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReviewState {
    data object Idle : ReviewState
    data class Working(val workoutId: Long) : ReviewState
    data class Done(val workoutId: Long, val text: String) : ReviewState
    data class Failed(val workoutId: Long, val message: String) : ReviewState
}

/** Reviews each workout as it finishes, and keeps the state the summary screen shows. */
class ReviewRunner(
    private val scope: CoroutineScope,
    private val workouts: WorkoutController,
    private val training: TrainingDao,
    private val reviewer: WorkoutReviewer,
) {
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Idle)
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    fun attach() {
        scope.launch {
            workouts.state.collect { state ->
                if (state is WorkoutState.Finished) launch { review(state.workout) }
            }
        }
    }

    fun retry(workout: WorkoutEntity) {
        scope.launch { review(workout) }
    }

    private suspend fun review(workout: WorkoutEntity) {
        if (workout.review != null) return
        _state.value = ReviewState.Working(workout.id)
        try {
            val benchmark: BenchmarkEntity? = training.benchmarks().firstOrNull { it.workoutId == workout.id }
            val text = reviewer.review(workout, benchmark)
            if (text == null) {
                _state.value = ReviewState.Idle
            } else {
                training.saveReview(workout.id, text)
                _state.value = ReviewState.Done(workout.id, text)
            }
        } catch (e: LlmException) {
            Log.w(TAG, "Review failed: ${e.failure}")
            _state.value = ReviewState.Failed(workout.id, e.failure.userMessage)
        }
    }

    private companion object {
        const val TAG = "HaanfReview"
    }
}
