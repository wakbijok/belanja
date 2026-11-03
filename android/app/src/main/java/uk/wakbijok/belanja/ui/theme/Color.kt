package uk.wakbijok.belanja.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Hum design tokens (design/design.md, LOCKED 25-07-2026).
 * Values are sRGB conversions of the OKLCH tokens; light paper is warm cream
 * (hue 95), dark paper shifts cool (hue 260) — never an inversion.
 */
@Immutable
data class HumColors(
    val paper: Color,
    val paper2: Color,
    val paper3: Color,
    val ink: Color,
    val pear: Color,       // primary action
    val pearDeep: Color,   // pressed edge / progress fill
    val cyan: Color,       // links, info
    val coral: Color,      // over-budget / needs-review only
    val mint: Color,       // success
    val catGrocery: Color,
    val catMakan: Color,
    val catMinyak: Color,
    val catOnline: Color,
    val catBills: Color,
    val catTransport: Color,
    val catHealth: Color,
    val catFamily: Color,
    val catPindahan: Color,
    val catLain: Color,
) {
    val hairline: Color get() = ink.copy(alpha = 0.12f)
    val ink60: Color get() = ink.copy(alpha = 0.60f)
    val ink40: Color get() = ink.copy(alpha = 0.40f)

    fun category(name: String): Color = when (name) {
        "Grocery" -> catGrocery
        "Makan" -> catMakan
        "Minyak" -> catMinyak
        "Online" -> catOnline
        "Bills" -> catBills
        "Transport" -> catTransport
        "Health" -> catHealth
        "Family" -> catFamily
        "Pindahan" -> catPindahan
        else -> catLain
    }
}

val HumLight = HumColors(
    paper = Color(0xFFF7F5EC),
    paper2 = Color(0xFFEEEBDF),
    paper3 = Color(0xFFE5E1D3),
    ink = Color(0xFF12171B),
    pear = Color(0xFFF6CE00),
    pearDeep = Color(0xFFBE9B00),
    cyan = Color(0xFF009FEF),
    coral = Color(0xFFFF3A5D),
    mint = Color(0xFF66DA85),
    catGrocery = Color(0xFF36A558),
    catMakan = Color(0xFFDD881B),
    catMinyak = Color(0xFFDB4241),
    catOnline = Color(0xFF9763CC),
    catBills = Color(0xFF0089D0),
    catTransport = Color(0xFF00A8B8),
    catHealth = Color(0xFFD4599B),
    catFamily = Color(0xFFD3A82D),
    catPindahan = Color(0xFF8894B8),
    catLain = Color(0xFF727C86),
)

val HumDark = HumColors(
    paper = Color(0xFF171B21),
    paper2 = Color(0xFF20242C),
    paper3 = Color(0xFF292E37),
    ink = Color(0xFFEAE8DF),
    pear = Color(0xFFEDC812),
    pearDeep = Color(0xFF9F8400),
    cyan = Color(0xFF22B8FB),
    coral = Color(0xFFFF646F),
    mint = Color(0xFF6FD087),
    catGrocery = Color(0xFF5BBD74),
    catMakan = Color(0xFFED9D44),
    catMinyak = Color(0xFFEF6661),
    catOnline = Color(0xFFB386E4),
    catBills = Color(0xFF44A8E7),
    catTransport = Color(0xFF26C0CF),
    catHealth = Color(0xFFE97AB2),
    catFamily = Color(0xFFE1B850),
    catPindahan = Color(0xFF9FABCE),
    catLain = Color(0xFF8F9AA4),
)

val LocalHumColors = staticCompositionLocalOf { HumLight }
