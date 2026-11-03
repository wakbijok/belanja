package uk.wakbijok.belanja.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.wakbijok.belanja.data.Txn

/**
 * The three August 2026 double-capture shapes the 5-minute rule let through,
 * and the shapes a wider rule must NOT merge (merchants and amounts synthetic).
 */
class CrossSourceTest {

    private val t0 = 1_800_000_000_000L
    private fun min(n: Int) = n * 60_000L

    private fun txn(pkg: String, merchant: String?, title: String, text: String, at: Long, rm: Long = 10000) =
        Txn(id = "$pkg@$at", sourcePkg = pkg, amountCents = rm, merchant = merchant, rawTitle = title,
            rawText = text, capturedAt = at, needsReview = false)

    private val spayKedai = txn("com.samsung.android.spay", "KEDAI CONTOH SG LAMA",
        "Maybank MasterCard Plat", "KEDAI CONTOH SG LAMA RM 100.00", t0)

    @Test
    fun `bank alert 14 minutes after the wallet tap is the same money`() {
        assertTrue(CrossSource.sameMoney(spayKedai, "com.maybank2u.life", "KEDAI CONTOH SG LAMA CHERAS MYS",
            "Maybank2u: Card Transaction", "You've just spent RM 100.00 at KEDAI CONTOH SG LAMA CHERAS MYS with your MasterCard ending 1",
            t0 + min(14)))
    }

    @Test
    fun `merchant-less grab app 20 minutes after the bank is the same money`() {
        val bank = txn("com.maybank2u.life", "GRAB-EC PETALING JAYA MYS", "Maybank2u: Card Transaction",
            "You've just spent RM 25.00 at GRAB-EC PETALING JAYA MYS with your MasterCard ending 1", t0, 2500)
        assertTrue(CrossSource.sameMoney(bank, "com.grabtaxi.passenger", null, "",
            "Your MasterCard 1234 has been charged MYR 25.00 for booking X", t0 + min(20)))
    }

    @Test
    fun `card alert 7 hours after a tng auto-debit for the same merchant is the same money`() {
        val tng = txn("my.com.tngdigital.ewallet", "Google", "Auto-Debit Payment", "RM20.00 to Google", t0, 2000)
        assertTrue(CrossSource.sameMoney(tng, "com.maybank2u.life", "GOOGLE*APPCONTOH MOUNTAIN VIEW USA",
            "Maybank2u: Card Transaction", "You've just spent RM 20.00 at GOOGLE*APPCONTOH with your MasterCard ending 1",
            t0 + min(442)))
    }

    @Test
    fun `a duitnow of the same amount on the same day is a different spend`() {
        val pump = txn("com.samsung.android.spay", "PSS LEBUHRAYA SILK", "Maybank MasterCard Plat", "RM 40.00", t0, 4000)
        assertFalse(CrossSource.sameMoney(pump, "com.maybank2u.life", "PAYEE A", "Maybank2u: Payments",
            "Successful payment of RM 40.00 to PAYEE A", t0 + min(30)))
    }

    @Test
    fun `two card alerts naming different merchants are different spends`() {
        assertFalse(CrossSource.sameMoney(spayKedai, "com.maybank2u.life", "KFC-TAMAN 15 KUALA LUMPUR MYS",
            "Maybank2u: Card Transaction", "You've just spent RM 100.00 at KFC with your MasterCard ending 1", t0 + min(40)))
    }

    @Test
    fun `two wallets are never merged beyond the tight window`() {
        val grab = txn("com.grabtaxi.passenger", null, "", "Booking confirmed RM 25.00", t0, 2500)
        assertFalse(CrossSource.sameMoney(grab, "my.com.tngdigital.ewallet", null, "Payment", "RM25.00 paid", t0 + min(30)))
    }

    @Test
    fun `inside five minutes anything cross-source merges, beyond twelve hours nothing does`() {
        val grab = txn("com.grabtaxi.passenger", null, "", "Booking confirmed RM 100.00", t0)
        assertTrue(CrossSource.sameMoney(grab, "my.com.tngdigital.ewallet", null, "Payment", "RM100.00", t0 + min(4)))
        assertFalse(CrossSource.sameMoney(spayKedai, "com.maybank2u.life", "KEDAI CONTOH SG LAMA CHERAS MYS",
            "Maybank2u: Card Transaction", "spent RM 100.00 with your MasterCard ending 1", t0 + min(13 * 60)))
    }

    @Test
    fun `same package and statement imports are someone else's problem`() {
        assertFalse(CrossSource.sameMoney(spayKedai, "com.samsung.android.spay", "KEDAI CONTOH SG LAMA",
            "Maybank MasterCard Plat", "RM 100.00", t0 + min(1)))
        val imported = txn("statement", "KEDAI CONTOH SG LAMA SP CHERAS", "", "", t0)
        assertFalse(CrossSource.sameMoney(imported, "com.maybank2u.life", "KEDAI CONTOH SG LAMA CHERAS MYS",
            "Maybank2u: Card Transaction", "spent RM 100.00 with your MasterCard ending 1", t0 + min(1)))
    }
}
