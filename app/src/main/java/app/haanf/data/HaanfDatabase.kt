package app.haanf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HrSessionEntity::class,
        HrSampleEntity::class,
        HrEventEntity::class,
        TalkTestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class HaanfDatabase : RoomDatabase() {
    abstract fun hrDao(): HrDao
    abstract fun talkTestDao(): TalkTestDao

    companion object {
        fun create(context: Context): HaanfDatabase =
            Room.databaseBuilder(context, HaanfDatabase::class.java, "haanf.db").build()
    }
}
