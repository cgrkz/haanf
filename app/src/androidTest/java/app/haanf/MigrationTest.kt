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

    private companion object {
        const val DB = "migration-test"
    }
}
