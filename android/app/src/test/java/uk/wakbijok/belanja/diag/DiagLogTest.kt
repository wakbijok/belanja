package uk.wakbijok.belanja.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reject lines carry raw notification text; codes and account fragments must not survive. */
class DiagLogTest {

    @Test
    fun `runs of four or more digits are masked to the same length`() {
        assertEquals(
            "Your TAC is ###### for transfer from account ending ****####.",
            DiagLog.maskDigits("Your TAC is 482913 for transfer from account ending ****1111."),
        )
        assertEquals("REF ################", DiagLog.maskDigits("REF 2026010112345678"))
    }

    @Test
    fun `amounts, dates and short numbers stay readable`() {
        assertEquals("RM 12.34 at KEDAI 7 on 29-07", DiagLog.maskDigits("RM 12.34 at KEDAI 7 on 29-07"))
        assertEquals("RM 2,500.00", DiagLog.maskDigits("RM 2,500.00"))
        assertEquals("RM ####.00", DiagLog.maskDigits("RM 1000.00"))
    }
}
