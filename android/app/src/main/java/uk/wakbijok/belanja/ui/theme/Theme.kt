package uk.wakbijok.belanja.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// Dynamic color is deliberately OFF: the wallpaper palette would replace the
// Hum brand (design/design.md). Two fixed schemes, system-driven switch.

private val LightColors = lightColorScheme(
    primary = HumLight.pear,
    onPrimary = HumLight.ink,
    primaryContainer = HumLight.pear.copy(alpha = 0.14f),
    onPrimaryContainer = HumLight.ink,
    secondary = HumLight.cyan,
    onSecondary = HumLight.paper,
    tertiary = HumLight.coral,
    onTertiary = HumLight.paper,
    background = HumLight.paper,
    onBackground = HumLight.ink,
    surface = HumLight.paper,
    onSurface = HumLight.ink,
    surfaceVariant = HumLight.paper2,
    onSurfaceVariant = HumLight.ink60,
    surfaceContainer = HumLight.paper2,
    surfaceContainerHigh = HumLight.paper3,
    outline = HumLight.hairline,
    outlineVariant = HumLight.hairline,
    error = HumLight.coral,
    onError = HumLight.paper,
)

private val DarkColors = darkColorScheme(
    primary = HumDark.pear,
    onPrimary = HumLight.ink, // ink stays dark on the pear button in both themes
    primaryContainer = HumDark.pear.copy(alpha = 0.14f),
    onPrimaryContainer = HumDark.ink,
    secondary = HumDark.cyan,
    onSecondary = HumDark.paper,
    tertiary = HumDark.coral,
    onTertiary = HumDark.paper,
    background = HumDark.paper,
    onBackground = HumDark.ink,
    surface = HumDark.paper,
    onSurface = HumDark.ink,
    surfaceVariant = HumDark.paper2,
    onSurfaceVariant = HumDark.ink60,
    surfaceContainer = HumDark.paper2,
    surfaceContainerHigh = HumDark.paper3,
    outline = HumDark.hairline,
    outlineVariant = HumDark.hairline,
    error = HumDark.coral,
    onError = HumDark.paper,
)

@Composable
fun BelanjaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val hum = if (darkTheme) HumDark else HumLight
    CompositionLocalProvider(LocalHumColors provides hum) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = HumTypography,
            content = content,
        )
    }
}
