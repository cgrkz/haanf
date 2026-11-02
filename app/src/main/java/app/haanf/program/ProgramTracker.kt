package app.haanf.program

import app.haanf.core.Clock
import app.haanf.data.EnrolmentEntity
import app.haanf.data.TrainingDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun EnrolmentEntity.progress() = ProgramProgress(
    week = week,
    session = session,
    lastWeekTrained = lastWeekTrained,
    lastWorkoutDate = lastWorkoutEpochDay?.let(LocalDate::ofEpochDay),
    completed = completedAtMs != null,
)

/** Saves enrolments. The decisions themselves all live in [ProgramRules]. */
class ProgramTracker(
    private val dao: TrainingDao,
    private val clock: Clock,
) {
    val enrolment = dao.observeEnrolment()

    fun today(): LocalDate =
        Instant.ofEpochMilli(clock.nowEpochMs()).atZone(ZoneId.systemDefault()).toLocalDate()

    suspend fun enrol(program: ProgramDefinition) {
        leave()
        dao.saveEnrolment(EnrolmentEntity(programId = program.id, enrolledAtMs = clock.nowEpochMs()))
    }

    suspend fun leave() {
        dao.enrolment()?.let { dao.saveEnrolment(it.copy(active = false)) }
    }

    suspend fun credit(programId: String) {
        val enrolment = dao.enrolment()?.takeIf { it.programId == programId } ?: return
        val program = Programs.byId(programId) ?: return
        val next = ProgramRules.afterWorkout(enrolment.progress(), program, today())
        dao.saveEnrolment(
            enrolment.copy(
                week = next.week,
                session = next.session,
                lastWeekTrained = next.lastWeekTrained,
                lastWorkoutEpochDay = next.lastWorkoutDate?.toEpochDay(),
                completedAtMs = if (next.completed) clock.nowEpochMs() else null,
            ),
        )
    }
}
