package uk.wakbijok.belanja.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.wakbijok.belanja.data.MerchantCat

/** Synthetic spellings shaped like the soak ledger's — the same kedai as seen by the wallet, the bank and the statement. */
class KedaiTest {

    @Test
    fun `wallet, bank and statement spellings share a fingerprint`() {
        val fp = Kedai.fingerprint("KEDAI CONTOH SG LAMA SP CHERAS")
        assertEquals("KEDAI CONTOH", fp)
        assertEquals(fp, Kedai.fingerprint("KEDAI CONTOH SG LAMA"))
        assertEquals(fp, Kedai.fingerprint("KEDAI CONTOH SG LAMA CHERAS MYS"))
        assertEquals("RESTORAN CONTOH", Kedai.fingerprint("RESTORAN CONTOH-ALFA SP SHAH ALAM"))
        assertEquals("RESTORAN CONTOH", Kedai.fingerprint("RESTORAN CONTOH BETA SP KUALA LUMPUR"))
        assertEquals("PUSTAKA CONTOH", Kedai.fingerprint("PUSTAKA SERI CONTOH BBB SHAH ALAM MYS"))
        assertEquals("PENGURUSAN AIR", Kedai.fingerprint("PENGURUSAN AIR SELANGO KUALA LUMPUR MYS"))
    }

    @Test
    fun `place, corporate and instrument words carry no identity`() {
        assertEquals("KFC", Kedai.fingerprint("KFC-TAMAN 15 MELAKA"))
        assertEquals("ELEVEN", Kedai.fingerprint("7-ELEVEN MALAYSIA S SP Shah Alam"))
        assertEquals("CONTOH EYE", Kedai.fingerprint("CONTOH EYE SPECIALIST KUALA LUMPUR MYS WITH YOUR MASTERCARD PLATINUM ENDING 1234"))
        assertNull(Kedai.fingerprint("SP KUALA LUMPUR"))
        assertNull(Kedai.fingerprint(null))
    }

    private fun dict(learned: Map<String, String> = emptyMap(), vararg history: Pair<String, String>) =
        Kedai.Dictionary(learned, history.map { MerchantCat(it.first, it.second) })

    @Test
    fun `history votes by majority and learned wins outright`() {
        val d = dict(
            history = arrayOf(
                "PSS SUNGAI MAWAR UT SP SELANGOR" to "Minyak",
                "PSS SUNGAI MAWAR UTARA" to "Minyak",
                "PSS SUNGAI MAWAR UTARA" to "Grocery",
            ),
        )
        assertEquals("Minyak", d.lookup("PSS SUNGAI MAWAR UTARA"))
        assertEquals("PSS MAWAR", Kedai.fingerprint("PSS SUNGAI MAWAR UTARA"))
        val taught = dict(mapOf("PSS MAWAR" to "Grocery"), "PSS SUNGAI MAWAR UTARA" to "Minyak")
        assertEquals("Grocery", taught.lookup("PSS SUNGAI MAWAR UT SP SELANGOR"))
    }

    @Test
    fun `branch codes fall through to a unanimous leading token`() {
        val d = dict(history = arrayOf("ECOSHOP - ABC 1 SP SHAH ALAM" to "Family", "ECOSHOP - ABC 4" to "Family"))
        assertEquals("Family", d.lookup("ECOSHOP - XYZ 2"))
    }

    @Test
    fun `a contested leading token is no evidence`() {
        val d = dict(history = arrayOf("SUPER ALFA-ALAM SP BDR SHAH ALAM" to "Makan", "SUPER BETA-JAYA PETALING JAYA" to "Grocery"))
        assertNull(d.lookup("SUPER GAMMA MART"))
        assertEquals("Grocery", d.lookup("SUPER BETA-JAYA"))
    }

    @Test
    fun `short leading tokens never stand alone`() {
        val d = dict(history = arrayOf("KFC-TAMAN 15 MELAKA" to "Makan"))
        assertNull(d.lookup("KFC-CONTOH MALL"))
        assertEquals("Makan", d.lookup("KFC-TAMAN 15-MELAKA MYS"))
    }

    @Test
    fun `truncated and generic rules now fire`() {
        assertEquals("Bills", Categorizer.categorize("PENGURUSAN AIR SELANGO KUALA LUMPUR MYS", ""))
        assertEquals("Makan", Categorizer.categorize("D ONE RESTAURANT", ""))
        assertEquals("Health", Categorizer.categorize("CONTOH EYE SPECIALIST KUALA LUMPUR MYS", ""))
        assertEquals("Bills", Categorizer.categorize("Maybank Islamic Financing", ""))
        assertEquals("Transport", Categorizer.categorize("2CP*BusOnline Ticket Kuala Lumpur MYS", ""))
    }
}
