package uk.wakbijok.belanja.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Date
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.data.Cycle
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.ui.theme.LocalHumColors

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    notificationAccessGranted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    batteryUnrestricted: Boolean,
    onFixBattery: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenStatement: () -> Unit,
    onTxnClick: (Txn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val monthLabel by viewModel.monthLabel.collectAsStateWithLifecycle()
    val monthKey by viewModel.monthKey.collectAsStateWithLifecycle()
    val canGoNext by viewModel.canGoNext.collectAsStateWithLifecycle()
    val isCurrentMonth by viewModel.isCurrentMonth.collectAsStateWithLifecycle()
    val totalCents by viewModel.monthTotalCents.collectAsStateWithLifecycle()
    val budgetCents by viewModel.overallBudgetCents.collectAsStateWithLifecycle()
    val stats by viewModel.categoryStats.collectAsStateWithLifecycle()
    val reviewTxns by viewModel.needsReviewTxns.collectAsStateWithLifecycle()
    val statements by viewModel.statements.collectAsStateWithLifecycle()

    LaunchedEffect(monthKey) { viewModel.refreshMonthData() }
    LaunchedEffect(Unit) { viewModel.refreshStatements() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // Header: hamburger + wordmark + gear.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    Icons.Outlined.Menu,
                    contentDescription = stringResource(R.string.action_menu),
                    tint = hum.ink60,
                )
            }
            Wordmark(dotColor = if (totalCents > budgetCents) hum.coral else hum.pear)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.action_settings),
                    tint = hum.ink60,
                )
            }
        }

        MonthNav(
            label = monthLabel,
            canGoNext = canGoNext,
            onPrev = viewModel::prevMonth,
            onNext = viewModel::nextMonth,
        )
        Spacer(Modifier.height(4.dp))

        val cycleDay by viewModel.cycleStartDay.collectAsStateWithLifecycle()
        HeroCard(
            totalCents = totalCents,
            budgetCents = budgetCents,
            isCurrentMonth = isCurrentMonth,
            daysLeft = Cycle.daysLeft(cycleDay),
            topCategory = stats.firstOrNull(),
        )

        // Persediaan: capture permissions still missing.
        if (!notificationAccessGranted || !batteryUnrestricted) {
            Spacer(Modifier.height(12.dp))
            val appMode by viewModel.appMode.collectAsStateWithLifecycle()
            SetupCard(
                solo = appMode == uk.wakbijok.belanja.settings.SettingsRepository.MODE_SOLO,
                notificationAccessGranted = notificationAccessGranted,
                batteryUnrestricted = batteryUnrestricted,
                onGrantNotification = onRequestNotificationAccess,
                onFixBattery = onFixBattery,
            )
        }

        // Penyata banner when the latest statement still has unresolved rows.
        val unresolved = statements.firstOrNull()?.unresolved ?: 0
        if (unresolved > 0) {
            Spacer(Modifier.height(12.dp))
            HumCard(
                color = hum.coral.copy(alpha = 0.09f),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenStatement),
            ) {
                Text(
                    stringResource(R.string.stmt_banner, unresolved),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (reviewTxns.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ReviewCard(
                txns = reviewTxns,
                onConfirm = viewModel::confirmReview,
                onReject = viewModel::rejectBrainProposal,
                onTxnClick = onTxnClick,
            )
        }

        if (stats.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CategoryPie(stats)
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.by_cat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.budgets_link),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = hum.cyan,
                modifier = Modifier.clickable(onClick = onOpenBudgets),
            )
        }
        Spacer(Modifier.height(4.dp))

        if (stats.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.empty_title),
                body = stringResource(R.string.empty_sub),
            )
        } else {
            stats.forEach { stat ->
                CategoryRow(stat, onClick = { onCategoryClick(stat.category) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- Hero -------------------------------------------------------------------

@Composable
private fun HeroCard(
    totalCents: Long,
    budgetCents: Long,
    isCurrentMonth: Boolean,
    daysLeft: Int,
    topCategory: CategoryStat?,
) {
    val hum = LocalHumColors.current
    val over = budgetCents > 0 && totalCents > budgetCents
    val ratio = if (budgetCents > 0) totalCents.toFloat() / budgetCents else 0f

    HumCard(
        color = if (over) hum.coral.copy(alpha = 0.10f) else hum.pear.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            MonoLabel(stringResource(if (over) R.string.over_label else R.string.hero_label))
            val remaining = budgetCents - totalCents
            HeroAmount(
                cents = remaining,
                color = if (over) hum.coral else hum.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (over) {
                    stringResource(R.string.over_sub, rm(totalCents), rmWhole(budgetCents))
                } else if (isCurrentMonth) {
                    stringResource(R.string.hero_sub, rmWhole(budgetCents), daysLeft)
                } else {
                    stringResource(R.string.hero_sub_past, rmWhole(budgetCents))
                },
                style = MaterialTheme.typography.bodySmall,
                color = hum.ink60,
            )
            Spacer(Modifier.height(12.dp))
            // 10dp pill progress bar; pearDeep fill, coral when over.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(hum.ink.copy(alpha = 0.08f), CircleShape)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio.coerceIn(0f, 1f))
                        .height(10.dp)
                        .background(if (over) hum.coral else hum.pearDeep, CircleShape)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    if (over && topCategory != null) {
                        stringResource(
                            R.string.over_top,
                            categoryLabel(topCategory.category),
                            rmWhole(totalCents - budgetCents),
                        )
                    } else {
                        stringResource(R.string.hero_spent, rm(totalCents))
                    },
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = hum.ink60,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(ratio * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = hum.ink60,
                )
            }
        }
    }
}

/** Huge ExtraBold amount with smaller sen digits, tabular numerals. */
@Composable
private fun HeroAmount(cents: Long, color: Color) {
    val hum = LocalHumColors.current
    val neg = cents < 0
    val abs = if (neg) -cents else cents
    val whole = "RM %,d".format(abs / 100)
    val sen = ".%02d".format(abs % 100)
    Text(
        buildAnnotatedString {
            if (neg) append("− ")
            append(whole)
            withStyle(
                MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = hum.ink60,
                ).toSpanStyle()
            ) { append(sen) }
        },
        style = MaterialTheme.typography.displaySmall.tnum(),
        fontWeight = FontWeight.ExtraBold,
        color = color,
    )
}

// ---- Review card ----------------------------------------------------------------

@Composable
private fun ReviewCard(
    txns: List<Txn>,
    onConfirm: (Txn) -> Unit,
    onReject: (Txn) -> Unit,
    onTxnClick: (Txn) -> Unit,
) {
    val hum = LocalHumColors.current
    HumCard(color = hum.coral.copy(alpha = 0.09f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.review_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.background(hum.coral, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${txns.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            txns.take(3).forEachIndexed { i, txn ->
                if (i > 0) DashedDivider()
                // Body opens detail (with raw notif). Accept/reject stay on their
                // own row so the ticks never steal the "I want to verify" tap.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onTxnClick(txn) }
                    ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            txn.merchant ?: txn.rawTitle.ifBlank { txn.sourcePkg },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            txn.amountCents?.let { rm(it) } ?: "?",
                            style = MaterialTheme.typography.bodyMedium.tnum(),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceChip(txn.sourcePkg)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${clockFmt.format(Date(txn.capturedAt))} · " +
                                stringResource(
                                    R.string.cat_suggest_q,
                                    categoryLabel(txn.brainCategory ?: txn.category),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // The tag earns the row a second look: a proposal is
                        // not the same claim as a keyword rule matching.
                        if (txn.brainCategory != null) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = hum.cyan.copy(alpha = 0.18f),
                            ) {
                                Text(
                                    stringResource(R.string.brain_tag),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = hum.cyan,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    // A reason you can judge beats a confidence score you
                    // cannot: when the brain is wrong, this line shows why.
                    txn.brainReason?.let { why ->
                        Text(
                            why,
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.review_open) + " · " +
                            stringResource(R.string.txd_raw),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = hum.cyan,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        // Rejecting must cost the same one tap as accepting —
                        // a suggestion you can only accept is not a suggestion.
                        if (txn.brainCategory != null) {
                            Surface(
                                onClick = { onReject(txn) },
                                shape = CircleShape,
                                color = hum.paper,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.review_reject),
                                        tint = hum.coral,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Surface(
                            onClick = { onConfirm(txn) },
                            shape = CircleShape,
                            color = hum.paper,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(R.string.review_confirm),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Category rows ----------------------------------------------------------------

@Composable
private fun CategoryRow(stat: CategoryStat, onClick: () -> Unit) {
    val hum = LocalHumColors.current
    val color = categoryColor(stat.category)
    val overCatBudget = stat.budgetCents != null && stat.totalCents > stat.budgetCents
    val barRatio = if (stat.budgetCents != null && stat.budgetCents > 0) {
        stat.totalCents.toFloat() / stat.budgetCents
    } else {
        stat.pctOfTotal
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIconTile(stat.category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                categoryLabel(stat.category),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(hum.ink.copy(alpha = 0.07f), CircleShape)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(barRatio.coerceIn(0f, 1f))
                        .height(5.dp)
                        .background(color, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                rmWhole(stat.totalCents),
                style = MaterialTheme.typography.bodyMedium.tnum(),
                fontWeight = FontWeight.Bold,
                color = if (overCatBudget) hum.coral else hum.ink,
            )
            Text(
                if (stat.budgetCents != null && stat.budgetCents > 0) {
                    stringResource(
                        R.string.pct_of_budget,
                        (stat.totalCents * 100 / stat.budgetCents).toInt(),
                        rmWhole(stat.budgetCents),
                    )
                } else {
                    stringResource(R.string.pct_of_total, (stat.pctOfTotal * 100).toInt())
                },
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.em),
                color = hum.ink60,
            )
        }
    }
}

// ---- Persediaan (setup steps, mockup screen 10) -----------------------------------

@Composable
private fun SetupCard(
    solo: Boolean,
    notificationAccessGranted: Boolean,
    batteryUnrestricted: Boolean,
    onGrantNotification: () -> Unit,
    onFixBattery: () -> Unit,
) {
    val hum = LocalHumColors.current
    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.setup_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.setup_sub),
                style = MaterialTheme.typography.bodySmall,
                color = hum.ink60,
            )
            Spacer(Modifier.height(8.dp))
            SetupStep(
                number = 1,
                done = true,
                title = stringResource(if (solo) R.string.setup_1_solo else R.string.setup_1),
                body = stringResource(if (solo) R.string.setup_1s_solo else R.string.setup_1s),
                action = null,
                onAction = {},
            )
            DashedDivider()
            SetupStep(
                number = 2,
                done = notificationAccessGranted,
                title = stringResource(R.string.setup_2),
                body = stringResource(R.string.setup_2s),
                action = if (notificationAccessGranted) null else stringResource(R.string.setup_open),
                onAction = onGrantNotification,
            )
            DashedDivider()
            SetupStep(
                number = 3,
                done = batteryUnrestricted,
                title = stringResource(R.string.setup_3),
                body = stringResource(R.string.setup_3s),
                action = if (batteryUnrestricted) null else stringResource(R.string.setup_open),
                onAction = onFixBattery,
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    done: Boolean,
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
) {
    val hum = LocalHumColors.current
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (done) hum.mint else hum.paper3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color(0xFF12171B),
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text("$number", style = MaterialTheme.typography.labelSmall, color = hum.ink)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = hum.ink60)
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                PushButton(action, onClick = onAction, compact = true)
            }
        }
    }
}


/**
 * Month-at-a-glance donut. The category rows below are the legend — same
 * colors, same order — so the chart carries no labels of its own; total
 * spend sits in the hole. Slices under 2% merge into the last one drawn
 * rather than rendering slivers.
 */
@Composable
private fun CategoryPie(stats: List<CategoryStat>) {
    val hum = LocalHumColors.current
    val total = stats.sumOf { it.totalCents }
    if (total <= 0) return
    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            val slices = stats.filter { it.totalCents > 0 }
            val colors = slices.map { categoryColor(it.category) }
            androidx.compose.foundation.Canvas(Modifier.size(170.dp)) {
                val stroke = 34.dp.toPx()
                val inset = stroke / 2
                val arcSize = androidx.compose.ui.geometry.Size(
                    size.width - stroke, size.height - stroke,
                )
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                var start = -90f
                val gap = if (slices.size > 1) 1.5f else 0f
                slices.forEachIndexed { i, st ->
                    val sweep = 360f * (st.totalCents.toFloat() / total)
                    if (sweep > gap) {
                        drawArc(
                            color = colors[i],
                            startAngle = start + gap / 2,
                            sweepAngle = sweep - gap,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = stroke,
                                cap = androidx.compose.ui.graphics.StrokeCap.Butt,
                            ),
                        )
                    }
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    rm(total),
                    style = MaterialTheme.typography.titleMedium.tnum(),
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    stringResource(R.string.pie_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = hum.ink60,
                )
            }
        }
    }
}
