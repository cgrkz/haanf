package app.haanf.ui

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.Features
import app.haanf.data.CoachFactEntity
import app.haanf.data.FactKind
import app.haanf.data.FactStatus
import app.haanf.data.ProfileEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.SourceState
import app.haanf.heartrate.ble.ScanSnapshot
import app.haanf.heartrate.ble.SeenDevice
import app.haanf.training.SafetyRules
import app.haanf.training.Sex
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.body
import app.haanf.ui.theme.condensed
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun Onboarding(
    container: AppContainer,
    step: OnboardingStep,
    profile: ProfileEntity?,
    onWelcomed: () -> Unit,
    onStartBenchmark: (PendingWorkout) -> Unit,
) {
    when (step) {
        OnboardingStep.WELCOME -> WelcomeScreen(onWelcomed)
        OnboardingStep.ABOUT_YOU -> AboutYouScreen(container, profile)
        OnboardingStep.CONNECT_WATCH -> ConnectWatchScreen(container, profile)
        OnboardingStep.FIRST_BENCHMARK -> FirstBenchmarkScreen(
            onStart = {
                container.onboarding.markFinished()
                onStartBenchmark(PendingWorkout(BENCHMARK_TITLE, null, null, benchmark = true, eyebrow = "Fitness benchmark"))
            },
            onLater = { container.onboarding.markFinished() },
        )
    }
}

/** About you and the watch, then the first benchmark while it is switched on. */
private val ONBOARDING_STEPS = if (Features.BENCHMARK) 3 else 2

@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    SystemBarIcons(darkBackground = true)
    val bars = remember {
        (0 until 31).map { index ->
            val breath = abs(sin(index * 0.42))
            val fade = 1 - index / 42.0
            (10 + 170 * breath * fade).roundToInt()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(HaanfColors.Primary)
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Text("Haanf", style = condensed(112.sp), color = HaanfColors.White, modifier = Modifier.padding(top = 48.dp))
        Row(
            Modifier.weight(1f).fillMaxWidth().clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bars.forEach { height ->
                Box(Modifier.width(5.dp).height(height.dp).clip(RoundedCornerShape(3.dp)).background(HaanfColors.White))
            }
        }
        Text("Build endurance you can feel.", style = condensed(44.sp, FontWeight.SemiBold, 1.05), color = HaanfColors.White)
        Text(
            "Follow a research-backed program, or let the AI coach choose today's workout.",
            style = body(18.sp, lineHeight = 1.4),
            color = HaanfColors.White,
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
        )
        PrimaryButton(
            "Get started",
            onClick = onStart,
            height = 60.dp,
            container = HaanfColors.White,
            content = HaanfColors.Text,
        )
    }
}

