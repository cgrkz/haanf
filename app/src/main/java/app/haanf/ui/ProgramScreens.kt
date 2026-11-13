package app.haanf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.haanf.AppContainer
import app.haanf.Features
import app.haanf.data.BenchmarkEntity
import app.haanf.data.EnrolmentEntity
import app.haanf.data.ProfileEntity
import app.haanf.data.zones
import app.haanf.program.ProgramDefinition
import app.haanf.program.ProgramNotice
import app.haanf.program.ProgramPosition
import app.haanf.program.ProgramRules
import app.haanf.program.ProgramSession
import app.haanf.program.ProgramWarning
import app.haanf.program.ProgramWarnings
import app.haanf.program.Programs
import app.haanf.program.progress
import app.haanf.training.SafetyRules
import app.haanf.training.Zone
import app.haanf.ui.theme.HaanfColors
import app.haanf.ui.theme.body
import app.haanf.ui.theme.condensed
import app.haanf.workout.ProgramSlot
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Words about a program that are not part of its fixed plan. Keyed by program id. */
private data class ProgramCopy(
    val tagline: String,
    val phases: List<Pair<String, String>>,
    val research: String,
)

private val programCopy = mapOf(
    Programs.FOUNDATION_8.id to ProgramCopy(
        tagline = "Build your aerobic base",
        phases = listOf(
            "Weeks 1–4" to "Easy sessions, building from 20 to 35 minutes. Week 4 is lighter.",
            "Weeks 5–8" to "One interval session a week, building to 4 x 4 minutes.",
        ),
        research = "In a 2007 study, 4 x 4 intervals raised VO2max more than steady running. That study " +
            "used moderately trained men, so this plan builds up to them more gently.",
    ),
)

private fun ProgramSession.minutes(): Int = (plan.totalSeconds + 30) / 60

@Composable
fun ProgramTab(container: AppContainer, nav: Navigator) {
    val profile by remember { container.trainingDao.observeProfile() }.collectAsStateWithLifecycle(LOADING)
    val enrolment by remember { container.programs.enrolment }.collectAsStateWithLifecycle(null)
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(null)
    val current = profile
    val scored = benchmarks ?: return
    if (current === LOADING) return

    val active = enrolment
    val program = active?.let { Programs.byId(it.programId) }
    if (active != null && program != null) {
        EnrolledProgram(container, nav, program, active, current)
    } else {
        HScreen {
            ProgramOverview(Programs.FOUNDATION_8)
            BottomActions { ProgramStartActions(container, nav, Programs.FOUNDATION_8, unlocked(scored)) }
        }
    }
}

