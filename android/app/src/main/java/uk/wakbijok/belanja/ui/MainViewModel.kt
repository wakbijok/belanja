package uk.wakbijok.belanja.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.api.ApiClient
import uk.wakbijok.belanja.api.ApiResult
import uk.wakbijok.belanja.api.DaySummary
import uk.wakbijok.belanja.api.ReceiptParse
import uk.wakbijok.belanja.api.RemoteTxn
import uk.wakbijok.belanja.api.StatementDetail
import uk.wakbijok.belanja.api.StatementInfo
import uk.wakbijok.belanja.api.TaxSummary
import uk.wakbijok.belanja.auth.AuthClient
import uk.wakbijok.belanja.auth.LoginResult
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.data.CategoryBudget
import uk.wakbijok.belanja.data.Cycle
import uk.wakbijok.belanja.data.DeletedTxn
import uk.wakbijok.belanja.data.MerchantCategory
import uk.wakbijok.belanja.data.ReceiptLocal
import uk.wakbijok.belanja.data.StatementLocal
import uk.wakbijok.belanja.data.StatementRowLocal
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.parser.Categorizer
import uk.wakbijok.belanja.parser.Instrument
import uk.wakbijok.belanja.parser.Kedai
import uk.wakbijok.belanja.parser.StatementParser
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.solo.BackupScheduler
import uk.wakbijok.belanja.solo.SafBackup
import uk.wakbijok.belanja.sync.SyncScheduler

data class CategoryStat(
    val category: String,
    val totalCents: Long,
    val count: Int,
    /** Share of the month's total spend, 0..1. */
    val pctOfTotal: Float,
    /** Per-category budget if set. */
    val budgetCents: Long?,
)

data class MonthPoint(val label: String, val totalCents: Long)

/** A propagation token matching more rows than this is an instrument/junk
 *  word, not a kedai name — never mass-flip the ledger. */
