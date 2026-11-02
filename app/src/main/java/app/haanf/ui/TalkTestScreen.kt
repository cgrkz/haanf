package app.haanf.ui

import android.media.MediaPlayer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.data.TalkTestEntity
import app.haanf.session.LiveText
import app.haanf.talktest.AudioInput
import app.haanf.talktest.Effort
import app.haanf.talktest.RecorderState
import app.haanf.talktest.TalkTestRequest
import app.haanf.talktest.TalkTestScript
import app.haanf.talktest.TalkTestService
import app.haanf.talktest.TalkTestType
import app.haanf.talktest.TrainingActivity
import kotlinx.coroutines.launch

@Composable
fun TalkTestScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.recorder.state.collectAsStateWithLifecycle()
    val live by container.live.live.collectAsStateWithLifecycle()
    val tests by container.talkTestDao.observeAll().collectAsStateWithLifecycle(emptyList())

    var type by rememberSaveable { mutableStateOf(TalkTestType.COUNTING) }
    var effort by rememberSaveable { mutableStateOf(Effort.EASY) }
    var activity by rememberSaveable { mutableStateOf(TrainingActivity.OUTDOOR_RUN) }
    var input by rememberSaveable { mutableStateOf(AudioInput.PHONE_MIC) }
    var pendingDelete by remember { mutableStateOf<TalkTestEntity?>(null) }

    var permissionRequests by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRequests++
    }
    val micMissing = remember(permissionRequests) {
        AppPermissions.missing(context, AppPermissions.microphone).isNotEmpty()
    }

    val busy = state is RecorderState.Preparing || state is RecorderState.Recording
    val player = rememberPlayer()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    if (live.sessionId != null) {
                        "Live heart rate: ${LiveText.bpm(live)}"
                    } else {
                        "No live heart rate session, so heart rate will be left blank."
                    },
                )
                ChoiceRow("Test", TalkTestType.entries, type, !busy, { it.label }) { type = it }
                ChoiceRow("Effort", Effort.entries, effort, !busy, { it.label }) { effort = it }
                ChoiceRow("Activity", TrainingActivity.entries, activity, !busy, { it.label }) { activity = it }
                ChoiceRow("Input", AudioInput.entries, input, !busy, { it.label }) { input = it }

                InfoCard {
                    Text(TalkTestScript.instructions(type), style = MaterialTheme.typography.titleMedium)
                }

                when {
                    micMissing -> {
                        if (permissionRequests > 0) ErrorText("Microphone access was not granted.")
                        Button(
                            onClick = {
                                launcher.launch(
                                    (AppPermissions.microphone + AppPermissions.notifications).toTypedArray(),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Allow microphone")
                        }
                    }
                    busy -> Button(
                        onClick = { TalkTestService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop")
                    }
                    else -> Button(
                        onClick = {
                            TalkTestService.start(context, TalkTestRequest(type, effort, activity, input))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start")
                    }
                }

                when (val current = state) {
                    RecorderState.Idle -> Unit
                    RecorderState.Preparing -> Hint("Preparing the microphone")
                    is RecorderState.Recording -> {
                        Text("Recording ${current.elapsedMs / 1000}s from ${current.actualInput}")
                        LinearProgressIndicator(
                            progress = { current.peak },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is RecorderState.Saved -> {
                        Hint("Saved, ${current.test.durationMs / 1000}s from ${current.test.actualInput}.")
                        current.warning?.let { ErrorText(it) }
                    }
                    is RecorderState.Failed -> ErrorText(current.message)
                }

                SectionTitle("Recordings")
                Hint("Stored privately on this phone. Nothing leaves it unless you tap export.")
                if (tests.isEmpty()) {
                    Hint("No recordings yet.")
                } else {
                    OutlinedButton(onClick = { container.talkTests.export(tests) }) {
                        Text("Export all with labels")
                    }
                }
            }
        }
        items(tests, key = { it.id }) { test ->
            InfoCard {
                Text(
                    "${enumLabel(test.effort)} · ${enumLabel(test.testType)} · ${enumLabel(test.activity)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Fact("Recorded", formatTime(test.recordedAtMs))
                Fact("Length", "${test.durationMs / 1000}s")
                Fact("Input asked for", enumLabel(test.requestedInput))
                Fact("Input used", test.actualInput)
                Fact("Heart rate", "${test.hrStartBpm ?: "--"} to ${test.hrEndBpm ?: "--"} bpm")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { player.play(container.talkTests.audioFile(test.fileName).path) }) {
                        Text("Play")
                    }
                    OutlinedButton(onClick = { container.talkTests.export(listOf(test)) }) {
                        Text("Export")
                    }
                    OutlinedButton(onClick = { pendingDelete = test }) {
                        Text("Delete")
                    }
                }
            }
        }
    }

    pendingDelete?.let { test ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this recording?") },
            text = { Text("The audio will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.talkTests.delete(test) }
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

private fun enumLabel(name: String): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private class Playback {
    private var player: MediaPlayer? = null

    fun play(path: String) {
        release()
        player = MediaPlayer().apply {
            setDataSource(path)
            prepare()
            start()
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}

@Composable
private fun rememberPlayer(): Playback {
    val playback = remember { Playback() }
    DisposableEffect(playback) {
        onDispose { playback.release() }
    }
    return playback
}
