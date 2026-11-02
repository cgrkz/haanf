package app.haanf.session

/**
 * Tracks whether readings are still arriving, whatever the link claims. A wearable
 * can stay connected and simply stop sending, which only a timer can catch.
 */
class DropoutDetector(
    private val dropoutAfterMs: Long = 10_000,
    private val reconnectAfterMs: Long = 30_000,
) {
    sealed interface Event {
        data class Started(val silentForMs: Long) : Event
        data class Ended(val gapMs: Long) : Event
        data class RequestReconnect(val silentForMs: Long) : Event
    }

    private var lastReadingMs: Long? = null
    private var inDropout = false
    private var lastReconnectRequestMs: Long? = null

    val active: Boolean get() = inDropout

    fun onReading(nowMs: Long): Event? {
        val previous = lastReadingMs
        lastReadingMs = nowMs
        lastReconnectRequestMs = null
        if (!inDropout) return null
        inDropout = false
        return Event.Ended(gapMs = nowMs - (previous ?: nowMs))
    }

    /** [linkStreaming] is false while the source is already recovering by itself. */
    fun onTick(nowMs: Long, linkStreaming: Boolean): Event? {
        val last = lastReadingMs ?: return null
        val silentFor = nowMs - last
        if (!inDropout) {
            if (silentFor < dropoutAfterMs) return null
            inDropout = true
            return Event.Started(silentFor)
        }
        val sinceRequest = nowMs - (lastReconnectRequestMs ?: last)
        if (linkStreaming && sinceRequest >= reconnectAfterMs) {
            lastReconnectRequestMs = nowMs
            return Event.RequestReconnect(silentFor)
        }
        return null
    }
}
