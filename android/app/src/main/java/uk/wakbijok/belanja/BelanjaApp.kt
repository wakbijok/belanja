package uk.wakbijok.belanja

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.llm.BrainWorker
import uk.wakbijok.belanja.llm.MuraiWorker
import uk.wakbijok.belanja.solo.BackupScheduler

class BelanjaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Diagnostics ring must exist before the listener service or any
        // worker logs its first line.
        DiagLog.init(this)
        // pdfbox-android needs its resources loaded once per process before
        // the solo statement parser opens a PDF.
        PDFBoxResourceLoader.init(applicationContext)
        // No-op in server mode (cancels the periodic work); hourly in solo.
        BackupScheduler.ensurePeriodic(this)
        // Clears the backlog the rules already left behind. Self-limiting: a
        // row is asked about once (brainAskedAt), so this cannot become a
        // per-launch charge, and it no-ops entirely when the brain is off.
        BrainWorker.nudge(this)
        // Channel must exist before the first nag can post; safe to repeat.
        MuraiWorker.ensureChannel(this)
    }
}
