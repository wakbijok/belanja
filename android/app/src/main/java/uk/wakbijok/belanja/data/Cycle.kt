package uk.wakbijok.belanja.data

import java.util.Calendar

/**
 * Budget-cycle arithmetic — the ONE place a "month" boundary is computed.
 * A cycle runs [startDay 00:00, same day next month); startDay 1 is a plain
 * calendar month, so existing users see no change. A start day a month is
 * too short for clamps to that month's last day — a day-31 payday starts
 * 28 Feb, 31 Mar, 30 Apr — the same rule banks use for statement dates.
 */
object Cycle {

    const val MIN_DAY = 1
    const val MAX_DAY = 31

    /** [start, end) of the cycle at [offset] from the one containing [now]. */
    fun range(offset: Int, startDay: Int, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val day = startDay.coerceIn(MIN_DAY, MAX_DAY)
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // Before this month's (clamped) payday we are still inside the cycle
        // that began last month.
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val effDay = minOf(day, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        // Month arithmetic happens anchored on the 1st: add(MONTH) from a
        // clamped day (28 Feb for payday 31) would pin the day and drift
        // every later month to the 28th. Day 1 exists everywhere.
        cal.set(Calendar.DAY_OF_MONTH, 1)
        if (dom < effDay) cal.add(Calendar.MONTH, -1)
        cal.add(Calendar.MONTH, offset)
        val from = startInMonth(cal, day)
        cal.add(Calendar.MONTH, 1)
        return from to startInMonth(cal, day)
    }

    /** The cycle start inside [monthFirst]'s month: payday, clamped to its length. */
    private fun startInMonth(monthFirst: Calendar, day: Int): Long {
        val c = monthFirst.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, minOf(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return c.timeInMillis
    }

    /** Days left in the cycle containing [now], including today. */
    fun daysLeft(startDay: Int, now: Long = System.currentTimeMillis()): Int {
        val (_, end) = range(0, startDay, now)
        return ((end - startOfDay(now)) / DAY_MS).toInt()
    }

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MS = 86_400_000L
}
