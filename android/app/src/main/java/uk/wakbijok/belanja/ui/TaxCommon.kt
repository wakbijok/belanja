package uk.wakbijok.belanja.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Locale
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.api.TaxSummary
import uk.wakbijok.belanja.ui.theme.LocalHumColors

// ---------------------------------------------------------------------------
// Cukai (LHDN relief) shared helpers — colors, names, suggestion rules and the
// relief picker sheet (design/cukai-mockup-v1.html). Cap/room numbers always
// come from the server summary; nothing here hardcodes YA figures.
// ---------------------------------------------------------------------------

/** All YA2026 relief codes, in display order (picker & overview fallback). */
val RELIEF_CODES = listOf(
    "medical_self", "medical_parents", "lifestyle", "sports", "education",
    "childcare", "oku_equipment", "breastfeeding", "ev_green", "vm2026", "zakat",
)

/** Current Year of Assessment (spending year). */
fun currentYa(): Int = Calendar.getInstance().get(Calendar.YEAR)

/**
 * Relief accent: reuse the existing Hum category colors by nearest hue
 * (design decision — no new tokens in HumColors).
 */
@Composable
@ReadOnlyComposable
fun reliefColor(code: String): Color {
    val hum = LocalHumColors.current
    return when (code) {
        "medical_self" -> hum.catHealth
        "medical_parents" -> hum.catBills
        "lifestyle" -> hum.catOnline
        "sports", "ev_green" -> hum.catGrocery
        "education" -> hum.catTransport
        "childcare" -> hum.catFamily
        "oku_equipment" -> hum.catLain
        "breastfeeding" -> hum.catMakan
        "vm2026" -> hum.coral
        "zakat" -> hum.mint
        else -> hum.catLain
    }
}

/**
 * Display name: API nameMs/nameEn by app locale when the summary is cached;
 * a local fallback otherwise (offline / summary not loaded yet), so the tag
 * row and suggestion card never show a bare code.
 */
fun reliefName(code: String, summary: TaxSummary?): String {
    val en = Locale.getDefault().language == "en"
    summary?.reliefs?.firstOrNull { it.code == code }?.let {
        return if (en) it.nameEn.ifBlank { it.nameMs } else it.nameMs.ifBlank { it.nameEn }
    }
    return when (code) {
        "medical_self" -> if (en) "Medical (self & family)" else "Perubatan (diri & keluarga)"
        "medical_parents" -> if (en) "Parents' medical" else "Perubatan ibu bapa"
        "lifestyle" -> if (en) "Lifestyle" else "Gaya Hidup"
        "sports" -> if (en) "Sports (additional)" else "Sukan (tambahan)"
        "education" -> if (en) "Education fees" else "Yuran pengajian"
        "childcare" -> if (en) "Childcare" else "Taska / tadika"
        "oku_equipment" -> if (en) "Disability (OKU) equipment" else "Peralatan OKU"
        "breastfeeding" -> if (en) "Breastfeeding equipment" else "Peralatan penyusuan"
        "ev_green" -> if (en) "EV / green" else "EV / hijau"
        "vm2026" -> "Visit Malaysia"
        "zakat" -> if (en) "Zakat & Fitrah (rebate)" else "Zakat & Fitrah (rebat)"
        else -> code
    }
}

// ---- Local suggestion rules (system suggests, user confirms — never auto) ----

private val PHRASE_RULES = listOf(
    // medical_self
    "FARMASI" to "medical_self", "PHARMACY" to "medical_self",
    "KLINIK" to "medical_self", "CLINIC" to "medical_self",
    "HOSPITAL" to "medical_self", "DENTAL" to "medical_self",
    // lifestyle: books & internet
    "PUSTAKA" to "lifestyle", "POPULAR" to "lifestyle", "BOOK" to "lifestyle",
    "UNIFI" to "lifestyle", "STREAMYX" to "lifestyle",
    "TIME DOTCOM" to "lifestyle", "INTERNET" to "lifestyle",
    // sports
    "GYM" to "sports", "FITNESS" to "sports", "ANYTIME" to "sports",
    // vm2026 attractions & theme parks
    "MUZIUM" to "vm2026", "MUSEUM" to "vm2026", "AQUARIA" to "vm2026",
    "FARM IN THE CITY" to "vm2026", "SUNWAY LAGOON" to "vm2026",
    "LEGOLAND" to "vm2026", "GENTING SKYWORLD" to "vm2026",
    // childcare
    "TASKA" to "childcare", "TADIKA" to "childcare",
    // ev_green
    "GENTARI" to "ev_green", "CHARGEV" to "ev_green",
    "JOMCHARGE" to "ev_green", "SHELL RECHARGE" to "ev_green",
    // zakat
    "ZAKAT" to "zakat",
)

/** Short names that must match a whole token ("ZOO" must not hit "ZOOM"). */
private val TOKEN_RULES = mapOf(
    "MPH" to "lifestyle",
    "ZOO" to "vm2026",
    "LZS" to "zakat",
    "PPZ" to "zakat",
    "MAIWP" to "zakat",
)

/**
 * Local merchant→relief suggestion. Returns a relief code or null. Only a
 * suggestion: the UI always asks for confirmation with a caveat line.
 */
