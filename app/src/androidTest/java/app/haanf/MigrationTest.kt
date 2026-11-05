package app.haanf

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.haanf.data.HaanfDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 0 sessions and recordings on real phones must survive the upgrade. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HaanfDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun version1DataSurvivesTheMoveToVersion2() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO hr_session (id, startedAtMs, deviceId, deviceName, sourceKind, watchWorkoutRunning) " +
                    "VALUES (2, 1000, 'B8', 'Google Pixel Watch 2', 'ble', 0)",
            )
            db.execSQL(
                "INSERT INTO hr_sample (sessionId, receivedAtMs, bpm, sensorContact, rrIntervalsMs) " +
                    "VALUES (2, 1100, 78, 'UNSUPPORTED', '')",
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT bpm FROM hr_sample WHERE sessionId = 2").use { cursor ->
                cursor.moveToFirst()
                assertEquals(78, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM workout").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun version2DataSurvivesTheMoveToVersion3() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL("INSERT INTO profile (id, birthYear) VALUES (1, 1996)")
            db.execSQL(
                "INSERT INTO workout (id, startedAtMs, title, activity, origin, recoverySeconds, easySeconds, " +
                    "hardSeconds, veryHardSeconds, noReadingSeconds, countedForProgram) " +
                    "VALUES (7, 1000, 'Easy', 'TREADMILL', 'FREE', 0, 60, 0, 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT birthYear, sex, weightKg FROM profile").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1996, cursor.getInt(0))
                assertEquals(true, cursor.isNull(1))
                assertEquals(true, cursor.isNull(2))
            }
            db.query("SELECT easySeconds FROM workout WHERE id = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals(60, cursor.getInt(0))
            }
        }
    }

    @Test
    fun version3DataSurvivesTheMoveToVersion4() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO workout (id, startedAtMs, title, activity, origin, recoverySeconds, easySeconds, " +
                    "hardSeconds, veryHardSeconds, noReadingSeconds, countedForProgram) " +
                    "VALUES (7, 1000, 'Easy', 'TREADMILL', 'FREE', 0, 60, 0, 0, 0, 0)",
            )
        }

        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            db.query("SELECT easySeconds, plannedHard, coached, review FROM workout WHERE id = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals(60, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(true, cursor.isNull(3))
            }
        }
    }

    @Test
    fun version4DataSurvivesTheMoveToVersion5() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO workout (id, startedAtMs, endedAtMs, title, activity, origin, recoverySeconds, " +
                    "easySeconds, hardSeconds, veryHardSeconds, noReadingSeconds, countedForProgram, plannedHard, " +
                    "coached, review) " +
                    "VALUES (9, 1000, 61000, 'Intervals', 'OUTDOOR_RUN', 'AI', 0, 30, 30, 0, 0, 0, 1, 1, 'Good work')",
            )
        }

        helper.runMigrationsAndValidate(DB, 5, true).use { db ->
            db.query("SELECT hardSeconds, coached, review, effortRating, hrDropBpm FROM workout WHERE id = 9").use { cursor ->
                cursor.moveToFirst()
                assertEquals(30, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("Good work", cursor.getString(2))
                assertEquals(true, cursor.isNull(3))
                assertEquals(true, cursor.isNull(4))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
