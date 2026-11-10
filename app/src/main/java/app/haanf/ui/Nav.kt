package app.haanf.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

enum class Tab(val label: String) {
    PROGRAM("Program"),
    COACH("Coach"),
    PROGRESS("Progress"),
}

/** Screens opened over the tabs. */
sealed interface Dest {
    data object ProgramDetail : Dest
    data object Chat : Dest
    data object Settings : Dest
    data object CoachSetup : Dest
    data object Devices : Dest
    data object History : Dest
    data object Benchmarks : Dest
    data class Lab(val tool: LabTool) : Dest
    data class WorkoutDetail(val id: Long) : Dest
    data class GetReady(val pending: PendingWorkout) : Dest
}

enum class LabTool(val title: String) {
    HEART_RATE("Heart rate tool"),
    TALK_TEST("Talk test recorder"),
    HEALTH_DATA("Health Connect report"),
}

/** A small back stack. The app has few screens, so no navigation library is needed. */
class Navigator {
    val stack = mutableStateListOf<Dest>()
    var tab by mutableIntStateOf(Tab.PROGRAM.ordinal)

    val top: Dest? get() = stack.lastOrNull()

    fun open(dest: Dest) {
        stack.add(dest)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
