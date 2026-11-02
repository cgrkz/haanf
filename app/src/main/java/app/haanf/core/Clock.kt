package app.haanf.core

import android.os.SystemClock

/** Wall clock for stored timestamps, monotonic clock for ages and gaps. */
interface Clock {
    fun nowEpochMs(): Long
    fun elapsedMs(): Long
}

object SystemTimeClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}
