package app.haanf.ai

import android.util.Log
import app.haanf.core.Clock
import app.haanf.data.CoachDao
import app.haanf.data.CoachFactEntity
import app.haanf.data.FactStatus
import app.haanf.data.TrainingDao
import app.haanf.data.WorkoutEntity
import app.haanf.data.zones
import app.haanf.health.HealthConnectReader
import app.haanf.health.Vitals
import app.haanf.program.ProgramRules
import app.haanf.program.ProgramTracker
import app.haanf.program.Programs
import app.haanf.program.progress
import app.haanf.training.Sex
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A workout counts as hard if it was planned that way or the heart rate says so. */
fun WorkoutEntity.wasHard(): Boolean = plannedHard || zone3Seconds + zone4Seconds + zone5Seconds >= HARD_SECONDS

private const val HARD_SECONDS = 600

/** What the AI features read and write, kept apart so they can be tested on the JVM. */
interface CoachDataSource {
    suspend fun snapshot(): CoachSnapshot?
    suspend fun ruleContext(minutesAvailable: Int, feeling: Int): RuleContext
    suspend fun lastBenchmarkDay(): LocalDate?
}

/** Gathers what the AI features read. Health Connect only answers while the app is visible. */
class CoachData(
    private val training: TrainingDao,
    private val coach: CoachDao,
    private val programs: ProgramTracker,
    private val vitalsSource: suspend () -> Vitals?,
    private val clock: Clock,
) : CoachDataSource {
    private var lastVitals: Vitals? = null

    private fun day(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

    override suspend fun snapshot(): CoachSnapshot? {
        val profile = training.profile() ?: return null
        val sex = profile.sex?.let(Sex::valueOf) ?: return null
        val weight = profile.weightKg ?: return null
        val today = programs.today()
        val age = today.year - profile.birthYear
        return CoachSnapshot(
            today = today,
            ageYears = age,
            sex = sex,
            weightKg = weight,
            zones = profile.zones(today),
            maxIsMeasured = profile.measuredMaxBpm != null,
            facts = coach.activeFacts(),
            benchmarks = training.benchmarks(),
            workouts = training.recentWorkouts(WORKOUTS_TO_FILTER),
            talkChecks = coach.recentTalkChecks(RECENT_TALK_CHECKS),
            vitals = freshVitals(),
            program = programLine(today),
        )
    }

    /** The last good reading stands in when Health Connect refuses a background read. */
    private suspend fun freshVitals(): Vitals? {
        val read = try {
            vitalsSource()
        } catch (e: Exception) {
            Log.w(TAG, "Vitals unavailable: ${e.message}")
            null
        }
        if (read != null) lastVitals = read
        return lastVitals
    }

    private suspend fun programLine(today: LocalDate): String? {
        val enrolment = programs.enrolment.first() ?: return null
        val program = Programs.byId(enrolment.programId) ?: return null
        val progress = enrolment.progress()
        if (progress.completed) return "${program.name}, finished."
        val position = ProgramRules.position(progress, today)
        val next = program.session(position.week, position.session)
        val kind = if (next.hard) "a hard session" else "an easy session"
        return "${program.name}, week ${position.week} of ${program.weeks.size}. " +
            "Next: ${next.plan.title}, $kind."
    }

    override suspend fun ruleContext(minutesAvailable: Int, feeling: Int): RuleContext {
        val today = programs.today()
        val recent = training.recentWorkouts(RECENT_WORKOUTS)
        return RuleContext(
            today = today,
            minutesAvailable = minutesAvailable,
            feeling = feeling,
            workoutsLogged = training.workoutCount(),
            hardDays = recent.filter { it.wasHard() }.map { day(it.startedAtMs) },
            hardProgramDays = recent.filter { it.programId != null && it.wasHard() }.map { day(it.startedAtMs) },
            nextProgramSessionHard = nextProgramSessionHard(today),
        )
    }

    private suspend fun nextProgramSessionHard(today: LocalDate): Boolean {
        val enrolment = programs.enrolment.first() ?: return false
        val program = Programs.byId(enrolment.programId) ?: return false
        val progress = enrolment.progress()
        if (progress.completed) return false
        val position = ProgramRules.position(progress, today)
        return program.session(position.week, position.session).hard
    }

    override suspend fun lastBenchmarkDay(): LocalDate? = training.benchmarks().firstOrNull()?.let { day(it.takenAtMs) }

    suspend fun proposeFact(kind: String, text: String): Long = coach.insertFact(
        CoachFactEntity(
            createdAtMs = clock.nowEpochMs(),
            kind = kind,
            text = text,
            status = FactStatus.PROPOSED.name,
        ),
    )

    private companion object {
        const val TAG = "HaanfCoach"
        const val RECENT_WORKOUTS = 14
        const val WORKOUTS_TO_FILTER = 40
        const val RECENT_TALK_CHECKS = 10
    }
}
