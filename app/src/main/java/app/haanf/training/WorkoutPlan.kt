package app.haanf.training

data class WorkoutStep(
    val label: String,
    val seconds: Int,
    val target: Zone,
)

data class WorkoutPlan(
    val title: String,
    val steps: List<WorkoutStep>,
) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }

    /** Where the plan stands after [elapsedSeconds], or null once every step has run. */
    fun positionAt(elapsedSeconds: Int): StepPosition? {
        var stepEnd = 0
        steps.forEachIndexed { index, step ->
            stepEnd += step.seconds
            if (elapsedSeconds < stepEnd) {
                return StepPosition(index, step, stepEnd - elapsedSeconds, steps.getOrNull(index + 1))
            }
        }
        return null
    }
}

data class StepPosition(
    val index: Int,
    val step: WorkoutStep,
    val remainingSeconds: Int,
    val next: WorkoutStep?,
)