fun suggestRelief(merchant: String?, category: String): String? {
    val hay = merchant.orEmpty().uppercase(Locale.ROOT)
    if (hay.isNotBlank()) {
        PHRASE_RULES.firstOrNull { (kw, _) -> kw in hay }?.let { return it.second }
        val tokens = hay.split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }.toSet()
        TOKEN_RULES.entries.firstOrNull { it.key in tokens }?.let { return it.value }
    }
    // Weak fallback: health-categorized spend is usually claimable medical.
    if (category == "Health") return "medical_self"
    return null
}

/** Caveat line shown under a suggestion (OTC tak layak, jam pintar tak layak…). */
fun reliefCaveatRes(code: String): Int = when (code) {
    "medical_self", "medical_parents" -> R.string.relief_caveat_medical
    "lifestyle" -> R.string.relief_caveat_lifestyle
    "sports" -> R.string.relief_caveat_sports
    "vm2026" -> R.string.relief_caveat_vm
    else -> R.string.relief_caveat_generic
}

// ---- "Apa yang layak" static eligibility lines (mockup screen 02) -----------

enum class EligMark { OK, NO, SPORTS }

data class EligLine(val textRes: Int, val mark: EligMark)

/** Static per-code eligibility lines; null = no info card for this code. */
fun eligibilityLines(code: String): List<EligLine>? = when (code) {
    "lifestyle" -> listOf(
        EligLine(R.string.elig_life_1, EligMark.OK),
        EligLine(R.string.elig_life_2, EligMark.OK),
        EligLine(R.string.elig_life_3, EligMark.OK),
        EligLine(R.string.elig_life_4, EligMark.NO),
        EligLine(R.string.elig_life_5, EligMark.SPORTS),
    )
    "sports" -> listOf(
        EligLine(R.string.elig_sport_1, EligMark.OK),
        EligLine(R.string.elig_sport_2, EligMark.OK),
        EligLine(R.string.elig_sport_3, EligMark.OK),
        EligLine(R.string.elig_sport_4, EligMark.NO),
    )
    "medical_self", "medical_parents" -> listOf(
        EligLine(R.string.elig_med_1, EligMark.OK),
        EligLine(R.string.elig_med_2, EligMark.OK),
        EligLine(R.string.elig_med_3, EligMark.OK),
        EligLine(R.string.elig_med_4, EligMark.OK),
        EligLine(R.string.elig_med_5, EligMark.NO),
    )
    else -> null
}

// ---- Money helpers -----------------------------------------------------------

/** Bare whole-ringgit figure for "/ 2,500" cap captions. */
internal fun rmBareWhole(cents: Long): String = "%,d".format(Locale.getDefault(), cents / 100)

// ---- Shared tiles -------------------------------------------------------------

/** 34dp rounded tile with the relief color dot (mockup .ic). */
@Composable
fun ReliefIconTile(code: String, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val color = reliefColor(code)
    Box(
        modifier
            .size(size)
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size / 3).background(color, CircleShape))
    }
}

// ---- Relief picker sheet (mockup screen 04) ------------------------------------

/**
 * ModalBottomSheet listing all 11 reliefs with room left per row. Ordering:
 * suggested first, then reliefs with room, then full ones; "Tak layak" at the
 * bottom sets the explicit none tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliefPickerSheet(
    summary: TaxSummary?,
    suggested: String?,
    current: String?,
    onPick: (String) -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hum = LocalHumColors.current
    val ya = summary?.ya ?: currentYa()
    val byCode = summary?.reliefs?.associateBy { it.code }.orEmpty()

    fun isFull(code: String): Boolean {
        val r = byCode[code] ?: return false
        val room = r.roomCents ?: return false
        return room <= 0
    }

    val ordered = RELIEF_CODES.sortedBy { code ->
        when {
            code == suggested -> 0
            !isFull(code) -> 1
            else -> 2
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = hum.paper) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.relief_pick_title, ya),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            ordered.forEach { code ->
                val relief = byCode[code]
                val color = reliefColor(code)
                val selected = code == (current ?: suggested)
                val cap = relief?.capCents
                val room = relief?.roomCents
                val warn = cap != null && room != null && room > 0 && room < cap / 5
                val roomText = when {
                    cap == null -> "∞"
                    room == null -> ""
                    room <= 0 -> stringResource(R.string.relief_full)
                    warn -> stringResource(R.string.relief_room_warn, rmWhole(room))
                    else -> stringResource(R.string.relief_room_left, rmWhole(room))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(
                            if (selected) color.copy(alpha = 0.08f) else hum.paper,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            1.5.dp,
                            if (selected) color else hum.hairline,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { onPick(code) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReliefIconTile(code)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            reliefName(code, summary),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val note = relief?.noteMs.orEmpty()
                        if (note.isNotBlank()) {
                            Text(
                                note,
                                style = MaterialTheme.typography.labelSmall,
                                color = hum.ink60,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (roomText.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            roomText,
                            style = MaterialTheme.typography.labelMedium.tnum(),
                            fontWeight = FontWeight.Bold,
                            color = if (warn) hum.coral else hum.ink60,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SoftButton(
                stringResource(R.string.relief_none),
                onClick = onNone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
