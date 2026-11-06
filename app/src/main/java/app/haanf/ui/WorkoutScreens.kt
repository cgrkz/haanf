package app.haanf.ui

import android.Manifest
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.ai.ReviewState
import app.haanf.data.BenchmarkEntity
import app.haanf.data.SplitEntity
import app.haanf.data.TalkAnswer
import app.haanf.data.WorkoutEntity
import app.haanf.program.Programs
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BenchmarkInputs
import app.haanf.training.BikeType
import app.haanf.training.MotionNow
import app.haanf.training.FitnessNorms
import app.haanf.training.HeartRateZones
import app.haanf.training.SafetyRules
import app.haanf.training.Sex
import app.haanf.training.RockportWalkTest
import app.haanf.training.WalkOutcome
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.HaanfIcons
import app.haanf.ui.theme.body
import app.haanf.ui.theme.color
import app.haanf.ui.theme.condensed
import app.haanf.ui.theme.onColor
import app.haanf.workout.BenchmarkRequest
import app.haanf.workout.HIGH_HEART_RATE_ALERT
import app.haanf.workout.TimelineEntry
import app.haanf.workout.TimelineKind
import app.haanf.workout.WorkoutOrigin
import app.haanf.workout.WorkoutRequest
import app.haanf.workout.WorkoutState
import app.haanf.workout.WorkoutText
import app.haanf.workout.inHardEffort
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val QUICK_REPLIES = listOf("Too easy", "OK", "Too hard")

/** Two dashes with room between them, so they don't run into one bar at display size. */
private const val NO_READING = "- -"
private val EFFORT_ENDS = "Very easy" to "All out"

private fun Zone.isHard() = this == Zone.HARD || this == Zone.VERY_HARD

// ---------------------------------------------------------------- Get ready

