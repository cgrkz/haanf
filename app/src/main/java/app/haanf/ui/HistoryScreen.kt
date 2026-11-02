package app.haanf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.WorkoutEntity
import app.haanf.talktest.TrainingActivity
import app.haanf.workout.WorkoutOrigin
import kotlinx.coroutines.launch

private fun activityLabel(workout: WorkoutEntity): String {
    val activity = TrainingActivity.entries.firstOrNull { it.name == workout.activity }
    return when {
        workout.origin != WorkoutOrigin.BENCHMARK.name -> activity?.label ?: workout.activity
        activity == TrainingActivity.TREADMILL -> "Treadmill walk"
        else -> "Outdoor walk"
    }
}

@Composable
fun HistoryScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val workouts by remember { container.trainingDao.observeWorkouts() }.collectAsStateWithLifecycle(emptyList())
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }
        .collectAsStateWithLifecycle(emptyList())
    var pendingDelete by remember { mutableStateOf<WorkoutEntity?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                if (workouts.isEmpty()) Hint("Your finished workouts appear here.")
            }
        }
        items(workouts, key = { it.id }) { workout ->
            InfoCard {
                Text(workout.title, style = MaterialTheme.typography.titleSmall)
                Hint("${formatTime(workout.startedAtMs)}, ${activityLabel(workout)}")
                benchmarks.firstOrNull { it.workoutId == workout.id }?.let {
                    Fact("Estimated VO2max", "%.1f".format(it.vo2Max))
                }
                WorkoutFacts(workout)
                workout.review?.let { Hint(it) }
                if (workout.programWeek != null) {
                    Hint(
                        "Program week ${workout.programWeek}" +
                            if (workout.countedForProgram) ", counted" else ", did not count",
                    )
                }
                OutlinedButton(onClick = { pendingDelete = workout }) { Text("Delete") }
            }
        }
    }

    pendingDelete?.let { workout ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this workout?") },
            text = { Text("Its heart rate data will be removed from this phone. Your program place stays.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.trainingDao.deleteWorkout(workout.id)
                        workout.hrSessionId?.let { container.hrDao.deleteSession(it) }
                    }
                    pendingDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}
