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
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class HaanfDatabase : RoomDatabase() {
    abstract fun hrDao(): HrDao
    abstract fun talkTestDao(): TalkTestDao
    abstract fun trainingDao(): TrainingDao

    companion object {
        fun create(context: Context): HaanfDatabase =
            Room.databaseBuilder(context, HaanfDatabase::class.java, "haanf.db").build()
    }
}
