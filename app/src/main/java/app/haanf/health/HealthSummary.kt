package app.haanf.health

import java.time.Instant

/** The facts kept from one Health Connect record. The values themselves are not needed here. */
data class RecordFact(
    val sourcePackage: String,
    val time: Instant,
    val sampleCount: Int = 1,
    val deviceLabel: String? = null,
)

data class SourceSummary(
    val sourcePackage: String,
    val sourceName: String,
    val recordCount: Int,
    val sampleCount: Int,
    val newest: Instant,
    val devices: List<String>,
)

sealed interface TypeResult {
    data class Read(val sources: List<SourceSummary>) : TypeResult
    data object NotGranted : TypeResult
    data class Error(val message: String) : TypeResult
}

data class TypeReport(val label: String, val result: TypeResult)

data class HealthReport(
    val windowDays: Int,
    val readAt: Instant,
    val types: List<TypeReport>,
)

object HealthSummary {
    private val knownApps = mapOf(
        "com.fitbit.FitbitMobile" to "Fitbit / Google Health",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
        "com.huami.watch.hmwatchmanager" to "Zepp",
        "com.samsung.android.shealth" to "Samsung Health",
        "com.strava" to "Strava",
    )

    fun sourceName(packageName: String): String = knownApps[packageName] ?: packageName

    /** Groups by writing app, busiest source first. */
    fun bySource(facts: List<RecordFact>): List<SourceSummary> =
        facts.groupBy { it.sourcePackage }
            .map { (source, records) ->
                SourceSummary(
                    sourcePackage = source,
                    sourceName = sourceName(source),
                    recordCount = records.size,
                    sampleCount = records.sumOf { it.sampleCount },
                    newest = records.maxOf { it.time },
                    devices = records.mapNotNull { it.deviceLabel }.distinct().sorted(),
                )
            }
            .sortedByDescending { it.recordCount }

    fun text(report: HealthReport): String = buildString {
        append("Haanf Health Connect report, last ${report.windowDays} days, read at ${report.readAt}\n")
        report.types.forEach { type ->
            append('\n').append(type.label).append('\n')
            when (val result = type.result) {
                TypeResult.NotGranted -> append("  Not granted\n")
                is TypeResult.Error -> append("  Could not read: ${result.message}\n")
                is TypeResult.Read -> {
                    if (result.sources.isEmpty()) append("  No data\n")
                    result.sources.forEach {
                        append("  ${it.sourceName} (${it.sourcePackage}): ${it.recordCount} records, ")
                        append("${it.sampleCount} samples, newest ${it.newest}")
                        if (it.devices.isNotEmpty()) append(", devices: ${it.devices.joinToString()}")
                        append('\n')
                    }
                }
            }
        }
    }
}
