package app.haanf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.haanf.core.Clock
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.body
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Small building blocks used by the Lab tools and plain screens, drawn with the same
 * visual system as the designed screens.
 */

private val timeFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

fun formatTime(epochMs: Long): String = timeFormat.format(Instant.ofEpochMilli(epochMs))

fun formatTime(instant: Instant): String = timeFormat.format(instant)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, style = body(15.sp), color = HaanfColors.TextSecondary)
}

@Composable
fun ErrorText(text: String) {
    Text(text, style = body(15.sp, FontWeight.SemiBold), color = HaanfColors.WarningIcon)
}

@Composable
fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = body(15.sp), color = HaanfColors.TextSecondary)
        Text(value, style = body(15.sp, FontWeight.SemiBold), color = HaanfColors.Text, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
fun InfoCard(content: @Composable () -> Unit) {
    HCard(spacing = 4.dp) { content() }
}

@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = body(14.sp, FontWeight.SemiBold), color = HaanfColors.Text)
        Choices(options, selected, label, onSelect, enabled = enabled, columns = minOf(options.size, 3))
    }
}

/** The monotonic clock, refreshed once a second so that ages count up on screen. */
@Composable
fun rememberElapsedNow(clock: Clock): State<Long> = produceState(clock.elapsedMs(), clock) {
    while (true) {
        delay(1_000)
        value = clock.elapsedMs()
    }
}
