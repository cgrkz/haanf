package app.haanf.talktest

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TalkTestType(val label: String) {
    COUNTING("Counting"),
    PASSAGE("Passage"),
}

enum class Effort(val label: String) {
    EASY("Easy"),
    MODERATE("Moderate"),
    HARD("Hard"),
}

enum class TrainingActivity(val label: String) {
    OUTDOOR_RUN("Outdoor run"),
    CYCLING("Cycling"),
    TREADMILL("Treadmill"),
}

enum class AudioInput(val label: String) {
    PHONE_MIC("Phone mic"),
    BLUETOOTH_EARBUDS("Bluetooth earbuds"),
}

data class TalkTestRequest(
    val type: TalkTestType,
    val effort: Effort,
    val activity: TrainingActivity,
    val input: AudioInput,
)

object TalkTestScript {
    /** Fixed so that recordings stay comparable. Changing it invalidates earlier recordings. */
    const val PASSAGE =
        "The morning road runs past the old mill and over the narrow bridge. " +
            "I keep my pace steady, breathe as easily as I can, and speak every word " +
            "clearly until I reach the final gate."

    const val COUNTING_INSTRUCTIONS =
        "Tap Start, then count aloud from one at a steady pace. " +
            "Tap Stop when you need to take a breath."

    fun instructions(type: TalkTestType): String = when (type) {
        TalkTestType.COUNTING -> COUNTING_INSTRUCTIONS
        TalkTestType.PASSAGE -> PASSAGE
    }

    /** Labels are in the name so an exported file stays identifiable on its own. */
    fun fileName(recordedAtEpochMs: Long, request: TalkTestRequest, zone: ZoneId): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(zone)
            .format(Instant.ofEpochMilli(recordedAtEpochMs))
        val labels = listOf(request.type, request.effort, request.activity, request.input)
            .joinToString("_") { it.name.lowercase() }
        return "talk_${stamp}_$labels.wav"
    }
}
