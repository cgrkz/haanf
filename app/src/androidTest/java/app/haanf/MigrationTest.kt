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

    @Test
    fun version5DataSurvivesTheMoveToVersion6() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL("INSERT INTO profile (id, birthYear, sex, weightKg) VALUES (1, 2004, 'MALE', 61.0)")
            db.execSQL(
                "INSERT INTO workout (id, startedAtMs, endedAtMs, title, activity, origin, avgBpm, maxBpm, recoverySeconds, " +
                    "easySeconds, hardSeconds, veryHardSeconds, noReadingSeconds, countedForProgram, plannedHard, " +
                    "coached, review, effortRating) " +
                    "VALUES (5, 1000, 1401000, 'Free workout', 'CYCLING', 'FREE', 149, 185, 5, 447, 421, 525, 0, 0, 0, 1, " +
                    "'A hard surge', 3)",
            )
        }

        helper.runMigrationsAndValidate(DB, 6, true).use { db ->
            db.query("SELECT avgBpm, review, effortRating, distanceMetres, climbMetres, bikeType FROM workout WHERE id = 5").use { cursor ->
                cursor.moveToFirst()
                assertEquals(149, cursor.getInt(0))
                assertEquals("A hard surge", cursor.getString(1))
                assertEquals(3, cursor.getInt(2))
                assertEquals(true, cursor.isNull(3))
                assertEquals(true, cursor.isNull(4))
                assertEquals(true, cursor.isNull(5))
            }
            db.query("SELECT weightKg, bikeType FROM profile WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(61.0, cursor.getDouble(0), 0.0)
                assertEquals(true, cursor.isNull(1))
            }
            db.execSQL(
                "INSERT INTO workout_split (workoutId, number, metres, seconds, endElapsedSeconds, climbMetres, descentMetres) " +
                    "VALUES (5, 1, 1000.0, 372, 372, 32.0, 0.0)",
            )
            db.execSQL(
                "INSERT INTO workout_event (workoutId, atMs, elapsedSeconds, kind, text, bpm) " +
                    "VALUES (5, 251000, 250, 'ATHLETE', 'Big hill coming up.', 147)",
            )
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM workout WHERE id = 5")
            db.query("SELECT (SELECT COUNT(*) FROM workout_split) + (SELECT COUNT(*) FROM workout_event)").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun version6WorkoutsAreRecountedInFiveZones() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO profile (id, birthYear) VALUES (1, 2004)")
            db.execSQL(
                "INSERT INTO hr_session (id, startedAtMs, deviceId, deviceName, sourceKind, watchWorkoutRunning) " +
                    "VALUES (3, 0, 'B8', 'Google Pixel Watch 2', 'ble', 0)",
            )
            // With a maximum of 200, these fall in Zones 1 to 5 for 4 seconds each.
            listOf(110, 130, 150, 170, 190).forEachIndexed { index, bpm ->
                listOf(0, 2_000).forEach { offset ->
                    db.execSQL(
                        "INSERT INTO hr_sample (sessionId, receivedAtMs, bpm, sensorContact, rrIntervalsMs) " +
                            "VALUES (3, ${index * 4_000 + offset}, $bpm, 'UNSUPPORTED', '')",
                    )
                }
            }
            val columns = "id, startedAtMs, endedAtMs, title, activity, origin, zoneMaxBpm, hrSessionId, " +
                "recoverySeconds, easySeconds, hardSeconds, veryHardSeconds, noReadingSeconds, countedForProgram"
            db.execSQL("INSERT INTO workout ($columns) VALUES (8, 0, 20000, 'Free', 'CYCLING', 'FREE', 200, 3, 1, 1, 1, 1, 0, 0)")
            db.execSQL("INSERT INTO workout ($columns) VALUES (9, 0, 60000, 'Old', 'TREADMILL', 'FREE', 200, NULL, 6, 7, 8, 9, 0, 0)")
        }

        helper.runMigrationsAndValidate(DB, 7, true).use { db ->
            val zones = "zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds"
            db.query("SELECT $zones, zoneStarts FROM workout WHERE id = 8").use { cursor ->
                cursor.moveToFirst()
                assertEquals(listOf(4, 4, 4, 4, 4), (0..4).map(cursor::getInt))
                assertEquals(true, cursor.isNull(5))
            }
            db.query("SELECT $zones FROM workout WHERE id = 9").use { cursor ->
                cursor.moveToFirst()
                assertEquals(listOf(6, 7, 8, 0, 9), (0..4).map(cursor::getInt))
            }
            db.query("SELECT zoneStarts FROM profile WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(true, cursor.isNull(0))
            }
        }
    }

    @Test
    fun version7ProfileKeepsItsZonesAndHasNoRestingHeartRateYet() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL("INSERT INTO profile (id, birthYear, measuredMaxBpm, zoneStarts) VALUES (1, 2004, 203, '140,160,175,190')")
        }

        helper.runMigrationsAndValidate(DB, 8, true).use { db ->
            db.query("SELECT measuredMaxBpm, zoneStarts, restingBpm, healthRestingBpm FROM profile WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(203, cursor.getInt(0))
                assertEquals("140,160,175,190", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals(true, cursor.isNull(3))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
