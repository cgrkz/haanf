package app.haanf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.BenchmarkEntity
import app.haanf.data.EnrolmentEntity
import app.haanf.data.ProfileEntity
import app.haanf.program.ProgramDefinition
import app.haanf.program.ProgramNotice
import app.haanf.program.ProgramPosition
import app.haanf.program.ProgramRules
import app.haanf.program.ProgramSession
import app.haanf.program.Programs
import app.haanf.program.progress
import app.haanf.training.BenchmarkInputs
import app.haanf.training.FitnessNorms
import app.haanf.training.HeartRateZones
import app.haanf.training.MaxHeartRate
import app.haanf.training.SafetyRules
import app.haanf.training.Sex
import app.haanf.training.WorkoutPlan
import app.haanf.training.Zone
import app.haanf.workout.ProgramSlot
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PendingWorkout(
    val title: String,
    val plan: WorkoutPlan?,
    val slot: ProgramSlot?,
    val benchmark: Boolean = false,
)

private val LOADING = ProfileEntity(birthYear = 0)
private const val FREE_WORKOUT_TITLE = "Free workout"
private const val BENCHMARK_TITLE = "1-mile walk benchmark"

/** What the benchmark needs, or null until setup has collected it. */
fun ProfileEntity.benchmarkInputs(today: LocalDate): BenchmarkInputs? {
    val knownSex = sex?.let(Sex::valueOf) ?: return null
    val weight = weightKg ?: return null
    return BenchmarkInputs(today.year - birthYear, knownSex, weight)
}

fun ProfileEntity.zones(today: LocalDate): HeartRateZones =
    HeartRateZones(MaxHeartRate.resolve(today.year - birthYear, measuredMaxBpm))

fun HeartRateZones.describe(zone: Zone): String {
    val range = rangeFor(zone)
    return "${zone.label}, ${range.first} to ${range.last} bpm"
}

@Composable
fun TodayScreen(container: AppContainer, onStart: (PendingWorkout) -> Unit) {
    val scope = rememberCoroutineScope()
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val enrolment by remember { container.programs.enrolment }.collectAsStateWithLifecycle(null)
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }
        .collectAsStateWithLifecycle(emptyList())
    var editingProfile by rememberSaveable { mutableStateOf(false) }

    val current = profile
    if (current === LOADING) return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineMedium)

        val today = container.programs.today()
        if (current == null || current.benchmarkInputs(today) == null || editingProfile) {
            ProfileCard(current) { birthYear, sex, weightKg, measuredMax ->
                scope.launch {
                    container.trainingDao.saveProfile(
                        (current ?: ProfileEntity(birthYear = birthYear)).copy(
                            birthYear = birthYear,
                            sex = sex.name,
                            weightKg = weightKg,
                            measuredMaxBpm = measuredMax,
                        ),
                    )
                }
                editingProfile = false
            }
            return@Column
        }

        if (current.healthCheckClearedAtMs == null) {
            HealthCheckCard {
                scope.launch {
                    container.trainingDao.saveProfile(
                        current.copy(healthCheckClearedAtMs = container.clock.nowEpochMs()),
                    )
                }
            }
            return@Column
        }

        val startBenchmark = { onStart(PendingWorkout(BENCHMARK_TITLE, null, null, benchmark = true)) }
        val active = enrolment
        val program = active?.let { Programs.byId(it.programId) }
        if (benchmarks.isEmpty()) {
            FirstBenchmarkCard(startBenchmark)
        } else {
            FitnessCard(benchmarks, startBenchmark)
        }
        if (active != null && program != null) {
            EnrolledCard(
                program = program,
                enrolment = active,
                today = today,
                onStart = onStart,
                onLeave = { scope.launch { container.programs.leave() } },
            )
        } else if (benchmarks.isNotEmpty()) {
            EnrolCard(Programs.FOUNDATION_8) { program ->
                scope.launch { container.programs.enrol(program) }
            }
        }

        InfoCard {
            Text("Free workout", style = MaterialTheme.typography.titleMedium)
            Hint("Any time, with no plan. It never changes your program.")
            OutlinedButton(onClick = { onStart(PendingWorkout(FREE_WORKOUT_TITLE, null, null)) }) {
                Text("Start free workout")
            }
        }

        InfoCard {
            val zones = current.zones(today)
            val source = if (current.measuredMaxBpm != null) "measured" else "estimated from your age"
            Text("Your heart rate zones", style = MaterialTheme.typography.titleMedium)
            Hint("Maximum ${zones.maxBpm} bpm, $source.")
            listOf(Zone.EASY, Zone.HARD, Zone.VERY_HARD).forEach { Hint(zones.describe(it)) }
            TextButton(onClick = { editingProfile = true }) { Text("Edit") }
        }
    }
}

