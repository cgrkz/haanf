package app.haanf.workout

import app.haanf.training.WorkoutStep

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
}
