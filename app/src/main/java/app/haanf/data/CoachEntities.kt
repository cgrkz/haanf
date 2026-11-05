package app.haanf.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One line of the ongoing chat with the coach. */
@Entity(tableName = "coach_message", indices = [Index("atMs")])
data class CoachMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val fromCoach: Boolean,
    val text: String,
)

enum class FactKind(val label: String) {
    INJURY("Injury"),
    CONDITION("Health condition"),
    NOTE("Note"),
}

enum class FactStatus { PROPOSED, ACTIVE, RESOLVED }

/** Something lasting about the user. The AI can only propose these: the user confirms them. */
@Entity(tableName = "coach_fact")
data class CoachFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtMs: Long,
    val kind: String,
    val text: String,
    val status: String,
)

enum class TalkAnswer(val label: String, val summary: String) {
    COMFORTABLE("Could talk", "could talk comfortably"),
    SOME_EFFORT("Some effort", "could talk with some effort"),
    COULD_NOT("Couldn't talk", "could not talk comfortably"),
}

/** A self-reported talk test, kept with heart rate to build the talking threshold. */
@Entity(
    tableName = "talk_check",
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
data class TalkCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val atMs: Long,
    val bpm: Int?,
    val answer: String,
)

@Dao
interface CoachDao {
    @Insert
    suspend fun insertMessage(message: CoachMessageEntity): Long

    @Query("SELECT * FROM coach_message ORDER BY atMs, id")
    fun observeMessages(): Flow<List<CoachMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM coach_message ORDER BY atMs DESC, id DESC LIMIT :limit) ORDER BY atMs, id")
    suspend fun recentMessages(limit: Int): List<CoachMessageEntity>

    @Insert
    suspend fun insertFact(fact: CoachFactEntity): Long

    @Update
    suspend fun updateFact(fact: CoachFactEntity)

    @Query("DELETE FROM coach_fact WHERE id = :id")
    suspend fun deleteFact(id: Long)

    @Query("SELECT * FROM coach_fact ORDER BY createdAtMs DESC")
    fun observeFacts(): Flow<List<CoachFactEntity>>

    @Query("SELECT * FROM coach_fact WHERE status = 'ACTIVE' ORDER BY createdAtMs")
    suspend fun activeFacts(): List<CoachFactEntity>

    @Query("SELECT * FROM coach_fact WHERE id = :id")
    suspend fun fact(id: Long): CoachFactEntity?

    @Insert
    suspend fun insertTalkCheck(check: TalkCheckEntity)

    @Query("SELECT * FROM talk_check WHERE workoutId = :workoutId ORDER BY atMs")
    fun observeTalkChecks(workoutId: Long): Flow<List<TalkCheckEntity>>

    @Query("SELECT * FROM talk_check ORDER BY atMs DESC LIMIT :limit")
    suspend fun recentTalkChecks(limit: Int): List<TalkCheckEntity>
}
