package app.haanf.heartrate

/** Backoff for link recovery. It never gives up: a watch can return at any point in a workout. */
class ReconnectPolicy(
    private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000),
    private val rescanEvery: Int = 3,
) {
    /** [attempt] counts from 1. */
    fun delayMs(attempt: Int): Long = delaysMs[(attempt - 1).coerceIn(0, delaysMs.lastIndex)]

    /** Some wearables rotate their address, so every few attempts look for it again. */
    fun shouldRescan(attempt: Int): Boolean = attempt % rescanEvery == 0
}
