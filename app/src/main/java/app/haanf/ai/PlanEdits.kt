package app.haanf.ai

import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone

enum class PlanChange(val isIncrease: Boolean) {
    SHORTEN(false),
    EASE(false),
    EXTEND(true),
    INTENSIFY(true),
}

/** Changes what is left of a running plan. Time already done is never rewritten. */
object PlanEdits {
    private const val MIN_LEFT_SECONDS = 60
    private const val COOL_DOWN_SECONDS = 180

    fun apply(plan: WorkoutPlan, elapsedSeconds: Int, change: PlanChange, minutes: Int): WorkoutPlan {
        val (done, left) = splitAt(plan, elapsedSeconds)
        val changed = when (change) {
            PlanChange.SHORTEN -> shorten(left, minutes * 60)
            PlanChange.EASE -> left.map { it.copy(target = easier(it.target)) }
            PlanChange.EXTEND -> extend(left, minutes * 60)
            PlanChange.INTENSIFY -> intensify(left)
        }
        return plan.copy(steps = done + changed)
    }

    /** Steps fully or partly done, then the steps still to come, split at [elapsedSeconds]. */
    private fun splitAt(plan: WorkoutPlan, elapsedSeconds: Int): Pair<List<WorkoutStep>, List<WorkoutStep>> {
        val done = mutableListOf<WorkoutStep>()
        val left = mutableListOf<WorkoutStep>()
        var start = 0
        plan.steps.forEach { step ->
            val end = start + step.seconds
            when {
                end <= elapsedSeconds -> done += step
                start >= elapsedSeconds -> left += step
                else -> {
                    done += step.copy(seconds = elapsedSeconds - start)
                    left += step.copy(seconds = end - elapsedSeconds)
                }
            }
            start = end
        }
        return done to left
    }

    private fun shorten(steps: List<WorkoutStep>, seconds: Int): List<WorkoutStep> {
        val total = steps.sumOf { it.seconds }
        var toCut = seconds.coerceAtMost(total - MIN_LEFT_SECONDS).coerceAtLeast(0)
        val kept = steps.toMutableList()
        // Cut work before the cool-down, so the session still ends gently.
        for (index in kept.lastIndex downTo 0) {
            if (toCut == 0) break
            val isCoolDown = index == kept.lastIndex && kept.size > 1 && !isHard(kept[index])
            val floor = if (isCoolDown) minOf(kept[index].seconds, COOL_DOWN_SECONDS) else 0
            val cut = minOf(toCut, kept[index].seconds - floor)
            kept[index] = kept[index].copy(seconds = kept[index].seconds - cut)
            toCut -= cut
        }
        return kept.filter { it.seconds > 0 }
    }

    private fun extend(steps: List<WorkoutStep>, seconds: Int): List<WorkoutStep> {
        val extra = WorkoutStep("Extra easy", seconds, Zone.EASY)
        return if (steps.isEmpty()) listOf(extra) else steps.dropLast(1) + extra + steps.last()
    }

    /** Keeps the final step easy, so a raised session still has a cool-down. */
    private fun intensify(steps: List<WorkoutStep>): List<WorkoutStep> =
        steps.mapIndexed { index, step ->
            if (index == steps.lastIndex && steps.size > 1) step else step.copy(target = harder(step.target))
        }

    private fun isHard(step: WorkoutStep) = step.target == Zone.HARD || step.target == Zone.VERY_HARD

    private fun easier(zone: Zone): Zone = when (zone) {
        Zone.VERY_HARD -> Zone.HARD
        Zone.HARD -> Zone.EASY
        else -> zone
    }

    private fun harder(zone: Zone): Zone = when (zone) {
        Zone.RECOVERY -> Zone.EASY
        Zone.EASY -> Zone.HARD
        else -> zone
    }

    fun toProposal(plan: WorkoutPlan, reason: String) = ProposedWorkout(
        title = plan.title,
        reason = reason,
        steps = plan.steps.map { ProposedStep(it.label, it.seconds / 60.0, it.target) },
    )
}
