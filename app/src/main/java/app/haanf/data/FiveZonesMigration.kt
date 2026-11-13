package app.haanf.data

import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import app.haanf.training.HeartRateZones
import app.haanf.training.TimedBpm
import app.haanf.training.WorkoutSummaries
import app.haanf.training.Zone

/**
 * Version 7 swaps the four named zones for five numbered ones. The old columns are renamed to
 * the zone nearest their middle, which only stands for workouts with no saved heart rate. Every
 * workout that kept its samples then has its time in each zone worked out again, against the
 * default zones for the maximum it was recorded with.
 */
@RenameColumn(tableName = "workout", fromColumnName = "recoverySeconds", toColumnName = "zone1Seconds")
@RenameColumn(tableName = "workout", fromColumnName = "easySeconds", toColumnName = "zone2Seconds")
@RenameColumn(tableName = "workout", fromColumnName = "hardSeconds", toColumnName = "zone3Seconds")
@RenameColumn(tableName = "workout", fromColumnName = "veryHardSeconds", toColumnName = "zone5Seconds")
class FiveZonesMigration : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        val workouts = buildList {
            db.query(
                "SELECT id, startedAtMs, endedAtMs, hrSessionId, zoneMaxBpm FROM workout " +
                    "WHERE endedAtMs IS NOT NULL AND hrSessionId IS NOT NULL AND zoneMaxBpm IS NOT NULL",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(longArrayOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.getLong(4)))
                }
            }
        }
        workouts.forEach { (id, startedAtMs, endedAtMs, sessionId, maxBpm) ->
            val samples = buildList {
                db.query("SELECT receivedAtMs, bpm FROM hr_sample WHERE sessionId = ?", arrayOf<Any>(sessionId)).use { cursor ->
                    while (cursor.moveToNext()) add(TimedBpm(cursor.getLong(0), cursor.getInt(1)))
                }
            }
            if (samples.isEmpty()) return@forEach
            val seconds = WorkoutSummaries.summarize(samples, startedAtMs, endedAtMs, HeartRateZones(maxBpm.toInt())).zoneSeconds
            db.execSQL(
                "UPDATE workout SET zone1Seconds = ?, zone2Seconds = ?, zone3Seconds = ?, zone4Seconds = ?, " +
                    "zone5Seconds = ? WHERE id = ?",
                Zone.entries.map { seconds[it] ?: 0 }.toTypedArray<Any>() + id,
            )
        }
    }
}
