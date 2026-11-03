package uk.wakbijok.belanja.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.parser.Categorizer
import uk.wakbijok.belanja.ui.theme.JetMono
import uk.wakbijok.belanja.ui.theme.LocalHumColors

private val sheetDateFmt = SimpleDateFormat("EEEE, d MMM yyyy · HH:mm", Locale.getDefault())

/**
 * Butiran transaksi — bottom sheet (mockup screen 03). Category change opens
 * a second sheet with the 3-column chip grid + auto-learn prompt (screen 04).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TxnDetailSheet(
    viewModel: MainViewModel,
    txnId: String,
    onDismiss: () -> Unit,
    onSnackbar: (String) -> Unit,
    /** Open "Notifikasi asal" expanded — used when jumping from the Akal queue. */
    expandRawInitially: Boolean = false,
) {
    val context = LocalContext.current
    val hum = LocalHumColors.current
    val txn by remember(txnId) { viewModel.observeTxn(txnId) }.collectAsStateWithLifecycle(null)
    val receiptIds by viewModel.receiptIds.collectAsStateWithLifecycle()
    val appMode by viewModel.appMode.collectAsStateWithLifecycle()
    val taxSummary by viewModel.taxSummary.collectAsStateWithLifecycle()
    val t = txn ?: return

    var showCategorySheet by rememberSaveable { mutableStateOf(false) }
    var showAmountDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Brain proposals and review-queue opens start with the raw notification
    // visible so the user can verify the guess without hunting for the toggle.
    var rawOpen by rememberSaveable(txnId) {
        mutableStateOf(expandRawInitially || txn?.brainCategory != null)
    }
    // If txn loads after first composition with a brain proposal, open raw.
    LaunchedEffect(t.id, t.brainCategory, expandRawInitially) {
        if (expandRawInitially || t.brainCategory != null) rawOpen = true
    }
    var showReliefPicker by rememberSaveable { mutableStateOf(false) }

    // Room-left figures for the tag row / picker need the cached tax summary.
    LaunchedEffect(Unit) { if (taxSummary == null) viewModel.refreshTaxSummary() }

    /** Local suggestion — a suggestion only; the user confirms (mockup 03). */
    val suggestion = remember(t.id, t.merchant, t.category) {
        suggestRelief(t.merchant, t.category)
    }

    val receiptId = receiptIds[t.id]
    val snack: (Int) -> Unit = { onSnackbar(context.getString(it)) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.uploadReceipt(t.id, it) { res -> snack(res) } } }

    // Camera scan: Play Services document scanner (edge detect + deskew +
    // lighting cleanup) returns a PDF; attach it the same path as a file pick.
    val scanReceipt = rememberReceiptScanner(
        onScanned = { uri -> viewModel.uploadReceipt(t.id, uri) { res -> snack(res) } },
        onUnavailable = { snack(R.string.rcpt_scanner_unavailable) },
    )

    val deletedMsg = stringResource(R.string.txd_deleted)
    val confirmedMsg = stringResource(R.string.txd_confirmed)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = hum.paper,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // Header: merchant + date, amount big on the right.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t.merchant ?: t.rawTitle.ifBlank { t.sourcePkg },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sheetDateFmt.format(Date(t.capturedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = hum.ink60,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    t.amountCents?.let { rm(it) }
                        ?: stringResource(R.string.txd_amount_unknown),
                    style = MaterialTheme.typography.titleLarge.tnum(),
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(12.dp))

            KvRow(stringResource(R.string.txd_cat)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        categoryLabel(t.category),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = categoryColor(t.category),
                    )
                    Text(" · ", color = hum.ink40)
                    Text(
                        stringResource(R.string.txd_change),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = hum.cyan,
                        modifier = Modifier.clickable { showCategorySheet = true },
                    )
                }
            }
            // Akal proposal — visible so the user can compare rules vs brain
            // before tapping confirm on the detail sheet.
            t.brainCategory?.let { proposed ->
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(hum.cyan.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            stringResource(R.string.review_brain_propose, categoryLabel(proposed)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = hum.cyan,
                        )
                        Text(
                            stringResource(R.string.review_rules_cat, categoryLabel(t.category)),
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                        )
                        t.brainReason?.let { why ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                why,
                                style = MaterialTheme.typography.bodySmall,
                                color = hum.ink60,
                            )
                        }
                    }
                }
            }
            DashedDivider()
            KvRow(stringResource(R.string.txd_amt)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.amountCents?.let { rm(it) } ?: "—",
                        style = MaterialTheme.typography.bodyMedium.tnum(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(" · ", color = hum.ink40)
                    Text(
                        stringResource(R.string.txd_fix),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = hum.cyan,
                        modifier = Modifier.clickable { showAmountDialog = true },
                    )
                }
            }
            DashedDivider()
            KvRow(stringResource(R.string.txd_src)) {
                Text(
                    sourceLabel(t.sourcePkg),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            DashedDivider()
            KvRow(stringResource(R.string.txd_receipt)) {
                if (receiptId == null) {
                    Text(
                        stringResource(R.string.txd_receipt_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = hum.ink40,
                    )
                } else {
                    Row {
                        Text(
                            stringResource(R.string.rcpt_view),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = hum.cyan,
                            modifier = Modifier.clickable {
                                viewModel.downloadReceipt(receiptId) { res -> snack(res) }
                            },
                        )
                        Text(" · ", color = hum.ink40)
                        Text(
                            stringResource(R.string.rcpt_delete),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = hum.coral,
                            modifier = Modifier.clickable {
                                viewModel.deleteReceipt(t.id, receiptId) { res -> snack(res) }
                            },
                        )
                    }
                }
            }
            DashedDivider()
            KvRow(stringResource(R.string.txd_relief)) {
                val relief = t.relief
                Text(
                    when {
                        relief == null -> stringResource(R.string.relief_untagged)
                        relief == "none" -> stringResource(R.string.relief_none)
                        else -> reliefName(relief, taxSummary)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        relief == null || relief == "none" -> hum.ink40
                        else -> reliefColor(relief)
                    },
                    modifier = Modifier.clickable { showReliefPicker = true },
                )
            }
            DashedDivider()
            KvRow(stringResource(R.string.txd_sync)) {
                // Solo mode has no server: "waiting for sync" would be a lie.
                val solo = appMode == uk.wakbijok.belanja.settings.SettingsRepository.MODE_SOLO
                Text(
                    stringResource(
                        when {
                            solo -> R.string.txd_saved_local
                            t.synced -> R.string.txd_synced
                            else -> R.string.txd_sync_pending
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (solo || t.synced) hum.mint else hum.ink60,
                )
            }

            // Lavender relief suggestion (mockup 03): untagged + a rule matched.
            if (t.relief == null && suggestion != null) {
                Spacer(Modifier.height(10.dp))
                HumCard(
                    color = hum.catOnline.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(
                                R.string.relief_suggest,
                                reliefName(suggestion, taxSummary),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(reliefCaveatRes(suggestion)),
                            style = MaterialTheme.typography.labelSmall,
                            color = hum.ink60,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PushButton(
                                stringResource(
                                    R.string.relief_suggest_yes,
                                    reliefName(suggestion, taxSummary),
                                ),
                                compact = true,
                                onClick = {
                                    viewModel.tagRelief(t, suggestion) { res -> snack(res) }
                                },
                            )
                            SoftButton(
                                stringResource(R.string.relief_pick_other),
                                compact = true,
                                onClick = { showReliefPicker = true },
                            )
                            SoftButton(
                                stringResource(R.string.relief_none),
                                compact = true,
                                onClick = {
                                    viewModel.tagRelief(t, "none") { res -> snack(res) }
                                },
                            )
                        }
                    }
                }
            }

            // Collapsible raw notification, mono.
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.txd_raw) + if (rawOpen) " ▴" else " ▾",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = hum.ink60,
                modifier = Modifier.clickable { rawOpen = !rawOpen },
            )
            if (rawOpen) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(hum.paper2, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        listOf(t.rawTitle, t.rawText).filter { it.isNotBlank() }
                            .joinToString("\n")
                            .ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetMono),
                        color = hum.ink60,
                    )
                }
            }

            // Receipt attach buttons (no receipt yet).
            if (receiptId == null) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton(
                        stringResource(R.string.rcpt_upload),
                        onClick = {
                            filePicker.launch(arrayOf("application/pdf", "image/*"))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SoftButton(
                        stringResource(R.string.rcpt_scan),
                        onClick = scanReceipt,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PushButton(
                    stringResource(R.string.txd_confirm),
                    onClick = {
                        viewModel.confirmReview(t)
                        onSnackbar(confirmedMsg)
                    },
                    modifier = Modifier.weight(1f),
                )
                DangerButton(
                    stringResource(R.string.txd_delete),
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (showReliefPicker) {
        ReliefPickerSheet(
            summary = taxSummary,
            suggested = suggestion,
            current = t.relief?.takeIf { it != "none" },
            onPick = { code ->
                showReliefPicker = false
                viewModel.tagRelief(t, code) { res -> snack(res) }
            },
            onNone = {
                showReliefPicker = false
                viewModel.tagRelief(t, "none") { res -> snack(res) }
            },
            onDismiss = { showReliefPicker = false },
        )
    }

    if (showCategorySheet) {
        CategorySheet(
            viewModel = viewModel,
            txn = t,
            onDismiss = { showCategorySheet = false },
            onSnackbar = onSnackbar,
        )
    }

    if (showAmountDialog) {
        AmountFixDialog(
            currentCents = t.amountCents,
            onDismiss = { showAmountDialog = false },
            onSave = { cents ->
                showAmountDialog = false
                viewModel.editAmount(t, cents) { snack(R.string.amount_edit_saved) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.txd_delete_title)) },
            text = { Text(stringResource(R.string.txd_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteTxn(t) {
                        onSnackbar(deletedMsg)
                        onDismiss()
                    }
                }) { Text(stringResource(R.string.txd_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun KvRow(key: String, value: @Composable () -> Unit) {
    val hum = LocalHumColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = hum.ink60,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

@Composable
private fun AmountFixDialog(
    currentCents: Long?,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var value by rememberSaveable {
        mutableStateOf(currentCents?.let { MainViewModel.formatRm(it) } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.txd_fix_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.txn_add_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { MainViewModel.parseRm(value)?.let(onSave) },
                enabled = MainViewModel.parseRm(value) != null,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---- Tukar kategori sheet (mockup screen 04) --------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(
    viewModel: MainViewModel,
    txn: Txn,
    onDismiss: () -> Unit,
    onSnackbar: (String) -> Unit,
) {
    val hum = LocalHumColors.current
    var selected by rememberSaveable { mutableStateOf(txn.category) }
    val rememberedMsg = stringResource(R.string.merchant_remembered)
    val changedMsg = stringResource(R.string.category_changed)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = hum.paper) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.pick_cat),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            // 3-column chip grid of all 9 categories.
            Categorizer.CATEGORIES.chunked(3).forEach { rowCats ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowCats.forEach { cat ->
                        CategoryChip(
                            category = cat,
                            selected = selected == cat,
                            onClick = { selected = cat },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowCats.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (selected != txn.category) {
                Spacer(Modifier.height(8.dp))
                HumCard(color = hum.cyan.copy(alpha = 0.09f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(
                                R.string.learn_q,
                                txn.merchant ?: txn.rawTitle.ifBlank { txn.sourcePkg },
                                categoryLabel(selected),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PushButton(
                                stringResource(R.string.learn_yes),
                                compact = true,
                                onClick = {
                                    viewModel.reassignCategory(txn, selected) {
                                        onSnackbar(rememberedMsg)
                                    }
                                    onDismiss()
                                },
                            )
                            SoftButton(
                                stringResource(R.string.learn_once),
                                onClick = {
                                    viewModel.reassignCategoryOnce(txn, selected) {
                                        onSnackbar(changedMsg)
                                    }
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(
    category: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val color = categoryColor(category)
    Column(
        modifier
            .background(
                if (selected) color.copy(alpha = 0.09f) else hum.paper,
                RoundedCornerShape(18.dp),
            )
            .androidBorder(selected, color, hum.hairline)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryIconTile(category, size = 30.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            categoryLabel(category),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.androidBorder(
    selected: Boolean,
    selectedColor: androidx.compose.ui.graphics.Color,
    hairline: androidx.compose.ui.graphics.Color,
): Modifier = this.then(
    Modifier.border(
        width = 1.5.dp,
        color = if (selected) selectedColor else hairline,
        shape = RoundedCornerShape(18.dp),
    )
)
