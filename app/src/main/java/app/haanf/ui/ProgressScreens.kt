package app.haanf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.BenchmarkEntity
import app.haanf.data.WorkoutEntity
import app.haanf.health.VitalTrend
import app.haanf.health.Vitals
import app.haanf.training.FitnessNorms
import app.haanf.training.Sex
import app.haanf.training.WeekTotals
import app.haanf.training.Zone
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.HaanfIcons
import app.haanf.ui.theme.body
import app.haanf.ui.theme.condensed
import app.haanf.workout.WorkoutOrigin
import app.haanf.workout.WorkoutText
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

enum class VitalsStatus { LOADING, UNAVAILABLE, NOT_SHARED, READ, FAILED }

class VitalsHolder(
    val vitals: Vitals?,
    val status: VitalsStatus,
    val missingPermissions: Set<String>,
    val askToShare: () -> Unit,
)

/** Reads resting heart rate, HRV and sleep from Health Connect, and asks to share them. */
@Composable
fun rememberVitals(container: AppContainer): VitalsHolder {
    val health = container.health
    var refreshes by remember { mutableIntStateOf(0) }
    var vitals by remember { mutableStateOf<Vitals?>(null) }
    var status by remember { mutableStateOf(VitalsStatus.LOADING) }
    var missing by remember { mutableStateOf(emptySet<String>()) }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refreshes++
    }
    LaunchedEffect(refreshes) {
        if (health.sdkStatus() != HealthConnectClient.SDK_AVAILABLE) {
            status = VitalsStatus.UNAVAILABLE
            return@LaunchedEffect
        }
        status = try {
            missing = health.permissions - health.grantedPermissions()
            if (missing.containsAll(health.permissions)) {
                VitalsStatus.NOT_SHARED
            } else {
                vitals = health.vitals(Instant.now())
                VitalsStatus.READ
            }
        } catch (e: Exception) {
            VitalsStatus.FAILED
        }
    }
    return VitalsHolder(vitals, status, missing) { launcher.launch(health.permissions) }
}

fun WorkoutEntity.originTag(): String = when (origin) {
    WorkoutOrigin.PROGRAM.name -> "Program"
    WorkoutOrigin.AI.name -> "Coach"
    WorkoutOrigin.BENCHMARK.name -> "Benchmark"
    else -> "Free"
}

fun WorkoutEntity.durationSeconds(): Int = (((endedAtMs ?: startedAtMs) - startedAtMs) / 1000).toInt()

@Composable
fun ProgressTab(container: AppContainer, nav: Navigator, vitals: VitalsHolder = rememberVitals(container)) {
    val workouts by remember { container.trainingDao.observeWorkouts() }.collectAsStateWithLifecycle(emptyList())
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(emptyList())
    val today = container.programs.today()
    val finished = workouts.filter { it.endedAtMs != null }

    HScreen {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("Progress", modifier = Modifier.weight(1f))
            Box(Modifier.offset(x = 12.dp)) {
                HIconButton(HaanfIcons.Settings, "Settings and Lab", onClick = { nav.open(Dest.Settings) })
            }
        }
        FitnessHeadline(benchmarks, today, nav)
        VitalsCard(vitals, today)
        WeekCard(finished, today)
        Column {
            SectionHeading("Recent workouts")
            if (finished.isEmpty()) {
                Hint("Your finished workouts appear here.")
            }
            finished.take(3).forEachIndexed { index, workout ->
                WorkoutRow(workout, today, divider = index < minOf(finished.size, 3) - 1) {
                    nav.open(Dest.WorkoutDetail(workout.id))
                }
            }
            if (finished.isNotEmpty()) LinkButton("See all workouts", onClick = { nav.open(Dest.History) })
        }
    }
}

