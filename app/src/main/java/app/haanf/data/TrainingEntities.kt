package app.haanf.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The single local user. Stored on the phone only: there are no accounts. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val birthYear: Int,
    val measuredMaxBpm: Int? = null,
    val sex: String? = null,
    val weightKg: Double? = null,
    val healthCheckClearedAtMs: Long? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceSourceKind: String? = null,
)

/** One workout from any mode. Only program workouts ever touch the program clock. */
@Entity(tableName = "workout", indices = [Index("startedAtMs")])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endReason: String? = null,
    val title: String,
    val activity: String,
    val origin: String,
    val programId: String? = null,
    val programWeek: Int? = null,
    val programSession: Int? = null,
    val plannedSeconds: Int? = null,
    val zoneMaxBpm: Int? = null,
    val hrSessionId: Long? = null,
    val deviceName: String? = null,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    val recoverySeconds: Int = 0,
    val easySeconds: Int = 0,
    val hardSeconds: Int = 0,
    val veryHardSeconds: Int = 0,
    val noReadingSeconds: Int = 0,
    val countedForProgram: Boolean = false,
)

@Entity(tableName = "program_enrolment")
data class EnrolmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programId: String,
    val enrolledAtMs: Long,
    val active: Boolean = true,
    val week: Int = 1,
    val session: Int = 0,
    val lastWeekTrained: Int? = null,
    val lastWorkoutEpochDay: Long? = null,
    val completedAtMs: Long? = null,
)

/** A scored benchmark. Its inputs are kept so a result can always be explained later. */
@Entity(
    tableName = "benchmark",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutId")],
)
data class BenchmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val protocol: String,
    val takenAtMs: Long,
    val timeSeconds: Int,
    val heartRateBpm: Int,
    val ageYears: Int,
    val sex: String,
    val weightKg: Double,
    val vo2Max: Double,
)
