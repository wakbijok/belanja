package uk.wakbijok.belanja.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.llm.MonthStory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/**
 * Analisis — dua segmen (cukai-mockup screen 01): Belanja (6-month bars,
 * "Apa yang berubah", kedai paling kerap — mockup 05) dan Cukai (LHDN relief
 * overview for the selected Year of Assessment).
 */
@Composable
fun AnalysisScreen(
    viewModel: MainViewModel,
    onOpenRelief: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    var taxMode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                stringResource(R.string.nav_insights),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.height(8.dp))

        // Segmented Belanja | Cukai toggle (mockup .mode pill).
        Row(
            Modifier
                .background(hum.paper2, CircleShape)
                .padding(3.dp)
        ) {
            ModeChip(stringResource(R.string.tax_mode_spend), selected = !taxMode) {
                taxMode = false
            }
            ModeChip(stringResource(R.string.tax_mode_tax), selected = taxMode) {
                taxMode = true
            }
        }
        Spacer(Modifier.height(6.dp))

        if (taxMode) {
            TaxOverview(viewModel, onOpenRelief)
        } else {
            SpendAnalysis(viewModel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val hum = LocalHumColors.current
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (selected) hum.ink else hum.ink60,
        modifier = Modifier
            .background(if (selected) hum.paper else hum.paper2, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    )
}

// ---- Segmen Belanja (unchanged behavior) --------------------------------------

@Composable
private fun SpendAnalysis(viewModel: MainViewModel) {
    val hum = LocalHumColors.current
    val monthLabel by viewModel.monthLabel.collectAsStateWithLifecycle()
    val monthKey by viewModel.monthKey.collectAsStateWithLifecycle()
    val canGoNext by viewModel.canGoNext.collectAsStateWithLifecycle()
    val series by viewModel.monthsSeries.collectAsStateWithLifecycle()
    val stats by viewModel.categoryStats.collectAsStateWithLifecycle()
    val prevTotals by viewModel.prevCategoryTotals.collectAsStateWithLifecycle()
    val prevLabel by viewModel.prevMonthLabel.collectAsStateWithLifecycle()
    val merchants by viewModel.topMerchants.collectAsStateWithLifecycle()

    LaunchedEffect(monthKey) { viewModel.refreshMonthsSeries() }

    Column(Modifier.fillMaxWidth()) {
        MonthNav(
            label = monthLabel,
            canGoNext = canGoNext,
            onPrev = viewModel::prevMonth,
            onNext = viewModel::nextMonth,
        )
        Spacer(Modifier.height(4.dp))

        // Cerita bulan — only on the current month; history months speak for
        // themselves through the numbers below.
        if (canGoNext.not()) {
            StoryCard()
            Spacer(Modifier.height(10.dp))
        }

        HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                MonoLabel(stringResource(R.string.chart_label))
                Spacer(Modifier.height(12.dp))
                MonthBars(
                    points = series,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.what_changed),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        InsightLines(stats = stats, prevTotals = prevTotals, prevLabel = prevLabel)

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.top_merchants),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        if (merchants.isEmpty()) {
            Text(
                stringResource(R.string.empty_month),
                style = MaterialTheme.typography.bodyMedium,
                color = hum.ink60,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            merchants.forEachIndexed { i, m ->
                if (i > 0) DashedDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        m.merchant,
                        style = MaterialTheme.typography.bodyMedium,
                        color = hum.ink60,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.merchant_stat, m.count, rm(m.totalCents)),
                        style = MaterialTheme.typography.bodyMedium.tnum(),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- Segmen Cukai (cukai-mockup screen 01) --------------------------------------

@Composable
private fun TaxOverview(viewModel: MainViewModel, onOpenRelief: (String) -> Unit) {
    val hum = LocalHumColors.current
    val ya by viewModel.taxYa.collectAsStateWithLifecycle()
    val summary by viewModel.taxSummary.collectAsStateWithLifecycle()
    val taxError by viewModel.taxError.collectAsStateWithLifecycle()
    var showEmpty by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ya) { viewModel.refreshTaxSummary() }

    // YA selector: ‹ Tahun Taksiran 2026 ›
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = viewModel::prevYa) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.tax_ya_prev),
            )
        }
        Text(
            stringResource(R.string.tax_ya, ya),
            style = MaterialTheme.typography.titleMedium.tnum(),
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = viewModel::nextYa, enabled = ya < currentYa()) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.tax_ya_next),
            )
        }
    }
    Spacer(Modifier.height(4.dp))

    val s = summary
    val err = taxError
    if (s == null && err != null) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(err),
                style = MaterialTheme.typography.bodyMedium,
                color = hum.coral,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.tax_needs_server),
                style = MaterialTheme.typography.bodySmall,
                color = hum.ink60,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            PushButton(
                stringResource(R.string.tax_retry),
                onClick = viewModel::refreshTaxSummary,
                compact = true,
            )
        }
        return
    }
    if (s == null) {
        Text(
            stringResource(R.string.tax_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = hum.ink60,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }

    // Pear hero: evidenced total + unproven sub-line + cyan nudge.
    HumCard(color = hum.pear.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            MonoLabel(stringResource(R.string.tax_hero_label))
            Text(
                rm(s.totalEvidencedCents),
                style = MaterialTheme.typography.headlineMedium.tnum(),
                fontWeight = FontWeight.ExtraBold,
            )
            if (s.totalUnprovenCents > 0) {
                Text(
                    stringResource(R.string.tax_hero_unproven, rm(s.totalUnprovenCents)),
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = hum.ink60,
                )
            }
            // Nudge: largest room among lifestyle/sports/vm2026 that is already
            // more than half filled — the caps worth actively chasing.
            val nudge = s.reliefs
                .filter { it.code in NUDGE_CODES }
                .filter { r ->
                    val cap = r.capCents
                    val room = r.roomCents
                    cap != null && room != null && room > 0 && room < cap / 2
                }
                .maxByOrNull { it.roomCents ?: 0L }
            if (nudge != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(hum.cyan.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(8.dp)
                            .background(hum.cyan, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.tax_nudge,
                            rm(nudge.roomCents ?: 0L),
                            reliefName(nudge.code, s),
                        ),
                        style = MaterialTheme.typography.bodySmall.tnum(),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // Relief rows: tagged ones always visible, empty ones behind the toggle.
    val (active, empty) = s.reliefs.partition { it.taggedCents > 0 }
    val shownActive = active.sortedByDescending { it.evidencedCents }
    shownActive.forEachIndexed { i, r ->
        if (i > 0) DashedDivider()
        ReliefRow(r, summary = s, onClick = { onOpenRelief(r.code) })
    }
    if (empty.isNotEmpty()) {
        if (showEmpty) {
            empty.forEach { r ->
                DashedDivider()
                ReliefRow(r, summary = s, onClick = { onOpenRelief(r.code) })
            }
        }
        Text(
            if (showEmpty) stringResource(R.string.tax_show_less)
            else stringResource(R.string.tax_show_all, empty.size),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = hum.cyan,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showEmpty = !showEmpty }
                .padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private val NUDGE_CODES = setOf("lifestyle", "sports", "vm2026")

/** One relief line: icon dot tile, name + progress + caption, amount / cap. */
@Composable
private fun ReliefRow(
    r: uk.wakbijok.belanja.api.TaxRelief,
    summary: uk.wakbijok.belanja.api.TaxSummary,
    onClick: () -> Unit,
) {
    val hum = LocalHumColors.current
    val color = reliefColor(r.code)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReliefIconTile(r.code)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                reliefName(r.code, summary),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            val fraction = when {
                r.capCents != null && r.capCents > 0 ->
                    (r.evidencedCents.toFloat() / r.capCents).coerceIn(0f, 1f)
                r.evidencedCents > 0 -> 1f
                else -> 0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(hum.ink.copy(alpha = 0.07f), CircleShape)
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(5.dp)
                            .background(if (r.overCap) hum.coral else color, CircleShape)
                    )
                }
            }
            val caption = buildString {
                append(stringResource(R.string.tax_receipts_n, r.receipts))
                if (r.noteMs.isNotBlank()) append(" · ").append(r.noteMs)
            }
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = hum.ink60,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                rm(r.evidencedCents),
                style = MaterialTheme.typography.bodySmall.tnum(),
                fontWeight = FontWeight.Bold,
                color = if (r.overCap) hum.coral else Color.Unspecified,
            )
            Text(
                r.capCents?.let { "/ ${rmBareWhole(it)}" } ?: "∞",
                style = MaterialTheme.typography.labelSmall.tnum(),
                color = if (r.overCap) hum.coral else hum.ink60,
            )
        }
    }
}

/** Canvas bar chart: rounded tops, current month full pearDeep, others muted. */
@Composable
private fun MonthBars(points: List<MonthPoint>, modifier: Modifier = Modifier) {
    val hum = LocalHumColors.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = hum.ink60
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val max = points.maxOf { it.totalCents }.coerceAtLeast(1L)
        val labelHeightPx = 40f
        val chartHeight = size.height - labelHeightPx
        val slot = size.width / points.size
        val barWidth = slot * 0.62f

        points.forEachIndexed { i, p ->
            val isNow = i == points.lastIndex
            val h = (p.totalCents.toFloat() / max) * (chartHeight * 0.94f)
            val left = slot * i + (slot - barWidth) / 2f
            val top = chartHeight - h.coerceAtLeast(6f)
            drawRoundRect(
                color = if (isNow) hum.pearDeep else hum.pearDeep.copy(alpha = 0.45f),
                topLeft = Offset(left, top),
                size = Size(barWidth, chartHeight - top),
                cornerRadius = CornerRadius(10f, 10f),
            )
            val layout = textMeasurer.measure(p.label, style = labelStyle)
            drawText(
                textLayoutResult = layout,
                color = labelColor,
                topLeft = Offset(
                    slot * i + (slot - layout.size.width) / 2f,
                    chartHeight + 10f,
                ),
            )
        }
    }
}

