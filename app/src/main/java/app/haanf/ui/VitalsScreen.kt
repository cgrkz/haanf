package app.haanf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import app.haanf.AppContainer
import app.haanf.health.VitalTrend
import app.haanf.health.Vitals
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val dayFormat = DateTimeFormatter.ofPattern("d MMM")

private fun Double.oneDecimal(): String = "%.1f".format(this)
private fun Double.whole(): String = roundToInt().toString()

@Composable
fun VitalsScreen(container: AppContainer) {
    val health = container.health
    var refreshes by remember { mutableIntStateOf(0) }
    var vitals by remember { mutableStateOf<Vitals?>(null) }
    var missing by remember { mutableStateOf(emptySet<String>()) }
    var failure by remember { mutableStateOf<String?>(null) }
    val available = remember(refreshes) { health.sdkStatus() == HealthConnectClient.SDK_AVAILABLE }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        refreshes++
    }

    LaunchedEffect(refreshes) {
        if (!available) return@LaunchedEffect
        failure = null
        try {
            missing = health.permissions - health.grantedPermissions()
            vitals = health.vitals(Instant.now())
        } catch (e: Exception) {
            failure = e.message ?: "Health Connect could not be read"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vitals", style = MaterialTheme.typography.headlineMedium)
        Hint("From Health Connect, comparing the last 7 days with the 7 before.")
        if (!available) {
            ErrorText("Health Connect is not available. See the Lab tab.")
            return@Column
        }
        if (missing.isNotEmpty()) {
            Button(onClick = { launcher.launch(health.permissions) }) { Text("Choose what to share") }
        }
        failure?.let { ErrorText(it) }
        val current = vitals
        VitalCard("Resting heart rate", "bpm", current?.restingHeartRate) { it.whole() }
        VitalCard("HRV", "ms", current?.hrv) { it.whole() }
        VitalCard("Sleep", "hours", current?.sleepHours) { it.oneDecimal() }
        OutlinedButton(onClick = { refreshes++ }) { Text("Read again") }
    }
}

@Composable
private fun VitalCard(title: String, unit: String, trend: VitalTrend?, format: (Double) -> String) {
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (trend == null) {
            Hint("No data in the last 4 weeks.")
        } else {
            Text("${format(trend.latest.value)} $unit", style = MaterialTheme.typography.headlineSmall)
            Hint("Latest from ${dayFormat.format(trend.latest.date)}")
            trend.recentAverage?.let { Fact("Last 7 days", "${format(it)} $unit") }
            trend.previousAverage?.let { Fact("7 days before", "${format(it)} $unit") }
            if (trend.stale) ErrorText("No new data for ${trend.daysSinceLatest} days.")
        }
    }
}
