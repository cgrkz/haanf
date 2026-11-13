package app.haanf.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.Features
import app.haanf.ai.AiProvider
import app.haanf.ai.AiSettings
import app.haanf.ai.AiSettingsStore
import app.haanf.ai.CheckIn
import app.haanf.ai.CoachTone
import app.haanf.ai.Feel
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
import app.haanf.health.Vitals
import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.HaanfIcons
import app.haanf.ui.theme.body
import app.haanf.workout.AiWorkoutContext
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val MIN_KEY_LENGTH = 20
private const val OTHER_MINUTES = -1
private val MINUTE_CHOICES = listOf(20, 30, 45, 60, OTHER_MINUTES)
private val ACTIVITY_LABELS = mapOf(
    TrainingActivity.OUTDOOR_RUN to "Run",
    TrainingActivity.CYCLING to "Cycle",
    TrainingActivity.TREADMILL to "Treadmill",
)

/**
 * The Coach tab's check-in and recommendation. It lives above the tabs so that a plan
 * survives a trip to Get ready and back, and a planner call keeps running off screen.
 */
class CoachTabState(val scope: CoroutineScope) {
    var minutes by mutableIntStateOf(30)
    var otherMinutes by mutableStateOf("")
    var feel by mutableStateOf(Feel.OK)
    var activity by mutableStateOf(TrainingActivity.OUTDOOR_RUN)
    var note by mutableStateOf("")
    var outcome by mutableStateOf<PlanOutcome?>(null)
    var checkIn by mutableStateOf<CheckIn?>(null)
    var working by mutableStateOf<String?>(null)
    var problem by mutableStateOf<String?>(null)
    var chatSending by mutableStateOf(false)
    var chatProblem by mutableStateOf<String?>(null)

    val chosenMinutes: Int?
        get() = if (minutes == OTHER_MINUTES) otherMinutes.toIntOrNull()?.takeIf { it in 5..240 } else minutes

    fun find(container: AppContainer) {
        val chosen = chosenMinutes ?: return
        val request = CheckIn(chosen, feel.score, note.trim(), activity)
        checkIn = request
        working = "Finding your workout"
        problem = null
        scope.launch {
            outcome = container.planner.plan(request)
            working = null
        }
    }

    /** Make it easier, or something else. Both go back through the planner and the rule check. */
    fun vary(container: AppContainer, previous: ProposedWorkout, easier: Boolean) {
        val request = checkIn ?: return
        working = if (easier) "Making it easier" else "Finding something else"
        problem = null
        scope.launch {
            when (val next = container.planner.plan(request, previous = previous, easier = easier)) {
                is PlanOutcome.Failed -> problem = next.message
                else -> outcome = next
            }
            working = null
        }
    }

    fun send(container: AppContainer, text: String) {
        chatSending = true
        chatProblem = null
        scope.launch {
            chatProblem = container.chat.send(text)
            chatSending = false
        }
    }
}

@Composable
fun CoachTab(container: AppContainer, nav: Navigator, state: CoachTabState, vitals: VitalsHolder? = null) {
    val settings by container.aiSettings.settings.collectAsStateWithLifecycle()
    val ready = remember(settings) { container.llm.client() != null }
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(null)
    val scored = benchmarks ?: return

    when {
        !ready -> CoachSetupScreen(
            container,
            onDone = {},
            onBack = null,
            onFreeWorkout = { nav.open(Dest.GetReady(freeWorkout())) },
        )
        Features.BENCHMARK && scored.isEmpty() -> CoachNeedsBenchmark(nav)
        else -> when (val outcome = state.outcome) {
            is PlanOutcome.Approved -> PlanScreen(container, nav, state, outcome.workout, fallback = false)
            is PlanOutcome.Fallback -> PlanScreen(container, nav, state, outcome.workout, fallback = true)
            else -> CheckInScreen(container, nav, state, vitals ?: rememberVitals(container))
        }
    }
}