/** Up to 3 BM sentences computed from this month vs the previous month. */
@Composable
private fun InsightLines(
    stats: List<CategoryStat>,
    prevTotals: Map<String, Long>,
    prevLabel: String,
) {
    val hum = LocalHumColors.current

    data class Insight(val category: String, val cur: Long, val prev: Long) {
        val delta get() = cur - prev
    }

    val insights = stats
        .map { Insight(it.category, it.totalCents, prevTotals[it.category] ?: 0L) }
        .filter { it.prev > 0 || it.cur > 0 }
        .filter { it.prev == 0L || abs(it.delta) * 100 / it.prev >= 5 }
        .sortedByDescending { abs(it.delta) }
        .take(3)

    if (insights.isEmpty()) {
        Text(
            stringResource(R.string.ins_none),
            style = MaterialTheme.typography.bodyMedium,
            color = hum.ink60,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    insights.forEachIndexed { i, ins ->
        if (i > 0) DashedDivider()
        val label = categoryLabel(ins.category)
        val lead: String
        val rest: String
        if (ins.prev == 0L) {
            lead = stringResource(R.string.ins_new_lead, label)
            rest = stringResource(R.string.ins_new_rest, rm(ins.cur))
        } else {
            val pct = (abs(ins.delta) * 100 / ins.prev).toInt()
            lead = stringResource(
                if (ins.delta >= 0) R.string.ins_up_lead else R.string.ins_down_lead,
                label,
                pct,
            )
            rest = stringResource(R.string.ins_rest, prevLabel, rm(ins.cur))
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(8.dp)
                    .background(categoryColor(ins.category), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    withStyle(
                        MaterialTheme.typography.bodyMedium
                            .copy(fontWeight = FontWeight.Bold)
                            .toSpanStyle()
                    ) { append(lead) }
                    append(rest)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}


/**
 * Cerita bulan (otak mockup v2, skrin 08). Every figure inside the paragraph
 * was computed by [MonthStory] before the model saw anything — the model only
 * words them. Failures are loud with a reason and a retry; never a spinner
 * that hangs (the Cukai lesson).
 */
@Composable
private fun StoryCard() {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MonthStory.State>(MonthStory.State.Loading) }

    LaunchedEffect(Unit) {
        state = MonthStory.cached(context)
        if (state is MonthStory.State.Loading) state = MonthStory.ensure(context)
    }

    when (val st = state) {
        MonthStory.State.Off -> Unit
        MonthStory.State.Loading -> HumCard(
            color = hum.cyan.copy(alpha = 0.09f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.story_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = hum.ink60,
                modifier = Modifier.padding(16.dp),
            )
        }
        is MonthStory.State.Ready -> HumCard(
            color = hum.cyan.copy(alpha = 0.09f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                MonoLabel(stringResource(R.string.story_label))
                Spacer(Modifier.height(8.dp))
                Text(st.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.story_by, st.txnCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = hum.ink60,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.story_redo),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = hum.cyan,
                        modifier = Modifier.clickable {
                            state = MonthStory.State.Loading
                            scope.launch { state = MonthStory.ensure(context, force = true) }
                        },
                    )
                }
            }
        }
        is MonthStory.State.Failed -> HumCard(
            color = hum.coral.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.story_fail, st.why),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.story_retry),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = hum.cyan,
                    modifier = Modifier.clickable {
                        state = MonthStory.State.Loading
                        scope.launch { state = MonthStory.ensure(context, force = true) }
                    },
                )
            }
        }
    }
}
