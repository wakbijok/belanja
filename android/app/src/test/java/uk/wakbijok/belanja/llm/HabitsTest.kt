package uk.wakbijok.belanja.llm

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.wakbijok.belanja.data.Txn

/**
 * Signals calibrated on the soak ledger (merchants here synthetic): bill-paying
 * sessions and toll runs must stay silent; a midnight discretionary buy, three
 * days at the same kedai and a category running 40% ahead of its own history
 * must not.
 */
class HabitsTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply {
            clear(); set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    private val now = at(2026, 9, 15, 14, 0)
    private var seq = 0

    private fun txn(
        atMs: Long, rm: Double, cat: String, merchant: String = "KEDAI",
        src: String = "com.samsung.android.spay",
    ) = Txn(
        id = "t${seq++}", sourcePkg = src, amountCents = Math.round(rm * 100), merchant = merchant,
        rawTitle = "", rawText = "", capturedAt = atMs, needsReview = false, category = cat,
    )

    /** Two earlier cycles of ordinary spending so baselines and percentiles exist. */
    private fun history(): List<Txn> = buildList {
        for (month in listOf(7, 8)) {
            for (d in 1..28 step 2) {
                add(txn(at(2026, month, d, 13), 25.0, "Makan", "WARUNG A", "statement"))
                add(txn(at(2026, month, d, 18), 60.0, "Grocery", "MYDIN BAZAR", "statement"))
            }
            add(txn(at(2026, month, 1, 10), 300.0, "Bills", "MY TNB", "statement"))
        }
    }

    private fun ids(all: List<Txn>, latest: Txn) =
        Habits.signals(all, latest, now, 1).map { it.id.substringBefore('@') }

    @Test
    fun `three discretionary taps in twenty minutes is a burst`() {
        val a = txn(at(2026, 9, 15, 13, 40), 12.0, "Makan", "KEDAI ALFA")
        val b = txn(at(2026, 9, 15, 13, 48), 26.0, "Makan", "GRAB FOOD")
        val c = txn(at(2026, 9, 15, 13, 58), 20.0, "Family", "KEDAI BETA")
        val s = Habits.signals(history() + a + b + c, c, now, 1)
        assertEquals("burst", s.first().id.substringBefore('@'))
        assertTrue(s.first().fact.contains("3 belian"))
    }

    @Test
    fun `paying three bills at once is not a burst`() {
        val a = txn(at(2026, 9, 15, 13, 40), 24.0, "Bills", "AIR SELANGOR")
        val b = txn(at(2026, 9, 15, 13, 44), 300.0, "Bills", "MY TNB")
        val c = txn(at(2026, 9, 15, 13, 49), 200.0, "Bills", "CELCOMDIGI")
        assertTrue(ids(history() + a + b + c, c).none { it == "burst" })
    }

    @Test
    fun `two big discretionary buys count, two small ones do not`() {
        val big1 = txn(at(2026, 9, 15, 13, 40), 90.0, "Online", "SHOPEE")
        val big2 = txn(at(2026, 9, 15, 13, 55), 80.0, "Online", "LAZADA")
        assertTrue(ids(history() + big1 + big2, big2).contains("burst"))
        val s1 = txn(at(2026, 9, 15, 13, 40), 5.0, "Makan", "ZUS")
        val s2 = txn(at(2026, 9, 15, 13, 55), 6.0, "Makan", "TEALIVE")
        assertTrue(ids(history() + s1 + s2, s2).none { it == "burst" })
    }

    @Test
    fun `a midnight discretionary buy is late-night, a dawn petrol fill is not`() {
        val late = txn(at(2026, 9, 15, 0, 18), 45.0, "Lain-lain", "KEDAI MALAM")
        assertTrue(ids(history() + late, late).contains("late"))
        val petrol = txn(at(2026, 9, 15, 4, 10), 40.0, "Minyak", "PSS SILK")
        assertTrue(ids(history() + petrol, petrol).none { it == "late" })
        val tiny = txn(at(2026, 9, 15, 0, 22), 4.49, "Online", "GOOGLE*APPCONTOH")
        assertTrue(ids(history() + tiny, tiny).none { it == "late" })
    }

    @Test
    fun `same kedai three days running is a streak, two is not`() {
        val d1 = txn(at(2026, 9, 13), 20.5, "Makan", "RESTORAN CONTOH")
        val d2 = txn(at(2026, 9, 14), 21.5, "Makan", "RESTORAN CONTOH")
        val d3 = txn(at(2026, 9, 15, 13), 20.3, "Makan", "RESTORAN CONTOH KUALA LUMPUR")
        val s = Habits.signals(history() + d1 + d2 + d3, d3, now, 1)
        val streak = s.first { it.id.startsWith("streak:") }
        assertTrue(streak.fact.contains("3 hari berturut-turut"))
        assertTrue(ids(history() + d2 + d3, d3).none { it.startsWith("streak") })
    }

    @Test
    fun `a category running well ahead of its own history drifts`() {
        val big = (1..5).map { txn(at(2026, 9, it * 2, 13), 150.0, "Makan", "SECRET RECIPE") }
        val s = Habits.signals(history() + big, big.last(), now, 1)
        val drift = s.first { it.id.startsWith("drift:Makan") }
        assertTrue(drift.fact.contains("biasanya"))
    }

    @Test
    fun `ordinary spending raises no drift and no pace`() {
        val usual = (1..7).map { txn(at(2026, 9, it * 2, 13), 25.0, "Makan", "WARUNG A") }
        val s = ids(history() + usual, usual.last())
        assertTrue(s.none { it.startsWith("drift") || it == "pace" })
    }

    @Test
    fun `burning twice the usual daily rate is a pace signal`() {
        val heavy = (1..14).map { txn(at(2026, 9, it, 13), 120.0, "Grocery", "MYDIN BAZAR") }
        val s = Habits.signals(history() + heavy, heavy.last(), now, 1)
        val pace = s.first { it.id.startsWith("pace@") }
        assertTrue(pace.fact.contains("lebih laju"))
    }

    @Test
    fun `repeat escalates in steps of two visits`() {
        fun visits(n: Int) = (1..n).map { txn(at(2026, 9, it, 13), 12.0, "Makan", "KEDAI CONTOH MALL") }
        assertNull(Habits.signals(history() + visits(3), visits(3).last(), now, 1).firstOrNull { it.id.startsWith("repeat") })
        val four = Habits.signals(history() + visits(4), visits(4).last(), now, 1).first { it.id.startsWith("repeat") }
        val five = Habits.signals(history() + visits(5), visits(5).last(), now, 1).first { it.id.startsWith("repeat") }
        val six = Habits.signals(history() + visits(6), visits(6).last(), now, 1).first { it.id.startsWith("repeat") }
        assertEquals(four.id, five.id)
        assertTrue(six.id != four.id)
    }

    @Test
    fun `a quiet ledger says nothing`() {
        val one = txn(at(2026, 9, 15, 13), 25.0, "Makan", "WARUNG A")
        assertTrue(Habits.signals(history() + one, one, now, 1).isEmpty())
    }
}
