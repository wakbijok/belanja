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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.api.RemoteTxn
import uk.wakbijok.belanja.ui.theme.LocalHumColors

private val reliefDayFmt = SimpleDateFormat("d MMM", Locale.getDefault())

/**
 * Butiran pelepasan (cukai-mockup screen 02): tinted hero, static "apa yang
 * layak" card, tagged txn list with receipt-evidence pills, near-cap nudge.
 */
@Composable
fun TaxReliefDetailScreen(
    viewModel: MainViewModel,
    code: String,
    onTxnClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val ya by viewModel.taxYa.collectAsStateWithLifecycle()
    val summary by viewModel.taxSummary.collectAsStateWithLifecycle()
    val txns by viewModel.taxTxns.collectAsStateWithLifecycle()

    LaunchedEffect(code, ya) { viewModel.refreshTaxSummary() }
    // Keyed on the summary too: tagging from the detail sheet refreshes the
    // summary cache, which re-pulls this list so the row appears/disappears.
    LaunchedEffect(code, ya, summary) { viewModel.loadReliefTxns(code) }

    val relief = summary?.reliefs?.firstOrNull { it.code == code }
    val color = reliefColor(code)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Tinted hero: evidenced, room left, receipts count.
        HumCard(color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                MonoLabel(
                    relief?.capCents?.let { stringResource(R.string.tax_cap_label, ya, rmWhole(it)) }
                        ?: stringResource(R.string.tax_cap_none, ya)
                )
                Text(
                    rm(relief?.evidencedCents ?: 0L),
                    style = MaterialTheme.typography.headlineMedium.tnum(),
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    when {
                        relief == null -> stringResource(R.string.tax_loading)
                        relief.overCap ->
                            stringResource(R.string.tax_over_cap_sub, relief.receipts)
                        relief.roomCents != null ->
                            stringResource(
                                R.string.tax_room_left, rm(relief.roomCents), relief.receipts
                            )
                        else -> stringResource(R.string.tax_receipts_n, relief.receipts)
                    },
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = if (relief?.overCap == true) hum.coral else hum.ink60,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // "APA YANG LAYAK" — static per-code eligibility lines.
        eligibilityLines(code)?.let { lines ->
            HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    MonoLabel(stringResource(R.string.tax_eligible_head))
                    lines.forEachIndexed { i, line ->
                        if (i > 0) DashedDivider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(line.textRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = hum.ink60,
                                modifier = Modifier.weight(1f),
                            )
                            when (line.mark) {
                                EligMark.OK -> Text(
                                    "✓",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                EligMark.NO -> Text(
                                    stringResource(R.string.tax_not_eligible_mark),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = hum.coral,
                                )
                                EligMark.SPORTS -> Text(
                                    stringResource(R.string.tax_use_sports),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        MonoLabel(stringResource(R.string.tax_tagged_txns))
        if (txns.isEmpty()) {
            Text(
                stringResource(R.string.tax_txns_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = hum.ink60,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            txns.forEach { t ->
                TaggedTxnRow(t, onClick = { onTxnClick(t.txn.id) })
                DashedDivider()
            }
        }

        // Near-cap coral nudge (room under 20% of the cap).
        val cap = relief?.capCents
        val room = relief?.roomCents
        if (cap != null && room != null && room > 0 && room < cap / 5) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(hum.coral.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(8.dp)
                        .background(hum.coral, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.tax_near_cap, rm(room), ya),
                    style = MaterialTheme.typography.bodySmall.tnum(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TaggedTxnRow(t: RemoteTxn, onClick: () -> Unit) {
    val hum = LocalHumColors.current
    val txn = t.txn
    val evidenced = t.receiptId != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                txn.merchant ?: txn.rawTitle.ifBlank { txn.sourcePkg },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${reliefDayFmt.format(Date(txn.capturedAt))} · ${categoryLabel(txn.category)} · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = hum.ink60,
                    maxLines = 1,
                )
                Text(
                    stringResource(if (evidenced) R.string.tax_evid_ok else R.string.tax_evid_no),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (evidenced) hum.ink else hum.coral,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(
                            if (evidenced) hum.mint.copy(alpha = 0.20f)
                            else hum.coral.copy(alpha = 0.13f),
                            CircleShape,
                        )
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            txn.amountCents?.let { rm(it) } ?: "—",
            style = MaterialTheme.typography.bodySmall.tnum(),
            fontWeight = FontWeight.Bold,
        )
    }
}
