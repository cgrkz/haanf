package app.haanf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.workout.WorkoutState

private val tabs = listOf("Today" to "🏃", "Coach" to "💬", "History" to "📅", "Vitals" to "📈", "Lab" to "🧪")
private val labTools = listOf("Heart rate", "Talk test", "Health data")

@Composable
fun HaanfRoot(container: AppContainer) {
    val workout by container.workouts.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingWorkout?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val showTabs = workout == WorkoutState.Idle && pending == null

    Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEachIndexed { index, (title, glyph) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(glyph) },
                            label = { Text(title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val current = workout
            val starting = pending
            when {
                current is WorkoutState.Running -> LiveWorkoutScreen(container, current)
                current is WorkoutState.Finished -> {
                    val profile by remember { container.trainingDao.observeProfile() }
                        .collectAsStateWithLifecycle(null)
                    FinishedWorkoutScreen(
                        container = container,
                        workout = current.workout,
                        outcome = current.benchmark,
                        inputs = profile?.benchmarkInputs(container.programs.today()),
                    ) {
                        container.workouts.dismissSummary()
                    }
                }
                starting != null -> StartWorkoutScreen(container, starting) { pending = null }
                tab == 0 -> TodayScreen(container) { pending = it }
                tab == 1 -> CoachScreen(container) { pending = it }
                tab == 2 -> HistoryScreen(container)
                tab == 3 -> VitalsScreen(container)
                else -> LabScreen(container)
            }
        }
    }
}

/** The Phase 0 tools, kept for diagnosing new phones and wearables. */
@Composable
private fun LabScreen(container: AppContainer) {
    var tool by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tool) {
            labTools.forEachIndexed { index, title ->
                Tab(selected = tool == index, onClick = { tool = index }, text = { Text(title) })
            }
        }
        when (tool) {
            0 -> HeartRateScreen(container)
            1 -> TalkTestScreen(container)
            else -> HealthDataScreen(container)
        }
    }
}
