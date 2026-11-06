package app.haanf.workout

import app.haanf.training.Split
import app.haanf.training.WorkoutStep
import kotlin.math.roundToInt

object WorkoutText {
    fun clock(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val rest = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
    }

    fun minutes(seconds: Int): String = "${(seconds + 30) / 60} min"

    /** What is spoken as a step begins. */
    fun stepLine(step: WorkoutStep): String {
        val minutes = step.seconds / 60
        val length = if (minutes == 1) "1 minute" else "$minutes minutes"
        return "${step.label}. $length."
    }

    fun km(metres: Double): String = "%.2f".format(metres / 1000)

    /** Pace as minutes per kilometre, such as 5:40. */
    fun pace(secondsPerKm: Int): String = clock(secondsPerKm)

    fun paceOf(speedMps: Double): String? = if (speedMps > 0.3) pace((1000 / speedMps).roundToInt()) else null

    fun kmh(speedMps: Double): String = "%.1f".format(speedMps * 3.6)

    /** A kilometre split, as the timeline records it. */
    fun splitLine(split: Split, ride: Boolean): String = buildString {
        val label = if (split.metres >= 999) "Kilometre ${split.number}" else "Last ${km(split.metres)} km"
        append("$label in ${clock(split.seconds)}")
        if (ride) {
            if (split.seconds > 0) append(", ${kmh(split.metres / split.seconds)} km/h")
        } else if (split.metres > 0) {
            append(", ${pace((split.seconds / (split.metres / 1000)).roundToInt())} /km")
        }
        append(", +${split.climbMetres.roundToInt()} m -${split.descentMetres.roundToInt()} m")
        split.avgWatts?.let { append(", about $it W") }
        split.gapSecondsPerKm?.let { append(", grade-adjusted ${pace(it)} /km") }
        append(".")
    }

    /** What is spoken after a kilometre: every one on a run, every five on a ride. */
    fun splitCue(split: Split, ride: Boolean): String {
        val km = if (split.number == 1) "1 kilometre" else "${split.number} kilometres"
        return if (ride) {
            "$km. ${kmh(1000.0 / split.seconds.coerceAtLeast(1))} kilometres an hour."
        } else {
            "$km. ${clock(split.seconds)} per kilometre."
        }
    }
}
