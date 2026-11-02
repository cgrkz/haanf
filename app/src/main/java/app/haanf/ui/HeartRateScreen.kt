package app.haanf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.HrSessionSummary
import app.haanf.export.Csv
import app.haanf.export.ShareFiles
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.SensorContact
import app.haanf.heartrate.SourceState
import app.haanf.heartrate.ble.ScanSnapshot
import app.haanf.heartrate.ble.SeenDevice
import app.haanf.session.HeartRateForegroundService
import app.haanf.session.LiveSnapshot
import app.haanf.session.LiveText
import kotlinx.coroutines.launch

@Composable
fun HeartRateScreen(container: AppContainer) {
    val live by container.live.live.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    when {
        live.sessionId != null -> LivePanel(container, live)
        picking -> DevicePicker(container, onClose = { picking = false })
        else -> SessionHistory(container, onScan = { picking = true })
    }
}

@Composable
private fun LivePanel(container: AppContainer, live: LiveSnapshot) {
    val context = LocalContext.current
    val now by rememberElapsedNow(container.clock)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            LiveText.bpm(live),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.testTag("bpm"),
        )
        InfoCard {
            Fact("Last reading", LiveText.age(live, now))
            Fact("Status", live.connection.label)
            Fact("Device", live.device?.name ?: live.device?.id.orEmpty())
            Fact("Workout on watch", if (live.watchWorkoutRunning) "Yes" else "No")
            Fact("Samples saved", live.sampleCount.toString())
            Fact("Dropouts", live.dropoutCount.toString())
        }
        if (live.inDropout) ErrorText("No readings are arriving. Haanf keeps trying to reconnect.")
        if (live.sensorContact == SensorContact.NOT_DETECTED) {
            ErrorText("The sensor reports no skin contact.")
        }
        (live.connection as? SourceState.Unsupported)?.let { unsupported ->
            ErrorText("This device does not share heart rate over the standard profile.")
            Hint("Services it offers: ${unsupported.offered.joinToString().ifEmpty { "none" }}")
            Hint("Check that sharing is on, on the watch, then stop and try again.")
        }
        Button(
            onClick = { HeartRateForegroundService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Stop session")
        }
        Hint("Recording continues with the screen off. Stop it here or from the notification.")
    }
}

@Composable
private fun SessionHistory(container: AppContainer, onScan: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by container.hrDao.observeSessions().collectAsStateWithLifecycle(emptyList())
    var pendingDelete by remember { mutableStateOf<HrSessionSummary?>(null) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan for a heart rate device")
                }
                SectionTitle("Saved sessions")
                if (sessions.isEmpty()) Hint("No sessions yet.")
            }
        }
        items(sessions, key = { it.session.id }) { summary ->
            InfoCard {
                val session = summary.session
                Text(session.deviceName ?: session.deviceId, style = MaterialTheme.typography.titleSmall)
                Fact("Started", formatTime(session.startedAtMs))
                Fact("Samples", summary.sampleCount.toString())
                Fact("Dropouts", summary.dropoutCount.toString())
                Fact("Workout on watch", if (session.watchWorkoutRunning) "Yes" else "No")
                session.endReason?.let { Fact("Ended by", it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            ShareFiles.clear(context)
                            val samples = container.hrDao.samples(session.id)
                            val events = container.hrDao.events(session.id)
                            val files = listOf(
                                ShareFiles.write(context, "haanf-hr-${session.id}-samples.csv", Csv.samples(samples)),
                                ShareFiles.write(context, "haanf-hr-${session.id}-events.csv", Csv.events(events)),
                            )
                            ShareFiles.send(context, files, "text/csv", "Export heart rate session")
                        }
                    }) {
                        Text("Export CSV")
                    }
                    OutlinedButton(onClick = { pendingDelete = summary }) {
                        Text("Delete")
                    }
                }
            }
        }
    }

    pendingDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this session?") },
            text = { Text("Its samples will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.hrDao.deleteSession(summary.session.id) }
                    pendingDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep")
                }
            },
        )
    }
}