@Composable
private fun EnrolledProgram(
    container: AppContainer,
    nav: Navigator,
    program: ProgramDefinition,
    enrolment: EnrolmentEntity,
    profile: ProfileEntity?,
) {
    val scope = rememberCoroutineScope()
    val today = container.programs.today()
    val progress = enrolment.progress()

    if (progress.completed) {
        HScreen {
            Column {
                Eyebrow(program.name)
                ScreenTitle("Program complete", modifier = Modifier.padding(top = 4.dp))
            }
            WeekStrip(program.weeks.size, program.weeks.size + 1)
            if (Features.BENCHMARK) {
                BodyText("All ${program.weeks.size} weeks are done. A benchmark now shows how far you've come.")
            } else {
                BodyText("All ${program.weeks.size} weeks are done.")
            }
            BottomActions {
                if (Features.BENCHMARK) {
                    PrimaryButton("Start benchmark", onClick = { nav.open(Dest.GetReady(benchmarkWorkout())) })
                    SecondaryButton("Choose a program", onClick = { scope.launch { container.programs.leave() } })
                } else {
                    PrimaryButton("Choose a program", onClick = { scope.launch { container.programs.leave() } })
                }
            }
        }
        return
    }

    val position = ProgramRules.position(progress, today)
    val session = program.session(position.week, position.session)
    val week = program.weeks[position.week - 1]
    HScreen {
        Column {
            Eyebrow(program.name)
            ScreenTitle(
                "Week ${position.week} of ${program.weeks.size}",
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            WeekStrip(program.weeks.size, position.week)
        }
        AppliedReset(position)
        ProgramWarnings.upcoming(progress, position, today)?.let { UpcomingReset(it, today) }

        val trainedToday = progress.lastWorkoutDate == today
        TodayCard(
            label = if (trainedToday) "Next session" else "Today",
            session = session,
            easyZone = profile?.zones(today)?.target(Zone.ZONE_2),
        ) {
            nav.open(
                Dest.GetReady(
                    PendingWorkout(
                        title = session.plan.title,
                        plan = session.plan,
                        slot = ProgramSlot(program.id, position.week, position.session),
                        hard = session.hard,
                        eyebrow = "${program.name}, Week ${position.week}",
                    ),
                ),
            )
        }

        Column {
            SectionHeading("This week")
            week.sessions.forEachIndexed { index, item ->
                val state = when {
                    index < position.session -> "Done"
                    index == position.session -> if (trainedToday) "Next" else "Today"
                    index == position.session + 1 -> "Next"
                    else -> "Later"
                }
                ListRow(
                    start = {
                        Text(
                            item.plan.title,
                            style = body(16.sp, if (index == position.session) FontWeight.Bold else FontWeight.Normal),
                            color = HaanfColors.Text,
                        )
                    },
                    end = {
                        Text(
                            state,
                            style = body(16.sp, FontWeight.SemiBold),
                            color = if (index > position.session) HaanfColors.TextSecondary else HaanfColors.Text,
                        )
                    },
                    divider = index < week.sessions.lastIndex,
                    minHeight = 44.dp,
                )
            }
        }
        BottomActions {
            LinkButton("See the full plan", onClick = { nav.open(Dest.ProgramDetail) })
        }
    }
}

/** Weeks as a strip: done weeks in blue, this week in grey, later weeks lighter. */
@Composable
private fun WeekStrip(weeks: Int, current: Int) {
    val done = (current - 1).coerceIn(0, weeks)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$done of $weeks weeks complete"
                role = Role.Image
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(weeks) { index ->
            val color = when {
                index + 1 < current -> HaanfColors.Primary
                index + 1 == current -> HaanfColors.InputBorder
                else -> HaanfColors.WeekFuture
            }
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

/** A reset that has already happened, explained. */
@Composable
private fun AppliedReset(position: ProgramPosition) {
    when (val notice = position.notice) {
        ProgramNotice.None -> Unit
        is ProgramNotice.RepeatingWeek -> Notice(
            "It's been ${notice.daysAway} days since your last program workout, so Week ${notice.week} " +
                "starts again from its first session.",
        )
        is ProgramNotice.RestartedProgram -> Notice(
            "It's been ${notice.daysAway} days since your last program workout, so the program has " +
                "restarted at Week 1.",
        )
    }
}

@Composable
private fun UpcomingReset(warning: ProgramWarning, today: LocalDate) {
    val left = "${Format.daysWord(warning.daysLeft)} left"
    val by = Format.deadline(warning.lastDay, today)
    val text = when (warning) {
        is ProgramWarning.WeekWillRepeat ->
            "$left to keep your place. Do a program workout by $by, or you'll repeat Week ${warning.week}."
        is ProgramWarning.ProgramWillRestart ->
            "$left before the program restarts. Do a program workout by $by to keep your place."
    }
    Notice(text.replace("by today", "today"))
}

@Composable
private fun TodayCard(label: String, session: ProgramSession, easyZone: String?, onStart: () -> Unit) {
    HCard(color = HaanfColors.Primary, padding = PaddingValues(20.dp), spacing = 4.dp) {
        Text(label, style = body(15.sp, FontWeight.SemiBold), color = HaanfColors.White)
        Text(session.plan.title, style = condensed(40.sp, lineHeight = 1.05), color = HaanfColors.White)
        val detail = if (session.hard) {
            "About ${session.minutes()} min, with warm-up, recoveries and cool-down. " +
                "Rest well the day before and after."
        } else {
            "${session.minutes()} min" + (easyZone?.let { " in $it." } ?: " in Zone 2.")
        }
        Text(
            detail,
            style = body(16.sp),
            color = HaanfColors.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        PrimaryButton(
            "Start session",
            onClick = onStart,
            height = 52.dp,
            container = HaanfColors.White,
            content = HaanfColors.Text,
        )
    }
}

/** What a program is, before enrolling or from "See the full plan". */
@Composable
private fun ProgramOverview(program: ProgramDefinition) {
    val copy = programCopy[program.id]
    val minutes = program.weeks.flatMap { it.sessions }.map { it.minutes() }
    val perWeek = program.weeks.map { it.sessions.size }.distinct()
    Column {
        copy?.let { Eyebrow(it.tagline) }
        ScreenTitle(program.name, size = 48, modifier = Modifier.padding(top = 4.dp))
    }
    StatRow(
        "${program.weeks.size}" to "weeks",
        perWeek.joinToString("–") to "sessions a week",
        "${minutes.min()}–${minutes.max()}" to "minutes each",
    )
    if (copy == null) {
        BodyText(program.summary)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            copy.phases.forEach { (weeks, text) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(weeks, style = body(15.sp, FontWeight.Bold), color = HaanfColors.Text, modifier = Modifier.width(84.dp))
                    Text(text, style = body(15.sp), color = HaanfColors.Text, modifier = Modifier.weight(1f))
                }
            }
        }
        HCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), spacing = 4.dp) {
            Text("The research behind it", style = body(16.sp, FontWeight.Bold), color = HaanfColors.Text)
            Text(copy.research, style = body(15.sp), color = HaanfColors.Text)
            Text("A draft plan, still under review.", style = body(14.sp), color = HaanfColors.TextSecondary)
        }
    }
    HCard(color = HaanfColors.InfoBackground, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), spacing = 4.dp) {
        Text("Stay consistent", style = body(16.sp, FontWeight.Bold), color = HaanfColors.Text)
        Text(
            "If you go ${ProgramRules.REPEAT_WEEK_AFTER_DAYS} days without a program workout, you'll repeat " +
                "that week. After ${ProgramRules.RESTART_AFTER_DAYS} days, the program restarts from Week 1. " +
                "Coach and free workouts are always available, but they don't count toward your program.",
            style = body(15.sp),
            color = HaanfColors.Text,
        )
    }
}