@Composable
fun GetReadyScreen(container: AppContainer, nav: Navigator, pending: PendingWorkout, coachTab: CoachTabState) {
    val context = LocalContext.current
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val live by container.live.live.collectAsStateWithLifecycle()
    val preview by container.preview.state.collectAsStateWithLifecycle()
    var activity by rememberSaveable { mutableStateOf(TrainingActivity.OUTDOOR_RUN) }
    var coached by rememberSaveable { mutableStateOf(true) }
    var talkChecks by rememberSaveable { mutableStateOf(false) }
    var cues by rememberSaveable { mutableStateOf(true) }
    val coachReady = !pending.benchmark && remember { container.llm.client() != null }

    var permissionRequests by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequests++
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRequests++ }
    val bluetoothMissing = remember(permissionRequests) { AppPermissions.missing(context, AppPermissions.bluetooth) }
    val locationMissing = remember(permissionRequests) { AppPermissions.missing(context, AppPermissions.location).isNotEmpty() }
    val micMissing = remember(permissionRequests) { AppPermissions.missing(context, AppPermissions.microphone).isNotEmpty() }

    val current = profile
    val device = current?.device()
    val labRunning = live.sessionId != null

    LaunchedEffect(device, bluetoothMissing.isEmpty(), labRunning) {
        if (device != null && bluetoothMissing.isEmpty() && !labRunning) container.preview.start(device) else container.preview.stop()
    }
    DisposableEffect(Unit) { onDispose { container.preview.stop() } }
    if (current === LOADING) return

    val today = container.programs.today()
    val outdoors = activity != TrainingActivity.TREADMILL
    val walksOutdoors = pending.benchmark && outdoors
    val gpsProviderOff = outdoors && context.getSystemService(LocationManager::class.java)
        ?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true
    val gpsOff = walksOutdoors && gpsProviderOff
    // Outside a benchmark, GPS is welcome but never required: without it, heart rate still works.
    val measures = outdoors && !locationMissing && !gpsProviderOff
    val scope = rememberCoroutineScope()
    val bike = current?.bikeType?.let { name -> BikeType.entries.firstOrNull { it.name == name } }
    val rides = activity == TrainingActivity.CYCLING && !pending.benchmark
    val benchmarkInputs = current?.benchmarkInputs(today)
    val canStart = device != null && current != null && bluetoothMissing.isEmpty() && !labRunning &&
        !gpsOff && !(walksOutdoors && locationMissing) && !(pending.benchmark && benchmarkInputs == null)

    SubScreen(onBack = { nav.back() }) {
        Column {
            pending.eyebrow?.let { Eyebrow(it) }
            ScreenTitle(pending.title, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            pending.plan?.let { Lead("About ${Format.minutes(it.totalSeconds)}") }
        }
        if (pending.benchmark) {
            Choices(
                listOf(TrainingActivity.OUTDOOR_RUN, TrainingActivity.TREADMILL),
                activity,
                { if (it == TrainingActivity.TREADMILL) "Treadmill" else "Outdoors" },
                { activity = it },
            )
            BenchmarkInstructions(walksOutdoors)
        } else {
            Choices(TrainingActivity.entries, activity, { it.label }, { activity = it })
        }
        if (rides && current != null) {
            LabeledChoices("Your bike") {
                Choices(BikeType.entries, bike, { it.label }, { chosen ->
                    scope.launch { container.trainingDao.saveProfile(current.copy(bikeType = chosen.name)) }
                })
                Hint(if (bike == null) "For estimating your watts. You can change it in Settings." else "Watts are an estimate, least accurate in wind.")
            }
        }

        when {
            bluetoothMissing.isNotEmpty() -> HCard(padding = PaddingValues(20.dp), spacing = 8.dp) {
                Text("Haanf needs Bluetooth access to read your watch", style = body(17.sp, FontWeight.Bold))
                PrimaryButton("Allow Bluetooth", onClick = {
                    permissionLauncher.launch((bluetoothMissing + AppPermissions.notifications).toTypedArray())
                })
            }
            device == null -> HCard(padding = PaddingValues(20.dp), spacing = 8.dp) {
                Text("No watch connected", style = body(17.sp, FontWeight.Bold))
                BodyText("A workout needs live heart rate from a watch or strap.", size = 15)
                PrimaryButton("Connect a watch", onClick = { nav.open(Dest.Devices) })
            }
            else -> Column {
                ConnectedCard(device.name ?: "Your watch", if (labRunning) live.connection else preview.connection, if (labRunning) live.bpm else preview.bpm)
                LinkButton("Change device", onClick = { nav.open(Dest.Devices) })
            }
        }

        HCard(padding = PaddingValues(0.dp), spacing = 0.dp) {
            if (coachReady) {
                ToggleRow("Coach me during this workout", "The coach talks you through it, and you can talk back.", coached, { coached = it })
                if (coached && micMissing && container.speech.available) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(HaanfIcons.Mic, contentDescription = null, tint = HaanfColors.TextSecondary, modifier = Modifier.size(20.dp))
                        LinkButton("Allow the microphone to talk back", onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
                    }
                }
                if (coached) {
                    HorizontalDivider(color = HaanfColors.Divider)
                    ToggleRow(
                        "Talk test checks",
                        "The coach asks if you can talk. Never during a hard effort.",
                        talkChecks,
                        { talkChecks = it },
                    )
                }
                HorizontalDivider(color = HaanfColors.Divider)
            }
            ToggleRow("Spoken cues", "Hear each step with your phone in a pocket.", cues, { cues = it })
        }
        if (labRunning) Notice("A Lab session is running. Stop it in Settings, under Lab, first.")
        if (gpsOff) Notice("Turn on location on this phone, or use a treadmill.")
        if (walksOutdoors && locationMissing) {
            Notice("Haanf uses GPS only to measure the mile. It keeps the distance, never your route.", kind = NoticeKind.INFO, icon = HaanfIcons.Info)
            SecondaryButton("Allow location", onClick = { permissionLauncher.launch(AppPermissions.location.toTypedArray()) })
        }
        if (outdoors && !pending.benchmark) {
            when {
                locationMissing -> {
                    Notice(
                        "Allow location to measure distance, speed and climbs. Haanf keeps the totals, never your route.",
                        kind = NoticeKind.INFO,
                        icon = HaanfIcons.Info,
                    )
                    SecondaryButton("Allow location", onClick = { permissionLauncher.launch(AppPermissions.location.toTypedArray()) })
                }
                gpsProviderOff -> Notice("Location is off on this phone, so distance and speed won't be measured.", kind = NoticeKind.INFO, icon = HaanfIcons.Info)
                else -> Hint("GPS measures distance and speed. Haanf keeps the totals, never your route.")
            }
        }

        BottomActions {
            PrimaryButton(
                "Start workout",
                height = 64.dp,
                enabled = canStart,
                onClick = {
                    val chosen = requireNotNull(device)
                    val zones = requireNotNull(current).zones(today)
                    val withCoach = coachReady && coached
                    container.preview.stop()
                    container.workouts.start(
                        WorkoutRequest(
                            title = pending.title,
                            activity = activity,
                            device = chosen,
                            zones = zones,
                            plan = pending.plan,
                            slot = pending.slot,
                            ai = pending.ai,
                            coached = withCoach,
                            plannedHard = pending.hard,
                            spokenCues = cues,
                            talkChecks = withCoach && talkChecks,
                            usesGps = if (pending.benchmark) walksOutdoors else measures,
                            riderKg = current.weightKg,
                            bike = bike,
                            benchmark = if (pending.benchmark) {
                                benchmarkInputs?.let { BenchmarkRequest(it, usesGps = walksOutdoors) }
                            } else {
                                null
                            },
                        ),
                    )
                    if (pending.ai != null) coachTab.outcome = null
                    nav.back()
                },
            )
        }
    }
}

