package app.haanf.health

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/** Read only, and deliberately so: this class has no call that writes to Health Connect. */
class HealthConnectReader(private val context: Context) {

    private class Reader<T : Record>(
        val label: String,
        val type: KClass<T>,
        val time: (T) -> Instant,
        val metadata: (T) -> Metadata,
        val samples: (T) -> Int = { 1 },
    ) {
        val permission: String = HealthPermission.getReadPermission(type)
    }

    private val readers: List<Reader<*>> = listOf(
        Reader("Heart rate", HeartRateRecord::class, { it.endTime }, { it.metadata }, { it.samples.size }),
        Reader("Resting heart rate", RestingHeartRateRecord::class, { it.time }, { it.metadata }),
        Reader("HRV (RMSSD)", HeartRateVariabilityRmssdRecord::class, { it.time }, { it.metadata }),
        Reader("Sleep", SleepSessionRecord::class, { it.endTime }, { it.metadata }),
        Reader("Exercise sessions", ExerciseSessionRecord::class, { it.endTime }, { it.metadata }),
    )

    val permissions: Set<String> = readers.map { it.permission }.toSet()

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private val client get() = HealthConnectClient.getOrCreate(context)

    suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions().intersect(permissions)

    suspend fun report(now: Instant, windowDays: Int = WINDOW_DAYS): HealthReport {
        val granted = grantedPermissions()
        val range = TimeRangeFilter.between(now.minus(Duration.ofDays(windowDays.toLong())), now)
        val types = readers.map { reader ->
            val result = if (reader.permission in granted) readType(reader, range) else TypeResult.NotGranted
            TypeReport(reader.label, result)
        }
        return HealthReport(windowDays, now, types)
    }

    private suspend fun <T : Record> readType(reader: Reader<T>, range: TimeRangeFilter): TypeResult =
        try {
            TypeResult.Read(HealthSummary.bySource(readFacts(reader, range)))
        } catch (e: SecurityException) {
            TypeResult.NotGranted
        } catch (e: RemoteException) {
            TypeResult.Error(e.message ?: "Health Connect did not respond")
        } catch (e: IOException) {
            TypeResult.Error(e.message ?: "Read failed")
        } catch (e: IllegalStateException) {
            TypeResult.Error(e.message ?: "Read failed")
        }

    private suspend fun <T : Record> readFacts(reader: Reader<T>, range: TimeRangeFilter): List<RecordFact> {
        val facts = mutableListOf<RecordFact>()
        var pageToken: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(reader.type, range, pageSize = PAGE_SIZE, pageToken = pageToken),
            )
            page.records.mapTo(facts) { record ->
                val metadata = reader.metadata(record)
                val device = metadata.device
                RecordFact(
                    sourcePackage = metadata.dataOrigin.packageName,
                    time = reader.time(record),
                    sampleCount = reader.samples(record),
                    deviceLabel = listOfNotNull(device?.manufacturer, device?.model)
                        .joinToString(" ")
                        .ifEmpty { null },
                )
            }
            pageToken = page.pageToken
        } while (pageToken != null)
        return facts
    }

    companion object {
        const val WINDOW_DAYS = 14
        private const val PAGE_SIZE = 1000
    }
}