/** Estimated VO2max is the headline. The talking threshold isn't measured yet, so it isn't shown. */
@Composable
private fun FitnessHeadline(benchmarks: List<BenchmarkEntity>, today: LocalDate, nav: Navigator) {
    HCard(color = HaanfColors.Primary, padding = PaddingValues(20.dp), spacing = 4.dp) {
        Text("Estimated VO2max", style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.White)
        val latest = benchmarks.firstOrNull()
        if (latest == null) {
            Text("Not measured yet", style = condensed(48.sp), color = HaanfColors.White)
            Text(
                "Walk one mile as fast as you can to see how fit you are today.",
                style = body(16.sp),
                color = HaanfColors.White,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            PrimaryButton(
                "Start benchmark",
                onClick = { nav.open(Dest.GetReady(benchmarkWorkout())) },
                height = 52.dp,
                container = HaanfColors.White,
                content = HaanfColors.Text,
            )
        } else {
            BigNumber("%.1f".format(latest.vo2Max), "mL/kg/min", size = 80, color = HaanfColors.White)
            val first = benchmarks.last()
            val sex = Sex.valueOf(latest.sex)
            val level = FitnessNorms.level(latest.vo2Max, latest.ageYears, sex).label
            val change = latest.vo2Max - first.vo2Max
            val text = buildString {
                append("$level for your age and sex. ")
                if (benchmarks.size > 1) {
                    val rounded = "%.1f".format(kotlin.math.abs(change))
                    append(
                        when {
                            rounded == "0.0" -> "The same as your first benchmark. "
                            change > 0 -> "Up $rounded since your first benchmark. "
                            else -> "Down $rounded since your first benchmark. "
                        },
                    )
                }
                append("Measured ${Format.relativeDay(latest.takenAtMs, today)}.")
            }
            Text(text, style = body(16.sp), color = HaanfColors.White)
            LinkButton("See benchmarks", onClick = { nav.open(Dest.Benchmarks) }, color = HaanfColors.White)
        }
    }
}

@Composable
private fun VitalsCard(holder: VitalsHolder, today: LocalDate) {
    HCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), spacing = 0.dp) {
        Text(
            "Vitals, last 4 weeks",
            style = body(17.sp, FontWeight.Bold),
            color = HaanfColors.Text,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        when (holder.status) {
            VitalsStatus.LOADING -> Hint("Reading Health Connect")
            VitalsStatus.UNAVAILABLE -> Box(Modifier.padding(bottom = 8.dp)) {
                Hint("Health Connect isn't available on this phone. Settings, under Lab, has a report.")
            }
            VitalsStatus.NOT_SHARED -> Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Share resting heart rate, HRV and sleep from Health Connect to see them here.")
                SecondaryButton("Choose what to share", onClick = holder.askToShare)
            }
            VitalsStatus.FAILED -> Box(Modifier.padding(bottom = 8.dp)) { Hint("Health Connect couldn't be read.") }
            VitalsStatus.READ -> {
                val vitals = holder.vitals
                VitalRow("Resting heart rate", vitals?.restingHeartRate, today, divider = true) {
                    "${it.latest.value.roundToInt()} bpm" + (Format.change(it.change)?.let { change -> ", $change" } ?: "")
                }
                VitalRow("HRV", vitals?.hrv, today, divider = true) {
                    "${it.latest.value.roundToInt()} ms" + (Format.change(it.change)?.let { change -> ", $change" } ?: "")
                }
                VitalRow("Sleep", vitals?.sleepHours, today, divider = false) {
                    val average = it.recentAverage
                    if (average != null) "${Format.hoursAndMinutes(average)} average" else Format.hoursAndMinutes(it.latest.value)
                }
                if (holder.missingPermissions.isNotEmpty()) {
                    LinkButton("Share more from Health Connect", onClick = holder.askToShare)
                }
            }
        }
    }
}

@Composable
private fun VitalRow(title: String, trend: VitalTrend?, today: LocalDate, divider: Boolean, value: (VitalTrend) -> String) {
    ListRow(
        start = {
            Column(Modifier.padding(vertical = 10.dp)) {
                val label = when {
                    trend == null -> title
                    trend.daysSinceLatest == 1L && trend.stale -> "$title, 1 day old"
                    trend.stale -> "$title, ${trend.daysSinceLatest} days old"
                    else -> title
                }
                Text(label, style = body(15.sp), color = HaanfColors.TextSecondary)
                Text(
                    trend?.let(value) ?: "No data",
                    style = body(18.sp, FontWeight.Bold),
                    color = HaanfColors.Text,
                )
            }
        },
        end = {
            if (trend != null && trend.days.size >= 2) {
                Sparkline(
                    trend.days.map { it.value },
                    description = "$title over the last 4 weeks, latest from ${Format.relativeDay(trend.latest.date, today)}",
                )
            }
        },
        divider = divider,
        minHeight = 72.dp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekCard(workouts: List<WorkoutEntity>, today: LocalDate) {
    val minutes = WeekTotals.zoneMinutes(workouts, today, ZoneId.systemDefault())
    val total = minutes.values.sum()
    HCard(spacing = 8.dp) {
        Text(
            if (total == 0) "This week" else "This week, $total minutes",
            style = body(17.sp, FontWeight.Bold),
            color = HaanfColors.Text,
        )
        if (total == 0) {
            Hint("No workouts yet this week.")
        } else {
            val shown = Zone.entries.filter { (minutes[it] ?: 0) > 0 }
            ZoneBar(
                shown.map { it to (minutes[it] ?: 0).toFloat() },
                height = 14.dp,
                description = shown.joinToString(", ") { "${it.label} ${minutes[it]} minutes" },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { zone -> ZoneKey(zone, "${zone.label} ${minutes[zone]}") }
            }
        }
    }
}

@Composable
private fun WorkoutRow(workout: WorkoutEntity, today: LocalDate, divider: Boolean, onOpen: () -> Unit) {
    ListRow(
        modifier = Modifier.clickable(onClick = onOpen),
        start = {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(workout.title, style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.Text)
                Text(
                    "${workout.originTag()}, ${Format.relativeDay(workout.startedAtMs, today)}",
                    style = body(14.sp),
                    color = HaanfColors.TextSecondary,
                )
            }
        },
        end = { Text(Format.minutes(workout.durationSeconds()), style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.Text) },
        divider = divider,
        minHeight = 56.dp,
    )
}

@Composable
fun HistoryScreen(container: AppContainer, nav: Navigator) {
    val workouts by remember { container.trainingDao.observeWorkouts() }.collectAsStateWithLifecycle(emptyList())
    val finished = workouts.filter { it.endedAtMs != null }
    val today = container.programs.today()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { Box(Modifier.offset(x = (-12).dp)) { BackButton { nav.back() } } }
        item { ScreenTitle("All workouts", modifier = Modifier.padding(bottom = 12.dp)) }
        if (finished.isEmpty()) item { Hint("Your finished workouts appear here.") }
        items(finished, key = { it.id }) { workout ->
            WorkoutRow(workout, today, divider = workout != finished.last()) { nav.open(Dest.WorkoutDetail(workout.id)) }
        }
    }
}