@Composable
private fun CoachHeader(title: String, nav: Navigator) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        ScreenTitle(title, modifier = Modifier.weight(1f))
        Row(Modifier.padding(start = 8.dp)) {
            HIconButton(HaanfIcons.Settings, "Coach settings", onClick = { nav.open(Dest.CoachSetup) })
            HIconButton(HaanfIcons.Coach, "Chat with your coach", onClick = { nav.open(Dest.Chat) })
        }
    }
}

@Composable
private fun CoachNeedsBenchmark(nav: Navigator) {
    HScreen(spacing = 20.dp) {
        CoachHeader("Let's find today's workout", nav)
        Notice(
            "Do your first benchmark, so the coach knows where you're starting from.",
            kind = NoticeKind.INFO,
            icon = HaanfIcons.Info,
        )
        BottomActions {
            PrimaryButton("Start benchmark", onClick = { nav.open(Dest.GetReady(benchmarkWorkout())) })
            LinkButton("Start a free workout", onClick = { nav.open(Dest.GetReady(freeWorkout())) })
        }
    }
}

fun freeWorkout() = PendingWorkout(FREE_WORKOUT_TITLE, null, null, eyebrow = "No plan, any length")

@Composable
private fun CheckInScreen(container: AppContainer, nav: Navigator, state: CoachTabState, vitals: VitalsHolder) {
    HScreen(spacing = 20.dp) {
        CoachHeader("Let's find today's workout", nav)

        (state.outcome as? PlanOutcome.Benchmark)?.let { due ->
            HCard(padding = PaddingValues(20.dp), spacing = 8.dp) {
                Text("A benchmark is due", style = body(18.sp, FontWeight.Bold), color = HaanfColors.Text)
                BodyText(due.reason)
                PrimaryButton("Start benchmark", onClick = { nav.open(Dest.GetReady(benchmarkWorkout())) })
                LinkButton("Not today", onClick = { state.outcome = null })
            }
        }

        LabeledChoices("How much time do you have?") {
            Choices(
                MINUTE_CHOICES,
                state.minutes,
                { if (it == OTHER_MINUTES) "Other" else "$it" },
                { state.minutes = it },
                enabled = state.working == null,
                height = 52.dp,
            )
            if (state.minutes == OTHER_MINUTES) {
                HTextField(
                    state.otherMinutes,
                    { state.otherMinutes = it.filter(Char::isDigit).take(3) },
                    "Minutes you have",
                    numeric = true,
                    placeholder = "For example, 25",
                )
            }
        }
        LabeledChoices("How do you feel?") {
            Choices(Feel.entries, state.feel, { it.label }, { state.feel = it }, enabled = state.working == null, columns = 3)
        }
        LabeledChoices("What are you doing?") {
            Choices(
                TrainingActivity.entries,
                state.activity,
                { ACTIVITY_LABELS.getValue(it) },
                { state.activity = it },
                enabled = state.working == null,
            )
        }
        HTextField(
            state.note,
            { state.note = it },
            "Anything else? (optional)",
            placeholder = "For example, legs are heavy",
            singleLine = false,
        )
        LastNight(vitals.vitals, vitals.status, container.programs.today())

        (state.outcome as? PlanOutcome.Failed)?.let { Notice(it.message) }
        state.problem?.let { Notice(it) }

        BottomActions {
            if (!state.feel.canTrain) {
                Text(
                    "Rest is the right call today. Haanf won't suggest a workout while you're unwell.",
                    style = body(16.sp),
                    color = HaanfColors.Text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Corner)
                        .background(HaanfColors.WarningBackground)
                        .padding(16.dp),
                )
            } else {
                PrimaryButton(
                    state.working ?: "Find my workout",
                    onClick = { state.find(container) },
                    enabled = state.working == null && state.chosenMinutes != null,
                )
            }
            LinkButton(
                "Start a free workout",
                onClick = { nav.open(Dest.GetReady(freeWorkout())) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** Last night's recovery, with the dates the data is from. Haanf can't see when the watch synced. */
@Composable
private fun LastNight(vitals: Vitals?, status: VitalsStatus, today: LocalDate) {
    val sleep = vitals?.sleepHours?.latest
    val resting = vitals?.restingHeartRate?.latest
    val title = if (sleep?.date == today) "Last night" else "Recovery"
    val text = when {
        status == VitalsStatus.UNAVAILABLE -> "Health Connect isn't available, so the coach can't see your sleep."
        status == VitalsStatus.NOT_SHARED -> "Share sleep and resting heart rate in Settings, so the coach can use them."
        sleep == null && resting == null -> "No sleep or resting heart rate in the last 4 weeks."
        else -> buildString {
            val parts = listOfNotNull(
                sleep?.let { "${Format.hoursAndMinutes(it.value)} sleep" },
                resting?.let { "resting heart rate ${it.value.roundToInt()}" },
            )
            append(parts.joinToString(", ").replaceFirstChar(Char::uppercase)).append(". ")
            if (sleep != null && resting != null && sleep.date == resting.date) {
                append("From ${Format.relativeDay(sleep.date, today)}.")
            } else {
                val dates = listOfNotNull(
                    sleep?.let { "sleep from ${Format.relativeDay(it.date, today)}" },
                    resting?.let { "resting heart rate from ${Format.relativeDay(it.date, today)}" },
                )
                append(dates.joinToString(", ").replaceFirstChar(Char::uppercase)).append('.')
            }
        }
    }
    HCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), spacing = 2.dp) {
        Text(title, style = body(15.sp, FontWeight.Bold), color = HaanfColors.Text)
        Text(text, style = body(15.sp), color = HaanfColors.Text)
    }
}

@Composable
private fun PlanScreen(
    container: AppContainer,
    nav: Navigator,
    state: CoachTabState,
    workout: ProposedWorkout,
    fallback: Boolean,
) {
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val current = profile
    if (current === LOADING) return
    val zones = current?.zones(container.programs.today())
    var question by rememberSaveable { mutableStateOf("") }
    val busy = state.working != null

    HScreen {
        Column {
            Eyebrow("Your workout today")
            ScreenTitle(workout.title, modifier = Modifier.padding(top = 4.dp))
        }
        if (fallback) {
            Notice("The coach couldn't plan a workout that passed your safety rules, so here's a simple one.")
        }
        Text(workout.reason, style = body(18.sp), color = HaanfColors.Text)
        ProposedSteps(workout, zones)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(HaanfIcons.Check, contentDescription = null, tint = HaanfColors.Text, modifier = Modifier.size(20.dp))
            Text("Checked against your safety rules", style = body(15.sp), color = HaanfColors.Text)
        }
        state.problem?.let { Notice(it) }
        BottomActions {
            PrimaryButton(
                state.working ?: "Start workout",
                enabled = !busy,
                onClick = {
                    val checkIn = state.checkIn
                    nav.open(
                        Dest.GetReady(
                            PendingWorkout(
                                title = workout.title,
                                plan = workout.toPlan(),
                                slot = null,
                                ai = checkIn?.let { AiWorkoutContext(it.minutes, it.feeling) },
                                hard = workout.isHard,
                                eyebrow = "Coach workout",
                            ),
                        ),
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    "Make it easier",
                    onClick = { state.vary(container, workout, easier = true) },
                    enabled = !busy,
                    height = 48.dp,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    "Something else",
                    onClick = { state.vary(container, workout, easier = false) },
                    enabled = !busy,
                    height = 48.dp,
                    modifier = Modifier.weight(1f),
                )
            }
            HTextField(
                question,
                { question = it },
                "Ask your coach",
                placeholder = "Can we go a bit longer?",
                trailing = {
                    HIconButton(HaanfIcons.Send, "Send to your coach", onClick = {
                        val text = question.trim()
                        if (text.isNotEmpty() && !state.chatSending) {
                            question = ""
                            state.send(container, text)
                            nav.open(Dest.Chat)
                        }
                    })
                },
            )
            LinkButton("Change my answers", onClick = {
                state.outcome = null
                state.problem = null
            })
        }
    }
}

@Composable
private fun ProposedSteps(workout: ProposedWorkout, zones: HeartRateZones?) {
    val description = workout.steps.joinToString(", ") {
        "${it.minutes.roundToInt()} minutes in ${it.target.label}"
    }
    HCard(spacing = 0.dp) {
        ZoneBar(workout.steps.map { it.target to it.minutes.toFloat() }, description = description)
        Box(Modifier.padding(top = 12.dp))
        workout.steps.forEach { step ->
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(step.label, style = body(16.sp), color = HaanfColors.Text)
                    Text(
                        zones?.target(step.target) ?: step.target.label,
                        style = body(14.sp),
                        color = HaanfColors.TextSecondary,
                    )
                }
                Text(Format.minutes((step.minutes * 60).roundToInt()), style = body(16.sp, FontWeight.SemiBold), color = HaanfColors.Text)
            }
        }
    }
}

