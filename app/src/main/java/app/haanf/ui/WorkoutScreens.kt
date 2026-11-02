package app.haanf.ui

import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.ProfileEntity
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.session.LiveText
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BenchmarkInputs
import app.haanf.training.FitnessNorms
import app.haanf.training.SafetyRules
import app.haanf.training.WalkOutcome
import app.haanf.training.Zone
import app.haanf.workout.BenchmarkRequest
import app.haanf.workout.WorkoutOrigin
import app.haanf.workout.WorkoutRequest
import app.haanf.workout.WorkoutState
import app.haanf.workout.WorkoutText
import kotlinx.coroutines.launch

private fun ProfileEntity.device(): HeartRateDevice? {
    val id = deviceId ?: return null
    val kind = deviceSourceKind ?: return null
    return HeartRateDevice(id, deviceName, kind)
}

@Composable
fun StartWorkoutScreen(container: AppContainer, pending: PendingWorkout, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(null)
    val live by container.live.live.collectAsStateWithLifecycle()
    var activity by rememberSaveable { mutableStateOf(TrainingActivity.OUTDOOR_RUN) }
    var picking by rememberSaveable { mutableStateOf(false) }

    val current = profile ?: return
    val device = current.device()
    val bluetoothMissing = AppPermissions.missing(context, AppPermissions.bluetooth).isNotEmpty()
    var locationRequests by remember { mutableIntStateOf(0) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationRequests++
    }
    val locationMissing = remember(locationRequests) {
        AppPermissions.missing(context, AppPermissions.location).isNotEmpty()
    }

    if (picking || device == null || bluetoothMissing) {
        DevicePicker(
            container = container,
            showWatchWorkoutSwitch = false,
            onClose = {
                picking = false
                if (device == null) onCancel()
            },
            onPick = { picked, _ ->
                scope.launch {
                    container.trainingDao.saveProfile(
                        current.copy(
                            deviceId = picked.id,
                            deviceName = picked.name,
                            deviceSourceKind = picked.sourceKind,
                        ),
                    )
                }
                picking = false
            },
        )
        return
    }

    val today = container.programs.today()
    val zones = current.zones(today)
    val walksOutdoors = pending.benchmark && activity != TrainingActivity.TREADMILL
    val gpsOff = walksOutdoors && !context.getSystemService(LocationManager::class.java)
        .isProviderEnabled(LocationManager.GPS_PROVIDER)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(pending.title, style = MaterialTheme.typography.headlineMedium)
        pending.plan?.let { plan ->
            InfoCard {
                plan.steps.forEach { step ->
                    Fact(step.label, WorkoutText.minutes(step.seconds))
                }
                Hint("Total ${WorkoutText.minutes(plan.totalSeconds)}. Steps are spoken aloud as they begin.")
            }
        }
        if (pending.benchmark) {
            BenchmarkInstructions(walksOutdoors)
            ChoiceRow(
                title = "Where",
                options = listOf(TrainingActivity.OUTDOOR_RUN, TrainingActivity.TREADMILL),
                selected = activity,
                enabled = true,
                label = { if (it == TrainingActivity.TREADMILL) "Treadmill" else "Outdoors" },
            ) { activity = it }
        } else {
            ChoiceRow("Activity", TrainingActivity.entries, activity, true, { it.label }) { activity = it }
        }
        InfoCard {
            Text(device.name ?: device.id, style = MaterialTheme.typography.titleSmall)
            Hint("Turn on heart rate sharing on the watch before you start.")
            TextButton(onClick = { picking = true }) { Text("Change device") }
        }
        if (live.sessionId != null) {
            ErrorText("A Lab session is running. Stop it in the Lab tab first.")
        }
        if (gpsOff) ErrorText("Turn on location on this phone, or use a treadmill.")
        if (walksOutdoors && locationMissing) {
            Hint("Haanf uses GPS only to measure the mile. It keeps the distance, never your route.")
            Button(
                onClick = { locationLauncher.launch(AppPermissions.location.toTypedArray()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Allow location")
            }
        }
        Button(
            enabled = live.sessionId == null && !gpsOff && !(walksOutdoors && locationMissing),
            onClick = {
                container.workouts.start(
                    WorkoutRequest(
                        title = pending.title,
                        activity = activity,
                        device = device,
                        zones = zones,
                        plan = pending.plan,
                        slot = pending.slot,
                        benchmark = if (pending.benchmark) {
                            current.benchmarkInputs(today)?.let { BenchmarkRequest(it, usesGps = walksOutdoors) }
                        } else {
                            null
                        },
                    ),
                )
                onCancel()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Go")
        }
        TextButton(onClick = onCancel) { Text("Back") }
    }
}

@Composable
private fun BenchmarkInstructions(outdoors: Boolean) {
    InfoCard {
        Text("How it works", style = MaterialTheme.typography.titleMedium)
        Text("1. Warm up with 5 minutes of easy walking, then rest for 2 minutes.")
        Text("2. Walk one mile as fast as you can, without running. Keep one foot on the ground.")
        Text(
            if (outdoors) {
                "3. Choose a flat route. GPS measures the mile, and Haanf stops by itself."
            } else {
                "3. Keep the treadmill flat. Tap Finish when it shows 1 mile, or 1.61 km."
            },
        )
        Hint(SafetyRules.BENCHMARK_STOP_RULE)
    }
}

@Composable
fun LiveWorkoutScreen(container: AppContainer, running: WorkoutState.Running) {
    val live by container.live.live.collectAsStateWithLifecycle()
    val now by rememberElapsedNow(container.clock)
    var confirming by remember { mutableStateOf(false) }
    var talkTest by rememberSaveable { mutableStateOf(false) }
    val zones = running.request.zones

    if (talkTest) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { talkTest = false }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Back to workout")
            }
            TalkTestScreen(container)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(running.request.title, style = MaterialTheme.typography.titleMedium)
        Text(
            LiveText.bpm(live),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.testTag("workout-bpm"),
        )
        Text(
            live.bpm?.let { zones.zoneFor(it).label } ?: " ",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("workout-zone"),
        )
        Text(
            WorkoutText.clock(running.elapsedSeconds),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("workout-clock"),
        )
        running.stepIndex?.let { index ->
            val plan = requireNotNull(running.request.plan)
            val step = plan.steps[index]
            InfoCard {
                Text(step.label, style = MaterialTheme.typography.titleLarge)
                Text("${WorkoutText.clock(running.stepRemainingSeconds ?: 0)} left")
                Hint("Aim for ${zones.describe(step.target)}.")
                plan.steps.getOrNull(index + 1)?.let { next ->
                    Hint("Next: ${next.label}, ${WorkoutText.minutes(next.seconds)}.")
                }
            }
        }
        if (running.planComplete) {
            InfoCard { Text("Session complete. Finish when you are ready.") }
        }
        running.request.benchmark?.let { benchmark ->
            InfoCard {
                Text("Walk as fast as you can. No running.", style = MaterialTheme.typography.titleMedium)
                if (benchmark.usesGps) {
                    Text("%.2f of 1.61 km".format(running.distanceMetres / 1000))
                    Hint("Haanf stops by itself at one mile.")
                } else {
                    Hint("Tap Finish when the treadmill shows 1 mile, or 1.61 km.")
                }
                Hint(SafetyRules.BENCHMARK_STOP_RULE)
            }
        }
        InfoCard {
            Fact("Last reading", LiveText.age(live, now))
            Fact("Status", live.connection.label)
        }
        if (live.inDropout) ErrorText("No readings are arriving. Haanf keeps trying to reconnect.")
        if (running.request.benchmark == null) {
            OutlinedButton(onClick = { talkTest = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Talk test")
            }
        }
        Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Finish")
        }
    }

    if (confirming && running.request.benchmark != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Did you finish the mile?") },
            text = { Text("The result only counts for a full mile. A shorter walk stays in your history.") },
            confirmButton = {
                TextButton(onClick = {
                    container.workouts.finish()
                    confirming = false
                }) {
                    Text("Yes, score it")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    container.workouts.finish(reason = "abandoned", mileCompleted = false)
                    confirming = false
                }) {
                    Text("No, stop without a score")
                }
            },
        )
    } else if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Finish this workout?") },
            text = { Text("It will be saved to your history.") },
            confirmButton = {
                TextButton(onClick = {
                    container.workouts.finish()
                    confirming = false
                }) {
                    Text("Finish")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep going") }
            },
        )
    }
}

