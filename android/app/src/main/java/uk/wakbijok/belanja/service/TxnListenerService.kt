package uk.wakbijok.belanja.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.data.Txn
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.parser.Categorizer
import uk.wakbijok.belanja.parser.CrossSource
import uk.wakbijok.belanja.parser.Kedai
import uk.wakbijok.belanja.parser.TxnParser
import uk.wakbijok.belanja.settings.SettingsRepository
import uk.wakbijok.belanja.llm.BrainWorker
import uk.wakbijok.belanja.llm.MuraiWorker
import uk.wakbijok.belanja.sync.SyncScheduler

class TxnListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val postedAt = sbn.postTime

        scope.launch {
            try {
                captureLock.withLock { handle(pkg, title, text, bigText, postedAt) }
            } catch (t: Throwable) {
                Log.e(TAG, "failed to handle notification from $pkg", t)
            }
        }
    }

    private suspend fun handle(
        pkg: String,
        title: String,
        text: String,
        bigText: String,
        postedAt: Long,
    ) {
        val settings = SettingsRepository(applicationContext)
        if (pkg !in settings.currentSources()) {
            // Payment-shaped text from an unmonitored package is a gap in the
            // allowlist, not noise — record it so Tetapan can offer to add it.
            val probeBody = if (bigText.length >= text.length) bigText else text
            val probe = listOf(title, probeBody).filter { it.isNotBlank() }.joinToString(" ")
            if (TxnParser.looksLikePayment(probe)) {
                settings.noteCandidate(pkg)
                DiagLog.log(TAG, "CALON $pkg: berbau bayaran tapi tak dipantau")
            } else {
                DiagLog.log(TAG, "reject $pkg: pkg not monitored")
            }
            return
        }

        // bigText usually contains text; use the longer body plus title for parsing.
        val body = if (bigText.length >= text.length) bigText else text
        val fullText = listOf(title, body).filter { it.isNotBlank() }.joinToString("\n")
        if (fullText.isBlank()) return

        val parsed = TxnParser.parse(pkg, fullText)
        val verdict = TxnParser.classify(fullText)
        if (parsed == null && verdict.startsWith("reject")) {
            // Log the text itself for monitored packages: tuning the filter
            // is impossible without seeing what was thrown away (the TNG
            // 'Rewards' false-reject, 27-07-2026). Digit runs are masked —
            // rejects are where OTP/TAC codes and account numbers land.
            DiagLog.log(TAG, "reject $pkg [$verdict]: ${DiagLog.maskDigits(fullText.replace("\n", " ").take(120))}")
            return
        }

        val db = AppDb.get(applicationContext)
        val dao = db.txnDao()

        // Dedupe: same source + same raw text within 2 minutes.
        // Cross-source first: the same purchase announced by a wallet and by
        // the bank behind it must land once, not twice.
        val amount = parsed?.amountCents
        if (amount != null) {
            val existing = dao.crossSourceCandidates(
                pkg, amount,
                postedAt - CrossSource.WIDE_WINDOW_MS,
                postedAt + CrossSource.WIDE_WINDOW_MS,
            ).firstOrNull { CrossSource.sameMoney(it, pkg, parsed.merchant, title, body, postedAt) }
            if (existing != null) {
                // Same purchase, two announcers. Common workflow: every card
                // payment goes through Samsung Wallet, whose notification
                // names the merchant cleanly — so the better source wins even
                // when it arrives second.
                if (sourceRank(pkg) > sourceRank(existing.sourcePkg)) {
                    dao.upgradeSource(existing.id, pkg, parsed.merchant, title, body)
                    DiagLog.log(TAG, "naik taraf sumber → $pkg (RM${amount / 100.0})")
                    SyncScheduler.syncNow(applicationContext)
                } else {
                    DiagLog.log(TAG, "dedupe silang-sumber $pkg: RM${amount / 100.0} sudah ada")
                }
                return
            }
        }

        val dupes = dao.countSimilar(pkg, title, parsed?.amountCents, postedAt - DEDUPE_WINDOW_MS)
        if (dupes > 0) {
            Log.d(TAG, "dedupe: skipping duplicate from $pkg")
            DiagLog.log(TAG, "reject $pkg: dedupe (amount=${parsed?.amountCents ?: "?"})")
            return
        }

        // Category: learned merchant override > kedai dictionary (the same
        // kedai under another spelling) > keyword rule > default.
        val merchantKey = Categorizer.merchantKey(parsed?.merchant, title)
        val learned = db.merchantCategoryDao().byKey(merchantKey)?.category
        val known = learned ?: Kedai.load(db).lookup(parsed?.merchant)
        val category = known ?: Categorizer.categorize(parsed?.merchant, fullText)

        // Lateral transfers never enter the ledger. The ledger is one-sided —
        // every row IS spending — so a transfer row poisons every formula
        // that forgets to filter it (Murai counted a RM6.4k card settlement
        // before this). Signals: keyword rules + the owner's name + the
        // registered account list from Profil. Ignored ≠ silent: the diag
        // line is the proof of capture, so a wrongly-ignored purchase is
        // findable, not lost.
        // Income is a different product. Any money-IN notification is ignored
        // outright — before lateral logic, before the registry, before rules.
        if (Categorizer.isInbound(title, fullText)) {
            DiagLog.log(
                TAG,
                "dana masuk diabaikan: ${DiagLog.maskDigits(parsed?.merchant ?: title.take(30))} " +
                    "RM${(parsed?.amountCents ?: 0) / 100.0} [$pkg]",
            )
            return
        }

        val lateral = category == "Pindahan" || Categorizer.isLateral(
            merchant = parsed?.merchant,
            rawTitle = title,
            fullText = fullText,
            ownName = settings.currentProfileName(),
            accounts = db.ownAccountDao().all(),
        )
        if (lateral) {
            DiagLog.log(
                TAG,
                "pindahan diabaikan: ${DiagLog.maskDigits(parsed?.merchant ?: title.take(30))} " +
                    "RM${(parsed?.amountCents ?: 0) / 100.0} [$pkg]",
            )
            return
        }
        // Rules said Pindahan but the registry overruled (wallet deliberately
        // not registered): it enters as spending, uncategorised, for review —
        // never as a "Pindahan"-categorised ledger row, which the one-sided
        // ledger no longer hosts.
        val finalCategory =
            if (category == "Pindahan") Categorizer.DEFAULT_CATEGORY else category

        // Late settlement echo: banks report old card transactions days after
        // the fact (a 24-07 fuel top-up echoed through Samsung Wallet on
        // 29-07, five days late, AFTER the same spend had entered the ledger
        // from the statement). If an identical amount already sits here from
        // a statement import within the window and the merchant agrees, this
        // is probably the same money twice — flag it for review rather than
        // counting it silently. Review, not drop: two genuine RM20 top-ups
        // at the same pump days apart are entirely normal for this user.
        val echoSuspect = parsed?.amountCents?.let { cents ->
            dao.statementRowsWithAmount(cents, postedAt - ECHO_WINDOW_MS).any { st ->
                val a = (parsed.merchant ?: "").uppercase().take(8)
                a.length >= 4 && (st.merchant ?: "").uppercase().startsWith(a)
            }
        } ?: false
        if (echoSuspect) {
            DiagLog.log(
                TAG,
                "kemungkinan gema penyata: ${parsed?.merchant} " +
                    "RM${(parsed?.amountCents ?: 0) / 100.0} — masuk sebagai perlu semak",
            )
        }

        dao.insert(
            Txn(
                id = stableId(pkg, parsed?.amountCents, parsed?.merchant, title, postedAt),
                sourcePkg = pkg,
                amountCents = parsed?.amountCents,
                merchant = parsed?.merchant,
                rawTitle = title,
                rawText = body,
                capturedAt = postedAt,
                needsReview = parsed == null || echoSuspect,
                synced = false,
                category = finalCategory,
                categoryOverridden = learned != null && finalCategory == category,
            )
        )
        Log.i(TAG, "captured txn from $pkg (parsed=${parsed != null})")
        DiagLog.log(
            TAG,
            "capture $pkg parsed=${parsed != null} cat=$category" +
                (if (learned == null && known != null) "(kedai)" else "") +
                " merchant=${parsed?.merchant ?: "?"} amount=${parsed?.amountCents ?: "?"}",
        )
        SyncScheduler.syncNow(applicationContext)
        // Off the capture path on purpose: the row is already saved and usable
        // offline; the brain's opinion arrives later and costs nothing if it
        // never does.
        BrainWorker.nudge(applicationContext)
        MuraiWorker.nudge(applicationContext)
    }

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
        SyncScheduler.ensurePeriodic(applicationContext)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stableId(
        pkg: String,
        amountCents: Long?,
        merchant: String?,
        title: String,
        postedAt: Long,
    ): String {
        // Bucket the timestamp so the collapsed post and its expanded update
        // (which can differ by a few ms) hash to the same id.
        val bucket = postedAt / DEDUPE_WINDOW_MS
        val seed = listOf(pkg, amountCents?.toString().orEmpty(), merchant.orEmpty(), title, bucket.toString())
            .joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        /** How far back a bank's late settlement echo can reasonably reach. */
        private const val ECHO_WINDOW_MS = 10 * 24 * 60 * 60 * 1000L

        private const val TAG = "TxnListenerService"
        private val captureLock = Mutex()

        // The collapsed post and its expanded update arrive milliseconds apart,
        // so a short window catches them. A wide one instead silently discards
        // a second genuine purchase of the same amount at the same merchant.
        private const val DEDUPE_WINDOW_MS = 30 * 1000L

        /**
         * Which announcer to trust when the same purchase arrives twice.
         * Samsung Wallet handles every card tap regardless of issuing bank
         * and names merchants without the card-instrument tail; the bank app
         * is the fallback for non-card payments (MAE is a wallet + banking
         * app — a credit card cannot be tapped through it).
         */
        private fun sourceRank(pkg: String): Int = when {
            pkg.contains("samsung.android.spay") -> 3
            pkg.contains("maybank") -> 2
            else -> 1
        }
    }
}
