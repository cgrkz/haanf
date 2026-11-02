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
import app.haanf.data.EnrolmentEntity
import app.haanf.data.ProfileEntity
import app.haanf.program.ProgramDefinition
import app.haanf.program.ProgramNotice
import app.haanf.program.ProgramPosition
import app.haanf.program.ProgramRules
import app.haanf.program.ProgramSession
import app.haanf.program.Programs
import app.haanf.program.progress
import app.haanf.training.HeartRateZones
import app.haanf.training.MaxHeartRate
import app.haanf.training.SafetyRules
import app.haanf.training.WorkoutPlan
import app.haanf.training.Zone
import app.haanf.workout.ProgramSlot
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PendingWorkout(
    val title: String,
    val plan: WorkoutPlan?,
    val slot: ProgramSlot?,
)

private val LOADING = ProfileEntity(birthYear = 0)
private const val FREE_WORKOUT_TITLE = "Free workout"

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
    var editingProfile by rememberSaveable { mutableStateOf(false) }

    val current = profile
    if (current === LOADING) return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineMedium)

        if (current == null || editingProfile) {
            ProfileCard(current) { birthYear, measuredMax ->
                scope.launch {
                    container.trainingDao.saveProfile(
                        (current ?: ProfileEntity(birthYear = birthYear))
                            .copy(birthYear = birthYear, measuredMaxBpm = measuredMax),
                    )
                }
                editingProfile = false
            }
            return@Column
        }

        val today = container.programs.today()
        val active = enrolment
        val program = active?.let { Programs.byId(it.programId) }
        if (active != null && program != null) {
            EnrolledCard(
                program = program,
                enrolment = active,
                today = today,
                onStart = onStart,
                onLeave = { scope.launch { container.programs.leave() } },
            )
        } else {
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
private fun ProfileCard(existing: ProfileEntity?, onSave: (Int, Int?) -> Unit) {
    var birthYear by rememberSaveable { mutableStateOf(existing?.birthYear?.toString().orEmpty()) }
    var measuredMax by rememberSaveable { mutableStateOf(existing?.measuredMaxBpm?.toString().orEmpty()) }
    val year = birthYear.toIntOrNull()
    val max = measuredMax.toIntOrNull()
    val valid = year != null && year in 1920..LocalDate.now().year - 13 &&
        (measuredMax.isBlank() || (max != null && max in 100..230))

    InfoCard {
        Text("About you", style = MaterialTheme.typography.titleMedium)
        Hint("Used for your heart rate zones. It stays on this phone.")
        OutlinedTextField(
            value = birthYear,
            onValueChange = { birthYear = it.filter(Char::isDigit).take(4) },
            label = { Text("Year of birth") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = measuredMax,
            onValueChange = { measuredMax = it.filter(Char::isDigit).take(3) },
            label = { Text("Measured maximum heart rate (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(requireNotNull(year), max) }, enabled = valid) {
            Text("Save")
        }
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
