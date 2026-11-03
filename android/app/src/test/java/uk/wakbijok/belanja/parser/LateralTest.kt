package uk.wakbijok.belanja.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.wakbijok.belanja.data.OwnAccount

/**
 * The lateral-transfer gate decides what never enters the ledger, so a wrong
 * answer here is silent money — either invisible spending (over-ignoring) or
 * double counts (under-ignoring). Cases mirror real bank notification shapes
 * with synthetic names and last-4 digits (not production account data).
 */
class LateralTest {

    // Synthetic fixture identity — keep tests deterministic without PII.
    private val name = "Ahmad Bin Ali"
    private val accounts = listOf(
        OwnAccount(1, "MAE", "1111"),
        OwnAccount(2, "TnG", "2222"),
        OwnAccount(3, "Shopeepay", "3333"),
        OwnAccount(4, "CIMB", "4444"),
    )

    private fun lateral(
        merchant: String?,
        title: String,
        text: String,
        accs: List<OwnAccount> = accounts,
    ) = Categorizer.isLateral(merchant, title, text, name, accs)

    // ---- registry flexibility: the question that prompted this test --------

    @Test
    fun `shopeepay topup is lateral while shopeepay is registered`() {
        assertTrue(lateral("SHOPEEPAY TOP UP", "Maybank2u: Card Transaction", "SHOPEEPAY TOP UP RM 50.00"))
    }

    @Test
    fun `removing shopeepay from the registry makes its topup spending again`() {
        val without = accounts.filter { it.label != "Shopeepay" }
        assertFalse(lateral("SHOPEEPAY TOP UP", "Maybank2u: Card Transaction", "SHOPEEPAY TOP UP RM 50.00", without))
    }

    @Test
    fun `an empty registry keeps the default keyword protection`() {
        assertTrue(lateral("SHOPEEPAY TOP UP", "Maybank2u: Card Transaction", "SHOPEEPAY TOP UP RM 50.00", emptyList()))
    }

    @Test
    fun `generic transfer keywords stay lateral regardless of registry`() {
        // Card settlement is never external spending, registered or not.
        assertTrue(
            lateral(
                "Maybank Master Card", "Maybank2u: Payments",
                "Successful payment of RM 1,000.00 to Maybank Master Card. REF:ABC123456X",
                emptyList(),
            )
        )
    }

    // ---- observed-shape cases ---------------------------------------------

    @Test
    fun `mae topup from own account is lateral`() {
        assertTrue(
            lateral(
                null, "Maybank2u: MAE Wallet",
                "You've successfully topped up RM 110.00 from account ending 1111 to your MAE Wallet balance.",
            )
        )
    }

    @Test
    fun `incoming duitnow credit is lateral`() {
        assertTrue(
            lateral(
                null, "CIMB OCTO MY",
                "You've received RM200.00 in your account ending 4444 on 29-JUL-2026.",
            )
        )
    }

    @Test
    fun `duitnow to own TRUNCATED name is lateral`() {
        // Banks often truncate the final letter of long Malay names.
        assertTrue(
            lateral(
                "AHMAD BIN AL", "DuitNow Transfer is successful!",
                "You have successfully transferred RM 5.00 to AHMAD BIN AL.",
            )
        )
    }

    @Test
    fun `duitnow to someone else is spending`() {
        assertFalse(
            lateral(
                "SITI BINTI HASSAN", "DuitNow Transfer is successful!",
                "You have successfully transferred RM 50.00 to SITI BINTI HASSAN.",
            )
        )
    }

    // ---- must NOT over-ignore ---------------------------------------------

    @Test
    fun `ordinary card spending that names own card is NOT lateral`() {
        // "spent" is not a transfer verb; the last4 alone must not trigger.
        assertFalse(
            lateral(
                "PENGURUSAN AIR SELANGO KUALA LUMPUR MYS", "Maybank2u: Card Transaction",
                "You've just spent RM 20.00 at PENGURUSAN AIR SELANGO KUALA LUMPUR MYS " +
                    "with your MasterCard ending 9999.",
            )
        )
    }

    @Test
    fun `online fee via payment gateway is spending`() {
        assertFalse(
            lateral(
                "BADAN CONTOH M'SIA KUALA LUMPUR MYS", "Maybank2u: Card Transaction",
                "You've just spent RM 50.00 at BADAN CONTOH M'SIA KUALA LUMPUR MYS " +
                    "with your MasterCard ending 9999.",
            )
        )
    }

    @Test
    fun `qr payment mentioning the wallet in the BODY is still spending`() {
        // Wallet name in the body ("via MAE") must not match — labels are
        // matched against merchant/title only.
        assertFalse(
            lateral(
                "RESTORAN NASI KANDAR", "Payment successful",
                "You have made a payment of RM 12.50 to RESTORAN NASI KANDAR via MAE.",
            )
        )
    }

    // ---- inbound: income is out of scope, ignored unconditionally ----------

    @Test
    fun `funds received with curly apostrophe and masked digits is inbound`() {
        assertTrue(
            Categorizer.isInbound(
                "Maybank2u: Funds Received",
                "You\u2019ve just received RM 2,500.00 in your account ending ***1111. REF: 20260101TESTREF01",
            )
        )
    }

    @Test
    fun `cimb credit is inbound`() {
        assertTrue(
            Categorizer.isInbound(
                "CIMB OCTO MY",
                "You've received RM200.00 in your account ending 4444 on 29-JUL-2026.",
            )
        )
    }

    @Test
    fun `ordinary spending is not inbound`() {
        assertFalse(
            Categorizer.isInbound(
                "Maybank2u: Card Transaction",
                "You've just spent RM 20.00 at PENGURUSAN AIR SELANGO with your MasterCard ending 9999.",
            )
        )
    }

    @Test
    fun `merchant saying they received your payment is not inbound`() {
        // "received" alone must not trigger — it needs "your account".
        assertFalse(
            Categorizer.isInbound(
                "Payment successful",
                "Tealive has received your payment of RM 5.00.",
            )
        )
    }

    @Test
    fun `masked last4 still matches the registry after normalisation`() {
        assertTrue(
            lateral(
                null, "Maybank2u: Transfer",
                "Transfer of RM 100.00 from account ending ***1111 completed.",
            )
        )
    }
}
