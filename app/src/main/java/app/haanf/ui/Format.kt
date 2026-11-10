package app.haanf.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** Words for numbers and dates. Kept free of Android so they are unit tested. */
object Format {
    private val shortDate = DateTimeFormatter.ofPattern("d MMM")

    fun minutes(seconds: Int): String = "${((seconds + 30) / 60)} min"

    /** How long a workout lasted. Under a minute it is given in seconds, so it never reads "0 min". */
    fun duration(seconds: Int): String = if (seconds < 60) "$seconds s" else minutes(seconds)

    /** "today", "yesterday", a weekday within the last week, then a date. */
    fun relativeDay(day: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days == 0L -> "today"
            days == 1L -> "yesterday"
            days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
            else -> shortDate.format(day)
        }
    }

    fun relativeDay(epochMs: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String =
        relativeDay(Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate(), today)

    /** "Sunday" for the coming week, "today", or a date further out. */
    fun deadline(day: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, day)
        return when {
            days <= 0L -> "today"
            days == 1L -> "tomorrow"
            days in 2..6 -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
            else -> shortDate.format(day)
        }
    }

    fun hoursAndMinutes(hours: Double): String {
        val total = (hours * 60).roundToInt()
        return "${total / 60} h ${"%02d".format(total % 60)} min"
    }

    fun daysWord(count: Long): String = when (count) {
        1L -> "One day"
        2L -> "Two days"
        3L -> "Three days"
        4L -> "Four days"
        5L -> "Five days"
        else -> "$count days"
    }

    /** "down 3", "up 5" or "steady", from a change already rounded for display. */
    fun change(delta: Double?): String? {
        val rounded = delta?.roundToInt() ?: return null
        return when {
            rounded > 0 -> "up $rounded"
            rounded < 0 -> "down ${-rounded}"
            else -> "steady"
        }
    }
}
