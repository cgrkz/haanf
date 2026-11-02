package app.haanf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HrDao {
    @Insert
    suspend fun insertSession(session: HrSessionEntity): Long

    @Insert
    suspend fun insertSample(sample: HrSampleEntity)

    @Insert
    suspend fun insertEvent(event: HrEventEntity)

    @Query("UPDATE hr_session SET endedAtMs = :endedAtMs, endReason = :reason WHERE id = :id")
    suspend fun closeSession(id: Long, endedAtMs: Long, reason: String)

    /** Sessions left open mean the process died mid-session. Their last sample marks the end. */
    @Query(
        """
        UPDATE hr_session
        SET endReason = :reason,
            endedAtMs = COALESCE(
                (SELECT MAX(receivedAtMs) FROM hr_sample WHERE sessionId = hr_session.id),
                startedAtMs
            )
        WHERE endedAtMs IS NULL
        """,
    )
    suspend fun closeOpenSessions(reason: String): Int

    @Query(
        """
        SELECT s.*,
            (SELECT COUNT(*) FROM hr_sample WHERE sessionId = s.id) AS sampleCount,
            (SELECT COUNT(*) FROM hr_event WHERE sessionId = s.id AND type = 'dropout_start') AS dropoutCount
        FROM hr_session s
        ORDER BY s.startedAtMs DESC
        """,
    )
    fun observeSessions(): Flow<List<HrSessionSummary>>

    @Query("SELECT * FROM hr_session WHERE id = :id")
    suspend fun session(id: Long): HrSessionEntity?

    @Query("SELECT * FROM hr_sample WHERE sessionId = :sessionId ORDER BY receivedAtMs, id")
    suspend fun samples(sessionId: Long): List<HrSampleEntity>

    @Query("SELECT * FROM hr_event WHERE sessionId = :sessionId ORDER BY atMs, id")
    suspend fun events(sessionId: Long): List<HrEventEntity>

    @Query("DELETE FROM hr_session WHERE id = :id")
    suspend fun deleteSession(id: Long)
}

@Dao
interface TalkTestDao {
    @Insert
    suspend fun insert(test: TalkTestEntity): Long

    @Query("SELECT * FROM talk_test ORDER BY recordedAtMs DESC")
    fun observeAll(): Flow<List<TalkTestEntity>>

    @Query("SELECT * FROM talk_test ORDER BY recordedAtMs")
    suspend fun all(): List<TalkTestEntity>

    @Query("DELETE FROM talk_test WHERE id = :id")
    suspend fun delete(id: Long)
}
