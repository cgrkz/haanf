package app.haanf.session

/** Turns session state into words. Kept free of Android so the wording is unit tested. */
object LiveText {
    fun bpm(snapshot: LiveSnapshot): String = snapshot.bpm?.let { "$it bpm" } ?: "-- bpm"

    fun age(snapshot: LiveSnapshot, nowElapsedMs: Long): String {
        val readAt = snapshot.lastValidReadingElapsedMs ?: return "no reading yet"
        val seconds = ((nowElapsedMs - readAt) / 1000).coerceAtLeast(0)
        return if (seconds < 60) "${seconds}s ago" else "${seconds / 60}m ${seconds % 60}s ago"
    }

    fun notification(snapshot: LiveSnapshot): String =
        "${bpm(snapshot)} · ${snapshot.connection.label}"
}
