package app.haanf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.BenchmarkEntity
import app.haanf.data.ProfileEntity
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.HaanfIcons
import app.haanf.ui.theme.body
import app.haanf.workout.WorkoutState

enum class OnboardingStep { WELCOME, ABOUT_YOU, CONNECT_WATCH, FIRST_BENCHMARK }

/** Which first-run step is still to do, or null when the app is ready. */
fun onboardingStep(
    profile: ProfileEntity?,
    benchmarks: List<BenchmarkEntity>,
    watchStepDone: Boolean,
    finished: Boolean,
    welcomed: Boolean,
    today: java.time.LocalDate,
): OnboardingStep? = when {
    profile == null && !welcomed -> OnboardingStep.WELCOME
    profile == null || profile.benchmarkInputs(today) == null || profile.healthCheckClearedAtMs == null ->
        OnboardingStep.ABOUT_YOU
    !watchStepDone && profile.deviceId == null -> OnboardingStep.CONNECT_WATCH
    !finished && benchmarks.isEmpty() -> OnboardingStep.FIRST_BENCHMARK
    else -> null
}

/** [sampleVitals] stands in for Health Connect, for screenshots only. */
@Composable
fun HaanfRoot(container: AppContainer, sampleVitals: VitalsHolder? = null) {
    val workout by container.workouts.state.collectAsStateWithLifecycle()
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(null)
    val watchDone by container.onboarding.watchStepDone.collectAsStateWithLifecycle()
    val finished by container.onboarding.finished.collectAsStateWithLifecycle()
    val nav = remember { Navigator() }
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    val coachTab = remember { CoachTabState(appScope) }
    val welcomed = remember { WelcomeState() }

    when (val current = workout) {
        is WorkoutState.Running -> LiveWorkoutScreen(container, current)
        is WorkoutState.Finished -> FinishedSummary(container, current) { container.workouts.dismissSummary() }
        WorkoutState.Idle -> {
            val loadedProfile = profile
            val loadedBenchmarks = benchmarks ?: return
            if (loadedProfile === LOADING) return
            val step = onboardingStep(
                profile = loadedProfile,
                benchmarks = loadedBenchmarks,
                watchStepDone = watchDone,
                finished = finished,
                welcomed = welcomed.done,
                today = container.programs.today(),
            )
            if (step != null && nav.top !is Dest.GetReady) {
                Onboarding(container, step, loadedProfile, onWelcomed = { welcomed.done = true }) { pending ->
                    nav.open(Dest.GetReady(pending))
                }
            } else {
                MainShell(container, nav, coachTab, sampleVitals)
            }
        }
    }
}

private class WelcomeState {
    var done by mutableStateOf(false)
}

@Composable
private fun MainShell(container: AppContainer, nav: Navigator, coachTab: CoachTabState, sampleVitals: VitalsHolder?) {
    BackHandler(enabled = nav.top != null) { nav.back() }
    val top = nav.top
    if (top != null) {
        Box(Modifier.fillMaxSize().background(HaanfColors.Background).safeDrawingPadding()) {
            Destination(container, nav, coachTab, top)
        }
        return
    }
    Scaffold(
        containerColor = HaanfColors.Background,
        bottomBar = { BottomTabs(Tab.entries[nav.tab]) { nav.tab = it.ordinal } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (Tab.entries[nav.tab]) {
                Tab.PROGRAM -> ProgramTab(container, nav)
                Tab.COACH -> CoachTab(container, nav, coachTab, sampleVitals)
                Tab.PROGRESS -> if (sampleVitals != null) ProgressTab(container, nav, sampleVitals) else ProgressTab(container, nav)
            }
        }
    }
}

@Composable
private fun Destination(container: AppContainer, nav: Navigator, coachTab: CoachTabState, dest: Dest) {
    when (dest) {
        Dest.ProgramDetail -> ProgramDetailScreen(container, nav)
        Dest.Chat -> ChatScreen(container, nav, coachTab)
        Dest.Settings -> SettingsScreen(container, nav)
        Dest.CoachSetup -> CoachSetupScreen(container, onDone = { nav.back() }, onBack = { nav.back() })
        Dest.Devices -> DevicesScreen(container, nav)
        Dest.History -> HistoryScreen(container, nav)
        Dest.Benchmarks -> BenchmarksScreen(container, nav)
        is Dest.Lab -> LabScreen(container, nav, dest.tool)
        is Dest.WorkoutDetail -> WorkoutDetailScreen(container, nav, dest.id)
        is Dest.GetReady -> GetReadyScreen(container, nav, dest.pending, coachTab)
    }
}

@Composable
private fun BottomTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(HaanfColors.Card).navigationBarsPadding()) {
        HorizontalDivider(color = HaanfColors.Divider, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            Tab.entries.forEach { tab ->
                val chosen = tab == selected
                val color = if (chosen) HaanfColors.Primary else HaanfColors.TextSecondary
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(selected = chosen, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val icon = when (tab) {
                        Tab.PROGRAM -> HaanfIcons.Program
                        Tab.COACH -> HaanfIcons.Coach
                        Tab.PROGRESS -> HaanfIcons.Progress
                    }
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    Text(
                        tab.label,
                        style = body(13.sp, if (chosen) FontWeight.Bold else FontWeight.SemiBold),
                        color = color,
                    )
                }
            }
        }
    }
}