@Composable
fun ChatScreen(container: AppContainer, nav: Navigator, state: CoachTabState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by remember { container.coachDao.observeMessages() }.collectAsStateWithLifecycle(emptyList())
    val facts by remember { container.coachDao.observeFacts() }.collectAsStateWithLifecycle(emptyList())
    val listening by container.speech.listening.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    var micRequests by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micRequests++ }
    val micMissing = remember(micRequests) { AppPermissions.missing(context, AppPermissions.microphone).isNotEmpty() }

    LaunchedEffect(messages.size, state.chatSending) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    fun update(fact: CoachFactEntity, status: FactStatus) {
        scope.launch { container.coachDao.updateFact(fact.copy(status = status.name)) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton { nav.back() }
            ScreenTitle("Chat", size = 36)
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item { KnownFacts(facts, onUpdate = ::update, onDismiss = { scope.launch { container.coachDao.deleteFact(it.id) } }) }
            if (messages.isEmpty()) {
                item { Hint("Tell the coach anything: a sore knee, a bad night, a goal. It remembers what matters.") }
            }
            items(messages, key = { it.id }) { ChatBubble(it) }
            if (state.chatSending) item { Hint("The coach is replying") }
        }
        state.chatProblem?.let { Box(Modifier.padding(horizontal = 24.dp)) { Notice(it) } }
        HorizontalDivider(color = HaanfColors.Divider)
        Row(
            Modifier.fillMaxWidth().background(HaanfColors.Card).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message your coach", style = body(16.sp), color = HaanfColors.TextSecondary) },
                    textStyle = body(16.sp),
                    shape = Corner,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = HaanfColors.InputBorder,
                        focusedBorderColor = HaanfColors.Primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (container.speech.available) {
                HIconButton(
                    HaanfIcons.Mic,
                    if (listening) "Listening" else "Speak a message",
                    tint = if (listening) HaanfColors.Primary else HaanfColors.Text,
                    onClick = {
                        if (micMissing) {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            container.speech.listen({ draft = it }, { state.chatProblem = it })
                        }
                    },
                )
            }
            HIconButton(
                HaanfIcons.Send,
                "Send",
                tint = if (draft.isNotBlank() && !state.chatSending) HaanfColors.Primary else HaanfColors.InputBorder,
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty() && !state.chatSending) {
                        draft = ""
                        state.send(container, text)
                    }
                },
            )
        }
    }
}

