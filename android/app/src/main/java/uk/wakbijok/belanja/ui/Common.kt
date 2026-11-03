package uk.wakbijok.belanja.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import uk.wakbijok.belanja.ui.theme.LocalHumColors
import java.text.SimpleDateFormat
import java.util.Locale

internal fun rm(cents: Long): String = "RM %,.2f".format(cents / 100.0)

internal val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/** Hum per-category accents — hue identity kept from v0.7, lightness tuned
 *  per theme (design/design.md). Resolves against the active palette. */
@Composable
@ReadOnlyComposable
internal fun categoryColor(category: String): Color =
    LocalHumColors.current.category(category)