@Composable
private fun AboutYouScreen(container: AppContainer, existing: ProfileEntity?) {
    val scope = rememberCoroutineScope()
    var birthYear by rememberSaveable { mutableStateOf(existing?.birthYear?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(existing?.weightKg?.let { formatWeight(it) }.orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(existing?.sex?.let(Sex::valueOf)) }
    var health by rememberSaveable { mutableStateOf("") }
    var flagged by rememberSaveable { mutableStateOf(false) }

    val year = birthYear.toIntOrNull()
    val kilograms = weight.toDoubleOrNull()
    val chosenSex = sex
    val valid = year != null && year in 1920..LocalDate.now().year - 13 &&
        kilograms != null && kilograms in 30.0..250.0 && chosenSex != null

    fun save() {
        scope.launch {
            if (health.isNotBlank()) {
                container.coachDao.insertFact(
                    CoachFactEntity(
                        createdAtMs = container.clock.nowEpochMs(),
                        kind = FactKind.INJURY.name,
                        text = health.trim(),
                        status = FactStatus.ACTIVE.name,
                    ),
                )
            }
            container.trainingDao.saveProfile(
                (existing ?: ProfileEntity(birthYear = requireNotNull(year))).copy(
                    birthYear = requireNotNull(year),
                    sex = requireNotNull(chosenSex).name,
                    weightKg = requireNotNull(kilograms),
                    healthCheckClearedAtMs = container.clock.nowEpochMs(),
                ),
            )
        }
    }

    HScreen(Modifier.safeDrawingPadding()) {
        Column {
            Eyebrow("Step 1 of $ONBOARDING_STEPS")
            ScreenTitle("About you", size = 40, modifier = Modifier.padding(vertical = 4.dp))
            Lead("Used for your heart rate zones and fitness estimates. It stays on this phone.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HTextField(birthYear, { birthYear = it.filter(Char::isDigit).take(4) }, "Year of birth", Modifier.weight(1f), numeric = true)
            HTextField(weight, { input -> weight = input.filter { it.isDigit() || it == '.' }.take(5) }, "Weight in kg", Modifier.weight(1f), numeric = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sex, for fitness formulas", style = body(14.sp, FontWeight.SemiBold), color = HaanfColors.Text)
            Choices(listOf(Sex.FEMALE, Sex.MALE), sex, { it.label }, { sex = it })
        }
        HTextField(
            health,
            { health = it },
            "Any injuries or health conditions? (optional)",
            placeholder = "For example, a sore left knee",
            singleLine = false,
        )
        HCard(spacing = 8.dp) {
            Text("Does either of these apply to you?", style = body(18.sp, FontWeight.Bold), color = HaanfColors.Text)
            SafetyRules.HEALTH_CHECK.forEach { Text(it, style = body(15.sp), color = HaanfColors.Text) }
        }
        if (flagged) Notice(SafetyRules.HEALTH_CHECK_ADVICE)
        if (!valid) Hint("Fill in your year of birth, weight and sex to continue.")
        BottomActions {
            if (flagged) {
                PrimaryButton("My doctor has cleared me", onClick = { save() }, enabled = valid)
                SecondaryButton("Back", onClick = { flagged = false })
            } else {
                PrimaryButton("Neither applies to me", onClick = { save() }, enabled = valid)
                SecondaryButton("One or both apply to me", onClick = { flagged = true }, enabled = valid)
            }
        }
    }
}

private fun formatWeight(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()

private enum class WatchChoice(val label: String) {
    PIXEL("Pixel Watch 2"),
    AMAZFIT("Amazfit GTR 2e"),
    OTHER("Another device"),
    ;

    fun matches(device: SeenDevice): Boolean {
        val name = device.name.orEmpty()
        return when (this) {
            PIXEL -> name.contains("Pixel Watch", ignoreCase = true) && (device.advertisesHeartRate || device.paired)
            AMAZFIT -> (name.contains("Amazfit", ignoreCase = true) || name.contains("GTR", ignoreCase = true)) &&
                device.advertisesHeartRate
            OTHER -> device.advertisesHeartRate
        }
    }
}

@Composable
private fun ConnectWatchScreen(container: AppContainer, profile: ProfileEntity?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choice by rememberSaveable { mutableStateOf(WatchChoice.PIXEL) }
    var picked by remember { mutableStateOf<SeenDevice?>(null) }
    var permissionRequests by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequests++
    }
    val missing = remember(permissionRequests) { AppPermissions.missing(context, AppPermissions.bluetooth) }
    val preview by container.preview.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { onDispose { container.preview.stop() } }

    fun finish(device: HeartRateDevice?) {
        container.preview.stop()
        scope.launch {
            if (device != null && profile != null) {
                container.trainingDao.saveProfile(
                    profile.copy(deviceId = device.id, deviceName = device.name, deviceSourceKind = device.sourceKind),
                )
            }
            container.onboarding.markWatchStepDone()
        }
    }

    HScreen(Modifier.safeDrawingPadding(), spacing = 20.dp) {
        Column {
            Eyebrow("Step 2 of $ONBOARDING_STEPS")
            ScreenTitle("Connect your watch", size = 40, modifier = Modifier.padding(vertical = 4.dp))
            Lead("Haanf reads your heart rate live during a workout.")
        }
        Choices(WatchChoice.entries, choice, { it.label }, {
            choice = it
            picked = null
            container.preview.stop()
        }, height = 56.dp)

        when (choice) {
            WatchChoice.PIXEL -> BodyText(
                "Keep your watch paired to this phone, and turn on Connected Fitness in its quick settings. " +
                    "Haanf then finds it on its own.",
            )
            WatchChoice.AMAZFIT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyText("1. Open the Zepp app.")
                BodyText("2. Go to Device, then General.")
                BodyText("3. Turn on Activity heart rate sharing.")
                Hint("Not yet tested with this watch.")
            }
            WatchChoice.OTHER -> BodyText(
                "Any monitor that uses standard Bluetooth heart rate should work, including most chest straps.",
            )
        }

        if (missing.isNotEmpty()) {
            HCard(padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
                Text("Haanf needs Bluetooth access to find it", style = body(17.sp, FontWeight.Bold))
                PrimaryButton("Allow Bluetooth", onClick = {
                    launcher.launch((missing + AppPermissions.notifications).toTypedArray())
                })
            }
        } else {
            WatchFinder(container, choice, picked) { device ->
                picked = device
                container.preview.start(device.toHeartRateDevice())
            }
            picked?.let { device ->
                ConnectedCard(device.name ?: device.address, preview.connection, preview.bpm)
            }
        }

        BottomActions {
            PrimaryButton("Continue", onClick = { finish(picked?.toHeartRateDevice()) }, enabled = picked != null)
            LinkButton("Continue without a watch", onClick = { finish(null) }, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun WatchFinder(container: AppContainer, choice: WatchChoice, picked: SeenDevice?, onPick: (SeenDevice) -> Unit) {
    val scan = remember { container.discovery.scan() }
    val snapshot by scan.collectAsStateWithLifecycle(ScanSnapshot())
    val matches = snapshot.devices.filter(choice::matches)

    LaunchedEffect(matches.firstOrNull()?.address, choice) {
        if (picked == null && choice != WatchChoice.OTHER) matches.firstOrNull()?.let(onPick)
    }

    snapshot.error?.let { ErrorText(it) }
    when {
        choice == WatchChoice.OTHER && picked == null -> HCard(padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Text("Looking for nearby monitors", style = body(17.sp, FontWeight.Bold))
            Hint("Put your strap on so it wakes up, then choose it.")
            matches.forEach { device ->
                LinkButton(device.name ?: device.address, onClick = { onPick(device) })
            }
        }
        picked == null && choice == WatchChoice.AMAZFIT -> HCard(padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Text("Looking for your watch", style = body(17.sp, FontWeight.Bold))
            Hint("If it doesn't appear, start a workout on the watch and wait a moment.")
        }
        picked == null -> HCard(padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
            Text("Looking for your watch", style = body(17.sp, FontWeight.Bold))
            Hint("Turn on Connected Fitness on the watch.")
        }
    }
}

@Composable
fun ConnectedCard(name: String, connection: SourceState, bpm: Int?) {
    val status = when (connection) {
        SourceState.Streaming -> "Heart rate connected"
        is SourceState.Unsupported -> "Doesn't share heart rate"
        SourceState.Pairing -> "Waiting for pairing"
        is SourceState.Failed -> "Couldn't connect"
        else -> "Connecting"
    }
    Row(
        Modifier.fillMaxWidth().clip(Corner).background(HaanfColors.Card).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
            Text(status, style = body(15.sp), color = HaanfColors.TextSecondary)
        }
        BigNumber(bpm?.toString() ?: "--", "bpm", size = 48)
    }
    if (connection is SourceState.Unsupported) {
        Notice("This device connected but doesn't offer standard heart rate. Check that sharing is on.")
    }
}

@Composable
private fun FirstBenchmarkScreen(onStart: () -> Unit, onLater: () -> Unit) {
    HScreen(Modifier.safeDrawingPadding(), spacing = 20.dp) {
        Column {
            Eyebrow("Step 3 of $ONBOARDING_STEPS")
            ScreenTitle("Your first step: a fitness benchmark", size = 40, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Lead("It tells Haanf how fit you are today, so you can see yourself improve.")
        }
        BenchmarkPitch()
        BottomActions {
            PrimaryButton("Start benchmark", onClick = onStart)
            Text(
                "Your program unlocks after this.",
                style = body(15.sp),
                color = HaanfColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LinkButton("Not now", onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The benchmark's promise and rules, shared by first run and the Program tab. */
@Composable
fun BenchmarkPitch() {
    Row(
        Modifier.fillMaxWidth().clip(Corner).background(HaanfColors.Card).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("1 mile", style = condensed(72.sp), color = HaanfColors.Text)
        Text("as fast as you can walk", style = body(17.sp, FontWeight.SemiBold), modifier = Modifier.padding(bottom = 8.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "It takes about 15 minutes.",
            "Walk as fast as you can. No running.",
            "Use a flat route with location on, or a treadmill.",
        ).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", style = body(16.sp), color = HaanfColors.Text)
                BodyText(line)
            }
        }
    }
    Notice(SafetyRules.BENCHMARK_STOP_RULE)
}