@Composable
private fun KnownFacts(
    facts: List<CoachFactEntity>,
    onUpdate: (CoachFactEntity, FactStatus) -> Unit,
    onDismiss: (CoachFactEntity) -> Unit,
) {
    val proposed = facts.filter { it.status == FactStatus.PROPOSED.name }
    val active = facts.filter { it.status == FactStatus.ACTIVE.name }
    HCard(spacing = 8.dp) {
        Text("What the coach knows", style = body(17.sp, FontWeight.Bold), color = HaanfColors.Text)
        if (proposed.isEmpty() && active.isEmpty()) {
            Hint("Nothing yet. Injuries, conditions and notes you agree to save appear here.")
        }
        proposed.forEach { fact ->
            BodyText("Remember this? ${fact.text}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Remember", onClick = { onUpdate(fact, FactStatus.ACTIVE) }, height = 48.dp, modifier = Modifier.weight(1f))
                SecondaryButton("Dismiss", onClick = { onDismiss(fact) }, height = 48.dp, modifier = Modifier.weight(1f))
            }
        }
        active.forEach { fact ->
            val kind = FactKind.entries.firstOrNull { it.name == fact.kind }?.label ?: fact.kind
            ListRow(
                start = { BodyText("$kind: ${fact.text}", size = 15) },
                end = { LinkButton("Mark resolved", onClick = { onUpdate(fact, FactStatus.RESOLVED) }) },
                divider = fact != active.last(),
            )
        }
    }
}

