package uk.wakbijok.belanja.diag

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import uk.wakbijok.belanja.data.AppDb

/**
 * In-memory + file-backed diagnostic ring buffer — Izu's replacement for
 * live-server visibility. Every capture decision, sync pass, SAF backup event,
 * statement parse and PATCH failure lands here as one timestamped line.
 *
 * Last [MAX_LINES] lines are kept, persisted to filesDir/diag.log (rewritten
 * from the ring once the file grows past 2× the ring so it never balloons).
 * No sensitive full-texts: merchant + amount are fine, raw notification
 * bodies are not.
 */
object DiagLog {

    private const val MAX_LINES = 500
    private const val FILE_NAME = "diag.log"

    private val buf = ArrayDeque<String>(MAX_LINES)
    private var file: File? = null
    private var appendsSinceRewrite = 0
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Idempotent; called from Application.onCreate before anything logs. */
    fun init(context: Context) {
        synchronized(buf) {
            if (file != null) return
            val f = File(context.filesDir, FILE_NAME)
            file = f
            if (f.exists()) {
                runCatching {
                    f.readLines().takeLast(MAX_LINES).forEach { buf.addLast(it) }
                }
            }
        }
    }

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] ${msg.replace('\n', ' ').take(300)}"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX_LINES) buf.removeFirst()
            val f = file ?: return
            runCatching {
                if (++appendsSinceRewrite > MAX_LINES) {
                    // Rotate: rewrite the file from the ring.
                    f.writeText(buf.joinToString("\n") + "\n")
                    appendsSinceRewrite = 0
                } else {
                    f.appendText(line + "\n")
                }
            }
        }
    }

    private val DIGIT_RUN = Regex("""\d{4,}""")

    /**
     * Masks every run of 4+ digits with '#' of the same length, so a raw
     * notification excerpt can be logged without its OTP/TAC, account or
     * card fragments. Short runs (amounts like 12.34, dates) stay readable.
     */
    fun maskDigits(s: String): String = s.replace(DIGIT_RUN) { "#".repeat(it.value.length) }

    /** Newest first, for the log screen. */
    fun lines(): List<String> = synchronized(buf) { buf.toList().asReversed() }

    /**
     * Diagnostic bundle: diag.log + a checkpointed copy of the Room DB + a
     * settings snapshot. Written under cacheDir/diag (FileProvider path) so
     * the share sheet / server upload can hand it out. Returns null when the
     * zip could not be built.
     */
    fun buildZip(context: Context, settingsSnapshot: String): File? = runCatching {
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        // Keep the folder tidy: old bundles serve no purpose.
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(dir, "belanja-diag-$stamp.zip")

        // Checkpoint WAL so the copied DB file is complete on its own.
        val db = AppDb.get(context)
        db.query("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val dbFile = context.getDatabasePath("belanja.db")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            file?.takeIf { it.exists() }?.let { f ->
                zip.putNextEntry(ZipEntry("diag.log"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (dbFile.exists()) {
                zip.putNextEntry(ZipEntry("belanja.db"))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("settings.txt"))
            zip.write(settingsSnapshot.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zipFile
    }.getOrNull()
}
