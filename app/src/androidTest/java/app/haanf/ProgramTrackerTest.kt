package app.haanf

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.haanf.core.Clock
import app.haanf.data.HaanfDatabase
import app.haanf.program.ProgramNotice
import app.haanf.program.ProgramRules
import app.haanf.program.ProgramTracker
import app.haanf.program.Programs
import app.haanf.program.progress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The reset rules against the real database, so saved progress reads back the same. */
@RunWith(AndroidJUnit4::class)
class ProgramTrackerTest {
    private lateinit var database: HaanfDatabase
    private lateinit var tracker: ProgramTracker
    private var nowMs = 1_793_000_000_000

    private val clock = object : Clock {
        override fun nowEpochMs() = nowMs
        override fun elapsedMs() = 0L
    }
    private val program = Programs.FOUNDATION_8

    private fun advanceDays(days: Int) {
        nowMs += days * 24 * 60 * 60 * 1000L
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaanfDatabase::class.java,
        ).build()
        tracker = ProgramTracker(database.trainingDao(), clock)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun countedWorkoutsMoveThroughTheWeekAndIntoTheNext() = runTest {
        tracker.enrol(program)
        repeat(3) {
            tracker.credit(program.id)
            advanceDays(2)
        }
        val enrolment = requireNotNull(tracker.enrolment.first())
        assertEquals(2, enrolment.week)
        assertEquals(0, enrolment.session)
        assertEquals(1, enrolment.lastWeekTrained)
    }

    @Test
    fun savedProgressRepeatsTheWeekAfterSevenDaysAway() = runTest {
        tracker.enrol(program)
        tracker.credit(program.id)
        advanceDays(7)
        val position = ProgramRules.position(requireNotNull(tracker.enrolment.first()).progress(), tracker.today())
        assertEquals(ProgramNotice.RepeatingWeek(1, 7), position.notice)
    }

    @Test
    fun leavingEndsTheEnrolmentAndEnrollingAgainStartsFresh() = runTest {
        tracker.enrol(program)
        tracker.credit(program.id)
        tracker.leave()
        assertNull(tracker.enrolment.first())
        tracker.enrol(program)
        assertEquals(0, requireNotNull(tracker.enrolment.first()).session)
    }

    @Test
    fun aCreditForAnotherProgramIsIgnored() = runTest {
        tracker.enrol(program)
        tracker.credit("some-other-program")
        assertEquals(0, requireNotNull(tracker.enrolment.first()).session)
    }

    @Test
    fun theLastSessionMarksTheProgramComplete() = runTest {
        tracker.enrol(program)
        repeat(24) {
            tracker.credit(program.id)
            advanceDays(2)
        }
        assertNotNull(requireNotNull(tracker.enrolment.first()).completedAtMs)
    }
}