/** The program opens once a benchmark is scored, or straight away while benchmarks are off. */
private fun unlocked(scored: List<BenchmarkEntity>): Boolean = !Features.BENCHMARK || scored.isNotEmpty()

/** Start, or the benchmark that has to come first. */
@Composable
private fun ColumnScope.ProgramStartActions(
    container: AppContainer,
    nav: Navigator,
    program: ProgramDefinition,
    unlocked: Boolean,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    if (unlocked) {
        PrimaryButton("Start ${program.name}", onClick = { confirming = true })
    } else {
        HCard(padding = PaddingValues(20.dp), spacing = 8.dp) {
            Text("First, a fitness benchmark", style = body(18.sp, FontWeight.Bold), color = HaanfColors.Text)
            BodyText("Walk one mile as fast as you can. The program unlocks once it's scored.")
            PrimaryButton("Start benchmark", onClick = { nav.open(Dest.GetReady(benchmarkWorkout())) })
        }
    }
    if (confirming) {
        HDialog(
            title = "Before you start",
            text = SafetyRules.ENROLMENT_WARNING,
            confirm = "I understand",
            onConfirm = {
                confirming = false
                scope.launch { container.programs.enrol(program) }
            },
            dismiss = "Not now",
            onDismiss = { confirming = false },
        )
    }
}

fun benchmarkWorkout() = PendingWorkout(BENCHMARK_TITLE, null, null, benchmark = true, eyebrow = "Fitness benchmark")

@Composable
fun ProgramDetailScreen(container: AppContainer, nav: Navigator) {
    val scope = rememberCoroutineScope()
    val enrolment by remember { container.programs.enrolment }.collectAsStateWithLifecycle(null)
    val benchmarks by remember { container.trainingDao.observeBenchmarks() }.collectAsStateWithLifecycle(null)
    var leaving by remember { mutableStateOf(false) }
    val active = enrolment
    val program = active?.let { Programs.byId(it.programId) } ?: Programs.FOUNDATION_8
    val today = container.programs.today()
    val scored = benchmarks ?: return

    SubScreen(onBack = { nav.back() }) {
        ProgramOverview(program)
        val position = active?.progress()?.takeIf { !it.completed }?.let { ProgramRules.position(it, today) }
        SectionHeading("Week by week")
        Column {
            program.weeks.forEachIndexed { index, week ->
                val number = index + 1
                ListRow(
                    start = {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                if (position?.week == number) "Week $number, this week" else "Week $number",
                                style = body(15.sp, FontWeight.Bold),
                                color = HaanfColors.Text,
                            )
                            Text(
                                week.sessions.joinToString(", ") { it.plan.title },
                                style = body(15.sp),
                                color = HaanfColors.TextSecondary,
                            )
                        }
                    },
                    divider = index < program.weeks.lastIndex,
                )
            }
        }
        BottomActions {
            if (active == null) {
                ProgramStartActions(container, nav, program, unlocked(scored))
            } else {
                LinkButton("Leave program", onClick = { leaving = true })
            }
        }
    }
    if (leaving) {
        HDialog(
            title = "Leave ${program.name}?",
            text = "Your place is lost. Your workout history stays.",
            confirm = "Leave",
            onConfirm = {
                leaving = false
                scope.launch { container.programs.leave() }
                nav.back()
            },
            dismiss = "Stay",
            onDismiss = { leaving = false },
        )
    }
}
