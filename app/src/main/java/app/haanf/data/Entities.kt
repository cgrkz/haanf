package app.haanf.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "hr_session")
data class HrSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endReason: String? = null,
    val deviceId: String,
    val deviceName: String?,
    val sourceKind: String,
    val watchWorkoutRunning: Boolean,
)

@Entity(
    tableName = "hr_sample",
    foreignKeys = [
        ForeignKey(
            entity = HrSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class HrSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val receivedAtMs: Long,
    val bpm: Int,
    val sensorContact: String,
    val rrIntervalsMs: String,
)

@Entity(
    tableName = "hr_event",
    foreignKeys = [
        ForeignKey(
            entity = HrSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class HrEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val atMs: Long,
    val type: String,
    val detail: String,
)

data class HrSessionSummary(
    @Embedded val session: HrSessionEntity,
    val sampleCount: Int,
    val dropoutCount: Int,
)

@Entity(tableName = "talk_test")
data class TalkTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAtMs: Long,
    val testType: String,
    val effort: String,
    val activity: String,
    val requestedInput: String,
    val actualInput: String,
    val audioSource: String,
    val sampleRateHz: Int,
    val durationMs: Long,
    val hrStartBpm: Int?,
    val hrEndBpm: Int?,
    val hrSessionId: Long?,
    val fileName: String,
)