@Composable
private fun BenchmarkInstructions(outdoors: Boolean) {
    HCard(spacing = 6.dp) {
        Text("How it works", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
        BodyText("1. Warm up with 5 minutes of easy walking, then rest for 2 minutes.", size = 15)
        BodyText("2. Walk one mile as fast as you can, without running. Keep one foot on the ground.", size = 15)
        BodyText(
            if (outdoors) {
                "3. Choose a flat route. GPS measures the mile, and Haanf stops by itself."
            } else {
                "3. Keep the treadmill flat. Tap Finish when it shows 1 mile, or 1.61 km."
            },
            size = 15,
        )
        Hint(SafetyRules.BENCHMARK_STOP_RULE)
    }
}

// ---------------------------------------------------------------- Live workout

/** Colours for the live screen. The whole screen takes the zone's colour. */
private data class LiveLook(val background: Color, val content: Color)

@Composable
fun LiveWorkoutScreen(container: AppContainer, running: WorkoutState.Running) {
    val live by container.live.live.collectAsStateWithLifecycle()
    val coach by container.coach.status.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    val request = running.request
    val zones = request.zones
    val plan = request.plan
    val step = running.stepIndex?.let { plan?.steps?.getOrNull(it) }
    val bpm = if (live.inDropout) null else live.bpm
    val inHardEffort = running.inHardEffort(bpm)

    val look = when {
        live.inDropout || request.benchmark != null -> LiveLook(HaanfColors.Dark, HaanfColors.White)
        step != null -> LiveLook(step.target.color(), step.target.onColor())
        bpm != null -> zones.zoneFor(bpm).let { LiveLook(it.color(), it.onColor()) }
        else -> LiveLook(HaanfColors.Dark, HaanfColors.White)
    }

    if (coach.awaitingTalkAnswer && !inHardEffort && request.benchmark == null) {
        TalkTestPanel(container, bpm)
        return
    }
    SystemBarIcons(darkBackground = look.content == HaanfColors.White)

    Box(Modifier.fillMaxSize().background(look.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            LiveHeader(running, step, look.content)
            LiveBanners(live.inDropout, running.highHeartRate, coach.offline, live.device?.name)
            when {
                request.benchmark != null -> BenchmarkLive(running, bpm, look.content)
                step != null && step.target.isHard() -> IntervalLive(running, step, bpm, zones, look.content)
                else -> SteadyLive(running, step, bpm, zones, look.content)
            }
            if (request.benchmark == null) {
                running.stepIndex?.let { index ->
                    val next = plan?.steps?.getOrNull(index + 1)
                    Text(
                        if (next != null) "Then ${WorkoutText.clock(next.seconds)} ${next.target.label.lowercase()}" else "Then you're done",
                        style = body(17.sp, FontWeight.SemiBold),
                        color = look.content,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            if (coach.active || coach.offline) {
                coach.lastMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = body(17.sp),
                        color = look.content,
                        maxLines = 4,
                        modifier = Modifier.padding(bottom = 12.dp).semantics { contentDescription = "Coach: $it" },
                    )
                }
                if (coach.thinking) Text("The coach is thinking", style = body(15.sp), color = look.content, modifier = Modifier.padding(bottom = 8.dp))
            }
            LiveActions(container, coach.active, look, onFinish = { confirming = true })
        }
    }

    if (confirming && request.benchmark != null) {
        HDialog(
            title = "Did you finish the mile?",
            text = "The result only counts for a full mile. A shorter walk stays in your history.",
            confirm = "Yes, score it",
            onConfirm = {
                confirming = false
                container.workouts.finish()
            },
            dismiss = "No, stop without a score",
            onDismiss = {
                confirming = false
                container.workouts.finish(reason = "abandoned", mileCompleted = false)
            },
            onDismissRequest = { confirming = false },
        )
    } else if (confirming) {
        HDialog(
            title = "Finish this workout?",
            text = "It will be saved to your history.",
            confirm = "Finish",
            onConfirm = {
                confirming = false
                container.workouts.finish()
            },
            dismiss = "Keep going",
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun LiveHeader(running: WorkoutState.Running, step: WorkoutStep?, content: Color) {
    val plan = running.request.plan
    val title = when {
        running.request.benchmark != null -> "1-mile walk"
        step != null -> step.label
        running.planComplete -> "Session complete"
        else -> running.request.title
    }
    Row(Modifier.fillMaxWidth()) {
        Text(title, style = condensed(40.sp), color = content, modifier = Modifier.weight(1f).alignByBaseline())
        val index = running.stepIndex
        if (index != null && plan != null) {
            Text(
                "Step ${index + 1} of ${plan.steps.size}",
                style = body(16.sp, FontWeight.SemiBold),
                color = content,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Composable
private fun LiveBanners(dropout: Boolean, highHeartRate: Boolean, coachOffline: Boolean, deviceName: String?) {
    val banners = buildList {
        if (dropout) add("Reconnecting to ${deviceName ?: "your watch"}. Your workout is still recording, so keep going.")
        if (highHeartRate) add(HIGH_HEART_RATE_ALERT)
        if (coachOffline) add("The coach is offline. Your workout carries on.")
    }
    if (banners.isEmpty()) return
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        banners.forEach { Notice(it) }
    }
}

/** A number as large as the space allows, up to [max]. */
@Composable
private fun HugeText(text: String, max: Int, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = condensed(max.sp, lineHeight = 0.95).copy(color = color),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 56.sp, maxFontSize = max.sp, stepSize = 4.sp),
        modifier = modifier,
    )
}

@Composable
private fun ColumnScope.SteadyLive(
    running: WorkoutState.Running,
    step: WorkoutStep?,
    bpm: Int?,
    zones: HeartRateZones,
    content: Color,
) {
    val current = bpm?.let(zones::zoneFor)
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.Bottom) {
            HugeText(bpm?.toString() ?: NO_READING, if (bpm == null) 120 else 200, content, Modifier.weight(1f, fill = false).testTag("workout-bpm"))
            Text("bpm", style = condensed(32.sp, FontWeight.SemiBold), color = content, modifier = Modifier.padding(start = 8.dp, bottom = 24.dp))
        }
        Text(
            zoneSentence(current, step?.target, zones),
            style = body(22.sp, FontWeight.Bold, 1.25),
            color = content,
            modifier = Modifier.padding(top = 8.dp).testTag("workout-zone"),
        )
        running.motion?.let { ClimbLine(it, content) }
    }
    ZoneStrip(current, content)
    val motion = running.motion?.takeIf { it.hasGps }
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val size = if (motion != null) 40 else 52
        val remaining = running.stepRemainingSeconds
        if (step != null && remaining != null) {
            LiveStat(WorkoutText.clock(remaining), "left in step", content, Modifier.weight(1f), size)
        } else {
            LiveStat(WorkoutText.clock(running.elapsedSeconds), "so far", content, Modifier.weight(1f).testTag("workout-clock"), size)
        }
        if (motion != null) {
            LiveStat(WorkoutText.kmShort(motion.distanceMetres), "km", content, Modifier.weight(1f).testTag("workout-distance"), size)
            val ride = running.request.activity == TrainingActivity.CYCLING
            val speed = motion.speedMps
            val value = when {
                speed == null -> "- -"
                ride -> WorkoutText.kmh(speed)
                else -> WorkoutText.paceOf(speed) ?: "- -"
            }
            LiveStat(value, if (ride) "km/h" else "per km", content, Modifier.weight(1f).testTag("workout-speed"), size)
        } else if (running.distanceMetres > 0) {
            LiveStat("%.1f".format(running.distanceMetres / 1000), "km so far", content, Modifier.weight(1f))
        } else if (step != null) {
            LiveStat(WorkoutText.clock(running.elapsedSeconds), "so far", content, Modifier.weight(1f).testTag("workout-clock"))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Shown while climbing, so the athlete knows why heart rate is rising. */
@Composable
private fun ClimbLine(motion: MotionNow, content: Color) {
    if (!motion.climbing) return
    val grade = motion.gradePercent?.roundToInt()?.let { "$it%" } ?: ""
    val gain = motion.climbSoFarMetres?.roundToInt()?.let { ", $it m up" }.orEmpty()
    Text(
        "Climbing $grade$gain",
        style = body(18.sp, FontWeight.SemiBold),
        color = content,
        modifier = Modifier.padding(top = 6.dp).testTag("workout-climb"),
    )
}

private fun zoneSentence(current: Zone?, target: Zone?, zones: HeartRateZones): String {
    if (current == null) return "Waiting for heart rate"
    if (target == null) return "In your ${current.label.lowercase()} zone."
    val range = zones.rangeFor(target)
    return when {
        current == target -> when (target) {
            Zone.RECOVERY, Zone.EASY -> "In your ${target.label.lowercase()} zone. Stay under ${range.last + 1}."
            Zone.HARD -> "In your hard zone. Stay under ${range.last + 1}."
            Zone.VERY_HARD -> "In your very hard zone."
        }
        current.ordinal > target.ordinal -> "Above your ${target.label.lowercase()} zone. Ease off to under ${range.last + 1}."
        else -> "Below your ${target.label.lowercase()} zone. Aim for ${range.first} or more."
    }
}

@Composable
private fun ZoneStrip(current: Zone?, content: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = current?.let { "Current zone: ${it.label}" } ?: "No current zone"
                role = Role.Image
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Zone.entries.forEach { zone ->
            val on = zone == current
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .padding(top = if (on) 0.dp else 3.dp)
                        .fillMaxWidth()
                        .height(if (on) 14.dp else 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (on) content else content.copy(alpha = 0.4f)),
                )
                Text(
                    zone.label,
                    style = body(14.sp, if (on) FontWeight.Bold else FontWeight.Normal),
                    color = content,
                    modifier = Modifier.padding(top = if (on) 1.dp else 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LiveStat(value: String, label: String, content: Color, modifier: Modifier = Modifier, size: Int = 52) {
    Column(modifier) {
        Text(value, style = condensed(size.sp), color = content, maxLines = 1)
        Text(label, style = body(15.sp), color = content)
    }
}

@Composable
private fun ColumnScope.IntervalLive(
    running: WorkoutState.Running,
    step: WorkoutStep,
    bpm: Int?,
    zones: HeartRateZones,
    content: Color,
) {
    val plan = requireNotNull(running.request.plan)
    val index = requireNotNull(running.stepIndex)
    val efforts = plan.steps.withIndex().filter { it.value.target.isHard() }
    val effort = efforts.indexOfFirst { it.index == index } + 1
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .semantics {
                contentDescription = "Effort $effort of ${efforts.size}"
                role = Role.Image
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(efforts.size) { position ->
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (position < effort) content else content.copy(alpha = 0.25f)),
            )
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        HugeText(WorkoutText.clock(running.stepRemainingSeconds ?: 0), 216, content, Modifier.testTag("workout-clock"))
        Text("left in this effort", style = body(22.sp, FontWeight.Bold), color = content, modifier = Modifier.padding(top = 8.dp))
        running.motion?.let { ClimbLine(it, content) }
    }
    val range = zones.rangeFor(step.target)
    val current = bpm?.let(zones::zoneFor)
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(bpm?.toString() ?: NO_READING, style = condensed(88.sp), color = content, modifier = Modifier.testTag("workout-bpm"))
            Text("bpm", style = condensed(24.sp, FontWeight.SemiBold), color = content, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
        }
        val where = when {
            current == null -> "No reading"
            current == step.target -> "In zone"
            current.ordinal > step.target.ordinal -> "Above zone"
            else -> "Below zone"
        }
        val target = if (step.target == Zone.VERY_HARD) "Target ${range.first} or more" else "Target ${range.first} to ${range.last}"
        Text(
            "$where\n$target",
            style = body(17.sp, FontWeight.SemiBold, 1.25),
            color = content,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(bottom = 6.dp).testTag("workout-zone"),
        )
    }
}

@Composable
private fun ColumnScope.BenchmarkLive(running: WorkoutState.Running, bpm: Int?, content: Color) {
    val benchmark = requireNotNull(running.request.benchmark)
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        if (benchmark.usesGps) {
            Row(verticalAlignment = Alignment.Bottom) {
                HugeText("%.2f".format(running.distanceMetres / 1000), 200, content, Modifier.weight(1f, fill = false))
                Text("km", style = condensed(32.sp, FontWeight.SemiBold), color = content, modifier = Modifier.padding(start = 8.dp, bottom = 24.dp))
            }
            Text(
                "of %.2f km. Haanf stops by itself at one mile.".format(RockportWalkTest.MILE_METRES / 1000),
                style = body(20.sp, FontWeight.Bold),
                color = content,
            )
        } else {
            HugeText(WorkoutText.clock(running.elapsedSeconds), 200, content, Modifier.testTag("workout-clock"))
            Text("Tap Finish when the treadmill shows 1 mile, or 1.61 km.", style = body(20.sp, FontWeight.Bold), color = content)
        }
        Text(
            "Walk as fast as you can. No running.",
            style = body(17.sp),
            color = content,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LiveStat(bpm?.toString() ?: "--", "bpm", content, Modifier.weight(1f).testTag("workout-bpm"))
        if (benchmark.usesGps) LiveStat(WorkoutText.clock(running.elapsedSeconds), "so far", content, Modifier.weight(1f).testTag("workout-clock"))
    }
    Text(SafetyRules.BENCHMARK_STOP_RULE, style = body(15.sp), color = content, modifier = Modifier.padding(bottom = 16.dp))
}

@Composable
private fun LiveActions(container: AppContainer, coachActive: Boolean, look: LiveLook, onFinish: () -> Unit) {
    val context = LocalContext.current
    val listening by container.speech.listening.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<String?>(null) }
    val canSpeak = container.speech.available && AppPermissions.missing(context, AppPermissions.microphone).isEmpty()

    if (coachActive && !canSpeak) {
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_REPLIES.forEach { reply ->
                LiveButton(reply, filled = true, look = look, height = 56.dp, modifier = Modifier.weight(1f)) {
                    container.coach.userSaid(reply)
                }
            }
        }
    }
    problem?.let { Text(it, style = body(15.sp), color = look.content, modifier = Modifier.padding(bottom = 8.dp)) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (coachActive && canSpeak) {
            LiveButton(
                if (listening) "Listening" else "Talk to coach",
                filled = true,
                look = look,
                modifier = Modifier.weight(1f).testTag("talk-to-coach"),
                enabled = !listening,
            ) {
                problem = null
                container.speech.listen(container.coach::userSaid) { problem = it }
            }
        }
        LiveButton("Finish", filled = false, look = look, modifier = Modifier.weight(1f), onClick = onFinish)
    }
}

@Composable
private fun LiveButton(
    text: String,
    filled: Boolean,
    look: LiveLook,
    modifier: Modifier = Modifier,
    height: Dp = 60.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = Corner,
            colors = ButtonDefaults.buttonColors(
                containerColor = look.content,
                contentColor = look.background,
                disabledContainerColor = look.content.copy(alpha = 0.7f),
                disabledContentColor = look.background,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = modifier.height(height),
        ) {
            Text(text, style = body(18.sp, FontWeight.Bold), textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = Corner,
            border = BorderStroke(2.dp, look.content),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = look.content),
            modifier = modifier.height(height),
        ) {
            Text(text, style = body(18.sp, FontWeight.Bold))
        }
    }
}

/** The coach asks if you could talk. Answer by voice or with one tap. Nothing is recorded. */
@Composable
internal fun TalkTestPanel(container: AppContainer, bpm: Int?) {
    val context = LocalContext.current
    val listening by container.speech.listening.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<String?>(null) }
    val canSpeak = container.speech.available && AppPermissions.missing(context, AppPermissions.microphone).isEmpty()
    val look = LiveLook(HaanfColors.Dark, HaanfColors.White)
    SystemBarIcons(darkBackground = true)

    Box(Modifier.fillMaxSize().background(look.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Talk test", style = body(17.sp, FontWeight.Bold), color = look.content)
                Text(bpm?.let { "$it bpm" } ?: "-- bpm", style = body(17.sp, FontWeight.SemiBold), color = look.content)
            }
            Text(
                "Could you talk comfortably just now?",
                style = condensed(56.sp, lineHeight = 1.02),
                color = look.content,
                modifier = Modifier.padding(top = 32.dp, bottom = 8.dp),
            )
            Text(
                "Keep your pace. Say a sentence out loud, then answer by voice or tap.",
                style = body(18.sp),
                color = look.content,
            )
            Spacer(Modifier.weight(1f))
            if (canSpeak) {
                LiveButton(
                    if (listening) "Listening" else "Answer by voice",
                    filled = false,
                    look = look,
                    height = 56.dp,
                    enabled = !listening,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    problem = null
                    container.speech.listen(container.coach::userSaid) { problem = it }
                }
            }
            problem?.let { Text(it, style = body(15.sp), color = look.content, modifier = Modifier.padding(top = 8.dp)) }
            Text("How was it?", style = body(18.sp, FontWeight.Bold), color = look.content, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TalkAnswer.entries.forEach { answer ->
                    LiveButton(answer.label, filled = true, look = look, height = 56.dp, modifier = Modifier.fillMaxWidth()) {
                        container.coach.answerTalkTest(answer)
                    }
                }
            }
            LinkButton(
                "Skip",
                onClick = { container.coach.skipTalkTest() },
                color = look.content,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Summary

/** A benchmark's result, from a walk just finished or from the saved benchmark. */
sealed interface BenchmarkResult {
    data class Scored(val vo2Max: Double, val timeSeconds: Int, val heartRateBpm: Int, val ageYears: Int, val sex: Sex) : BenchmarkResult
    data class NotScored(val reason: String?) : BenchmarkResult

    companion object {
        fun from(outcome: WalkOutcome?, inputs: BenchmarkInputs?): BenchmarkResult = when {
            outcome is WalkOutcome.Scored && inputs != null ->
                Scored(outcome.vo2Max, outcome.timeSeconds, outcome.heartRateBpm, inputs.ageYears, inputs.sex)
            outcome is WalkOutcome.NotScored -> NotScored(outcome.reason)
            else -> NotScored(null)
        }

        fun from(workout: WorkoutEntity, saved: BenchmarkEntity?): BenchmarkResult? = when {
            workout.origin != WorkoutOrigin.BENCHMARK.name -> null
            saved != null -> Scored(saved.vo2Max, saved.timeSeconds, saved.heartRateBpm, saved.ageYears, Sex.valueOf(saved.sex))
            else -> NotScored(null)
        }
    }
}

@Composable
fun FinishedSummary(container: AppContainer, finished: WorkoutState.Finished, onDone: () -> Unit) {
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val saved by remember(finished.workout.id) { container.trainingDao.observeWorkout(finished.workout.id) }
        .collectAsStateWithLifecycle(null)
    val current = profile
    if (current === LOADING) return
    val workout = saved ?: finished.workout
    val isBenchmark = workout.origin == WorkoutOrigin.BENCHMARK.name
    val result = if (isBenchmark) BenchmarkResult.from(finished.benchmark, current?.benchmarkInputs(container.programs.today())) else null
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(emptyList())
    val firstScore = result is BenchmarkResult.Scored && benchmarks.size == 1

    HScreen(Modifier.safeDrawingPadding()) {
        Column {
            ScreenTitle(if (isBenchmark) "Benchmark done" else "Workout done", modifier = Modifier.padding(bottom = 2.dp))
            Lead("${workout.title}, ${activityLabel(workout)}")
        }
        WorkoutSummaryBody(container, workout, result, live = true, firstScore = firstScore)
        BottomActions { PrimaryButton("Done", onClick = onDone) }
    }
}

fun activityLabel(workout: WorkoutEntity): String {
    val activity = TrainingActivity.entries.firstOrNull { it.name == workout.activity }
    return when {
        workout.origin != WorkoutOrigin.BENCHMARK.name -> activity?.label?.lowercase() ?: workout.activity
        activity == TrainingActivity.TREADMILL -> "treadmill walk"
        else -> "outdoor walk"
    }
}

/** Everything a finished workout shows, both straight after it and from history. */
@Composable
fun ColumnScope.WorkoutSummaryBody(
    container: AppContainer,
    workout: WorkoutEntity,
    result: BenchmarkResult?,
    live: Boolean,
    firstScore: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val enrolment by remember { container.programs.enrolment }.collectAsStateWithLifecycle(null)
    val talkChecks by remember(workout.id) { container.coachDao.observeTalkChecks(workout.id) }.collectAsStateWithLifecycle(emptyList())
    val splits by remember(workout.id) { container.trainingDao.observeSplits(workout.id) }.collectAsStateWithLifecycle(emptyList())
    val events by remember(workout.id) { container.trainingDao.observeEvents(workout.id) }.collectAsStateWithLifecycle(emptyList())
    val said = events.mapNotNull(TimelineEntry::from).filter { it.kind.isSaid }

    if (!live) {
        Column {
            ScreenTitle(workout.title, modifier = Modifier.padding(bottom = 2.dp))
            Lead(activityLabel(workout).replaceFirstChar(Char::uppercase))
        }
    }
    ProgramLine(workout, enrolment?.programId)
    result?.let { BenchmarkResultCard(it, firstScore = firstScore) }

    StatRow(
        *listOfNotNull(
            WorkoutText.clock(workout.durationSeconds()) to "time",
            (workout.avgBpm?.toString() ?: "--") to "average bpm",
            workout.hrDropBpm?.let { "$it" to "bpm drop in 1 min" },
        ).toTypedArray(),
        size = 32,
    )
    MotionStats(workout)
    ZoneCard(workout)
    if (splits.isNotEmpty()) SplitsCard(splits, workout.activity == TrainingActivity.CYCLING.name)
    if (said.isNotEmpty()) {
        ConversationCard(said)
    } else if (talkChecks.isNotEmpty()) {
        HCard(spacing = 4.dp) {
            Text("Talk test", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
            talkChecks.forEach { check ->
                val answer = TalkAnswer.entries.firstOrNull { it.name == check.answer }?.label ?: check.answer
                val minutesIn = ((check.atMs - workout.startedAtMs) / 60_000).coerceAtLeast(0)
                val at = check.bpm?.let { " at $it bpm" }.orEmpty()
                BodyText("$answer$at, $minutesIn min in.")
            }
        }
    }
    CoachNote(container, workout)
    LabeledChoices("How hard was that?") {
        Choices(
            (1..5).toList(),
            workout.effortRating,
            { "$it" },
            { rating -> scope.launch { container.trainingDao.setEffortRating(workout.id, rating) } },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(EFFORT_ENDS.first, style = body(14.sp), color = HaanfColors.TextSecondary)
            Text(EFFORT_ENDS.second, style = body(14.sp), color = HaanfColors.TextSecondary)
        }
    }
}

/** Distance, speed or pace, climb and effort, when the phone measured them. */
@Composable
private fun MotionStats(workout: WorkoutEntity) {
    val ride = workout.activity == TrainingActivity.CYCLING.name
    val metres = workout.distanceMetres
    val climb = workout.climbMetres
    if (metres == null && climb == null) return
    val speed = metres?.let { distance -> workout.movingSeconds?.takeIf { it > 0 }?.let { distance / it } }
    StatRow(
        *listOfNotNull(
            metres?.let { WorkoutText.kmShort(it) to "km" },
            speed?.let { if (ride) WorkoutText.kmh(it) to "km/h moving" else (WorkoutText.paceOf(it) ?: "--") to "per km" },
            climb?.let { "${it.roundToInt()}" to "m climbed" },
        ).toTypedArray(),
        size = 32,
    )
    val facts = buildList {
        workout.avgWatts?.let { watts ->
            val bike = workout.bikeType?.let { name -> BikeType.entries.firstOrNull { it.name == name }?.label?.lowercase() }
            add("About $watts W on average" + (bike?.let { ", estimated for a $it" } ?: ", estimated"))
        }
        workout.gapSecondsPerKm?.let { add("Grade-adjusted pace ${WorkoutText.pace(it)} per km") }
        workout.maxSpeedMps?.takeIf { ride }?.let { add("Top speed ${WorkoutText.kmh(it)} km/h") }
        workout.descentMetres?.takeIf { it >= 1 }?.let { add("${it.roundToInt()} m descended") }
    }
    if (facts.isNotEmpty()) Hint(facts.joinToString(". ") + ".")
}

@Composable
private fun SplitsCard(splits: List<SplitEntity>, ride: Boolean) {
    HCard(spacing = 6.dp) {
        Text("Each kilometre", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
        splits.forEach { split ->
            val label = if (split.metres >= 999) "${split.number}" else "${WorkoutText.kmShort(split.metres)} km"
            val pace = when {
                split.seconds <= 0 -> "--"
                ride -> "${WorkoutText.kmh(split.metres / split.seconds)} km/h"
                else -> "${WorkoutText.pace((split.seconds / (split.metres / 1000)).roundToInt())} /km"
            }
            val height = listOfNotNull(
                split.climbMetres.roundToInt().takeIf { it >= 1 }?.let { "+$it m" },
                split.descentMetres.roundToInt().takeIf { it >= 1 }?.let { "\u2212$it m" },
            ).joinToString(" ").ifEmpty { "flat" }
            val parts = listOfNotNull(
                WorkoutText.clock(split.seconds),
                pace,
                height,
                split.avgBpm?.let { "$it bpm" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = body(15.sp, FontWeight.Bold), color = HaanfColors.Text, modifier = Modifier.size(width = 56.dp, height = 22.dp))
                Text(parts.joinToString("   "), style = body(15.sp), color = HaanfColors.Text)
            }
        }
    }
}

/** What the coach and the athlete said, with the time into the workout. */
@Composable
private fun ConversationCard(said: List<TimelineEntry>) {
    HCard(spacing = 10.dp) {
        Text("What you and the coach said", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
        said.forEach { entry ->
            val who = when (entry.kind) {
                TimelineKind.COACH -> "Coach"
                TimelineKind.ATHLETE -> "You"
                TimelineKind.TALK_TEST -> "Talk test"
                else -> "Plan"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(WorkoutText.clock(entry.elapsedSeconds), style = body(14.sp), color = HaanfColors.TextSecondary, modifier = Modifier.size(width = 44.dp, height = 20.dp))
                Column(Modifier.weight(1f)) {
                    Text(who, style = body(14.sp, FontWeight.Bold), color = HaanfColors.Text)
                    Text(entry.text, style = body(15.sp), color = HaanfColors.Text)
                }
            }
        }
    }
}

@Composable
private fun ProgramLine(workout: WorkoutEntity, enrolledProgramId: String?) {
    val programName = (workout.programId ?: enrolledProgramId)?.let { Programs.byId(it)?.name } ?: return
    val text = when {
        workout.programId == null -> "Doesn't count toward $programName. Only program sessions do."
        workout.countedForProgram -> "Counts toward $programName"
        else -> "Doesn't count toward $programName: under 80% of the plan. This session is still next."
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (workout.countedForProgram) {
            Icon(HaanfIcons.Check, contentDescription = null, tint = HaanfColors.Text, modifier = Modifier.size(20.dp))
        }
        Text(text, style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.Text)
    }
}

@Composable
private fun BenchmarkResultCard(result: BenchmarkResult, firstScore: Boolean) {
    when (result) {
        is BenchmarkResult.Scored -> HCard(color = HaanfColors.Primary, padding = PaddingValues(20.dp), spacing = 4.dp) {
            Text("Estimated VO2max", style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.White)
            BigNumber("%.1f".format(result.vo2Max), "mL/kg/min", size = 80, color = HaanfColors.White, modifier = Modifier.testTag("vo2max"))
            Text(
                FitnessNorms.level(result.vo2Max, result.ageYears, result.sex).label + " for your age and sex",
                style = body(18.sp, FontWeight.Bold),
                color = HaanfColors.White,
            )
            Text(
                "Typical: %.1f. This is an estimate, not a lab result.".format(FitnessNorms.median(result.ageYears, result.sex)),
                style = body(15.sp),
                color = HaanfColors.White,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(WorkoutText.clock(result.timeSeconds), style = condensed(32.sp), color = HaanfColors.White)
                    Text("mile time", style = body(14.sp), color = HaanfColors.White)
                }
                Column {
                    Text("${result.heartRateBpm}", style = condensed(32.sp), color = HaanfColors.White)
                    Text("bpm near the end", style = body(14.sp), color = HaanfColors.White)
                }
            }
            if (firstScore) {
                Text("Your program is unlocked.", style = body(15.sp, FontWeight.SemiBold), color = HaanfColors.White, modifier = Modifier.padding(top = 4.dp))
            }
        }
        is BenchmarkResult.NotScored -> HCard(spacing = 4.dp) {
            Text("No score this time", style = body(18.sp, FontWeight.Bold), color = HaanfColors.Text)
            BodyText(result.reason ?: "Only a full mile is scored.")
            Hint("Try again when you're ready. Your history keeps this walk.")
        }
    }
}

@Composable
private fun ZoneCard(workout: WorkoutEntity) {
    val zones = listOf(
        Zone.RECOVERY to workout.recoverySeconds,
        Zone.EASY to workout.easySeconds,
        Zone.HARD to workout.hardSeconds,
        Zone.VERY_HARD to workout.veryHardSeconds,
    )
    HCard(spacing = 8.dp) {
        Text("Time in each zone", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
        if (zones.all { it.second == 0 }) {
            Hint("No heart rate readings were saved.")
        } else {
            ZoneBar(
                zones.map { it.first to it.second.toFloat() },
                height = 16.dp,
                description = zones.joinToString(", ") { "${it.first.label} ${Format.minutes(it.second)}" },
            )
            zones.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (zone, seconds) -> ZoneKey(zone, zone.label, Format.minutes(seconds), Modifier.weight(1f)) }
                }
            }
        }
        val facts = buildList {
            workout.maxBpm?.let { add("Highest heart rate $it bpm") }
            if (workout.noReadingSeconds >= 30) add("No reading for ${WorkoutText.clock(workout.noReadingSeconds)}")
        }
        if (facts.isNotEmpty()) Hint(facts.joinToString(". ") + ".")
    }
}

@Composable
private fun CoachNote(container: AppContainer, workout: WorkoutEntity) {
    val review by container.reviews.state.collectAsStateWithLifecycle()
    val state = review
    val (text, failed) = when {
        state is ReviewState.Working && state.workoutId == workout.id -> "The coach is reviewing this workout." to false
        state is ReviewState.Failed && state.workoutId == workout.id && workout.review == null -> state.message to true
        state is ReviewState.Done && state.workoutId == workout.id -> state.text to false
        workout.review != null -> workout.review to false
        else -> return
    }
    HCard(color = HaanfColors.InfoBackground, spacing = 4.dp) {
        Text("Coach note", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
        BodyText(text)
        if (failed) LinkButton("Retry", onClick = { container.reviews.retry(workout) })
    }
}

