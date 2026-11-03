package uk.wakbijok.belanja.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Title/text shapes as captured on the soak phone (merchants, payees, amounts and digits synthetic). */
class InstrumentTest {

    @Test
    fun `maybank card alert is card`() {
        assertEquals(
            Instrument.CARD,
            Instrument.of("com.maybank2u.life", "Maybank2u: Card Transaction",
                "You've just spent RM 10.00 at KEDAI CONTOH with your MasterCard Platinum ending 1234."),
        )
    }

    @Test
    fun `maybank payments, scan and pay, atm are account money`() {
        assertEquals(Instrument.ACCOUNT, Instrument.of("com.maybank2u.life", "Maybank2u: Payments",
            "Successful payment of RM 500.00 to Maybank Islamic Financing. REF:1"))
        assertEquals(Instrument.ACCOUNT, Instrument.of("com.maybank2u.life", "Maybank2u: Scan & Pay",
            "RM 5.00 paid to PAYEE A"))
        assertEquals(Instrument.ACCOUNT, Instrument.of("com.maybank2u.life", "ATM Cash-out", "RM 200.00"))
        assertEquals(Instrument.ACCOUNT, Instrument.of("com.maybank2u.life", "Cardless Withdrawal", "RM 200.00"))
    }

    @Test
    fun `samsung wallet is always card`() {
        assertEquals(Instrument.CARD,
            Instrument.of("com.samsung.android.spay", "Maybank MasterCard Plat", "KEDAI CONTOH RM 100.00"))
    }

    @Test
    fun `grab announcing a card charge is card`() {
        assertEquals(Instrument.CARD, Instrument.of("com.grabtaxi.passenger", "",
            "Your MasterCard 1234 has been charged MYR 25.00 for booking X"))
    }

    @Test
    fun `tng toll and transfer are balance money, auto-debit is wallet`() {
        val tng = "my.com.tngdigital.ewallet"
        assertEquals(Instrument.ACCOUNT, Instrument.of(tng, "Successful Toll Payment", "RM1.66 deducted"))
        assertEquals(Instrument.ACCOUNT, Instrument.of(tng, "Transfer Successful.", "RM4.00 sent"))
        assertEquals(Instrument.ACCOUNT, Instrument.of(tng, "DuitNow Payment", "RM10.00 to PAYEE A"))
        assertEquals(Instrument.WALLET, Instrument.of(tng, "Auto-Debit Payment", "RM20.00 to Google"))
    }

    @Test
    fun `statement imports count as card`() {
        assertEquals(Instrument.CARD, Instrument.of("statement", "", ""))
    }
}
