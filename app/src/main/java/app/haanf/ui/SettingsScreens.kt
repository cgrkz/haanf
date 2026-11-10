package app.haanf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.ai.CoachTone
import app.haanf.data.ProfileEntity
import app.haanf.export.Csv
import app.haanf.export.ShareFiles
import app.haanf.training.BikeType
import app.haanf.training.Sex
import app.haanf.training.Zone
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.HaanfIcons
import app.haanf.ui.theme.body
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val settings by container.aiSettings.settings.collectAsStateWithLifecycle()
    val vitals = rememberVitals(container)
    var editing by rememberSaveable { mutableStateOf(false) }
    var removingKey by remember { mutableStateOf(false) }
    val current = profile
    if (current === LOADING) return
    val today = container.programs.today()

    SubScreen(onBack = { nav.back() }) {
        ScreenTitle("Settings")

        SettingsSection("About you") {
            if (editing || current == null) {
                ProfileEditor(current) { updated ->
                    scope.launch { container.trainingDao.saveProfile(updated) }
                    editing = false
                }
            } else {
                Fact("Year of birth", "${current.birthYear}")
                Fact("Weight", current.weightKg?.let { "%.0f kg".format(it) } ?: "Not set")
                Fact("Sex, for fitness formulas", current.sex?.let { Sex.valueOf(it).label } ?: "Not set")
                Fact("Measured maximum", current.measuredMaxBpm?.let { "$it bpm" } ?: "Not measured")
                LinkButton("Edit", onClick = { editing = true })
                Hint("Injuries and conditions are kept in the coach chat, under What the coach knows.")
            }
        }

        current?.let { saved ->
            SettingsSection("Heart rate zones") {
                val zones = saved.zones(today)
                val source = if (saved.measuredMaxBpm != null) "measured" else "estimated from your age"
                Hint("Maximum ${zones.maxBpm} bpm, $source.")
                Zone.entries.forEach { zone ->
                    val range = zones.rangeFor(zone)
                    val text = when (zone) {
                        Zone.RECOVERY -> "under ${range.last + 1} bpm"
                        Zone.VERY_HARD -> "${range.first} bpm and over"
                        else -> "${range.first} to ${range.last} bpm"
                    }
                    ZoneKey(zone, zone.label, text)
                }
            }
        }

        SettingsSection("Your watch") {
            BodyText(current?.deviceName ?: current?.deviceId?.let { "Unnamed device" } ?: "No watch connected")
            LinkButton(if (current?.deviceId == null) "Connect a watch" else "Change device", onClick = { nav.open(Dest.Devices) })
        }

        current?.let { saved ->
            SettingsSection("Your bike") {
                val bike = saved.bikeType?.let { name -> BikeType.entries.firstOrNull { it.name == name } }
                Choices(BikeType.entries, bike, { it.label }, { chosen ->
                    scope.launch { container.trainingDao.saveProfile(saved.copy(bikeType = chosen.name)) }
                })
                Hint("Used to estimate watts on rides: a heavier, more upright bike takes more power at the same speed.")
            }
        }

        SettingsSection("Coach") {
            val chosen = settings
            if (chosen == null) {
                Hint("No key set up. Everything else in Haanf works without one.")
                LinkButton("Set up your coach", onClick = { nav.open(Dest.CoachSetup) })
            } else {
                Fact("Provider", chosen.provider.label)
                Fact("Model", chosen.model)
                Text("Tone", style = body(14.sp, FontWeight.SemiBold), color = HaanfColors.Text, modifier = Modifier.padding(top = 8.dp))
                Choices(CoachTone.entries, chosen.tone, { it.label }, { container.aiSettings.updateTone(it) }, height = 56.dp)
                Hint(chosen.tone.description)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LinkButton("Change key or model", onClick = { nav.open(Dest.CoachSetup) })
                    LinkButton("Remove key", onClick = { removingKey = true })
                }
            }
        }

        SettingsSection("Health Connect") {
            Hint(
                when (vitals.status) {
                    VitalsStatus.UNAVAILABLE -> "Health Connect isn't available on this phone."
                    VitalsStatus.NOT_SHARED -> "Nothing shared yet. Haanf only reads, and never writes."
                    else -> "Haanf reads resting heart rate, HRV, sleep and exercise. It never writes."
                },
            )
            if (vitals.status != VitalsStatus.UNAVAILABLE) LinkButton("Choose what to share", onClick = vitals.askToShare)
        }

        SettingsSection("Export") {
            Hint("A CSV of your workouts, through the share sheet. Nothing leaves the phone unless you send it.")
            LinkButton("Export workouts", onClick = {
                scope.launch {
                    val workouts = container.trainingDao.recentWorkouts(Int.MAX_VALUE)
                    ShareFiles.clear(context)
                    val file = ShareFiles.write(context, "haanf-workouts.csv", Csv.workouts(workouts))
                    ShareFiles.send(context, listOf(file), "text/csv", "Export workouts")
                }
            })
        }

        SettingsSection("Lab") {
            Hint("Tools for checking a new phone or watch.")
            LabTool.entries.forEachIndexed { index, tool ->
                ListRow(
                    modifier = Modifier.clickable { nav.open(Dest.Lab(tool)) },
                    start = { BodyText(tool.title) },
                    end = { Icon(HaanfIcons.Forward, contentDescription = null, tint = HaanfColors.TextSecondary) },
                    divider = index < LabTool.entries.lastIndex,
                )
            }
        }

        SettingsSection("About") {
            Hint("Type is Barlow and Barlow Condensed, under the SIL Open Font License 1.1, bundled with the app.")
        }
    }

    if (removingKey) {
        HDialog(
            title = "Remove your key?",
            text = "AI features stop. Your workouts, chat and everything else stay on this phone.",
            confirm = "Remove",
            onConfirm = {
                removingKey = false
                container.aiSettings.clear()
            },
            dismiss = "Keep",
            onDismiss = { removingKey = false },
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    HCard(spacing = 6.dp) {
        Text(title, style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun ProfileEditor(existing: ProfileEntity?, onSave: (ProfileEntity) -> Unit) {
    var birthYear by rememberSaveable { mutableStateOf(existing?.birthYear?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(existing?.weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(existing?.sex?.let(Sex::valueOf)) }
    var measuredMax by rememberSaveable { mutableStateOf(existing?.measuredMaxBpm?.toString().orEmpty()) }
    val year = birthYear.toIntOrNull()
    val kilograms = weight.toDoubleOrNull()
    val max = measuredMax.toIntOrNull()
    val chosenSex = sex
    val valid = year != null && year in 1920..LocalDate.now().year - 13 &&
        kilograms != null && kilograms in 30.0..250.0 && chosenSex != null &&
        (measuredMax.isBlank() || (max != null && max in 100..230))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HTextField(birthYear, { birthYear = it.filter(Char::isDigit).take(4) }, "Year of birth", Modifier.weight(1f), numeric = true)
            HTextField(weight, { input -> weight = input.filter { it.isDigit() || it == '.' }.take(5) }, "Weight in kg", Modifier.weight(1f), numeric = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sex, for fitness formulas", style = body(14.sp, FontWeight.SemiBold), color = HaanfColors.Text)
            Choices(listOf(Sex.FEMALE, Sex.MALE), sex, { it.label }, { sex = it })
        }
        HTextField(
            measuredMax,
            { measuredMax = it.filter(Char::isDigit).take(3) },
            "Measured maximum heart rate (optional)",
            numeric = true,
            placeholder = "Leave empty to estimate from age",
        )
        PrimaryButton("Save", enabled = valid, onClick = {
            onSave(
                (existing ?: ProfileEntity(birthYear = requireNotNull(year))).copy(
                    birthYear = requireNotNull(year),
                    weightKg = requireNotNull(kilograms),
                    sex = requireNotNull(chosenSex).name,
                    measuredMaxBpm = max,
                ),
            )
        })
    }
}

@Composable
fun DevicesScreen(container: AppContainer, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val current = profile
    if (current === LOADING) return
    DevicePicker(
        container = container,
        showWatchWorkoutSwitch = false,
        onClose = { nav.back() },
        onPick = { picked, _ ->
            scope.launch {
                val base = current ?: return@launch
                container.trainingDao.saveProfile(
                    base.copy(deviceId = picked.id, deviceName = picked.name, deviceSourceKind = picked.sourceKind),
                )
            }
            nav.back()
        },
    )
}

/** The Phase 0 tools, kept for diagnosing new phones and wearables. */
@Composable
fun LabScreen(container: AppContainer, nav: Navigator, tool: LabTool) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { nav.back() }
            Box(Modifier.offset(x = 4.dp)) { ScreenTitle(tool.title, size = 32) }
        }
        when (tool) {
            LabTool.HEART_RATE -> HeartRateScreen(container)
            LabTool.TALK_TEST -> TalkTestScreen(container)
            LabTool.HEALTH_DATA -> HealthDataScreen(container)
        }
    }
}
