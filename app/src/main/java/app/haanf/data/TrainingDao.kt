package app.haanf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observeProfile(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun profile(): ProfileEntity?

    @Upsert
    suspend fun saveProfile(profile: ProfileEntity)

    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Update
    suspend fun updateWorkout(workout: WorkoutEntity)

    @Query("UPDATE workout SET hrSessionId = :hrSessionId WHERE id = :id")
    suspend fun attachHeartRateSession(id: Long, hrSessionId: Long)

    @Query("SELECT * FROM workout WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout WHERE endedAtMs IS NULL")
    suspend fun unfinishedWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workout WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun recentWorkouts(limit: Int): List<WorkoutEntity>

    @Query(
        "SELECT * FROM workout WHERE endedAtMs IS NOT NULL AND startedAtMs >= :fromMs AND startedAtMs < :toMs " +
            "ORDER BY startedAtMs DESC",
    )
    suspend fun workoutsBetween(fromMs: Long, toMs: Long): List<WorkoutEntity>

    @Query("SELECT COUNT(*) FROM workout WHERE endedAtMs IS NOT NULL")
    suspend fun workoutCount(): Int

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun workout(id: Long): WorkoutEntity?

    @Query("UPDATE workout SET effortRating = :rating WHERE id = :id")
    suspend fun setEffortRating(id: Long, rating: Int)

    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkout(id: Long): Flow<WorkoutEntity?>

    @Query("UPDATE workout SET review = :review WHERE id = :id")
    suspend fun saveReview(id: Long, review: String)

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Insert
    suspend fun insertBenchmark(benchmark: BenchmarkEntity)

    @Query("SELECT * FROM benchmark ORDER BY takenAtMs DESC")
    fun observeBenchmarks(): Flow<List<BenchmarkEntity>>

    @Query("SELECT * FROM benchmark ORDER BY takenAtMs DESC")
    suspend fun benchmarks(): List<BenchmarkEntity>

    @Query("SELECT * FROM program_enrolment WHERE active = 1 LIMIT 1")
    fun observeEnrolment(): Flow<EnrolmentEntity?>

    @Query("SELECT * FROM program_enrolment WHERE active = 1 LIMIT 1")
    suspend fun enrolment(): EnrolmentEntity?

    @Upsert
    suspend fun saveEnrolment(enrolment: EnrolmentEntity)

    @Insert
    suspend fun insertSplit(split: SplitEntity)

    @Query("SELECT * FROM workout_split WHERE workoutId = :workoutId ORDER BY number")
    suspend fun splits(workoutId: Long): List<SplitEntity>

    @Query("SELECT * FROM workout_split WHERE workoutId = :workoutId ORDER BY number")
    fun observeSplits(workoutId: Long): Flow<List<SplitEntity>>

    @Insert
    suspend fun insertEvent(event: WorkoutEventEntity)

    @Query("SELECT * FROM workout_event WHERE workoutId = :workoutId ORDER BY elapsedSeconds, id")
    suspend fun events(workoutId: Long): List<WorkoutEventEntity>

    @Query("SELECT * FROM workout_event WHERE workoutId = :workoutId ORDER BY elapsedSeconds, id")
    fun observeEvents(workoutId: Long): Flow<List<WorkoutEventEntity>>
}