@Composable
private fun ProfileCard(existing: ProfileEntity?, onSave: (Int, Sex, Double, Int?) -> Unit) {
    var birthYear by rememberSaveable { mutableStateOf(existing?.birthYear?.toString().orEmpty()) }
    var measuredMax by rememberSaveable { mutableStateOf(existing?.measuredMaxBpm?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(existing?.weightKg?.toString().orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(existing?.sex?.let(Sex::valueOf)) }
    val year = birthYear.toIntOrNull()
    val max = measuredMax.toIntOrNull()
    val kilograms = weight.toDoubleOrNull()
    val chosenSex = sex
    val valid = year != null && year in 1920..LocalDate.now().year - 13 &&
        kilograms != null && kilograms in 30.0..250.0 &&
        chosenSex != null &&
        (measuredMax.isBlank() || (max != null && max in 100..230))

    InfoCard {
        Text("About you", style = MaterialTheme.typography.titleMedium)
        Hint("Used for your heart rate zones and fitness benchmark. It stays on this phone.")
        OutlinedTextField(
            value = birthYear,
            onValueChange = { birthYear = it.filter(Char::isDigit).take(4) },
            label = { Text("Year of birth") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = weight,
            onValueChange = { input -> weight = input.filter { it.isDigit() || it == '.' }.take(5) },
            label = { Text("Weight in kg") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceRow(
            title = "Sex used by the fitness formula",
            options = Sex.entries.toList<Sex?>(),
            selected = sex,
            enabled = true,
            label = { it?.label.orEmpty() },
            onSelect = { sex = it },
        )
        OutlinedTextField(
            value = measuredMax,
            onValueChange = { measuredMax = it.filter(Char::isDigit).take(3) },
            label = { Text("Measured maximum heart rate (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(requireNotNull(year), requireNotNull(chosenSex), requireNotNull(kilograms), max) },
            enabled = valid,
        ) {
            Text("Save")
        }
    }
}

/** Only the outcome is saved, never which statement applied. */
@Composable
private fun HealthCheckCard(onCleared: () -> Unit) {
    var flagged by rememberSaveable { mutableStateOf(false) }
    InfoCard {
        Text("A quick health check", style = MaterialTheme.typography.titleMedium)
        Text("Before your fitness benchmark, does either of these apply to you?")
        SafetyRules.HEALTH_CHECK.forEach { Text("• $it") }
        if (flagged) {
            ErrorText(SafetyRules.HEALTH_CHECK_ADVICE)
            Button(onClick = onCleared, modifier = Modifier.fillMaxWidth()) {
                Text("My doctor has cleared me")
            }
            TextButton(onClick = { flagged = false }) { Text("Back") }
        } else {
            Button(onClick = onCleared, modifier = Modifier.fillMaxWidth()) {
                Text("Neither applies to me")
            }
            OutlinedButton(onClick = { flagged = true }, modifier = Modifier.fillMaxWidth()) {
                Text("One or both apply to me")
            }
        }
    }
}

@Composable
private fun FirstBenchmarkCard(onStart: () -> Unit) {
    InfoCard {
        Text("Your first step: a fitness benchmark", style = MaterialTheme.typography.titleMedium)
        Text(
            "Walk one mile as fast as you can. It takes about 15 minutes, and it tells Haanf " +
                "how fit you are today, so you can see yourself improve.",
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start benchmark")
        }
        Hint("Your program unlocks after this.")
    }
}

@Composable
private fun FitnessCard(benchmarks: List<BenchmarkEntity>, onRepeat: () -> Unit) {
    val latest = benchmarks.first()
    val first = benchmarks.last()
    val sex = Sex.valueOf(latest.sex)
    InfoCard {
        Text("Your fitness", style = MaterialTheme.typography.titleMedium)
        Text("%.1f".format(latest.vo2Max), style = MaterialTheme.typography.headlineMedium)
        Hint("Estimated VO2max in mL/kg/min, from ${formatTime(latest.takenAtMs)}")
        Text(FitnessNorms.level(latest.vo2Max, latest.ageYears, sex).label)
        Hint("Compared with others of your age and sex. Typical: %.1f.".format(FitnessNorms.median(latest.ageYears, sex)))
        if (benchmarks.size > 1) {
            Text("%+.1f since your first benchmark".format(latest.vo2Max - first.vo2Max))
        }
        TextButton(onClick = onRepeat) { Text("Repeat benchmark") }
    }
}

@Composable
private fun EnrolCard(program: ProgramDefinition, onEnrol: (ProgramDefinition) -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    InfoCard {
        Text(program.name, style = MaterialTheme.typography.titleMedium)
        Text(program.summary)
        Hint("Draft plan, under review.")
        Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Start this program")
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Before you start") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(SafetyRules.ENROLMENT_WARNING)
                    Text(
                        "Keep training to keep your place. After ${ProgramRules.REPEAT_WEEK_AFTER_DAYS} days " +
                            "without a program workout, you repeat the last week you trained. After " +
                            "${ProgramRules.RESTART_AFTER_DAYS} days, you restart at Week 1. Free workouts " +
                            "do not stop this clock.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEnrol(program)
                    confirming = false
                }) {
                    Text("I understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun EnrolledCard(
    program: ProgramDefinition,
    enrolment: EnrolmentEntity,
    today: LocalDate,
    onStart: (PendingWorkout) -> Unit,
    onLeave: () -> Unit,
) {
    val progress = enrolment.progress()
    var leaving by remember { mutableStateOf(false) }
    InfoCard {
        Text(program.name, style = MaterialTheme.typography.titleMedium)
        if (progress.completed) {
            Text("Finished. All ${program.weeks.size} weeks are done.")
        } else {
            val position = ProgramRules.position(progress, today)
            val session = program.session(position.week, position.session)
            Text(
                "Week ${position.week} of ${program.weeks.size}, " +
                    "session ${position.session + 1} of ${program.weeks[position.week - 1].sessions.size}",
            )
            NoticeText(position)
            Text(session.plan.title, style = MaterialTheme.typography.titleLarge)
            if (session.hard) Hint("A hard session. Rest well the day before and after.")
            Button(
                onClick = {
                    onStart(
                        PendingWorkout(
                            title = session.plan.title,
                            plan = session.plan,
                            slot = ProgramSlot(program.id, position.week, position.session),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start")
            }
            ClockHint(position)
            WeekOutline(program.weeks[position.week - 1].sessions, position.session)
        }
        TextButton(onClick = { leaving = true }) {
            Text(if (progress.completed) "Choose a program" else "Leave program")
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Leave ${program.name}?") },
            text = { Text("Your place is lost. Your workout history stays.") },
            confirmButton = {
                TextButton(onClick = {
                    onLeave()
                    leaving = false
                }) {
                    Text("Leave")
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text("Stay") }
            },
        )
    }
}

@Composable
private fun NoticeText(position: ProgramPosition) {
    when (val notice = position.notice) {
        ProgramNotice.None -> Unit
        is ProgramNotice.RepeatingWeek ->
            ErrorText("${notice.daysAway} days away, so Week ${notice.week} repeats from its first session.")
        is ProgramNotice.RestartedProgram ->
            ErrorText("${notice.daysAway} days away, so the program restarts at Week 1.")
    }
}

@Composable
private fun ClockHint(position: ProgramPosition) {
    val toRepeat = position.daysUntilRepeat
    val toRestart = position.daysUntilRestart
    when {
        toRepeat != null -> Hint("Train within ${days(toRepeat)} to keep your place.")
        toRestart != null -> Hint("Train within ${days(toRestart)} to avoid restarting at Week 1.")
    }
}

private fun days(count: Long): String = if (count == 1L) "1 day" else "$count days"

@Composable
private fun WeekOutline(sessions: List<ProgramSession>, current: Int) {
    Column {
        Text("This week", style = MaterialTheme.typography.labelLarge)
        sessions.forEachIndexed { index, session ->
            val mark = when {
                index < current -> "✓"
                index == current -> "→"
                else -> "·"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark)
                Text(session.plan.title)
            }
        }
    }
}
