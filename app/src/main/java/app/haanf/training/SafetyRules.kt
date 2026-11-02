package app.haanf.training

/** Rules shared by every kind of workout. AI workouts will have to pass these too. */
object SafetyRules {
    const val ENROLMENT_WARNING =
        "This plan is general fitness guidance, not medical advice. Check with a doctor before " +
            "you start if you have a heart condition, high blood pressure, or have been told to " +
            "limit exercise. Stop and seek help if you feel chest pain, faintness or unusual " +
            "shortness of breath."

    /** In our own words, following the factors in ACSM's 2015 screening recommendations. */
    val HEALTH_CHECK = listOf(
        "A doctor has told me I have a heart, kidney or metabolic condition, such as diabetes.",
        "I get chest pain, dizziness, fainting or unusual breathlessness when I am active.",
    )

    const val HEALTH_CHECK_ADVICE =
        "Please check with your doctor before doing this test or starting a program."

    const val BENCHMARK_STOP_RULE =
        "Stop at once if you feel chest pain, faintness or unusual shortness of breath."

    private const val COUNTED_SHARE = 0.8

    /** A shorter attempt stays in history but does not count as a program workout. */
    fun countsAsDone(elapsedSeconds: Int, plannedSeconds: Int): Boolean =
        elapsedSeconds >= plannedSeconds * COUNTED_SHARE
}

/** Speaks up when heart rate stays above the maximum, without nagging on a brief spike. */
class OverMaxMonitor(
    private val maxBpm: Int,
    private val holdMs: Long = 30_000,
    private val repeatMs: Long = 120_000,
) {
    private var overSinceMs: Long? = null
    private var lastAlertMs: Long? = null

    /** Returns true when the user should be told to ease off. */
    fun onBpm(bpm: Int, nowMs: Long): Boolean {
        if (bpm <= maxBpm) {
            overSinceMs = null
            return false
        }
        val since = overSinceMs ?: nowMs.also { overSinceMs = it }
        if (nowMs - since < holdMs) return false
        val last = lastAlertMs
        if (last != null && nowMs - last < repeatMs) return false
        lastAlertMs = nowMs
        return true
    }
}
