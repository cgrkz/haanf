package app.haanf.workout

import android.content.Context
import app.haanf.data.BenchmarkEntity
import app.haanf.data.HrDao
import app.haanf.data.TrainingDao
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.program.ProgramTracker
import app.haanf.session.HeartRateForegroundService
import app.haanf.training.TimedBpm

class ServiceLiveLink(private val context: Context) : LiveLink {
    override fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean) =
        HeartRateForegroundService.start(
            context,
            device,
            watchWorkoutRunning = false,
            usesLocation = usesLocation,
            usesMicrophone = usesMicrophone,
        )

    override fun end() = HeartRateForegroundService.stop(context)
}

class RoomWorkoutStore(
    private val training: TrainingDao,
    private val heartRate: HrDao,
    private val programs: ProgramTracker,
) : WorkoutStore {
    override suspend fun open(workout: WorkoutEntity): Long = training.insertWorkout(workout)

    override suspend fun close(workout: WorkoutEntity) = training.updateWorkout(workout)

    override suspend fun attachHeartRateSession(workoutId: Long, sessionId: Long) =
        training.attachHeartRateSession(workoutId, sessionId)

    override suspend fun unfinished(): List<WorkoutEntity> = training.unfinishedWorkouts()

    override suspend fun samples(sessionId: Long): List<TimedBpm> =
        heartRate.samples(sessionId).map { TimedBpm(it.receivedAtMs, it.bpm) }

    override suspend fun creditProgram(programId: String) = programs.credit(programId)

    override suspend fun saveBenchmark(benchmark: BenchmarkEntity) = training.insertBenchmark(benchmark)
}
