package app.haanf.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import app.haanf.AppContainer
import app.haanf.export.ShareFiles
import app.haanf.health.HealthConnectReader
import app.haanf.health.HealthReport
import app.haanf.health.HealthSummary
import app.haanf.health.TypeReport
import app.haanf.health.TypeResult
import java.time.Instant

private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

@Composable
fun HealthDataScreen(container: AppContainer) {
    val context = LocalContext.current
    val health = container.health
    var status by remember { mutableIntStateOf(health.sdkStatus()) }
    var refreshes by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var missing by remember { mutableStateOf(health.permissions) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        refreshes++
    }

    LaunchedEffect(refreshes) {
        status = health.sdkStatus()
        if (status != HealthConnectClient.SDK_AVAILABLE) return@LaunchedEffect
        loading = true
        failure = null
        try {
            missing = health.permissions - health.grantedPermissions()
            report = if (missing.size < health.permissions.size) health.report(Instant.now()) else null
        } catch (e: Exception) {
            failure = e.message ?: "Health Connect could not be read"
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Health Connect", style = MaterialTheme.typography.titleLarge)
        Hint("Last ${HealthConnectReader.WINDOW_DAYS} days. Read only: Haanf never writes here.")

        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE ->
                ErrorText("Health Connect is not available on this phone.")
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                ErrorText("Health Connect needs to be installed or updated.")
                Button(onClick = {
                    val store = "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
                    context.startActivity(Intent(Intent.ACTION_VIEW, store.toUri()))
                }) {
                    Text("Open Play Store")
                }
                OutlinedButton(onClick = { refreshes++ }) { Text("Check again") }
            }
            else -> {
                if (missing.isNotEmpty()) {
                    Hint("${missing.size} of ${health.permissions.size} data types are not shared with Haanf.")
                    Button(
                        onClick = { launcher.launch(health.permissions) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose what to share")
                    }
                }
                OutlinedButton(onClick = { refreshes++ }, enabled = !loading) {
                    Text(if (loading) "Reading" else "Read again")
                }
                failure?.let { ErrorText(it) }
                report?.let { current ->
                    current.types.forEach { TypeCard(it) }
                    OutlinedButton(onClick = {
                        ShareFiles.clear(context)
                        val file = ShareFiles.write(context, "haanf-health-report.txt", HealthSummary.text(current))
                        ShareFiles.send(context, listOf(file), "text/plain", "Export Health Connect report")
                    }) {
                        Text("Export this report")
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeCard(type: TypeReport) {
    InfoCard {
        Text(type.label, style = MaterialTheme.typography.titleSmall)
        when (val result = type.result) {
            TypeResult.NotGranted -> Hint("Not shared with Haanf")
            is TypeResult.Error -> ErrorText("Could not read: ${result.message}")
            is TypeResult.Read -> {
                if (result.sources.isEmpty()) Hint("No data in this period")
                result.sources.forEach { source ->
                    Column(Modifier.padding(top = 4.dp)) {
                        Text(source.sourceName)
                        Fact("Records", source.recordCount.toString())
                        if (source.sampleCount != source.recordCount) {
                            Fact("Samples", source.sampleCount.toString())
                        }
                        Fact("Newest", formatTime(source.newest))
                        if (source.devices.isNotEmpty()) Hint("From: ${source.devices.joinToString()}")
                    }
                }
            }
        }
    }
}
