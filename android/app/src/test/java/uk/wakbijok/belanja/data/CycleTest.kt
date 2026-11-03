package uk.wakbijok.belanja.data

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class CycleTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply {
            clear(); set(y, m - 1, d, h, 0, 0)
        }.timeInMillis

    @Test
    fun `startDay 1 is the plain calendar month`() {
        val (from, until) = Cycle.range(0, 1, at(2026, 8, 19))
        assertEquals(at(2026, 8, 1, 0), from)
        assertEquals(at(2026, 9, 1, 0), until)
    }

    @Test
    fun `after payday the cycle starts this month`() {
        val (from, until) = Cycle.range(0, 28, at(2026, 8, 29))
        assertEquals(at(2026, 8, 28, 0), from)
        assertEquals(at(2026, 9, 28, 0), until)
    }

    @Test
    fun `before payday we are still in last month's cycle`() {
        val (from, until) = Cycle.range(0, 28, at(2026, 8, 19))
        assertEquals(at(2026, 7, 28, 0), from)
        assertEquals(at(2026, 8, 28, 0), until)
    }

    @Test
    fun `offset minus one is the previous cycle`() {
        val (from, until) = Cycle.range(-1, 28, at(2026, 8, 19))
        assertEquals(at(2026, 6, 28, 0), from)
        assertEquals(at(2026, 7, 28, 0), until)
    }

    @Test
    fun `february keeps the day-28 boundary`() {
        val (from, until) = Cycle.range(0, 28, at(2026, 3, 1))
        assertEquals(at(2026, 2, 28, 0), from)
        assertEquals(at(2026, 3, 28, 0), until)
    }

    @Test
    fun `day 31 clamps short months to their last day`() {
        // Mid-February with a day-31 payday: cycle began 31 Jan, ends 28 Feb.
        val (from, until) = Cycle.range(0, 31, at(2026, 2, 15))
        assertEquals(at(2026, 1, 31, 0), from)
        assertEquals(at(2026, 2, 28, 0), until)
    }

    @Test
    fun `clamped february does not drift later months off day 31`() {
        // Calendar.add(MONTH) from 28 Feb would pin later starts to the 28th;
        // the anchor-on-the-1st arithmetic must keep March at the 31st.
        val (from, until) = Cycle.range(0, 31, at(2026, 3, 5))
        assertEquals(at(2026, 2, 28, 0), from)
        assertEquals(at(2026, 3, 31, 0), until)
        val (aprFrom, aprUntil) = Cycle.range(1, 31, at(2026, 3, 5))
        assertEquals(at(2026, 3, 31, 0), aprFrom)
        assertEquals(at(2026, 4, 30, 0), aprUntil)
    }

    @Test
    fun `clamped payday itself starts the new cycle`() {
        // 28 Feb IS payday when the chosen day is 31.
        val (from, until) = Cycle.range(0, 31, at(2026, 2, 28))
        assertEquals(at(2026, 2, 28, 0), from)
        assertEquals(at(2026, 3, 31, 0), until)
    }

    @Test
    fun `day 29 is accepted and not silently capped`() {
        val (from, until) = Cycle.range(0, 29, at(2026, 8, 29))
        assertEquals(at(2026, 8, 29, 0), from)
        assertEquals(at(2026, 9, 29, 0), until)
    }

    @Test
    fun `cycles tile with no gap or overlap across a year`() {
        val now = at(2026, 8, 19)
        for (day in intArrayOf(1, 15, 28, 29, 30, 31)) {
            for (off in -12..0) {
                val (_, untilPrev) = Cycle.range(off - 1, day, now)
                val (fromCur, _) = Cycle.range(off, day, now)
                assertEquals("day=$day off=$off", untilPrev, fromCur)
            }
        }
    }

    @Test
    fun `daysLeft counts today through the day before payday`() {
        // 19 Ogo, payday 28: 19..27 inclusive = 9 days.
        assertEquals(9, Cycle.daysLeft(28, at(2026, 8, 19)))
        // Payday itself starts a fresh cycle: full month remaining.
        assertEquals(31, Cycle.daysLeft(28, at(2026, 8, 28)))
    }

    @Test
    fun `daysLeft matches the old formula for calendar months`() {
        // 19 Ogo, calendar month: 31 - 19 + 1 = 13.
        assertEquals(13, Cycle.daysLeft(1, at(2026, 8, 19)))
    }
}