@Composable
private fun ChatBubble(message: CoachMessageEntity) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromCoach) Arrangement.Start else Arrangement.End,
    ) {
        Text(
            message.text,
            style = body(16.sp),
            color = if (message.fromCoach) HaanfColors.Text else HaanfColors.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(Corner)
                .background(if (message.fromCoach) HaanfColors.Card else HaanfColors.Primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Provider, key, model and tone. Shown in the Coach tab until a key works, and from Settings. */
@Composable
fun CoachSetupScreen(
    container: AppContainer,
    onDone: () -> Unit,
    onBack: (() -> Unit)?,
    onFreeWorkout: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val existing = container.aiSettings.settings.collectAsStateWithLifecycle().value
    var provider by rememberSaveable { mutableStateOf(existing?.provider ?: AiProvider.OPENROUTER) }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: provider.defaultModel) }
    var tone by rememberSaveable { mutableStateOf(existing?.tone ?: CoachTone.WARM) }
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val content: @Composable ColumnScope.() -> Unit = {
        Column {
            Eyebrow("Bring your own key")
            ScreenTitle("Set up your coach", modifier = Modifier.padding(vertical = 4.dp))
            Lead("The coach plans workouts, talks you through them and reviews them.")
        }
        LabeledChoices("Provider") {
            Choices(AiProvider.entries, provider, { it.label }, {
                provider = it
                model = it.defaultModel
            }, enabled = !checking)
        }
        HTextField(key, { key = it }, "API key", placeholder = provider.keyHint, password = true, enabled = !checking)
        existing?.let { saved ->
            Hint("Your ${saved.provider.label} key is saved. Paste a key again to change the provider, model or tone.")
        }
        HTextField(model, { model = it }, "Model", enabled = !checking)
        LabeledChoices("Coach tone") {
            Choices(CoachTone.entries, tone, { it.label }, { tone = it }, enabled = !checking, height = 56.dp)
            Hint(tone.description)
        }
        HCard(color = HaanfColors.InfoBackground, spacing = 6.dp) {
            Text("What the AI receives", style = body(16.sp, FontWeight.Bold), color = HaanfColors.Text)
            BodyText(
                "Your age, sex and weight, injuries and conditions, workouts and their heart rate " +
                    "summaries, benchmarks, resting heart rate, HRV and sleep, your chat, and how you " +
                    "feel. It goes to ${provider.label} using your key, under their privacy terms.",
                size = 15,
            )
            BodyText("It never receives your location, your voice, or your device details.", size = 15)
            Hint("Your key is encrypted on this phone. Calls cost you money on your own account.")
        }
        problem?.let { Notice(it) }
        BottomActions {
            PrimaryButton(
                if (checking) "Checking your key" else "Agree and connect",
                enabled = !checking && key.trim().length >= MIN_KEY_LENGTH && model.isNotBlank(),
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
            )
            onFreeWorkout?.let {
                LinkButton("Start a free workout", onClick = it, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
    if (onBack != null) SubScreen(onBack = onBack, content = content) else HScreen(content = content)
}
