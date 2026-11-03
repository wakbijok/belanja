@file:OptIn(ExperimentalTextApi::class)

package uk.wakbijok.belanja.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import uk.wakbijok.belanja.R

private fun jakarta(weight: FontWeight) = Font(
    R.font.plus_jakarta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun mono(weight: FontWeight) = Font(
    R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Jakarta = FontFamily(
    jakarta(FontWeight.Normal),
    jakarta(FontWeight.Medium),
    jakarta(FontWeight.SemiBold),
    jakarta(FontWeight.Bold),
    jakarta(FontWeight.ExtraBold),
)

val JetMono = FontFamily(mono(FontWeight.Normal), mono(FontWeight.Medium))

private val default = Typography()

private fun TextStyle.jakarta(tracking: Float? = null) = copy(
    fontFamily = Jakarta,
    letterSpacing = tracking?.em ?: letterSpacing,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Hum type scale: rounded sans everywhere, mono for uppercase labels,
 *  tight tracking on display sizes, tabular numerals set per-money-text. */
val HumTypography = Typography(
    displayLarge = default.displayLarge.jakarta(-0.03f).copy(fontWeight = FontWeight.ExtraBold),
    displayMedium = default.displayMedium.jakarta(-0.03f).copy(fontWeight = FontWeight.ExtraBold),
    displaySmall = default.displaySmall.jakarta(-0.025f).copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = default.headlineLarge.jakarta(-0.025f).copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = default.headlineMedium.jakarta(-0.025f).copy(fontWeight = FontWeight.Bold),
    headlineSmall = default.headlineSmall.jakarta(-0.02f).copy(fontWeight = FontWeight.Bold),
    titleLarge = default.titleLarge.jakarta(-0.02f).copy(fontWeight = FontWeight.Bold),
    titleMedium = default.titleMedium.jakarta(-0.01f).copy(fontWeight = FontWeight.SemiBold),
    titleSmall = default.titleSmall.jakarta().copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = default.bodyLarge.jakarta(),
    bodyMedium = default.bodyMedium.jakarta(),
    bodySmall = default.bodySmall.jakarta(),
    labelLarge = default.labelLarge.jakarta().copy(fontWeight = FontWeight.SemiBold),
    labelMedium = default.labelMedium.jakarta().copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(
        fontFamily = JetMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.1.em,
    ),
)
