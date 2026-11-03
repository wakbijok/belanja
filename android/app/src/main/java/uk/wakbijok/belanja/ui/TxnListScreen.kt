package uk.wakbijok.belanja.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.api.ReceiptParse
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.parser.Categorizer
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/** Flat list model: day headers interleaved with rows so the calendar can jump. */
private sealed interface ListItem {
    data class DayHeader(val dayStartMillis: Long, val totalCents: Long) : ListItem
    data class Row(val txn: Txn) : ListItem
}

@Composable
fun TxnListScreen(
    viewModel: MainViewModel,
    onTxnClick: (Txn) -> Unit,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val monthTxns by viewModel.monthTxns.collectAsStateWithLifecycle()
    val allTxns by viewModel.txns.collectAsStateWithLifecycle()
    val monthLabel by viewModel.monthLabel.collectAsStateWithLifecycle()
    val monthKey by viewModel.monthKey.collectAsStateWithLifecycle()
    val canGoNext by viewModel.canGoNext.collectAsStateWithLifecycle()
    val daySummaries by viewModel.daySummaries.collectAsStateWithLifecycle()
    val cycleRange by viewModel.cycleRange.collectAsStateWithLifecycle()
    val receiptIds by viewModel.receiptIds.collectAsStateWithLifecycle()
    val remoteResults by viewModel.searchResults.collectAsStateWithLifecycle()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAdd by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(monthKey) {
        viewModel.refreshMonthData()
        selectedDay = null
    }
    LaunchedEffect(query) { viewModel.searchQuery.value = query }

    val dayKeyFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    // Group the month's rows by local day, newest day first.
    val items = remember(monthTxns, daySummaries) {
        buildList {
            monthTxns
                .groupBy { startOfDay(it.capturedAt) }
                .toSortedMap(compareByDescending { it })
                .forEach { (dayStart, txns) ->
                    val serverTotal = daySummaries[dayKeyFmt.format(Date(dayStart))]?.totalCents
                    add(
                        ListItem.DayHeader(
                            dayStart,
                            serverTotal ?: txns.sumOf { it.amountCents ?: 0L },
                        )
                    )
                    txns.sortedByDescending { it.capturedAt }.forEach { add(ListItem.Row(it)) }
                }
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val parseFailMsg = stringResource(R.string.manual_parse_fail)
    val deletedMsg = stringResource(R.string.txn_deleted)

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // Header: title, + and search icons.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.nav_txn),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.txn_add),
                    tint = hum.ink60,
                )
            }
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) query = ""
            }) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.txn_search),
                    tint = if (searchOpen) hum.ink else hum.ink60,
                )
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.txn_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        if (searchOpen && query.isNotBlank()) {
            SearchResults(
                query = query,
                localTxns = allTxns,
                remoteTxns = remoteResults,
                receiptIds = receiptIds,
                onTxnClick = onTxnClick,
            )
        } else {
            MonthNav(
                label = monthLabel,
                canGoNext = canGoNext,
                onPrev = viewModel::prevMonth,
                onNext = viewModel::nextMonth,
                trailing = {
                    IconButton(
                        onClick = { calendarOpen = !calendarOpen },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Text(
                            if (calendarOpen) "▴" else "▾",
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                        )
                    }
                },
            )

            if (calendarOpen) {
                MonthCalendar(
                    range = cycleRange,
                    daysWithTxns = remember(items, daySummaries) {
                        items.filterIsInstance<ListItem.DayHeader>()
                            .map { dayOfMonth(it.dayStartMillis) }
                            .toSet() +
                            daySummaries.keys.mapNotNull { it.takeLast(2).toIntOrNull() }
                    },
                    selectedDay = selectedDay?.let { dayOfMonth(it) },
                    onDayTap = { day ->
                        val target = items.indexOfFirst {
                            it is ListItem.DayHeader && dayOfMonth(it.dayStartMillis) == day
                        }
                        if (target >= 0) {
                            selectedDay =
                                (items[target] as ListItem.DayHeader).dayStartMillis
                            scope.launch { listState.animateScrollToItem(target) }
                        }
                    },
                )
                Spacer(Modifier.height(4.dp))
            }

            if (items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.empty_title),
                    body = stringResource(R.string.empty_sub),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items.forEach { item ->
                        when (item) {
                            is ListItem.DayHeader -> item(key = "d${item.dayStartMillis}") {
                                DayHeaderRow(item)
                            }
                            is ListItem.Row -> item(key = item.txn.id) {
                                SwipeToDeleteRow(
                                    onDelete = {
                                        viewModel.deleteTxn(item.txn) {
                                            onSnackbar(deletedMsg)
                                        }
                                    },
                                ) {
                                    CompactTxnRow(
                                        txn = item.txn,
                                        hasReceipt = receiptIds.containsKey(item.txn.id),
                                        onClick = { onTxnClick(item.txn) },
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        AddTxnDialog(
            onDismiss = { showAdd = false },
            onParse = { uri, onParsed ->
                viewModel.parseReceipt(uri) { parsed ->
                    if (parsed == null) onSnackbar(parseFailMsg)
                    onParsed(parsed)
                }
            },
            onSave = { merchant, amount, category, dayMillis, receiptUri ->
                viewModel.addManualTxn(merchant, amount, category, dayMillis, receiptUri) { res ->
                    showAdd = false
                    onSnackbar(context.getString(res))
                }
            },
            onScanUnavailable = {
                onSnackbar(context.getString(R.string.rcpt_scanner_unavailable))
            },
        )
    }
}

// ---- Swipe to delete -----------------------------------------------------------

/**
 * Full swipe right-to-left deletes. The content sits on an opaque paper
 * background so the coral reveal stays behind the row edge while dragging.
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hum = LocalHumColors.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(hum.coral, RoundedCornerShape(12.dp))
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.txn_delete),
                    tint = hum.paper,
                )
            }
        },
    ) {
        Box(Modifier.background(hum.paper)) { content() }
    }
}

// ---- Day header + compact row -------------------------------------------------

@Composable
private fun DayHeaderRow(header: ListItem.DayHeader) {
    val hum = LocalHumColors.current
    Column(Modifier.padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            MonoLabel(dayHeaderLabel(header.dayStartMillis), modifier = Modifier.weight(1f))
            Text(
                rm(header.totalCents),
                style = MaterialTheme.typography.bodySmall.tnum(),
                fontWeight = FontWeight.SemiBold,
                color = hum.ink60,
            )
        }
        Spacer(Modifier.height(4.dp))
        DashedDivider()
    }
}

@Composable
internal fun CompactTxnRow(
    txn: Txn,
    hasReceipt: Boolean,
    onClick: () -> Unit,
    showCategory: Boolean = true,
) {
    val hum = LocalHumColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIconTile(txn.category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                txn.merchant ?: txn.rawTitle.ifBlank { txn.sourcePkg },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceChip(txn.sourcePkg)
                Spacer(Modifier.width(6.dp))
                Text(
                    clockFmt.format(Date(txn.capturedAt)) +
                        if (showCategory) " · ${categoryLabel(txn.category)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = hum.ink60,
                    maxLines = 1,
                )
                if (txn.needsReview) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.tag_review),
                        style = MaterialTheme.typography.labelSmall,
                        color = hum.coral,
                        modifier = Modifier
                            .background(
                                hum.coral.copy(alpha = 0.12f),
                                RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
                if (hasReceipt) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.txn_has_receipt),
                        tint = hum.ink40,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            txn.amountCents?.let { rm(it) } ?: "?",
            style = MaterialTheme.typography.bodyMedium.tnum(),
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---- Search results (local + remote, grouped by month) ---------------------------

@Composable
private fun SearchResults(
    query: String,
    localTxns: List<Txn>,
    remoteTxns: List<Txn>,
    receiptIds: Map<String, String>,
    onTxnClick: (Txn) -> Unit,
) {
    val monthFmt = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val q = query.trim().lowercase()
    val merged = remember(query, localTxns, remoteTxns) {
        val local = localTxns.filter {
            (it.merchant ?: "").lowercase().contains(q) ||
                it.rawText.lowercase().contains(q) ||
                it.rawTitle.lowercase().contains(q)
        }
        (local + remoteTxns).distinctBy { it.id }.sortedByDescending { it.capturedAt }
    }
    if (merged.isEmpty()) {
        Text(
            stringResource(R.string.txn_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalHumColors.current.ink60,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        merged.groupBy { monthFmt.format(Date(it.capturedAt)) }.forEach { (month, txns) ->
            item(key = "m$month") {
                Column(Modifier.padding(top = 16.dp)) {
                    MonoLabel(month)
                    Spacer(Modifier.height(4.dp))
                    DashedDivider()
                }
            }
            txns.forEach { txn ->
                item(key = "s${txn.id}") {
                    CompactTxnRow(
                        txn = txn,
                        hasReceipt = receiptIds.containsKey(txn.id),
                        onClick = { onTxnClick(txn) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---- Inline month calendar -----------------------------------------------------

@Composable
private fun MonthCalendar(
    range: Pair<Long, Long>,
    daysWithTxns: Set<Int>,
    selectedDay: Int?,
    onDayTap: (Int) -> Unit,
) {
    val hum = LocalHumColors.current
    // The cycle's days in order (28, 29, …, 31, 1, 2, …, 27 for a payday
    // cycle; 1…31 for a calendar month). Day-of-month numbers never repeat
    // inside one cycle, so they stay valid tap keys.
    val cycleDays = remember(range) {
        buildList {
            val c = Calendar.getInstance().apply { timeInMillis = range.first }
            while (c.timeInMillis < range.second) {
                add(c.get(Calendar.DAY_OF_MONTH))
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }
    // Monday-first offset of the cycle's first day.
    val firstDow = remember(range) {
        val c = Calendar.getInstance().apply { timeInMillis = range.first }
        ((c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY) + 7) % 7
    }
    val dowLabels = remember {
        val fmt = SimpleDateFormat("EE", Locale.getDefault())
        val c = Calendar.getInstance()
        // Any known Monday.
        c.set(2024, Calendar.JANUARY, 1)
        (0 until 7).map {
            val s = fmt.format(c.time).uppercase().take(2)
            c.add(Calendar.DAY_OF_MONTH, 1)
            s
        }
    }

    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                dowLabels.forEach { d ->
                    Text(
                        d,
                        style = MaterialTheme.typography.labelSmall,
                        color = hum.ink40,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val totalCells = firstDow + cycleDays.size
            val rows = (totalCells + 6) / 7
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { c ->
                        val cell = r * 7 + c
                        val day = cycleDays.getOrNull(cell - firstDow)
                        if (day != null) {
                            val has = day in daysWithTxns
                            val sel = day == selectedDay
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(1.dp)
                                    .background(
                                        if (sel) hum.pear else androidx.compose.ui.graphics.Color.Transparent,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable(enabled = has) { onDayTap(day) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "$day",
                                    style = MaterialTheme.typography.bodySmall.tnum(),
                                    fontWeight = if (has) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) {
                                        androidx.compose.ui.graphics.Color(0xFF12171B)
                                    } else if (has) hum.ink else hum.ink40,
                                )
                                Box(
                                    Modifier
                                        .size(4.dp)
                                        .background(
                                            when {
                                                sel && has -> androidx.compose.ui.graphics.Color(0xFF12171B)
                                                has -> hum.pearDeep
                                                else -> androidx.compose.ui.graphics.Color.Transparent
                                            },
                                            androidx.compose.foundation.shape.CircleShape,
                                        )
                                )
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ---- Manual add dialog -----------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTxnDialog(
    onDismiss: () -> Unit,
    onParse: (Uri, onParsed: (ReceiptParse?) -> Unit) -> Unit,
    onSave: (merchant: String, amountRm: String, category: String, dayMillis: Long, receiptUri: Uri?) -> Unit,
    onScanUnavailable: () -> Unit,
) {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    var merchant by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(Categorizer.DEFAULT_CATEGORY) }
    var receiptUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var receiptName by rememberSaveable { mutableStateOf("") }
    var receiptDate by rememberSaveable { mutableStateOf<String?>(null) }

    fun attach(uri: Uri, name: String) {
        receiptUri = uri
        receiptName = name
        onParse(uri) { parsed ->
            if (parsed == null) return@onParse
            // Prefill only what the user has not typed yet.
            if (merchant.isBlank()) parsed.merchant?.let { merchant = it }
            if (amount.isBlank()) parsed.amountCents?.let { amount = MainViewModel.formatRm(it) }
            receiptDate = parsed.date
        }
    }

    val receiptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) attach(uri, displayName(context, uri))
    }

    val scannedName = stringResource(R.string.rcpt_scanned_name)
    val scanReceipt = rememberReceiptScanner(
        onScanned = { uri -> attach(uri, scannedName) },
        onUnavailable = onScanUnavailable,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.txn_add_title)) },
        text = {
            Column {
                if (receiptUri == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                receiptPicker.launch(arrayOf("application/pdf", "image/*"))
                            },
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = null,
                                tint = hum.cyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.manual_attach_receipt),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = hum.cyan,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = scanReceipt),
                        ) {
                            Icon(
                                Icons.Outlined.PhotoCamera,
                                contentDescription = null,
                                tint = hum.cyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.manual_scan_receipt),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = hum.cyan,
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(hum.paper2, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = stringResource(R.string.manual_receipt_attached),
                            tint = hum.ink60,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            receiptName.ifBlank { stringResource(R.string.manual_receipt_attached) },
                            style = MaterialTheme.typography.bodySmall,
                            color = hum.ink60,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                receiptDate?.let { date ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.manual_receipt_date, date),
                        style = MaterialTheme.typography.bodySmall,
                        color = hum.ink60,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(stringResource(R.string.txn_add_merchant)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(stringResource(R.string.txn_add_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Categorizer.CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(categoryLabel(cat)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Receipt date (if parsed) wins over "now"; noon-shifting is
                    // the ViewModel's job.
                    val dayMillis = receiptDate?.let { d ->
                        runCatching {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)?.time
                        }.getOrNull()
                    } ?: System.currentTimeMillis()
                    onSave(merchant, amount, category, dayMillis, receiptUri)
                },
                enabled = merchant.isNotBlank() && amount.isNotBlank(),
            ) { Text(stringResource(R.string.txn_add_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** DISPLAY_NAME of a picked document; falls back to a generic label. */
private fun displayName(context: Context, uri: Uri): String {
    var name = "resit"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
    }
    return name
}

// ---- Date helpers ------------------------------------------------------------------

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayOfMonth(millis: Long): Int = Calendar.getInstance().apply {
    timeInMillis = millis
}.get(Calendar.DAY_OF_MONTH)
