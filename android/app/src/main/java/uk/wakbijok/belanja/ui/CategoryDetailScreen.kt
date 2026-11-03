package uk.wakbijok.belanja.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.data.Txn

@Composable
fun CategoryDetailScreen(
    viewModel: MainViewModel,
    category: String,
    onTxnClick: (Txn) -> Unit,
    modifier: Modifier = Modifier,
) {
    val txns by remember(category) { viewModel.monthTxnsForCategory(category) }
        .collectAsStateWithLifecycle(emptyList())
    val trend by remember(category) { viewModel.observeTrend(category) }
        .collectAsStateWithLifecycle(emptyList())
    val budgets by viewModel.categoryBudgets.collectAsStateWithLifecycle()
    val monthLabel by viewModel.monthLabel.collectAsStateWithLifecycle()
    val receiptIds by viewModel.receiptIds.collectAsStateWithLifecycle()

    val totalCents = txns.sumOf { it.amountCents ?: 0L }
    val budgetCents = budgets[category]
    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Header: month total (+ budget if set).
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(monthLabel, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        rm(totalCents),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (budgetCents != null && totalCents > budgetCents)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        budgetCents?.let { stringResource(R.string.bud_of, rm(it)) }
                            ?: stringResource(R.string.bud_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showBudgetDialog = true }) {
                    Text(
                        stringResource(
                            if (budgetCents == null) R.string.bud_set else R.string.bud_edit
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.cat_last6), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        TrendChart(
            points = trend,
            barColor = categoryColor(category),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.cat_txns), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (txns.isEmpty()) {
            Text(
                stringResource(R.string.cat_empty, categoryLabel(category)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(txns, key = { it.id }) { txn ->
                    CompactTxnRow(
                        txn = txn,
                        hasReceipt = receiptIds.containsKey(txn.id),
                        onClick = { onTxnClick(txn) },
                        showCategory = false,
                    )
                }
            }
        }
    }

    if (showBudgetDialog) {
        BudgetDialog(
            category = category,
            currentCents = budgetCents,
            onSave = { rm ->
                viewModel.setCategoryBudgetRm(category, rm)
                showBudgetDialog = false
            },
            onDismiss = { showBudgetDialog = false },
        )
    }
}

@Composable
private fun BudgetDialog(
    category: String,
    currentCents: Long?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable {
        mutableStateOf(currentCents?.let { MainViewModel.formatRm(it) } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bud_dialog_title, categoryLabel(category))) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.bud_monthly_rm)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.bud_remove_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Plain-Canvas monthly bar chart, no chart library. */
@Composable
internal fun TrendChart(
    points: List<MonthPoint>,
    barColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val max = (points.maxOf { it.totalCents }).coerceAtLeast(1L)
        val labelHeightPx = 36f
        val chartHeight = size.height - labelHeightPx
        val slot = size.width / points.size
        val barWidth = slot * 0.55f

        points.forEachIndexed { i, p ->
            val h = (p.totalCents.toFloat() / max) * (chartHeight * 0.92f)
            val left = slot * i + (slot - barWidth) / 2f
            val top = chartHeight - h
            drawRoundRect(
                color = if (p.totalCents > 0) barColor else barColor.copy(alpha = 0.25f),
                topLeft = Offset(left, top),
                size = Size(barWidth, if (p.totalCents > 0) h else 4f),
                cornerRadius = CornerRadius(6f, 6f),
            )
            val layout = textMeasurer.measure(p.label, style = labelStyle)
            drawText(
                textLayoutResult = layout,
                color = labelColor,
                topLeft = Offset(
                    slot * i + (slot - layout.size.width) / 2f,
                    chartHeight + 8f,
                ),
            )
        }
    }
}
