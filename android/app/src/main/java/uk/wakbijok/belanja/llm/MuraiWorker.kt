package uk.wakbijok.belanja.llm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * Murai — the resident chatterbox (from "mulut murai": a mouth that cannot
 * stay shut). Two lanes, chosen by how the budget is doing:
 *
 *  ROAST — near or over budget, the laser mouth fires. Once per threshold
 *  (70/90/100%) per month; repeated nagging gets muted, and a muted Murai
 *  protects nothing. The WARNING must survive a dead Akal: thresholds and
 *  sums are computed locally, the model only supplies the voice, and a plain
 *  numbered template ships when it can't.
 *
 *  BEBEL — budget healthy, so there is nothing to warn about; Murai just
 *  can't help commenting on what you bought. At most once a day, and ONLY
 *  when Akal is reachable: a warning without personality is still worth
 *  sending, a joke without personality is not.
 *
 * Event-based, never scheduled — evaluated after each capture.
 */
class MuraiWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = SettingsRepository(ctx)
        if (!settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_MAKCIK)) {
            return Result.success()
        }

        // Quiet hours are a hard wall, not a delay queue: the next capture
        // after 08:00 re-evaluates, and the threshold marker is only written
        // when a notification actually goes out.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= QUIET_FROM || hour < QUIET_UNTIL) return Result.success()

        val budget = settings.budgetCents.first()
        if (budget <= 0) return Result.success()

        val month = BrainFacts.monthKey()
        val cycleDay = settings.currentCycleStartDay()
        val (start, end) = BrainFacts.monthRange(0, startDay = cycleDay)
        val all = AppDb.get(ctx).txnDao().all()
        val monthTxns = all.filter { it.capturedAt in start until end }
        val spent = BrainFacts.totalCents(monthTxns)
        val pct = spent * 100 / budget

        if (pct < 70) return bebel(settings, all, monthTxns, pct)

        // ---- ROAST: from 70% up, EVERY purchase gets shot at, tone scaling
        // with the live percentage. One notification a month assumes the
        // buying stops after the first warning; appetite doesn't work that
        // way — so neither does Murai.
        val latest = latestPurchaseToday(monthTxns) ?: return Result.success()

        val left = budget - spent
        val days = BrainFacts.daysLeft(startDay = cycleDay)
        val fresh = freshSignals(settings, all, latest, cycleDay)
        val facts = buildString {
            append("Belian baru: ${latest.merchant ?: "kedai tak bernama"}, ")
            append("${BrainFacts.rm(latest.amountCents ?: 0)}, kategori ${latest.category}. ")
            append("Keadaan bajet: ${BrainFacts.rm(spent)} daripada ${BrainFacts.rm(budget)} ($pct%).")
            if (left > 0) append(" Baki ${BrainFacts.rm(left)} untuk $days hari lagi.")
            else append(" Bajet dah pecah ${BrainFacts.rm(-left)}, dan masih membeli.")
            fresh.forEach { append("\n").append(it.fact) }
        }

        val message = brainVoice(settings, facts, pct)
        if (message != null) {
            notify(ctx, message)
            settings.addMuraiSaid(fresh.map { it.id })
            DiagLog.log(TAG, "tembak $pct% [${fresh.joinToString { it.id }}] — ${message.take(60)}")
            return Result.success()
        }

        // Akal down: the WARNING still matters, but a repeated identical
        // template per purchase is spam. Fall back to once per threshold band.
        val threshold = if (pct >= 100) 100 else if (pct >= 90) 90 else 70
        val marker = "$month:$threshold"
        if (marker !in settings.currentMakcikFired()) {
            notify(ctx, template(threshold, spent, budget, left, days))
            settings.addMakcikFired(marker)
            DiagLog.log(TAG, "templat $threshold% (Akal mati)")
        }
        return Result.success()
    }

    /** The purchase that triggered this evaluation — today's newest spend row. */
    private fun latestPurchaseToday(
        monthTxns: List<uk.wakbijok.belanja.data.Txn>,
    ): uk.wakbijok.belanja.data.Txn? {
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return monthTxns
            .filter { it.capturedAt >= dayStart && it.amountCents != null && it.category != "Pindahan" }
            .maxByOrNull { it.capturedAt }
    }

    /**
     * BEBEL lane: budget is healthy, so Murai comments on today's purchase for
     * fun. Once per local day; silence (never a template) when Akal is down.
     */
    private suspend fun bebel(
        settings: SettingsRepository,
        all: List<uk.wakbijok.belanja.data.Txn>,
        monthTxns: List<uk.wakbijok.belanja.data.Txn>,
        pct: Long,
    ): Result {
        val today = todayKey()
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val latest = monthTxns.filter { it.capturedAt >= dayStart && it.amountCents != null }
            .maxByOrNull { it.capturedAt } ?: return Result.success()

        // Once a day — unless something urgent just happened (a burst, a
        // late-night buy). A friend who already said hi still speaks up
        // when you buy three things in ten minutes.
        val fresh = freshSignals(settings, all, latest, settings.currentCycleStartDay())
        val urgent = fresh.any { it.priority <= 2 }
        if (settings.currentMuraiChatDay() == today && !urgent) return Result.success()

        val cfg = settings.currentLlmConfig()
        val month = BrainFacts.monthKey()
        if (!cfg.configured) return Result.success()

        val facts = buildString {
            append("Belian hari ini: ${latest.merchant ?: "kedai tak bernama"}, ")
            append("${BrainFacts.rm(latest.amountCents ?: 0)}, kategori ${latest.category}. ")
            append("Bajet bulan ini baru $pct% guna — semuanya sihat.")
            fresh.forEach { append("\n").append(it.fact) }
        }
        val system = MuraiVoice.system(settings.currentMakcikPersona(), MuraiVoice.Lane.BEBEL, pct)

        when (val r = LlmClient.complete(cfg, system, facts, REPLY_BUDGET, MuraiVoice.TEMPERATURE)) {
            is LlmResult.Ok -> {
                settings.noteLlmCall(month, r.value.tokensIn, r.value.tokensOut)
                notify(applicationContext, r.value.text.take(250))
                settings.setMuraiChatDay(today)
                settings.addMuraiSaid(fresh.map { it.id })
                DiagLog.log(TAG, "bebel [${fresh.joinToString { it.id }}]: ${r.value.text.take(60)}")
            }
            is LlmResult.Err -> {
                if (r.error !is LlmError.Unreachable) settings.noteLlmCall(month, 0, 0)
                // No template here on purpose: a joke needs a voice or nothing.
                DiagLog.log(TAG, "bebel dilangkau: ${describe(r.error)}")
            }
        }
        return Result.success()
    }

    /**
     * Habit signals not yet mentioned, most urgent first, at most two — a
     * message about four patterns is a report, not a remark.
     */
    private suspend fun freshSignals(
        settings: SettingsRepository,
        all: List<uk.wakbijok.belanja.data.Txn>,
        latest: uk.wakbijok.belanja.data.Txn,
        cycleDay: Int,
    ): List<Habits.Signal> {
        val said = settings.currentMuraiSaid()
        return Habits.signals(all, latest, System.currentTimeMillis(), cycleDay)
            .filter { it.id !in said }
            .take(2)
    }

    /** Name the cause: "Unreachable" alone hid a read timeout behind a network story. */
    private fun describe(e: LlmError): String = when (e) {
        is LlmError.Unreachable -> "tak sampai (${e.reason})"
        is LlmError.Http -> "HTTP ${e.code}"
        is LlmError.Unauthorized -> "kunci ditolak (${e.code})"
        is LlmError.ThinkingRanOut -> "habis token berfikir (${e.tokensSpent})"
        is LlmError.BadShape -> "bentuk pelik (${e.reason})"
        LlmError.NoModelList -> "404"
    }

    private fun todayKey(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * The roast. The dial (Sopan/Murai/Lazer) chooses the voice; the live
     * percentage only sets how much bite that voice is allowed — see
     * [MuraiVoice]. Same person at 70% and at 105%, different licence.
     */
    private suspend fun brainVoice(
        settings: SettingsRepository,
        facts: String,
        pct: Long,
    ): String? {
        val cfg = settings.currentLlmConfig()
        if (!cfg.configured) return null
        val month = BrainFacts.monthKey()
        val system = MuraiVoice.system(settings.currentMakcikPersona(), MuraiVoice.Lane.ROAST, pct)

        return when (val r = LlmClient.complete(cfg, system, facts, REPLY_BUDGET, MuraiVoice.TEMPERATURE)) {
            is LlmResult.Ok -> {
                settings.noteLlmCall(month, r.value.tokensIn, r.value.tokensOut)
                r.value.text.take(300)
            }
            is LlmResult.Err -> {
                if (r.error !is LlmError.Unreachable) settings.noteLlmCall(month, 0, 0)
                DiagLog.log(TAG, "otak gagal, guna templat: ${describe(r.error)}")
                null
            }
        }
    }

    /** The warning that survives everything. Same numbers, no personality. */
    private fun template(threshold: Int, spent: Long, budget: Long, left: Long, days: Int): String =
        when (threshold) {
            100 -> applicationContext.getString(
                R.string.makcik_t100, BrainFacts.rm(spent), BrainFacts.rm(budget), days,
            )
            90 -> applicationContext.getString(
                R.string.makcik_t90, BrainFacts.rm(left), days,
            )
            else -> applicationContext.getString(
                R.string.makcik_t70, BrainFacts.rm(spent), BrainFacts.rm(budget),
            )
        }

    private fun notify(ctx: Context, message: String) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DiagLog.log(TAG, "tiada kebenaran notifikasi — teguran tak dapat dihantar")
            return
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val pending = android.app.PendingIntent.getActivity(
            ctx, 0, launch,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(ctx.getString(R.string.makcik_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "Murai"
        private const val WORK = "belanja-murai"
        const val CHANNEL_ID = "makcik"
        private const val NOTIF_ID = 4021
        private const val QUIET_FROM = 23
        private const val QUIET_UNTIL = 8
        private const val REPLY_BUDGET = 4000

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Once per process is enough for the channel; safe to repeat. */
        fun ensureChannel(ctx: Context) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.makcik_title),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        fun nudge(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                val settings = SettingsRepository(appContext)
                if (!settings.currentLlmFeatures().contains(SettingsRepository.LLM_F_MAKCIK)) {
                    return@launch
                }
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WORK,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<MuraiWorker>().build(),
                )
            }
        }
    }
}
