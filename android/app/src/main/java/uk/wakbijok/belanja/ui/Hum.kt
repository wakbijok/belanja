package uk.wakbijok.belanja.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.ui.theme.LocalHumColors

// ---------------------------------------------------------------------------
// Hum shared components (design/design.md, LOCKED): 18dp cards, pill push
// buttons, mono uppercase labels, tabular numerals on every money figure.
// ---------------------------------------------------------------------------

val CardShape = RoundedCornerShape(18.dp)

/** Every money figure renders with tabular numerals (design.md). */
fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

/** JetBrains Mono uppercase label, 11sp, tracking 0.10em. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (color == Color.Unspecified) LocalHumColors.current.ink60 else color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Rounded 18dp card on the given surface color (default paper-2 tint). */
@Composable
fun HumCard(
    modifier: Modifier = Modifier,
    color: Color = LocalHumColors.current.paper2,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier, shape = CardShape, color = color, content = content)
}

/**
 * Primary pear pill button with the Hum "push" feel: the face presses down
 * 3dp while touched — no scale, no spring overshoot (design.md).
 */
@Composable
fun PushButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    container: Color = LocalHumColors.current.pear,
    contentColor: Color = Color(0xFF12171B), // ink stays dark on pear in both themes
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
        ),
        contentPadding = if (compact) {
            androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        } else {
            ButtonDefaults.ContentPadding
        },
        modifier = modifier.graphicsLayer { translationY = if (pressed) 3.dp.toPx() else 0f },
    ) {
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Secondary soft button: paper-2 face, ink text. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    PushButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        compact = compact,
        container = LocalHumColors.current.paper2,
        contentColor = LocalHumColors.current.ink,
    )
}

/** Coral-tinted destructive pill. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    PushButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        container = hum.coral.copy(alpha = 0.14f),
        contentColor = hum.coral,
    )
}

/** Dashed hairline, as the mockup draws under day headers and between rows. */
@Composable
fun DashedDivider(modifier: Modifier = Modifier) {
    val color = LocalHumColors.current.hairline
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
    }
}

/** The pulsing pear dot from the wordmark; gentle by design (reduced motion). */
@Composable
fun PulsingDot(size: Dp, modifier: Modifier = Modifier, color: Color = LocalHumColors.current.pear) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(color, CircleShape)
    )
}

/** Wordmark "Belanja" + pear dot. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, dotColor: Color = LocalHumColors.current.pear) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.width(3.dp))
        PulsingDot(9.dp, color = dotColor)
    }
}

// ---- Categories ------------------------------------------------------------

/** Display name for an internal category key ("Makan" → Makan / Food). */
@Composable
fun categoryLabel(category: String): String = stringResource(
    when (category) {
        "Grocery" -> R.string.cat_grocery
        "Makan" -> R.string.cat_makan
        "Minyak" -> R.string.cat_minyak
        "Online" -> R.string.cat_online
        "Bills" -> R.string.cat_bills
        "Transport" -> R.string.cat_transport
        "Health" -> R.string.cat_health
        "Family" -> R.string.cat_family
        "Pindahan" -> R.string.cat_pindahan
        else -> R.string.cat_lain
    }
)

fun categoryIcon(category: String): ImageVector = when (category) {
    "Grocery" -> Icons.Outlined.ShoppingCart
    "Makan" -> Icons.Outlined.Restaurant
    "Minyak" -> Icons.Outlined.LocalGasStation
    "Online" -> Icons.Outlined.ShoppingBag
    "Bills" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "Transport" -> Icons.Outlined.DirectionsBus
    "Health" -> Icons.Outlined.FavoriteBorder
    "Family" -> Icons.Outlined.Group
    "Pindahan" -> Icons.Outlined.SwapHoriz
    else -> Icons.Outlined.MoreHoriz
}

/** 36dp rounded tile, category color at 16% over paper, icon in full color. */
@Composable
fun CategoryIconTile(category: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val color = categoryColor(category)
    Box(
        modifier
            .size(size)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            categoryIcon(category),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(size / 2),
        )
    }
}

// ---- Source chips ------------------------------------------------------------

/** Short label for a notification source package ("MAE", "TNG", …). */
@Composable
fun sourceLabel(sourcePkg: String): String {
    val p = sourcePkg.lowercase()
    return when {
        p == "manual" -> stringResource(R.string.src_manual)
        p == "statement" -> stringResource(R.string.src_statement)
        "mae" in p || "maybank" in p -> "MAE"
        "tng" in p || "touchngo" in p || "tngd" in p -> "TNG"
        "setel" in p -> "Setel"
        "shopee" in p -> "ShopeePay"
        "grab" in p -> "GrabPay"
        // Full name on purpose: ShopeePay abbreviates ITSELF as "SPay" in its
        // own transactions, so the short form pointed at the wrong wallet.
        "spay" in p || "samsung" in p -> "SamsungPay"
        else -> sourcePkg.substringAfterLast('.').take(8).uppercase()
    }
}

/** Tiny bordered mono chip: MAE / TNG / Setel / ShopeePay. */
@Composable
fun SourceChip(sourcePkg: String, modifier: Modifier = Modifier) {
    val hum = LocalHumColors.current
    Text(
        sourceLabel(sourcePkg).uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = androidx.compose.ui.unit.TextUnit(
                9f, androidx.compose.ui.unit.TextUnitType.Sp
            ),
        ),
        color = hum.ink60,
        maxLines = 1,
        modifier = modifier
            .background(hum.paper2, RoundedCornerShape(5.dp))
            .border(1.dp, hum.hairline, RoundedCornerShape(5.dp))
            .padding(horizontal = 4.dp, vertical = 0.dp),
    )
}

// ---- Month nav ------------------------------------------------------------

@Composable
fun MonthNav(
    label: String,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.IconButton(onClick = onPrev) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.month_prev),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        androidx.compose.material3.IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.month_next),
            )
        }
        trailing?.invoke()
    }
}

// ---- Empty state ------------------------------------------------------------

/** Three colored dots + title + body, per mockup screen 10. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val hum = LocalHumColors.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(14.dp).background(hum.pear, CircleShape))
            Box(Modifier.size(14.dp).background(hum.cyan, CircleShape))
            Box(Modifier.size(14.dp).background(hum.coral, CircleShape))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = hum.ink60,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ---- Money & date formatting ------------------------------------------------

/** "RM 4,000" — whole-ringgit form used in sub-lines and captions. */
internal fun rmWhole(cents: Long): String =
    "RM %,d".format(Locale.getDefault(), cents / 100)

/** Day-group header: "JUMAAT · 24 JUL". */
internal fun dayHeaderLabel(millis: Long, locale: Locale = Locale.getDefault()): String {
    val d = Date(millis)
    val dow = SimpleDateFormat("EEEE", locale).format(d)
    val dm = SimpleDateFormat("d MMM", locale).format(d)
    return "$dow · $dm".uppercase(locale)
}
