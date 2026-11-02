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

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query("SELECT * FROM program_enrolment WHERE active = 1 LIMIT 1")
    fun observeEnrolment(): Flow<EnrolmentEntity?>

    @Query("SELECT * FROM program_enrolment WHERE active = 1 LIMIT 1")
    suspend fun enrolment(): EnrolmentEntity?

    @Upsert
    suspend fun saveEnrolment(enrolment: EnrolmentEntity)
}
