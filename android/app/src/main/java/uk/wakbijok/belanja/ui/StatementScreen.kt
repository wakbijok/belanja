package uk.wakbijok.belanja.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.api.StatementRow
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/** Penyata — credit-card reconciliation (mockup screen 06). */
@Composable
fun StatementScreen(
    viewModel: MainViewModel,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val statements by viewModel.statements.collectAsStateWithLifecycle()
    val detail by viewModel.statementDetail.collectAsStateWithLifecycle()
    val busy by viewModel.statementBusy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshStatements(loadLatestDetail = true) }

    val snack: (Int) -> Unit = { onSnackbar(context.getString(it)) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.uploadStatement(it) { res -> snack(res) } } }

    val latest = statements.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        if (latest == null) {
            if (busy) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                EmptyState(
                    title = stringResource(R.string.stmt_title),
                    body = stringResource(R.string.stmt_empty),
                )
                PushButton(
                    stringResource(R.string.set_stmt_up),
                    onClick = { picker.launch(arrayOf("application/pdf", "text/*")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        // Statement card.
        HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    MonoLabel(
                        stringResource(R.string.stmt_label, monthTitle(latest.month).uppercase())
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        latest.filename,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.stmt_uploaded,
                            SimpleDateFormat("d MMM", Locale.getDefault())
                                .format(Date(latest.uploadedAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = hum.ink60,
                    )
                }
                Spacer(Modifier.width(12.dp))
                SoftButton(
                    stringResource(R.string.stmt_reupload),
                    onClick = { picker.launch(arrayOf("application/pdf", "text/*")) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Stat chips.
        val unclearCount = detail?.unclear?.size ?: 0
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip(
                count = latest.matched,
                label = stringResource(R.string.stmt_matched),
                color = hum.mint.copy(alpha = 0.16f),
                modifier = Modifier.weight(1f),
            )
            StatChip(
                count = latest.missing,
                label = stringResource(R.string.stmt_missing),
                color = hum.coral.copy(alpha = 0.12f),
                modifier = Modifier.weight(1f),
            )
            StatChip(
                count = unclearCount,
                label = stringResource(R.string.stmt_pending),
                color = hum.paper2,
                modifier = Modifier.weight(1f),
            )
        }

        if (busy && detail == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        val d = detail
        if (d != null) {
            val missing = d.rows.filter { it.status == "missing" && it.resolved == null }
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.stmt_missing_head),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (missing.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PushButton(
                            stringResource(R.string.stmt_add_all, missing.size),
                            onClick = {
                                viewModel.resolveAllStatementRows(d.id, "add") { res -> snack(res) }
                            },
                            compact = true,
                        )
                        SoftButton(
                            stringResource(R.string.stmt_ignore_all),
                            onClick = {
                                viewModel.resolveAllStatementRows(d.id, "ignore") { res -> snack(res) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                missing.forEachIndexed { i, row ->
                    if (i > 0) DashedDivider()
                    MissingRow(
                        row = row,
                        onAdd = {
                            // null = let the server suggest; passing the on-screen
                            // suggestion would wrongly stamp it as a user override
                            viewModel.resolveStatementRow(
                                d.id, row.id, "add", null,
                            ) { res -> snack(res) }
                        },
                        onIgnore = {
                            viewModel.resolveStatementRow(
                                d.id, row.id, "ignore", null,
                            ) { res -> snack(res) }
                        },
                    )
                }
            }

            if (d.unclear.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.stmt_pending_head),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                d.unclear.forEachIndexed { i, txn ->
                    if (i > 0) DashedDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            txn.merchant ?: txn.rawTitle.ifBlank { txn.sourcePkg },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            SimpleDateFormat("d MMM", Locale.getDefault())
                                .format(Date(txn.capturedAt)).uppercase() +
                                " · " + (txn.amountCents?.let { rm(it) } ?: "?") +
                                " · " + stringResource(R.string.stmt_not_posted),
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.stmt_note),
            style = MaterialTheme.typography.bodySmall,
            color = hum.ink60,
        )
        if (d != null) {
            Spacer(Modifier.height(16.dp))
            DangerButton(
                stringResource(R.string.stmt_delete_run),
                onClick = { viewModel.deleteStatement(d.id) { res -> snack(res) } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatChip(count: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    HumCard(color = color, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.titleLarge.tnum(),
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = LocalHumColors.current.ink60,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MissingRow(
    row: StatementRow,
    onAdd: () -> Unit,
    onIgnore: () -> Unit,
) {
    val hum = LocalHumColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.description,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stmtDateLabel(row.txnDate) + " · " + rm(row.amountCents),
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = hum.ink60,
                )
                row.suggestedCategory?.let { cat ->
                    Spacer(Modifier.width(6.dp))
                    val c = categoryColor(cat)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(c.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        Box(Modifier.size(6.dp).background(c, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            categoryLabel(cat),
                            style = MaterialTheme.typography.labelSmall,
                            color = c,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.stmt_ignore),
                style = MaterialTheme.typography.labelMedium,
                color = hum.ink40,
                modifier = Modifier.clickable(onClick = onIgnore).padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        PushButton(stringResource(R.string.stmt_add), onClick = onAdd, compact = true)
    }
}

/** "2026-07-18" → "18 JUL". */
private fun stmtDateLabel(iso: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
    SimpleDateFormat("d MMM", Locale.getDefault()).format(d).uppercase()
}.getOrDefault(iso)