private const val MAX_PROPAGATION_ROWS = 10

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDb.get(app)
    private val dao = db.txnDao()
    private val merchantCategoryDao = db.merchantCategoryDao()
    private val categoryBudgetDao = db.categoryBudgetDao()
    private val receiptLocalDao = db.receiptLocalDao()
    private val statementLocalDao = db.statementLocalDao()
    private val settingsRepo = SettingsRepository(app)

    // ---- App mode (server | solo) ------------------------------------------

    /** null = still loading from DataStore. */
    val appMode: StateFlow<String?> = settingsRepo.appMode
        .map { it as String? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun isSolo(): Boolean =
        settingsRepo.currentAppMode() == SettingsRepository.MODE_SOLO

    /** Login screen link "Guna tanpa pelayan →": enter the app, no server. */
    fun useSoloMode() {
        viewModelScope.launch {
            settingsRepo.setAppMode(SettingsRepository.MODE_SOLO)
            DiagLog.log(TAG, "mode → solo (from login)")
            // Guarded internally: cancels sync scheduling, starts backups.
            SyncScheduler.ensurePeriodic(getApplication())
            BackupScheduler.ensurePeriodic(getApplication())
        }
    }

    /**
     * Tetapan mode switch. Never touches local data in either direction —
     * Room stays exactly as it is; only scheduling and affordances change.
     * solo→server with no stored session simply lands on the login screen.
     */
    fun setAppMode(mode: String) {
        viewModelScope.launch {
            settingsRepo.setAppMode(mode)
            DiagLog.log(TAG, "mode → $mode")
            SyncScheduler.ensurePeriodic(getApplication())
            BackupScheduler.ensurePeriodic(getApplication())
            if (mode == SettingsRepository.MODE_SERVER &&
                settingsRepo.currentSessionToken().isNotBlank()
            ) {
                SyncScheduler.syncNow(getApplication())
            }
        }
    }

    /**
     * PATCH wrapper: solo mode is local-only (the Room update the caller just
     * made IS the whole edit); server mode logs failures to the diag ring —
     * the batch upsert on the next sync still converges them.
     */
    private suspend fun patchTxn(
        id: String,
        amountCents: Long? = null,
        category: String? = null,
        needsReview: Boolean? = null,
        relief: String? = null,
    ) {
        if (isSolo()) return
        val r = ApiClient.patchTxn(
            settingsRepo.currentSessionToken(), id,
            amountCents = amountCents, category = category,
            needsReview = needsReview, relief = relief,
        )
        when (r) {
            is ApiResult.Ok -> Unit
            is ApiResult.Http -> DiagLog.log(TAG, "PATCH $id failed: HTTP ${r.code}")
            is ApiResult.Fail -> DiagLog.log(TAG, "PATCH $id failed: ${r.message}")
        }
    }

    // ---- Session ----------------------------------------------------------

    /** null = still loading from DataStore, "" = logged out, else JWT. */
    val sessionToken: StateFlow<String?> = settingsRepo.sessionToken
        .map { it as String? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val loginBusy = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)

    fun login(username: String, password: String) {
        if (loginBusy.value) return
        loginError.value = null
        val app = getApplication<Application>()
        if (username.isBlank() || password.isBlank()) {
            loginError.value = app.getString(R.string.login_empty)
            return
        }
        loginBusy.value = true
        viewModelScope.launch {
            when (val r = AuthClient.login(username.trim(), password)) {
                is LoginResult.Success -> {
                    settingsRepo.setSessionToken(r.token)
                    settingsRepo.setUsername(username.trim())
                    // Logging in IS choosing server mode (solo→server path).
                    settingsRepo.setAppMode(SettingsRepository.MODE_SERVER)
                    SyncScheduler.ensurePeriodic(getApplication())
                    BackupScheduler.ensurePeriodic(getApplication())
                    // One pass does both directions: flushes anything captured
                    // while logged out and pulls the ledger back, so a fresh
                    // install recovers its history without any further tap.
                    SyncScheduler.syncNow(getApplication())
                }
                LoginResult.BadCredentials ->
                    loginError.value = app.getString(R.string.login_bad)
                is LoginResult.Error -> loginError.value = r.message
            }
            loginBusy.value = false
        }
    }

    /** No server-side session invalidation exists; dropping the token is logout. */
    fun logout() {
        viewModelScope.launch { settingsRepo.clearSession() }
    }

    // ---- Month selection --------------------------------------------------

    /** 0 = current month, -1 = last month, ... */
    private val monthOffset = MutableStateFlow(0)

    /** Payday the budget cycle starts on (1..28); 1 = calendar month. */
    val cycleStartDay: StateFlow<Int> = settingsRepo.cycleStartDay
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    fun setCycleStartDay(day: Int) =
        viewModelScope.launch { settingsRepo.setCycleStartDay(day) }

    /** (offset, cycle start day) — every month-scoped flow derives from this. */
    private val monthSel: Flow<Pair<Int, Int>> =
        combine(monthOffset, cycleStartDay) { off, day -> off to day }

    /** [start, end) of the selected cycle, for the calendar and day grouping. */
    val cycleRange: StateFlow<Pair<Long, Long>> = monthSel
        .map { (off, day) -> monthRange(off, day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), monthRange(0, 1))

    val monthLabel: StateFlow<String> = monthSel
        .map { (off, day) -> cycleLabel(monthRange(off, day), day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val canGoNext: StateFlow<Boolean> = monthOffset
        .map { it < 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun prevMonth() { monthOffset.value -= 1 }
    fun nextMonth() { if (monthOffset.value < 0) monthOffset.value += 1 }

    // ---- Data for the selected month -------------------------------------

    val monthTxns: StateFlow<List<Txn>> = monthSel
        .flatMapLatest { (off, day) ->
            val (from, until) = monthRange(off, day)
            dao.observeBetween(from, until)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val monthTotalCents: StateFlow<Long> = monthTxns
        .map { txns -> txns.filter { it.category != "Pindahan" }.sumOf { it.amountCents ?: 0L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val overallBudgetCents: StateFlow<Long> = settingsRepo.budgetCents
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_BUDGET_CENTS,
        )

    val categoryBudgets: StateFlow<Map<String, Long>> = categoryBudgetDao.observeAll()
        .map { list -> list.associate { it.category to it.amountCents } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val categoryStats: StateFlow<List<CategoryStat>> =
        combine(monthTxns, categoryBudgets) { txns, budgets ->
            // Pindahan is funding movement, not spending — excluded from the
            // denominator so category shares reflect real spend
            val total = txns.filter { it.category != "Pindahan" }.sumOf { it.amountCents ?: 0L }
            txns.groupBy { it.category }
                .map { (cat, list) ->
                    val catTotal = list.sumOf { it.amountCents ?: 0L }
                    CategoryStat(
                        category = cat,
                        totalCents = catTotal,
                        count = list.size,
                        pctOfTotal = if (total > 0) catTotal.toFloat() / total else 0f,
                        budgetCents = budgets[cat],
                    )
                }
                .sortedByDescending { it.totalCents }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun monthTxnsForCategory(category: String): Flow<List<Txn>> =
        monthTxns.map { txns -> txns.filter { it.category == category } }

    /** Last 6 cycles (oldest first) ending at the selected one. */
    fun observeTrend(category: String): Flow<List<MonthPoint>> =
        monthSel.flatMapLatest { (off, day) ->
            val months = (off - 5..off).map { o -> monthRange(o, day) }
            combine(
                months.map { range ->
                    dao.observeTotalForCategoryBetween(category, range.first, range.second)
                }
            ) { totals ->
                months.mapIndexed { i, range ->
                    MonthPoint(trendLabelFmt.format(Date(range.second - 1)), totals[i])
                }
            }
        }

    // ---- All txns (Transactions tab) & txn detail -------------------------

    val txns: StateFlow<List<Txn>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeTxn(id: String): Flow<Txn?> = dao.observeById(id)

    /**
     * Manual re-categorization: persist on the txn, remember the merchant so
     * future (and past, non-overridden) txns auto-categorize.
     */
    fun reassignCategory(txn: Txn, category: String, onRemembered: () -> Unit) {
        viewModelScope.launch {
            val key = Categorizer.merchantKey(txn.merchant, txn.rawTitle)
            dao.updateCategory(txn.id, category)
            if (key.isNotBlank()) {
                merchantCategoryDao.upsert(MerchantCategory(key, category))
                // Teach the kedai, not the spelling: the next capture of the
                // same shop under the wallet's or the bank's variant lands
                // categorised.
                Kedai.fingerprint(txn.merchant)?.let { fp ->
                    merchantCategoryDao.upsert(MerchantCategory(Kedai.LEARNED_PREFIX + fp, category))
                }
                dao.recategorizeMerchant(key, category)
                // Statement variants of the same kedai ("RESTORANCONTOH NASI KUKUS"
                // vs "RESTORANCONTOH NASIKUKUS SHAH ALAM") miss the exact-key sweep;
                // catch them via the longest distinctive token of the key.
                Categorizer.distinctiveToken(key)?.let { token ->
                    // Bound: a token matching many rows is an instrument/junk
                    // word, not a kedai name — never mass-flip the ledger.
                    if (dao.countMerchantContaining(token) <= MAX_PROPAGATION_ROWS) {
                        dao.recategorizeMerchantContaining(token, category)
                    }
                }
            }
            SyncScheduler.syncNow(getApplication())
            onRemembered()
        }
    }

    /**
     * Delete locally and record a tombstone, so the next sync deletes it
     * server-side too. Without the tombstone the following pull would just
     * download the row again and the deletion would appear to undo itself.
     */
    fun deleteTxn(txn: Txn, onDone: () -> Unit) {
        viewModelScope.launch {
            dao.addTombstone(DeletedTxn(txn.id, System.currentTimeMillis()))
            dao.deleteById(txn.id)
            SyncScheduler.syncNow(getApplication())
            onDone()
        }
    }

    // ---- Sync status -------------------------------------------------------

    val pendingSyncCount: StateFlow<Int> = dao.observeAll()
        .map { list -> list.count { !it.synced } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val lastSyncAt: StateFlow<Long> = settingsRepo.lastSyncAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastSyncMsg: StateFlow<String> = settingsRepo.lastSyncMsg
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Manual sync. The worker owns both directions, so pressing Sync and the
     * 15-minute background pass do exactly the same thing — there is no second
     * code path that can quietly behave differently.
     */
    fun syncNow() {
        viewModelScope.launch {
            settingsRepo.recordSync(System.currentTimeMillis(), "sedang sync…")
            SyncScheduler.syncNow(getApplication())
        }
    }

    // ---- Budgets ----------------------------------------------------------

    fun setOverallBudgetRm(rm: String) = viewModelScope.launch {
        parseRm(rm)?.let { settingsRepo.setBudgetCents(it) }
    }

    /**
     * Blank clears the budget. An unparseable value is left alone: treating it
     * as "clear" meant a field the app itself had formatted in a comma-decimal
     * locale would wipe the budget the moment Save was pressed.
     */
    fun setCategoryBudgetRm(category: String, rm: String) = viewModelScope.launch {
        if (rm.isBlank()) {
            categoryBudgetDao.delete(category)
            return@launch
        }
        val cents = parseRm(rm) ?: return@launch
        if (cents <= 0) {
            categoryBudgetDao.delete(category)
        } else {
            categoryBudgetDao.upsert(CategoryBudget(category, cents))
        }
    }

    // ---- Month key & review queue -----------------------------------------

    /**
     * Selected month as "yyyy-MM" (server month parameter). A cycle is keyed
     * by the month its LAST day falls in — 28 Jul–27 Ogo is "August's money";
     * for calendar months this is the month itself, unchanged.
     */
    val monthKey: StateFlow<String> = monthSel
        .map { (off, day) -> monthKeyFmt.format(Date(monthRange(off, day).second - 1)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, monthKeyFmt.format(Date()))

    val isCurrentMonth: StateFlow<Boolean> = monthOffset
        .map { it == 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Review queue for the selected month, newest first. */
    /**
     * The queue is "needs a decision from you": a capture the parser could not
     * read, OR a row the brain has an opinion about. Kept separate from the
     * needsReview flag itself so a proposal never marks the row dirty for sync.
     */
    val needsReviewTxns: StateFlow<List<Txn>> = monthTxns
        .map { list ->
            list.filter {
                // A proposal that merely agrees with the rules offers no new
                // decision — surfacing it would teach the user to skim the queue.
                it.needsReview || (it.brainCategory != null && it.brainCategory != it.category)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ledger-wide count: drives the badge dot on the Utama tab. */
    val needsReviewCount: StateFlow<Int> = dao.observeAll()
        .map { list ->
            list.count {
                it.needsReview || (it.brainCategory != null && it.brainCategory != it.category)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Confirm a needs-review row: optimistic local flip, immediate PATCH so the
     * server agrees without waiting for the next sync pass, then a sync anyway
     * (the batch upsert carries the same bit, so a failed PATCH still converges).
     */
    /**
     * Accepting the queue's tick. When the brain had proposed a category, THIS
     * is the moment it becomes ledger truth — never earlier. The proposal is
     * cleared either way so the row leaves the queue.
     */
    fun confirmReview(txn: Txn) {
        viewModelScope.launch {
            txn.brainCategory?.let { proposed ->
                dao.updateCategory(txn.id, proposed)
                patchTxn(txn.id, category = proposed)
            }
            dao.clearBrainProposal(txn.id)
            dao.updateNeedsReview(txn.id, false)
            // Solo mode: the local flip IS the resolution — no PATCH, no sync.
            patchTxn(txn.id, needsReview = false)
            SyncScheduler.syncNow(getApplication())
        }
    }

    /**
     * Rejecting Akal's proposal: the suggestion vanishes, the rules' category
     * stands, and nothing is written to the ledger or synced — a proposal was
     * never ledger truth to begin with. If the row was also flagged
     * needsReview by the parser, that flag stays; it was there for its own
     * reason.
     */
    fun rejectBrainProposal(txn: Txn) {
        viewModelScope.launch { dao.clearBrainProposal(txn.id) }
    }

    /** One-off category change: this txn only, no merchant remembering. */
    fun reassignCategoryOnce(txn: Txn, category: String, onDone: () -> Unit) {
        viewModelScope.launch {
            dao.updateCategory(txn.id, category)
            patchTxn(txn.id, category = category)
            SyncScheduler.syncNow(getApplication())
            onDone()
        }
    }

    /** Amount correction (mockup: "betulkan"). */
    fun editAmount(txn: Txn, cents: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            dao.updateAmount(txn.id, cents)
            patchTxn(txn.id, amountCents = cents)
            SyncScheduler.syncNow(getApplication())
            onDone()
        }
    }

    /**
     * Manual/cash entry from the + button on Transaksi. With a receipt attached
     * the order matters: insert locally, push the row up directly (batch-of-one,
     * same payload as the sync push) so the server row exists, then upload the
     * receipt against it, then sync. A failed receipt upload keeps the txn —
     * the snackbar says so and the paperclip can be added later from Butiran.
     */
    fun addManualTxn(
        merchant: String,
        amountRm: String,
        category: String,
        dayMillis: Long,
        receiptUri: Uri? = null,
        onResult: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            val cents = parseRm(amountRm)
            if (merchant.isBlank() || cents == null || cents <= 0) {
                onResult(R.string.err_generic)
                return@launch
            }
            // Noon of the chosen day keeps the row inside that local day
            // whatever timezone the server buckets with.
            val at = Calendar.getInstance().apply {
                timeInMillis = dayMillis
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val txn = Txn(
                id = "manual-${UUID.randomUUID()}",
                sourcePkg = "manual",
                amountCents = cents,
                merchant = merchant.trim(),
                rawTitle = merchant.trim(),
                rawText = "",
                capturedAt = at,
                needsReview = false,
                synced = false,
                category = category,
                categoryOverridden = true,
            )
            dao.insert(txn)
            if (receiptUri == null) {
                SyncScheduler.syncNow(getApplication())
                onResult(R.string.txn_added)
                return@launch
            }
            if (isSolo()) {
                // Local save + SAF write-through; the txn stays either way.
                val res = attachReceiptLocal(txn.id, receiptUri)
                onResult(
                    when (res) {
                        R.string.rcpt_uploaded, R.string.rcpt_saved_pending -> R.string.txn_added
                        else -> R.string.rcpt_upload_failed
                    }
                )
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            var receiptOk = false
            if (ApiClient.pushTxn(token, txn) is ApiResult.Ok) {
                dao.markSynced(listOf(txn.id))
                val payload = withContext(Dispatchers.IO) { readUri(receiptUri) }
                if (payload != null && payload.first.size <= 10 * 1024 * 1024) {
                    val (bytes, filename, mime) = payload
                    when (val r = ApiClient.uploadReceipt(token, txn.id, bytes, filename, mime)) {
                        is ApiResult.Ok -> {
                            serverReceiptIds.value = serverReceiptIds.value + (txn.id to r.value)
                            receiptOk = true
                        }
                        else -> Unit
                    }
                }
            }
            SyncScheduler.syncNow(getApplication())
            onResult(if (receiptOk) R.string.txn_added else R.string.rcpt_upload_failed)
        }
    }

    /**
     * Parse a picked receipt for the manual-add dialog. Null on any failure
     * (offline, unsupported type); images legitimately parse to all-null fields.
     */
    fun parseReceipt(uri: Uri, onResult: (ReceiptParse?) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                // Receipt OCR is server-side only; the manual dialog simply
                // gets no prefill in solo mode.
                onResult(null)
                return@launch
            }
            val payload = withContext(Dispatchers.IO) { readUri(uri) }
            if (payload == null) {
                onResult(null)
                return@launch
            }
            val (bytes, filename, mime) = payload
            val token = settingsRepo.currentSessionToken()
            when (val r = ApiClient.parseReceipt(token, bytes, filename, mime)) {
                is ApiResult.Ok -> onResult(r.value)
                else -> onResult(null)
            }
        }
    }

    /**
     * Tetapan → Penyelenggaraan: re-run keyword rules server-side over
     * non-overridden Lain-lain txns, then sync so the changes land locally.
     * onResult gets a snackbar resource plus the count for its %1$d slot.
     */
    fun recategorizeServer(onResult: (Int, Int) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                // Same maintenance action, local rules: non-overridden
                // Lain-lain rows re-run through learned dict + keyword rules.
                var changed = 0
                val kedai = Kedai.load(db)
                for (t in dao.uncategorized()) {
                    val key = Categorizer.merchantKey(t.merchant, t.rawTitle)
                    val learned = merchantCategoryDao.byKey(key)?.category
                    val cat = learned ?: kedai.lookup(t.merchant) ?: Categorizer.categorize(
                        t.merchant, listOf(t.rawTitle, t.rawText).joinToString("\n")
                    )
                    if (cat != Categorizer.DEFAULT_CATEGORY) {
                        dao.setCategory(t.id, cat)
                        changed++
                    }
                }
                DiagLog.log(TAG, "local recategorize: $changed updated")
                onResult(R.string.recat_done, changed)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (val r = ApiClient.recategorize(token)) {
                is ApiResult.Ok -> {
                    SyncScheduler.syncNow(getApplication())
                    onResult(R.string.recat_done, r.value)
                }
                is ApiResult.Http -> onResult(R.string.err_generic, 0)
                is ApiResult.Fail -> onResult(R.string.err_offline, 0)
            }
        }
    }

    // ---- Server month data: per-day totals, receipts map -------------------

    /** Per-local-day totals from GET /api/summary/days, keyed "yyyy-MM-dd". */
    val daySummaries = MutableStateFlow<Map<String, DaySummary>>(emptyMap())

    /** txnId → receiptId, server-side rows (month extras, search, tax lists). */
    private val serverReceiptIds = MutableStateFlow<Map<String, String>>(emptyMap())

    /** txnId → receiptId from the solo receiptLocal table. */
    private val localReceiptIds: Flow<Map<String, String>> = receiptLocalDao.observeAll()
        .map { list -> list.associate { it.txnId to it.id } }

    /**
     * txnId → receiptId behind every paperclip indicator and the detail-sheet
     * receipt row. Server mode reads the server cache; solo mode reads
     * receiptLocal — same UI, different source of truth.
     */
    val receiptIds: StateFlow<Map<String, String>> =
        combine(appMode, serverReceiptIds, localReceiptIds) { mode, server, local ->
            if (mode == SettingsRepository.MODE_SOLO) local else server
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Refresh server-side month extras; quiet no-op offline (local data stands in). */
    fun refreshMonthData() {
        viewModelScope.launch {
            if (isSolo()) return@launch // paperclips come from receiptLocal
            val token = settingsRepo.currentSessionToken()
            if (token.isBlank()) return@launch
            val month = monthKey.value
            when (val r = ApiClient.fetchSummaryDays(token, month)) {
                is ApiResult.Ok -> daySummaries.value = r.value.associateBy { it.day }
                else -> Unit
            }
            when (val r = ApiClient.fetchTxns(token, month = month)) {
                is ApiResult.Ok -> serverReceiptIds.value = buildMap {
                    r.value.txns.forEach { t -> t.receiptId?.let { put(t.txn.id, it) } }
                }
                else -> Unit
            }
        }
    }

    // ---- 6-month series (Analisis) ------------------------------------------

    /** Server series when reachable; the local ledger otherwise. */
    private val serverMonths = MutableStateFlow<List<MonthPoint>?>(null)

    private val localMonths: Flow<List<MonthPoint>> = monthSel.flatMapLatest { (off, day) ->
        val months = (off - 5..off).map { o -> monthRange(o, day) }
        combine(
            months.map { range -> dao.observeTotalBetween(range.first, range.second) }
        ) { totals ->
            months.mapIndexed { i, range ->
                MonthPoint(trendLabelFmt.format(Date(range.second - 1)).uppercase(), totals[i])
            }
        }
    }

    val monthsSeries: StateFlow<List<MonthPoint>> =
        combine(serverMonths, localMonths) { server, local -> server ?: local }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshMonthsSeries() {
        viewModelScope.launch {
            if (isSolo()) {
                serverMonths.value = null // local series is the whole truth
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            if (token.isBlank()) return@launch
            when (val r = ApiClient.fetchSummaryMonths(token, end = monthKey.value, n = 6)) {
                is ApiResult.Ok -> serverMonths.value = r.value.map { m ->
                    val label = runCatching {
                        trendLabelFmt.format(monthKeyFmt.parse(m.month)!!).uppercase()
                    }.getOrDefault(m.month.takeLast(2))
                    MonthPoint(label, m.totalCents)
                }
                else -> serverMonths.value = null
            }
        }
    }

    // ---- Insights: previous-month totals + top merchants ---------------------

    /** Previous cycle's per-category totals (local mirror of the server ledger). */
    val prevCategoryTotals: StateFlow<Map<String, Long>> = monthSel
        .flatMapLatest { (off, day) ->
            val (from, until) = monthRange(off - 1, day)
            dao.observeBetween(from, until)
        }
        .map { txns ->
            txns.groupBy { it.category }
                .mapValues { (_, list) -> list.sumOf { it.amountCents ?: 0L } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val prevMonthLabel: StateFlow<String> = monthSel
        .map { (off, day) -> trendLabelFmt.format(Date(monthRange(off - 1, day).second - 1)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    data class MerchantStat(val merchant: String, val count: Int, val totalCents: Long)

    val topMerchants: StateFlow<List<MerchantStat>> = monthTxns
        .map { txns ->
            txns.filter { !it.merchant.isNullOrBlank() && it.amountCents != null }
                .groupBy { it.merchant!!.trim().uppercase() }
                .map { (m, list) ->
                    MerchantStat(m, list.size, list.sumOf { it.amountCents ?: 0L })
                }
                .sortedWith(compareByDescending<MerchantStat> { it.count }.thenByDescending { it.totalCents })
                .take(5)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Search ----------------------------------------------------------------

    val searchQuery = MutableStateFlow("")

    /** Remote keyword search across all months (server FTS); empty offline. */
    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<Txn>> = searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            kotlinx.coroutines.flow.flow {
                if (q.isBlank()) {
                    emit(emptyList())
                    return@flow
                }
                if (isSolo()) {
                    // Room is the whole ledger in solo mode: a plain substring
                    // scan over merchant/raw text replaces the server FTS.
                    val needle = q.trim().uppercase()
                    emit(
                        dao.all()
                            .filter {
                                (it.merchant ?: "").uppercase().contains(needle) ||
                                    it.rawTitle.uppercase().contains(needle) ||
                                    it.rawText.uppercase().contains(needle)
                            }
                            .sortedByDescending { it.capturedAt }
                            .take(200)
                    )
                    return@flow
                }
                val token = settingsRepo.currentSessionToken()
                when (val r = ApiClient.fetchTxns(token, q = q, limit = 200)) {
                    is ApiResult.Ok -> {
                        // Search rows may live outside the loaded month; keep their
                        // receipt ids so the detail sheet still shows the paperclip.
                        val extra = buildMap {
                            r.value.txns.forEach { t -> t.receiptId?.let { put(t.txn.id, it) } }
                        }
                        if (extra.isNotEmpty()) serverReceiptIds.value = serverReceiptIds.value + extra
                        emit(r.value.txns.map { it.txn })
                    }
                    else -> emit(emptyList())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Receipts ----------------------------------------------------------------

    /**
     * SOLO attach: local file first (filesDir/receipts/{id}.{ext} is the
     * primary), then SAF write-through. A failed write-through leaves the row
     * pending — the hourly BackupWorker retries; the receipt itself is safe.
     */
    private suspend fun attachReceiptLocal(txnId: String, uri: Uri): Int {
        if (receiptLocalDao.byTxnId(txnId) != null) return R.string.rcpt_exists
        val payload = withContext(Dispatchers.IO) { readUri(uri) }
            ?: return R.string.rcpt_upload_failed
        val (bytes, filename, mime) = payload
        if (bytes.size > 10 * 1024 * 1024) return R.string.rcpt_too_big
        val txn = dao.byId(txnId)
        val receipt = ReceiptLocal(
            id = UUID.randomUUID().toString().replace("-", ""),
            txnId = txnId,
            merchant = txn?.merchant ?: txn?.rawTitle,
            amountCents = txn?.amountCents,
            txnCapturedAt = txn?.capturedAt ?: System.currentTimeMillis(),
            filename = filename,
            mime = mime,
            sizeBytes = bytes.size.toLong(),
            sha256 = SafBackup.sha256Hex(bytes),
            uploadedAt = System.currentTimeMillis(),
            backupUri = null,
        )
        val app = getApplication<Application>()
        val saved = withContext(Dispatchers.IO) {
            runCatching { SafBackup.localFile(app, receipt).writeBytes(bytes) }.isSuccess
        }
        if (!saved) return R.string.rcpt_upload_failed
        receiptLocalDao.insert(receipt)
        DiagLog.log(TAG, "receipt attached ${receipt.id.take(8)} txn=$txnId (${bytes.size}B)")
        val backedUp = withContext(Dispatchers.IO) { SafBackup.backupReceipt(app, receipt) }
        return if (backedUp) R.string.rcpt_uploaded else R.string.rcpt_saved_pending
    }

    /** Attach a picked file; result is a snackbar string resource. */
    fun uploadReceipt(txnId: String, uri: Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                onResult(attachReceiptLocal(txnId, uri))
                return@launch
            }
            val payload = withContext(Dispatchers.IO) { readUri(uri) }
            if (payload == null) {
                onResult(R.string.rcpt_upload_failed)
                return@launch
            }
            val (bytes, filename, mime) = payload
            if (bytes.size > 10 * 1024 * 1024) {
                onResult(R.string.rcpt_too_big)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (val r = ApiClient.uploadReceipt(token, txnId, bytes, filename, mime)) {
                is ApiResult.Ok -> {
                    serverReceiptIds.value = serverReceiptIds.value + (txnId to r.value)
                    onResult(R.string.rcpt_uploaded)
                }
                is ApiResult.Http -> onResult(
                    when (r.code) {
                        409 -> R.string.rcpt_exists
                        413 -> R.string.rcpt_too_big
                        else -> R.string.rcpt_upload_failed
                    }
                )
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    /** Download to Downloads (MediaStore on Q+) and open it. */
    fun downloadReceipt(receiptId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                // View/download = open the primary local copy in place.
                val receipt = receiptLocalDao.byId(receiptId)
                if (receipt == null) {
                    onResult(R.string.rcpt_download_failed)
                    return@launch
                }
                val opened = withContext(Dispatchers.IO) { openLocalReceipt(receipt) }
                onResult(if (opened) R.string.rcpt_downloaded else R.string.rcpt_download_failed)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            val meta = when (val m = ApiClient.fetchReceiptMeta(token, receiptId)) {
                is ApiResult.Ok -> m.value
                else -> null
            }
            when (val r = ApiClient.downloadReceipt(token, receiptId)) {
                is ApiResult.Ok -> {
                    val mime = meta?.mime ?: r.value.mime
                    val filename = meta?.filename ?: "resit-$receiptId"
                    val opened = withContext(Dispatchers.IO) {
                        saveAndOpen(r.value.bytes, filename, mime)
                    }
                    onResult(if (opened) R.string.rcpt_downloaded else R.string.rcpt_download_failed)
                }
                is ApiResult.Http -> onResult(R.string.rcpt_download_failed)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    fun deleteReceipt(txnId: String, receiptId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                val receipt = receiptLocalDao.byId(receiptId)
                if (receipt == null) {
                    onResult(R.string.rcpt_delete_failed)
                    return@launch
                }
                val app = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    // SAF copy + tombstone first, then the local file + row.
                    SafBackup.deleteBackup(app, receipt)
                    SafBackup.localFile(app, receipt).delete()
                }
                receiptLocalDao.delete(receiptId)
                DiagLog.log(TAG, "receipt deleted ${receiptId.take(8)} txn=$txnId")
                onResult(R.string.rcpt_deleted)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (ApiClient.deleteReceipt(token, receiptId)) {
                is ApiResult.Ok -> {
                    serverReceiptIds.value = serverReceiptIds.value - txnId
                    onResult(R.string.rcpt_deleted)
                }
                is ApiResult.Http -> onResult(R.string.rcpt_delete_failed)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    private fun readUri(uri: Uri): Triple<ByteArray, String, String>? {
        return try {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            var name = "fail"
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            Triple(bytes, name, mime)
        } catch (t: Throwable) {
            null
        }
    }

    /** Solo mode: open filesDir/receipts/{id}.{ext} via the FileProvider. */
    private fun openLocalReceipt(receipt: ReceiptLocal): Boolean {
        return try {
            val app = getApplication<Application>()
            val f = SafBackup.localFile(app, receipt)
            if (!f.exists()) return false
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, receipt.mime)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Q+: MediaStore Downloads; pre-Q: app cache exposed via FileProvider. */
    private fun saveAndOpen(bytes: ByteArray, filename: String, mime: String): Boolean {
        return try {
            val app = getApplication<Application>()
            val viewUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val uri = app.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                uri
            } else {
                val dir = File(app.cacheDir, "receipts").apply { mkdirs() }
                val f = File(dir, filename.ifBlank { "resit" })
                f.writeBytes(bytes)
                FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
            }
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(viewUri, mime)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ---- Cukai (LHDN reliefs) --------------------------------------------------

    /** Selected Year of Assessment; defaults to the current spending year. */
    val taxYa = MutableStateFlow(currentYa())

    /** Cached server summary for the selected YA (also feeds the picker rooms). */
    val taxSummary = MutableStateFlow<TaxSummary?>(null)

    /** Non-null = the last refresh failed (string resource). Cukai is the one
     *  screen with no local fallback — a silent failure here reads as an
     *  eternal spinner, so the failure must be loud. */
    val taxError = MutableStateFlow<Int?>(null)

    /** Tagged txns for the relief detail screen (server-side, carries receiptId). */
    val taxTxns = MutableStateFlow<List<RemoteTxn>>(emptyList())

    fun prevYa() {
        taxYa.value -= 1
        taxSummary.value = null
        refreshTaxSummary()
    }

    fun nextYa() {
        if (taxYa.value >= currentYa()) return
        taxYa.value += 1
        taxSummary.value = null
        refreshTaxSummary()
    }

    fun refreshTaxSummary() {
        viewModelScope.launch {
            taxError.value = null
            if (isSolo()) {
                // Local computation over Room: same YA window and cap math as
                // /api/tax/summary; evidence = a receiptLocal row exists.
                val ya = taxYa.value
                val (from, until) = TaxLocal.yaBoundsMs(ya)
                val tagged = dao.taggedBetween(from, until)
                val evidenced = receiptLocalDao.byTxnIds(tagged.map { it.id })
                    .map { it.txnId }.toSet()
                taxSummary.value = TaxLocal.summary(ya, tagged, evidenced)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            if (token.isBlank()) {
                taxError.value = R.string.err_offline
                return@launch
            }
            when (val r = ApiClient.fetchTaxSummary(token, taxYa.value)) {
                is ApiResult.Ok -> taxSummary.value = r.value
                is ApiResult.Http -> taxError.value = R.string.tax_load_fail
                is ApiResult.Fail -> taxError.value = R.string.err_offline
            }
        }
    }

    fun loadReliefTxns(code: String) {
        viewModelScope.launch {
            taxTxns.value = emptyList()
            if (isSolo()) {
                val (from, until) = TaxLocal.yaBoundsMs(taxYa.value)
                val txns = dao.byReliefBetween(code, from, until)
                val receipts = receiptLocalDao.byTxnIds(txns.map { it.id })
                    .associateBy { it.txnId }
                taxTxns.value = txns.map { RemoteTxn(it, receipts[it.id]?.id) }
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            if (token.isBlank()) return@launch
            when (val r = ApiClient.fetchTaxTxns(token, taxYa.value, code)) {
                is ApiResult.Ok -> {
                    taxTxns.value = r.value
                    // Keep the paperclip visible when a row opens the detail sheet.
                    val extra = buildMap {
                        r.value.forEach { t -> t.receiptId?.let { put(t.txn.id, it) } }
                    }
                    if (extra.isNotEmpty()) serverReceiptIds.value = serverReceiptIds.value + extra
                }
                else -> Unit
            }
        }
    }

    /**
     * Tag / untag a relief: same shape as [reassignCategoryOnce] — optimistic
     * local update (marks the row unsynced so protectedIds shields it from the
     * pull), immediate PATCH, then a sync pass whose batch upsert carries the
     * same field, so a failed PATCH still converges. relief = code, "none",
     * or null to clear (sent as "" which the server treats as clear).
     */
    fun tagRelief(txn: Txn, relief: String?, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            dao.updateRelief(txn.id, relief)
            patchTxn(txn.id, relief = relief ?: "")
            SyncScheduler.syncNow(getApplication())
            refreshTaxSummary()
            onResult(
                when (relief) {
                    null -> R.string.relief_cleared_snack
                    "none" -> R.string.relief_none_snack
                    else -> R.string.relief_tagged_snack
                }
            )
        }
    }

    // ---- Statements -----------------------------------------------------------

    val statements = MutableStateFlow<List<StatementInfo>>(emptyList())
    val statementDetail = MutableStateFlow<StatementDetail?>(null)
    val statementBusy = MutableStateFlow(false)

    /** Device timezone offset in minutes (server pins 480; solo follows the phone). */
    private fun tzOffsetMinutes(): Int =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    /**
     * Keyword rules first, then the kedai dictionary by fingerprint — not by
     * any shared token, which is how a clinic in one town once turned every
     * descriptor from that town into Health.
     */
    private suspend fun suggestCategoryLocal(
        description: String,
        kedai: Kedai.Dictionary? = null,
    ): String {
        val ruled = Categorizer.categorize(null, description)
        if (ruled != Categorizer.DEFAULT_CATEGORY) return ruled
        return (kedai ?: Kedai.load(db)).lookup(description)
            ?.takeIf { it != "Pindahan" }
            ?: Categorizer.DEFAULT_CATEGORY
    }

    /**
     * The statement as the merchant-name baseline. For every matched row:
     * an override on the capture teaches the descriptor's fingerprint (the
     * canonical spelling), and an untaught Lain-lain capture takes whatever
     * the ledger already knows about that descriptor. Returns
     * (relabelled, taught).
     */
    private suspend fun reconcileCategoriesFromStatement(
        rows: List<StatementParser.Row>,
        ledger: List<Txn>,
    ): Pair<Int, Int> {
        val byId = ledger.associateBy { it.id }
        val kedai = Kedai.load(db)
        var relabelled = 0
        var taught = 0
        for (r in rows) {
            val t = r.matchedTxnId?.let(byId::get) ?: continue
            val fp = Kedai.fingerprint(r.description) ?: continue
            if (t.categoryOverridden && t.category != Categorizer.DEFAULT_CATEGORY) {
                merchantCategoryDao.upsert(MerchantCategory(Kedai.LEARNED_PREFIX + fp, t.category))
                taught++
            } else if (!t.categoryOverridden && t.category == Categorizer.DEFAULT_CATEGORY) {
                val cat = kedai.lookup(r.description) ?: kedai.lookup(t.merchant)
                if (cat != null && cat != Categorizer.DEFAULT_CATEGORY && cat != "Pindahan") {
                    dao.setCategory(t.id, cat)
                    relabelled++
                }
            }
        }
        return relabelled to taught
    }

    private fun localStatementInfo(s: StatementLocal, rows: List<StatementRowLocal>) =
        StatementInfo(
            id = s.id,
            month = s.month,
            filename = s.filename,
            uploadedAt = s.uploadedAt,
            matched = rows.count { it.status == "matched" },
            missing = rows.count { it.status == "missing" },
            credits = rows.count { it.status == "credit" },
            unresolved = rows.count { it.status == "missing" && it.resolved == null },
        )

    private suspend fun localStatements(): List<StatementInfo> =
        statementLocalDao.statements().map { localStatementInfo(it, statementLocalDao.rows(it.id)) }

    /** Solo detail: rows + suggestions + the "belum jelas" txns, like the server. */
    private suspend fun localStatementDetail(id: String): StatementDetail? {
        val s = statementLocalDao.byId(id) ?: return null
        val rows = statementLocalDao.rows(id)
        val apiRows = rows.map { r ->
            uk.wakbijok.belanja.api.StatementRow(
                id = r.id,
                txnDate = r.txnDate,
                description = r.description,
                amountCents = r.amountCents,
                credit = r.credit,
                status = r.status,
                resolved = r.resolved,
                suggestedCategory = if (r.status == "missing" && r.resolved == null) {
                    suggestCategoryLocal(r.description)
                } else {
                    null
                },
            )
        }
        // Card captures inside the cycle that no statement row matched —
        // usually payments still pending posting. Only card money can be
        // "unclear" against a card statement: tolls, transfers and loan
        // instalments are not pending, they are simply elsewhere. Captures
        // after the cycle's last day belong to the next statement.
        val matchedIds = rows.mapNotNull { it.matchedTxnId }.toSet()
        val unclear = if (rows.isEmpty()) {
            emptyList()
        } else {
            val tz = tzOffsetMinutes()
            val lo = StatementParser.dayWindowMs(rows.minOf { it.txnDate }, tz).first
            val hi = StatementParser.dayEndMs(rows.maxOf { it.txnDate }, tz)
            dao.all()
                .filter {
                    it.capturedAt in lo until hi && it.sourcePkg != "statement" &&
                        it.id !in matchedIds && Instrument.of(it) == Instrument.CARD
                }
                .sortedByDescending { it.capturedAt }
        }
        return StatementDetail(id = s.id, month = s.month, rows = apiRows, unclear = unclear)
    }

    fun refreshStatements(loadLatestDetail: Boolean = false) {
        viewModelScope.launch {
            if (isSolo()) {
                statements.value = localStatements()
                if (loadLatestDetail) {
                    statements.value.firstOrNull()?.let { loadStatementDetail(it.id) }
                }
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            if (token.isBlank()) return@launch
            when (val r = ApiClient.fetchStatements(token)) {
                is ApiResult.Ok -> {
                    statements.value = r.value
                    if (loadLatestDetail) {
                        r.value.firstOrNull()?.let { loadStatementDetail(it.id) }
                    }
                }
                else -> Unit
            }
        }
    }

    fun loadStatementDetail(id: String) {
        viewModelScope.launch {
            statementBusy.value = true
            if (isSolo()) {
                localStatementDetail(id)?.let { statementDetail.value = it }
                statementBusy.value = false
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (val r = ApiClient.fetchStatementDetail(token, id)) {
                is ApiResult.Ok -> statementDetail.value = r.value
                else -> Unit
            }
            statementBusy.value = false
        }
    }

    fun uploadStatement(uri: Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            statementBusy.value = true
            val payload = withContext(Dispatchers.IO) { readUri(uri) }
            if (payload == null) {
                statementBusy.value = false
                onResult(R.string.stmt_upload_fail)
                return@launch
            }
            val (bytes, filename, mime) = payload
            if (isSolo()) {
                onResult(uploadStatementLocal(bytes, filename, mime))
                statementBusy.value = false
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (ApiClient.uploadStatement(token, bytes, filename, mime)) {
                is ApiResult.Ok -> {
                    refreshStatements(loadLatestDetail = true)
                    onResult(R.string.stmt_upload_ok)
                }
                is ApiResult.Http -> onResult(R.string.stmt_upload_fail)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
            statementBusy.value = false
        }
    }

    /**
     * SOLO upload: parse + match on-device (parser/StatementParser.kt, a port
     * of server statements.py) and store the run in the local tables.
     * Re-uploading a month replaces the old run, exactly like the server.
     */
    private suspend fun uploadStatementLocal(bytes: ByteArray, filename: String, mime: String): Int {
        val parsed = withContext(Dispatchers.IO) {
            if (mime == "application/pdf") {
                StatementParser.parsePdf(bytes)
            } else {
                StatementParser.parseText(bytes.toString(Charsets.UTF_8))
            }
        }
        when (parsed) {
            is StatementParser.Result.Err -> {
                DiagLog.log(TAG, "statement parse failed: ${parsed.reason}")
                return when (parsed.reason) {
                    StatementParser.Fail.LOCKED_PDF -> R.string.stmt_pdf_locked
                    else -> R.string.stmt_parse_fail
                }
            }
            is StatementParser.Result.Ok -> {
                val month = "%04d-%02d".format(
                    Locale.US, parsed.statementDate.year, parsed.statementDate.monthValue
                )
                // Statement-sourced txns ARE candidates: on a re-upload, rows
                // previously added must come back matched, not as duplicates.
                val ledger = dao.all()
                StatementParser.matchRows(parsed.rows, ledger, tzOffsetMinutes())
                statementLocalDao.byMonth(month)?.let { old ->
                    statementLocalDao.deleteRows(old.id)
                    statementLocalDao.deleteStatement(old.id)
                }
                val statementId = UUID.randomUUID().toString().replace("-", "")
                statementLocalDao.upsertStatement(
                    StatementLocal(
                        id = statementId,
                        month = month,
                        statementDate = parsed.statementDate.toString(),
                        filename = filename.ifBlank { "statement.pdf" },
                        uploadedAt = System.currentTimeMillis(),
                    )
                )
                statementLocalDao.insertRows(
                    parsed.rows.map { r ->
                        StatementRowLocal(
                            statementId = statementId,
                            postingDate = r.postingDate,
                            txnDate = r.txnDate,
                            description = r.description,
                            amountCents = r.amountCents,
                            credit = r.credit,
                            status = r.status,
                            matchedTxnId = r.matchedTxnId,
                            resolved = null,
                        )
                    }
                )
                val (relabelled, taught) = reconcileCategoriesFromStatement(parsed.rows, ledger)
                DiagLog.log(
                    TAG,
                    "statement $month parsed: ${parsed.rows.size} rows, " +
                        "${parsed.rows.count { it.status == "matched" }} matched, " +
                        "${parsed.rows.count { it.status == "missing" }} missing; " +
                        "kategori: $relabelled dilabel semula, $taught kedai diajar",
                )
                refreshStatements(loadLatestDetail = true)
                return R.string.stmt_upload_ok
            }
        }
    }

    /**
     * SOLO resolve-add: same contract as the server's _resolve_add — a local
     * txn (source 'statement') at local noon of the txn date; a category the
     * user picked counts as an override, the on-screen suggestion does not.
     */
    private suspend fun resolveAddLocal(
        statementId: String,
        row: StatementRowLocal,
        category: String?,
    ) {
        val suggested = suggestCategoryLocal(row.description)
        val overridden = category != null && category != suggested
        val cat = category ?: suggested
        val txnId = "stmt-${statementId.take(8)}-${row.id}"
        val d = java.time.LocalDate.parse(row.txnDate)
        val capturedAt = Calendar.getInstance().apply {
            clear()
            set(d.year, d.monthValue - 1, d.dayOfMonth, 12, 0, 0)
        }.timeInMillis
        dao.insert(
            Txn(
                id = txnId,
                sourcePkg = "statement",
                amountCents = row.amountCents,
                merchant = row.description,
                rawTitle = "Penyata",
                rawText = "Dari penyata: ${row.description}",
                capturedAt = capturedAt,
                needsReview = false,
                synced = false,
                category = cat,
                categoryOverridden = overridden,
            )
        )
        statementLocalDao.resolveRow(row.id, "added", txnId)
    }

    /** Resolve a "tiada dalam Belanja" row: add (with category) or ignore. */
    fun resolveStatementRow(
        statementId: String,
        rowId: Long,
        action: String,
        category: String?,
        onResult: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            if (isSolo()) {
                val row = statementLocalDao.rowById(statementId, rowId)
                if (row == null || row.status != "missing" || row.resolved != null) {
                    onResult(R.string.stmt_resolve_fail)
                    return@launch
                }
                if (action == "ignore") {
                    statementLocalDao.resolveRow(rowId, "ignored", row.matchedTxnId)
                } else {
                    resolveAddLocal(statementId, row, category)
                }
                loadStatementDetail(statementId)
                refreshStatements()
                onResult(if (action == "add") R.string.stmt_added else R.string.stmt_ignored)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (ApiClient.resolveStatementRow(token, statementId, rowId, action, category)) {
                is ApiResult.Ok -> {
                    loadStatementDetail(statementId)
                    refreshStatements()
                    // The added txn now exists server-side; pull it down.
                    if (action == "add") SyncScheduler.syncNow(getApplication())
                    onResult(if (action == "add") R.string.stmt_added else R.string.stmt_ignored)
                }
                is ApiResult.Http -> onResult(R.string.stmt_resolve_fail)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    /** Bulk: add every unresolved missing row, or ignore them all. */
    fun resolveAllStatementRows(statementId: String, action: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                val open = statementLocalDao.rows(statementId)
                    .filter { it.status == "missing" && it.resolved == null }
                for (row in open) {
                    if (action == "ignore") {
                        statementLocalDao.resolveRow(row.id, "ignored", row.matchedTxnId)
                    } else {
                        resolveAddLocal(statementId, row, null)
                    }
                }
                loadStatementDetail(statementId)
                refreshStatements()
                onResult(if (action == "add") R.string.stmt_added_all else R.string.stmt_ignored_all)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (ApiClient.resolveAllStatementRows(token, statementId, action)) {
                is ApiResult.Ok -> {
                    loadStatementDetail(statementId)
                    refreshStatements()
                    if (action == "add") SyncScheduler.syncNow(getApplication())
                    onResult(if (action == "add") R.string.stmt_added_all else R.string.stmt_ignored_all)
                }
                is ApiResult.Http -> onResult(R.string.stmt_resolve_fail)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    /** Remove a statement run; txns already added from it stay. */
    fun deleteStatement(statementId: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            if (isSolo()) {
                // Txns already added from this run stay — real ledger entries.
                statementLocalDao.deleteRows(statementId)
                statementLocalDao.deleteStatement(statementId)
                statementDetail.value = null
                refreshStatements()
                onResult(R.string.stmt_deleted)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (ApiClient.deleteStatement(token, statementId)) {
                is ApiResult.Ok -> {
                    statementDetail.value = null
                    refreshStatements()
                    onResult(R.string.stmt_deleted)
                }
                is ApiResult.Http -> onResult(R.string.stmt_resolve_fail)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    // ---- Sandaran (SAF) — solo mode -----------------------------------------------

    val safFolderName: StateFlow<String> = settingsRepo.safFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val lastBackupAt: StateFlow<Long> = settingsRepo.lastBackupAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val lastBackupMsg: StateFlow<String> = settingsRepo.lastBackupMsg
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Persist the picked tree (the Activity already took the permission). */
    fun setSafTree(uri: Uri) {
        viewModelScope.launch {
            val name = runCatching {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
                    .substringAfterLast(':').substringAfterLast('/')
            }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "folder" }
            settingsRepo.setSafTree(uri.toString(), name)
            DiagLog.log(TAG, "SAF tree set: $name")
            BackupScheduler.ensurePeriodic(getApplication())
            // Immediate first pass: backfill pendings + today's DB copy.
            BackupScheduler.backupNow(getApplication())
        }
    }

    fun backupNow() = BackupScheduler.backupNow(getApplication())

    // ---- Diagnostik ----------------------------------------------------------------

    /** Snapshot of the diag ring, newest first; refreshed when the screen opens. */
    val diagLines = MutableStateFlow<List<String>>(emptyList())

    fun refreshDiagLines() {
        diagLines.value = DiagLog.lines()
    }

    private suspend fun settingsSnapshot(): String {
        val app = getApplication<Application>()
        val notifOk = androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(app).contains(app.packageName)
        val battOk = app.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(app.packageName) ?: false
        return buildString {
            appendLine("version: ${uk.wakbijok.belanja.BuildConfig.VERSION_NAME} (${uk.wakbijok.belanja.BuildConfig.VERSION_CODE})")
            appendLine("mode: ${settingsRepo.currentAppMode()}")
            appendLine("safTree: ${settingsRepo.currentSafTree().ifBlank { "-" }}")
            appendLine("notificationAccess: $notifOk")
            appendLine("batteryUnrestricted: $battOk")
            appendLine("sourcePackages:")
            SettingsRepository.SOURCE_PACKAGES.forEach { appendLine("  - $it") }
        }
    }

    /** Build the zip (log + checkpointed DB + settings) and open the share sheet. */
    fun exportDiag(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val snapshot = settingsSnapshot()
            val zip = withContext(Dispatchers.IO) { DiagLog.buildZip(app, snapshot) }
            if (zip == null) {
                onResult(R.string.diag_export_fail)
                return@launch
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                app.startActivity(
                    Intent.createChooser(send, null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { onResult(R.string.diag_export_fail) }
        }
    }

    /**
     * Server mode: POST the bundle to /api/diag. The endpoint may not exist
     * yet server-side — a 404 gets its own graceful snackbar so this lights
     * up by itself once Izu lands it.
     */
    fun sendDiagToServer(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val snapshot = settingsSnapshot()
            val zip = withContext(Dispatchers.IO) { DiagLog.buildZip(app, snapshot) }
            if (zip == null) {
                onResult(R.string.diag_export_fail)
                return@launch
            }
            val token = settingsRepo.currentSessionToken()
            when (val r = ApiClient.uploadDiag(token, zip.readBytes(), zip.name)) {
                is ApiResult.Ok -> onResult(R.string.diag_sent)
                is ApiResult.Http -> onResult(
                    if (r.code == 404) R.string.diag_endpoint_missing else R.string.err_generic
                )
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    // ---- Account / app settings --------------------------------------------------

    val username: StateFlow<String> = settingsRepo.username
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    init {
        // Sessions carried over from before the username pref existed (v0.7)
        // have a token but no stored name — recover it from the JWT `sub`.
        viewModelScope.launch {
            val stored = settingsRepo.username.first()
            val token = settingsRepo.currentSessionToken()
            if (stored.isBlank() && !token.isNullOrBlank()) {
                jwtSubject(token)?.let { settingsRepo.setUsername(it) }
            }
        }
    }

    private fun jwtSubject(token: String): String? = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return null
        val json = android.util.Base64.decode(
            payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        ).decodeToString()
        org.json.JSONObject(json).optString("sub").takeIf { it.isNotBlank() }
    }.getOrNull()

    val candidateSources: StateFlow<Set<String>> = settingsRepo.candidateSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val extraSources: StateFlow<Set<String>> = settingsRepo.extraSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun addSource(pkg: String) = viewModelScope.launch { settingsRepo.addSource(pkg) }

    fun dismissCandidate(pkg: String) =
        viewModelScope.launch { settingsRepo.dismissCandidate(pkg) }

    val themeMode: StateFlow<String> = settingsRepo.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.THEME_SYSTEM)

    fun setThemeMode(mode: String) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }

    fun changePassword(current: String, new: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val token = settingsRepo.currentSessionToken()
            when (ApiClient.changePassword(token, current, new)) {
                is ApiResult.Ok -> onResult(R.string.chpass_ok)
                is ApiResult.Http -> onResult(R.string.chpass_fail)
                is ApiResult.Fail -> onResult(R.string.err_offline)
            }
        }
    }

    // ---- Helpers ----------------------------------------------------------

    companion object {
        private const val TAG = "MainViewModel"

        /** Accepts both `1500.00` and `1500,00`, whatever the device locale. */
        fun parseRm(rm: String): Long? =
            rm.trim().replace(",", ".").toDoubleOrNull()?.let { (it * 100).toLong() }

        /** Edit fields are formatted with a fixed locale so they parse back. */
        fun formatRm(cents: Long): String = String.format(Locale.US, "%.2f", cents / 100.0)

        private val monthLabelFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        private val trendLabelFmt = SimpleDateFormat("MMM", Locale.getDefault())
        private val monthKeyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
        private val cycleDayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        private val cycleEndFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        /** [start, end) millis of the cycle `offset` months from now. */
        fun monthRange(offset: Int, startDay: Int = 1): Pair<Long, Long> =
            Cycle.range(offset, startDay)

        /** "Ogos 2026" for calendar months, "28 Jul – 27 Ogo 2026" for cycles. */
        private fun cycleLabel(range: Pair<Long, Long>, startDay: Int): String =
            if (startDay == 1) monthLabelFmt.format(Date(range.first))
            else cycleDayFmt.format(Date(range.first)) + " – " +
                cycleEndFmt.format(Date(range.second - 1))
    }
}
