package app.haanf.data

import androidx.room.ColumnInfo
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
    /** A [app.haanf.training.BikeType] name, for estimating watts. */
    val bikeType: String? = null,
    /** Where Zones 2 to 5 start, if the athlete set them. Null follows their maximum. */
    val zoneStarts: String? = null,
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
    /** Where Zones 2 to 5 started for this workout. Null means the defaults for [zoneMaxBpm]. */
    val zoneStarts: String? = null,
    val zone1Seconds: Int = 0,
    val zone2Seconds: Int = 0,
    val zone3Seconds: Int = 0,
    @ColumnInfo(defaultValue = "0") val zone4Seconds: Int = 0,
    val zone5Seconds: Int = 0,
    val noReadingSeconds: Int = 0,
    val countedForProgram: Boolean = false,
    @ColumnInfo(defaultValue = "0") val plannedHard: Boolean = false,
    @ColumnInfo(defaultValue = "0") val coached: Boolean = false,
    val review: String? = null,
    val effortRating: Int? = null,
    val hrDropBpm: Int? = null,
    /** Totals from the phone's GPS and barometer. Never a position or a height above sea level. */
    val distanceMetres: Double? = null,
    val movingSeconds: Int? = null,
    val maxSpeedMps: Double? = null,
    val climbMetres: Double? = null,
    val descentMetres: Double? = null,
    val avgWatts: Int? = null,
    val gapSecondsPerKm: Int? = null,
    val bikeType: String? = null,
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

/** One kilometre of a workout, or its shorter last part. Totals only, never where it was. */
@Entity(
    tableName = "workout_split",
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
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val number: Int,
    val metres: Double,
    val seconds: Int,
    val endElapsedSeconds: Int,
    val avgBpm: Int?,
    val climbMetres: Double,
    val descentMetres: Double,
    val avgWatts: Int?,
    val gapSecondsPerKm: Int?,
)

/**
 * One line of a workout's timeline: what the coach or athlete said, or what happened.
 * Each carries the time into the workout, so the coach and the review can follow the order.
 */
@Entity(
    tableName = "workout_event",
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
data class WorkoutEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val atMs: Long,
    val elapsedSeconds: Int,
    val kind: String,
    val text: String,
    val bpm: Int?,
)

/** Seconds in each zone, as saved. */
fun WorkoutEntity.zoneSeconds(): Map<app.haanf.training.Zone, Int> = mapOf(
    app.haanf.training.Zone.ZONE_1 to zone1Seconds,
    app.haanf.training.Zone.ZONE_2 to zone2Seconds,
    app.haanf.training.Zone.ZONE_3 to zone3Seconds,
    app.haanf.training.Zone.ZONE_4 to zone4Seconds,
    app.haanf.training.Zone.ZONE_5 to zone5Seconds,
)

/** The zones this workout was measured against. */
fun WorkoutEntity.zones(): app.haanf.training.HeartRateZones? =
    zoneMaxBpm?.let { app.haanf.training.HeartRateZones.of(it, zoneStarts) }