@Composable
fun WorkoutFacts(workout: WorkoutEntity) {
    val duration = ((workout.endedAtMs ?: workout.startedAtMs) - workout.startedAtMs) / 1000
    Fact("Time", WorkoutText.clock(duration.toInt()))
    Fact("Average heart rate", workout.avgBpm?.let { "$it bpm" } ?: "No readings")
    workout.maxBpm?.let { Fact("Highest heart rate", "$it bpm") }
    val zones = listOf(
        Zone.RECOVERY to workout.recoverySeconds,
        Zone.EASY to workout.easySeconds,
        Zone.HARD to workout.hardSeconds,
        Zone.VERY_HARD to workout.veryHardSeconds,
    )
    zones.filter { it.second > 0 }.forEach { (zone, seconds) ->
        Fact(zone.label, WorkoutText.clock(seconds))
    }
    if (workout.noReadingSeconds >= 30) Fact("No reading", WorkoutText.clock(workout.noReadingSeconds))
}

@Composable
private fun BenchmarkResultCard(outcome: WalkOutcome?, inputs: BenchmarkInputs?) {
    InfoCard {
        when {
            outcome is WalkOutcome.Scored && inputs != null -> {
                Text("Estimated VO2max", style = MaterialTheme.typography.titleMedium)
                Text(
                    "%.1f mL/kg/min".format(outcome.vo2Max),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.testTag("vo2max"),
                )
                Text(FitnessNorms.level(outcome.vo2Max, inputs.ageYears, inputs.sex).label)
                Hint(
                    "Typical for your age and sex: %.1f. This is an estimate, not a lab result."
                        .format(FitnessNorms.median(inputs.ageYears, inputs.sex)),
                )
                Fact("Mile time", WorkoutText.clock(outcome.timeSeconds))
                Fact("Heart rate near the end", "${outcome.heartRateBpm} bpm")
            }
            outcome is WalkOutcome.NotScored -> {
                Text("No score this time", style = MaterialTheme.typography.titleMedium)
                Text(outcome.reason)
                Hint("Try again when you are ready. Your history keeps this walk.")
            }
            else -> {
                Text("No score this time", style = MaterialTheme.typography.titleMedium)
                Hint("Only a full mile is scored. Try again when you are ready.")
            }
        }
    }
}

@Composable
fun FinishedWorkoutScreen(
    workout: WorkoutEntity,
    outcome: WalkOutcome?,
    inputs: BenchmarkInputs?,
    onDone: () -> Unit,
) {
    val isBenchmark = workout.origin == WorkoutOrigin.BENCHMARK.name
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (isBenchmark) "Benchmark finished" else "Workout saved",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(workout.title, style = MaterialTheme.typography.titleMedium)
        if (isBenchmark) BenchmarkResultCard(outcome, inputs)
        InfoCard { WorkoutFacts(workout) }
        if (workout.programId != null) {
            Text(
                if (workout.countedForProgram) {
                    "Counted towards your program."
                } else {
                    "Under 80% of the plan, so it does not count towards your program. " +
                        "It stays in your history, and this session is still next."
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onDone) { Text("Done") }
        }
    }
}
