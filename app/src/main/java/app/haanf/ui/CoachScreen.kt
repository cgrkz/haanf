package app.haanf.ui

import android.Manifest
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.ai.AiProvider
import app.haanf.ai.AiSettings
import app.haanf.ai.AiSettingsStore
import app.haanf.ai.CheckIn
import app.haanf.ai.CoachTone
import app.haanf.ai.LlmEffort
import app.haanf.ai.LlmException
import app.haanf.ai.LlmItem
import app.haanf.ai.LlmRequest
import app.haanf.ai.PlanOutcome
import app.haanf.ai.ProposedWorkout
import app.haanf.data.CoachFactEntity
import app.haanf.data.CoachMessageEntity
import app.haanf.data.FactKind
import app.haanf.data.FactStatus
import app.haanf.talktest.TrainingActivity
import app.haanf.workout.AiWorkoutContext
import app.haanf.workout.WorkoutText
import kotlinx.coroutines.launch

private val MINUTE_CHOICES = listOf(15, 20, 30, 45, 60)
private val FEELINGS = listOf(1 to "Awful", 2 to "Poor", 3 to "OK", 4 to "Good", 5 to "Great")
private const val BENCHMARK_TITLE = "1-mile walk benchmark"

private fun String.isNotBlankKey() = trim().length >= MIN_KEY_LENGTH

private const val MIN_KEY_LENGTH = 20

@Composable
fun CoachScreen(container: AppContainer, onStart: (PendingWorkout) -> Unit) {
    val settings by container.aiSettings.settings.collectAsStateWithLifecycle()
    val ready = remember(settings) { container.llm.client() != null }
    var changing by rememberSaveable { mutableStateOf(false) }

    if (!ready || changing) {
        CoachSetup(container, settings) { changing = false }
    } else {
        CoachHome(container, settings, onStart) { changing = true }
    }
}

@Composable
private fun CoachSetup(container: AppContainer, existing: AiSettings?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by rememberSaveable { mutableStateOf(existing?.provider ?: AiProvider.OPENROUTER) }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: provider.defaultModel) }
    var tone by rememberSaveable { mutableStateOf(existing?.tone ?: CoachTone.WARM) }
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up your coach", style = MaterialTheme.typography.headlineMedium)
        Text("The coach plans workouts, talks you through them and reviews them. It uses your own API key.")
        ChoiceRow("Provider", AiProvider.entries, provider, !checking, { it.label }) {
            provider = it
            model = it.defaultModel
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key (${provider.keyHint})") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceRow("Coach tone", CoachTone.entries, tone, !checking, { it.label }) { tone = it }
        Hint(tone.description)
        InfoCard {
            Text("What the AI receives", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your age, sex and weight, injuries and conditions, workouts and their heart rate " +
                    "summaries, benchmarks, resting heart rate, HRV and sleep, your chat, and how you " +
                    "feel. It goes to ${provider.label} using your key, under their privacy terms.",
            )
            Text("It never receives your location, your voice, or your device details.")
            Hint("Your key is encrypted on this phone. Calls cost you money on your own account.")
        }
        problem?.let { ErrorText(it) }
        Button(
            enabled = !checking && key.isNotBlankKey() && model.isNotBlank(),
            onClick = {
                val chosen = AiSettings(provider, model.trim(), tone)
                checking = true
                problem = null
                scope.launch {
                    try {
                        val client = AiSettingsStore.build(chosen, key.trim())
                        client.send(LlmRequest("Reply with OK.", listOf(LlmItem.User("OK?")), effort = LlmEffort.LOW))
                        container.aiSettings.save(chosen, key.trim())
                        key = ""
                        onDone()
                    } catch (e: LlmException) {
                        problem = e.failure.userMessage
                    }
                    checking = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (checking) "Checking your key" else "Agree and connect")
        }
        if (existing != null) TextButton(onClick = onDone) { Text("Back") }
    }
}

@Composable
private fun CoachHome(
    container: AppContainer,
    settings: AiSettings?,
    onStart: (PendingWorkout) -> Unit,
    onChangeKey: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by remember { container.coachDao.observeMessages() }.collectAsStateWithLifecycle(emptyList())
    val facts by remember { container.coachDao.observeFacts() }.collectAsStateWithLifecycle(emptyList())
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(emptyList())
    val listening by container.speech.listening.collectAsStateWithLifecycle()
    val list = rememberLazyListState()

    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var chatProblem by remember { mutableStateOf<String?>(null) }
    var micRequests by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micRequests++ }
    val micMissing = remember(micRequests) { AppPermissions.missing(context, AppPermissions.microphone).isNotEmpty() }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Coach", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
            item {
                if (benchmarks.isEmpty()) {
                    InfoCard {
                        Text("Do your first benchmark on Today, so the coach knows where you are starting from.")
                    }
                } else {
                    PlanCard(container, onStart)
                }
            }
            item { FactsCard(container, facts) }
            item { SettingsCard(container, settings, onChangeKey) }
            item { SectionTitle("Your training log") }
            if (messages.isEmpty()) {
                item { Hint("Tell the coach anything: a sore knee, a bad night, a goal. It remembers what matters.") }
            }
            items(messages, key = { it.id }) { ChatBubble(it) }
            if (sending) item { Hint("The coach is replying") }
        }
        chatProblem?.let {
            Column(Modifier.padding(horizontal = 16.dp)) { ErrorText(it) }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message your coach") },
                modifier = Modifier.weight(1f),
            )
            if (container.speech.available) {
                OutlinedButton(onClick = {
                    if (micMissing) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        container.speech.listen({ draft = it }, { chatProblem = it })
                    }
                }) {
                    Text(if (listening) "…" else "🎙")
                }
            }
            Button(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    sending = true
                    chatProblem = null
                    scope.launch {
                        chatProblem = container.chat.send(text)
                        sending = false
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ChatBubble(message: CoachMessageEntity) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromCoach) Arrangement.Start else Arrangement.End,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.fromCoach) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ),
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH),
        ) {
            Text(message.text, modifier = Modifier.padding(10.dp))
        }
    }
}