@Composable
fun WorkoutDetailScreen(container: AppContainer, nav: Navigator, id: Long) {
    val scope = rememberCoroutineScope()
    val workout by remember(id) { container.trainingDao.observeWorkout(id) }.collectAsStateWithLifecycle(null)
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(emptyList())
    var deleting by remember { mutableStateOf(false) }
    val current = workout ?: return
    val benchmark = benchmarks.firstOrNull { it.workoutId == id }

    SubScreen(onBack = { nav.back() }) {
        Eyebrow("${current.originTag()}, ${formatTime(current.startedAtMs)}")
        WorkoutSummaryBody(
            container = container,
            workout = current,
            result = BenchmarkResult.from(current, benchmark),
            live = false,
        )
        BottomActions {
            LinkButton("Delete this workout", onClick = { deleting = true })
        }
    }
    if (deleting) {
        HDialog(
            title = "Delete this workout?",
            text = "Its heart rate data will be removed from this phone. Your program place stays.",
            confirm = "Delete",
            onConfirm = {
                deleting = false
                scope.launch {
                    container.trainingDao.deleteWorkout(current.id)
                    current.hrSessionId?.let { container.hrDao.deleteSession(it) }
                }
                nav.back()
            },
            dismiss = "Keep",
            onDismiss = { deleting = false },
        )
    }
}

@Composable
fun BenchmarksScreen(container: AppContainer, nav: Navigator) {
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(emptyList())
    val today = container.programs.today()
    SubScreen(onBack = { nav.back() }) {
        Column {
            Eyebrow("1-mile walk test")
            ScreenTitle("Benchmarks", modifier = Modifier.padding(top = 4.dp))
        }
        Lead("The same fixed test each time, so the numbers compare. Estimates, not lab results.")
        if (benchmarks.isEmpty()) {
            Hint("No benchmarks yet.")
        } else {
            HCard(spacing = 0.dp) {
                benchmarks.forEachIndexed { index, benchmark ->
                    val level = FitnessNorms.level(benchmark.vo2Max, benchmark.ageYears, Sex.valueOf(benchmark.sex)).label
                    ListRow(
                        modifier = Modifier.clickable { nav.open(Dest.WorkoutDetail(benchmark.workoutId)) },
                        start = {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    Format.relativeDay(benchmark.takenAtMs, today).replaceFirstChar(Char::uppercase),
                                    style = body(16.sp, FontWeight.SemiBold),
                                    color = HaanfColors.Text,
                                )
                                Text(
                                    "$level, mile in ${WorkoutText.clock(benchmark.timeSeconds)}",
                                    style = body(14.sp),
                                    color = HaanfColors.TextSecondary,
                                )
                            }
                        },
                        end = { Text("%.1f".format(benchmark.vo2Max), style = condensed(30.sp), color = HaanfColors.Text) },
                        divider = index < benchmarks.lastIndex,
                        minHeight = 56.dp,
                    )
                }
            }
        }
        BottomActions {
            PrimaryButton("Start benchmark", onClick = { nav.open(Dest.GetReady(benchmarkWorkout())) })
        }
    }
}