@Composable
private fun DevicePicker(container: AppContainer, onClose: () -> Unit) {
    val context = LocalContext.current
    var permissionRequests by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequests++
    }
    val missing = remember(permissionRequests) { AppPermissions.missing(context, AppPermissions.bluetooth) }

    if (missing.isNotEmpty()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Haanf needs Bluetooth access to find your watch.")
            if (permissionRequests > 0) ErrorText("Bluetooth access was not granted.")
            Button(onClick = { launcher.launch((missing + AppPermissions.notifications).toTypedArray()) }) {
                Text("Allow Bluetooth")
            }
            TextButton(onClick = onClose) { Text("Back") }
        }
        return
    }

    var showAll by rememberSaveable { mutableStateOf(false) }
    var watchWorkout by rememberSaveable { mutableStateOf(false) }
    val scan = remember { container.discovery.scan() }
    val snapshot by scan.collectAsStateWithLifecycle(ScanSnapshot())
    val shown = if (showAll) {
        snapshot.devices
    } else {
        snapshot.devices.filter { it.advertisesHeartRate || (it.paired && it.radio != "classic") }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("Back") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showAll,
                        onClick = { showAll = false },
                        label = { Text("Heart rate") },
                    )
                    FilterChip(
                        selected = showAll,
                        onClick = { showAll = true },
                        label = { Text("All nearby (diagnostic)") },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Workout running on the watch")
                    Switch(checked = watchWorkout, onCheckedChange = { watchWorkout = it })
                }
                Hint("Pixel Watch 2: quick settings > Connected Fitness > Connect.")
                Hint("Amazfit: Zepp app > Device > General > Activity heart rate sharing.")
                Hint("A watch may allow one connection at a time. Close other fitness apps first.")
                snapshot.error?.let { ErrorText(it) }
                if (showAll) {
                    OutlinedButton(onClick = { exportScan(context, snapshot) }) {
                        Text("Export this list")
                    }
                }
                if (shown.isEmpty()) Hint("Scanning. Nothing found yet.")
            }
        }
        items(shown, key = { it.address }) { device ->
            DeviceCard(device, showDetails = showAll) {
                connect(context, device.toHeartRateDevice(), watchWorkout)
                onClose()
            }
        }
    }
}

private fun connect(context: android.content.Context, device: HeartRateDevice, watchWorkout: Boolean) {
    HeartRateForegroundService.start(context, device, watchWorkout)
}

private fun exportScan(context: android.content.Context, snapshot: ScanSnapshot) {
    val text = snapshot.devices.joinToString("\n") { device ->
        listOf(
            device.name ?: "(no name)",
            device.address,
            "rssi=${device.rssi ?: "n/a"}",
            "connectable=${device.connectable ?: "n/a"}",
            "paired=${device.paired}",
            "connected_to_phone=${device.connectedToPhone}",
            "radio=${device.radio}",
            "services=${device.services}",
            "service_data=${device.serviceData}",
            "manufacturers=${device.manufacturers}",
        ).joinToString(" | ")
    }
    ShareFiles.clear(context)
    val file = ShareFiles.write(context, "haanf-bluetooth-scan.txt", text)
    ShareFiles.send(context, listOf(file), "text/plain", "Export Bluetooth scan")
}

@Composable
private fun DeviceCard(device: SeenDevice, showDetails: Boolean, onConnect: () -> Unit) {
    InfoCard {
        Text(device.name ?: "(no name)", style = MaterialTheme.typography.titleSmall)
        Hint(device.address)
        if (device.advertisesHeartRate) Text("Advertises Heart Rate (0x180D)")
        if (device.paired) Hint("Paired with this phone")
        if (device.connectedToPhone) Hint("Connected to this phone")
        if (showDetails) {
            Hint("Signal: ${device.rssi?.let { "$it dBm" } ?: "not seen in this scan"}")
            Hint("Radio: ${device.radio}, connectable: ${device.connectable ?: "unknown"}")
            Hint("Services: ${device.services.joinToString().ifEmpty { "none advertised" }}")
            if (device.serviceData.isNotEmpty()) Hint("Service data: ${device.serviceData.joinToString()}")
            if (device.manufacturers.isNotEmpty()) Hint("Manufacturer: ${device.manufacturers.joinToString()}")
        }
        OutlinedButton(onClick = onConnect) {
            Text("Connect")
        }
    }
}
