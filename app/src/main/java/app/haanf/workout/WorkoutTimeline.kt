package app.haanf.workout

import app.haanf.data.WorkoutEventEntity
import app.haanf.training.Zone

/** What a timeline line records. The first four are what was said; the rest is what happened. */
enum class TimelineKind {
    COACH, ATHLETE, TALK_TEST, PLAN_CHANGE,
    STEP, ALERT, DROPOUT, RECONNECTED, CLIMB, SPLIT,
    ;

    val isSaid: Boolean get() = this == COACH || this == ATHLETE || this == TALK_TEST || this == PLAN_CHANGE
}

data class TimelineEntry(val elapsedSeconds: Int, val kind: TimelineKind, val text: String, val bpm: Int?) {
    /** One timed line, as the coach and the review read it. */
    fun line(): String {
        val who = when (kind) {
            TimelineKind.COACH -> "Coach: "
            TimelineKind.ATHLETE -> "Athlete: "
            TimelineKind.TALK_TEST -> "Talk test: "
            else -> ""
        }
        val heartRate = bpm?.let { " ($it bpm)" }.orEmpty()
        return "[${WorkoutText.clock(elapsedSeconds)}] $who$text$heartRate"
    }

    companion object {
        fun from(event: WorkoutEventEntity): TimelineEntry? {
            val kind = TimelineKind.entries.firstOrNull { it.name == event.kind } ?: return null
            return TimelineEntry(event.elapsedSeconds, kind, event.text, event.bpm)
        }
    }
}

/**
 * True during a hard effort, when a talk test must not be asked. A planned step decides when
 * there is one. Otherwise heart rate does: Hard or Very hard counts.
 */
fun WorkoutState.Running.inHardEffort(bpm: Int?): Boolean {
    val step = stepIndex?.let { request.plan?.steps?.getOrNull(it) }
    val zone = step?.target ?: bpm?.let(request.zones::zoneFor) ?: return false
    return zone == Zone.HARD || zone == Zone.VERY_HARD
}