private const val BUBBLE_WIDTH = 0.85f

@Composable
private fun PlanCard(container: AppContainer, onStart: (PendingWorkout) -> Unit) {
    val scope = rememberCoroutineScope()
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    var feeling by rememberSaveable { mutableIntStateOf(3) }
    var activity by rememberSaveable { mutableStateOf(TrainingActivity.OUTDOOR_RUN) }
    var note by rememberSaveable { mutableStateOf("") }
    var planning by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<PlanOutcome?>(null) }

    InfoCard {
        Text("Plan today's workout", style = MaterialTheme.typography.titleMedium)
        ChoiceRow("Time you have", MINUTE_CHOICES, minutes, !planning, { "$it min" }) { minutes = it }
        ChoiceRow("How you feel", FEELINGS.map { it.first }, feeling, !planning, { value ->
            FEELINGS.first { it.first == value }.second
        }) { feeling = it }
        ChoiceRow("Activity", TrainingActivity.entries, activity, !planning, { it.label }) { activity = it }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Anything else? (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !planning,
            onClick = {
                planning = true
                outcome = null
                scope.launch {
                    outcome = container.planner.plan(CheckIn(minutes, feeling, note.trim(), activity))
                    planning = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (planning) "Planning" else "Plan my workout")
        }
        if (planning) CircularProgressIndicator()

        when (val current = outcome) {
            null -> Unit
            is PlanOutcome.Failed -> ErrorText(current.message)
            is PlanOutcome.Benchmark -> {
                Text("A benchmark is due", style = MaterialTheme.typography.titleSmall)
                Text(current.reason)
                OutlinedButton(onClick = {
                    onStart(PendingWorkout(BENCHMARK_TITLE, null, null, benchmark = true))
                }) {
                    Text("Start benchmark")
                }
            }
            is PlanOutcome.Approved -> ProposedCard(current.workout, null) { start(it, minutes, feeling, onStart) }
            is PlanOutcome.Fallback -> ProposedCard(
                current.workout,
                "The coach could not plan a workout that passed the safety rules, so here is a simple one.",
            ) { start(it, minutes, feeling, onStart) }
        }
    }
}

private fun start(workout: ProposedWorkout, minutes: Int, feeling: Int, onStart: (PendingWorkout) -> Unit) {
    onStart(
        PendingWorkout(
            title = workout.title,
            plan = workout.toPlan(),
            slot = null,
            ai = AiWorkoutContext(minutes, feeling),
            hard = workout.isHard,
        ),
    )
}

@Composable
private fun ProposedCard(workout: ProposedWorkout, notice: String?, onStart: (ProposedWorkout) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notice?.let { Hint(it) }
        Text(workout.title, style = MaterialTheme.typography.titleLarge)
        Text(workout.reason)
        workout.steps.forEach { step ->
            Fact(step.label, "${WorkoutText.minutes((step.minutes * 60).toInt())}, ${step.target.label.lowercase()}")
        }
        Button(onClick = { onStart(workout) }, modifier = Modifier.fillMaxWidth()) {
            Text("Start this workout")
        }
    }
}

@Composable
private fun FactsCard(container: AppContainer, facts: List<CoachFactEntity>) {
    val scope = rememberCoroutineScope()
    val proposed = facts.filter { it.status == FactStatus.PROPOSED.name }
    val active = facts.filter { it.status == FactStatus.ACTIVE.name }
    if (proposed.isEmpty() && active.isEmpty()) return

    fun update(fact: CoachFactEntity, status: FactStatus) {
        scope.launch { container.coachDao.updateFact(fact.copy(status = status.name)) }
    }

    InfoCard {
        Text("What the coach knows", style = MaterialTheme.typography.titleMedium)
        proposed.forEach { fact ->
            Text("Remember this? ${fact.text}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { update(fact, FactStatus.ACTIVE) }) { Text("Remember") }
                OutlinedButton(onClick = { scope.launch { container.coachDao.deleteFact(fact.id) } }) { Text("Dismiss") }
            }
        }
        active.forEach { fact ->
            val kind = FactKind.entries.firstOrNull { it.name == fact.kind }?.label ?: fact.kind
            Text("$kind: ${fact.text}")
            TextButton(onClick = { update(fact, FactStatus.RESOLVED) }) { Text("Mark resolved") }
        }
    }
}

@Composable
private fun SettingsCard(container: AppContainer, settings: AiSettings?, onChangeKey: () -> Unit) {
    var removing by remember { mutableStateOf(false) }
    val current = settings ?: return
    InfoCard {
        Text("Coach settings", style = MaterialTheme.typography.titleMedium)
        Hint("${current.provider.label}, ${current.model}")
        ChoiceRow("Tone", CoachTone.entries, current.tone, true, { it.label }) { container.aiSettings.updateTone(it) }
        Hint(current.tone.description)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChangeKey) { Text("Change key or model") }
            TextButton(onClick = { removing = true }) { Text("Remove key") }
        }
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove your key?") },
            text = { Text("AI features stop. Your workouts, chat and everything else stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    container.aiSettings.clear()
                    removing = false
                }) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep") } },
        )
    }
}
