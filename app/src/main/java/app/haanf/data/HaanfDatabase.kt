package app.haanf.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HrSessionEntity::class,
        HrSampleEntity::class,
        HrEventEntity::class,
        TalkTestEntity::class,
        ProfileEntity::class,
        WorkoutEntity::class,
        EnrolmentEntity::class,
        BenchmarkEntity::class,
        CoachMessageEntity::class,
        CoachFactEntity::class,
        TalkCheckEntity::class,
        SplitEntity::class,
        WorkoutEventEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = FiveZonesMigration::class),
        AutoMigration(from = 7, to = 8),
    ],
)
abstract class HaanfDatabase : RoomDatabase() {
    abstract fun hrDao(): HrDao
    abstract fun talkTestDao(): TalkTestDao
    abstract fun trainingDao(): TrainingDao
    abstract fun coachDao(): CoachDao

    companion object {
        fun create(context: Context): HaanfDatabase =
            Room.databaseBuilder(context, HaanfDatabase::class.java, "haanf.db").build()
    }
}
