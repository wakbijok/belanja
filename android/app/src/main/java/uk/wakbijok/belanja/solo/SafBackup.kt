package uk.wakbijok.belanja.solo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import uk.wakbijok.belanja.data.AppDb
import uk.wakbijok.belanja.data.ReceiptLocal
import uk.wakbijok.belanja.diag.DiagLog
import uk.wakbijok.belanja.settings.SettingsRepository

/**
 * SOLO-mode receipt survivability: write-through backup to a user-picked SAF
 * tree — the same semantics as the server's Nextcloud mirror
 * (server/app/mirror.py), different transport.
 *
 * - canonical identity = receipt.id (UUID) — never the filename
 * - the backup document URI is STORED on the row at write time
 * - sha256 anchors the content
 * - manifest.jsonl per year folder makes the flat store self-describing;
 *   deletes append a tombstone line
 * - files are immutable after write; the only mutation is an explicit delete
 *
 * Raw DocumentsContract (no DocumentFile dependency): find/create dirs and
 * files under the persisted tree URI.
 */
object SafBackup {

    private const val TAG = "SafBackup"
    const val DB_DUMP_KEEP = 30

    val EXT = mapOf(
        "application/pdf" to ".pdf", "image/jpeg" to ".jpg",
        "image/png" to ".png", "image/webp" to ".webp",
    )

    fun extFor(mime: String): String = EXT[mime] ?: ".bin"

    // ---- naming (applies to NEW writes only; stored URIs never re-derived) ----

    /** Non-alnum → '-', max 48, uppercase (mirror.py _slug). */
    fun slug(s: String?): String {
        val cleaned = Regex("[^A-Za-z0-9]+").replace((s ?: "").trim(), "-").trim('-')
        return cleaned.ifBlank { "RESIT" }.take(48).uppercase(Locale.ROOT)
    }

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun localDay(epochMs: Long): String = dayFmt.format(Date(epochMs))

    /** "2026/2026-07-18_MERCHANT-SLUG_RM12.34_ab12cd34.pdf" (mirror.py mirror_name). */
    fun mirrorName(r: ReceiptLocal): String {
        val day = localDay(r.txnCapturedAt)
        val amount = r.amountCents?.let {
            "RM" + String.format(Locale.US, "%.2f", it / 100.0)
        } ?: "RM?"
        return "${day.take(4)}/${day}_${slug(r.merchant)}_${amount}_${r.id.take(8)}${extFor(r.mime)}"
    }

    /** Primary local copy: filesDir/receipts/{id}.{ext}. */
    fun localFile(context: Context, r: ReceiptLocal): File =
        File(File(context.filesDir, "receipts").apply { mkdirs() }, "${r.id}${extFor(r.mime)}")

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ---- SAF tree plumbing -------------------------------------------------

    private fun docUri(tree: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** (docId, mime) of the named child, or null. */
    private fun findChild(
        resolver: ContentResolver,
        tree: Uri,
        parentDocId: String,
        name: String,
    ): Pair<String, String>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return c.getString(0) to (c.getString(2) ?: "")
            }
        }
        return null
    }

    /** All children of a directory as (docId, displayName) pairs. */
    private fun listChildren(
        resolver: ContentResolver,
        tree: Uri,
        parentDocId: String,
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    /** Find or create a direct subdirectory of the tree root; returns its docId. */
    private fun ensureDir(resolver: ContentResolver, tree: Uri, name: String): String? {
        findChild(resolver, tree, rootDocId(tree), name)?.let { (id, mime) ->
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return id
        }
        val created = DocumentsContract.createDocument(
            resolver, docUri(tree, rootDocId(tree)),
            DocumentsContract.Document.MIME_TYPE_DIR, name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    /** Create a file in [dirDocId] and write [bytes]; returns its URI. */
    private fun createAndWrite(
        resolver: ContentResolver,
        tree: Uri,
        dirDocId: String,
        mime: String,
        name: String,
        bytes: ByteArray,
    ): Uri? {
        val uri = DocumentsContract.createDocument(resolver, docUri(tree, dirDocId), mime, name)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            uri
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            null
        }
    }

    // ---- manifest: local append in filesDir, whole-file overwrite in the tree ----

    private fun manifestLocal(context: Context, year: String): File =
        File(File(context.filesDir, "receipts").apply { mkdirs() }, "manifest-$year.jsonl")

    private fun manifestAppend(
        context: Context,
        resolver: ContentResolver,
        tree: Uri,
        year: String,
        entry: JSONObject,
    ) {
        val local = manifestLocal(context, year)
        local.appendText(entry.toString() + "\n")
        runCatching {
            val yearDir = ensureDir(resolver, tree, year) ?: return
            val existing = findChild(resolver, tree, yearDir, "manifest.jsonl")
            val bytes = local.readBytes()
            if (existing != null) {
                resolver.openOutputStream(docUri(tree, existing.first), "wt")
                    ?.use { it.write(bytes) }
            } else {
                createAndWrite(resolver, tree, yearDir, "application/json", "manifest.jsonl", bytes)
            }
        }.onFailure { DiagLog.log(TAG, "manifest $year tree write failed: ${it.javaClass.simpleName}") }
    }

    private fun entryFor(r: ReceiptLocal, path: String): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("txnId", r.txnId) // explicit link — folder-only recovery must not guess
        put("sha256", r.sha256)
        put("path", path)
        put("merchant", r.merchant ?: JSONObject.NULL)
        put("amountCents", r.amountCents ?: JSONObject.NULL)
        put("txnCapturedAt", r.txnCapturedAt)
        put("uploadedAt", r.uploadedAt)
        put("mime", r.mime)
    }

    /** Relative path of a stored backup ("2026/name.pdf"), from the doc URI. */
    private fun relativePath(tree: Uri, backupUri: String): String? = runCatching {
        val docId = DocumentsContract.getDocumentId(Uri.parse(backupUri))
        docId.removePrefix(rootDocId(tree)).trimStart('/').ifBlank { null }
    }.getOrNull()

    // ---- operations ----------------------------------------------------------

    /**
     * Write-through: copy the local file into `<year>/` in the tree, store the
     * returned document URI on the row, append a manifest line. Returns true
     * on success; false leaves the row pending for the hourly sweep.
     */
    suspend fun backupReceipt(context: Context, receipt: ReceiptLocal): Boolean {
        val settings = SettingsRepository(context)
        val treeStr = settings.currentSafTree()
        if (treeStr.isBlank()) return false
        val tree = Uri.parse(treeStr)
        val resolver = context.contentResolver
        val src = localFile(context, receipt)
        if (!src.exists()) {
            DiagLog.log(TAG, "backup ${receipt.id.take(8)} failed: local file missing")
            return false
        }
        val path = mirrorName(receipt)
        val year = path.substringBefore('/')
        val name = path.substringAfter('/')
        return runCatching {
            val yearDir = ensureDir(resolver, tree, year)
                ?: error("cannot create $year/")
            val uri = createAndWrite(resolver, tree, yearDir, receipt.mime, name, src.readBytes())
                ?: error("write failed")
            AppDb.get(context).receiptLocalDao().setBackupUri(receipt.id, uri.toString())
            manifestAppend(context, resolver, tree, year, entryFor(receipt, path))
            DiagLog.log(TAG, "backup ok ${receipt.id.take(8)} → $path")
            true
        }.getOrElse {
            DiagLog.log(TAG, "backup ${receipt.id.take(8)} failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    /**
     * Explicit delete only: remove the tree copy and append a tombstone line
     * (mirror.py delete_mirror). Quiet no-op when no backup landed yet.
     */
    suspend fun deleteBackup(context: Context, receipt: ReceiptLocal) {
        val settings = SettingsRepository(context)
        val treeStr = settings.currentSafTree()
        val backup = receipt.backupUri
        if (treeStr.isBlank() || backup == null) return
        val tree = Uri.parse(treeStr)
        val resolver = context.contentResolver
        val path = relativePath(tree, backup) ?: mirrorName(receipt)
        runCatching {
            DocumentsContract.deleteDocument(resolver, Uri.parse(backup))
        }
        manifestAppend(
            context, resolver, tree, path.substringBefore('/'),
            JSONObject().apply {
                put("id", receipt.id)
                put("deleted", true)
                put("path", path)
                put("deletedAt", System.currentTimeMillis())
            },
        )
        DiagLog.log(TAG, "backup deleted ${receipt.id.take(8)} ($path)")
    }

    /** Backfill/retry: back up every pending row. Returns ok to failed counts. */
    suspend fun sweep(context: Context): Pair<Int, Int> {
        val pending = AppDb.get(context).receiptLocalDao().pending()
        var ok = 0
        var failed = 0
        for (r in pending) {
            if (backupReceipt(context, r)) ok++ else failed++
        }
        if (pending.isNotEmpty()) DiagLog.log(TAG, "sweep: $ok ok, $failed failed")
        return ok to failed
    }

    /**
     * Daily DB copy to `_db/belanja-YYYY-MM-DD.db` in the tree after a full
     * WAL checkpoint; keeps the newest [DB_DUMP_KEEP] (mirror.py dump_db).
     */
    suspend fun backupDb(context: Context): Boolean {
        val settings = SettingsRepository(context)
        val treeStr = settings.currentSafTree()
        if (treeStr.isBlank()) return false
        val tree = Uri.parse(treeStr)
        val resolver = context.contentResolver
        val today = localDay(System.currentTimeMillis())
        return runCatching {
            val db = AppDb.get(context)
            db.query("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            val dbFile = context.getDatabasePath("belanja.db")
            val dirId = ensureDir(resolver, tree, "_db") ?: error("cannot create _db/")
            val name = "belanja-$today.db"
            // Idempotent within a day: replace any same-day copy.
            findChild(resolver, tree, dirId, name)?.let { (id, _) ->
                runCatching { DocumentsContract.deleteDocument(resolver, docUri(tree, id)) }
            }
            createAndWrite(
                resolver, tree, dirId, "application/octet-stream", name, dbFile.readBytes()
            ) ?: error("db write failed")
            // Rotate: keep the newest DB_DUMP_KEEP dumps.
            val dumps = listChildren(resolver, tree, dirId)
                .filter { it.second.startsWith("belanja-") && it.second.endsWith(".db") }
                .sortedBy { it.second }
            dumps.dropLast(DB_DUMP_KEEP).forEach { (id, _) ->
                runCatching { DocumentsContract.deleteDocument(resolver, docUri(tree, id)) }
            }
            DiagLog.log(TAG, "db backup ok → _db/$name")
            true
        }.getOrElse {
            DiagLog.log(TAG, "db backup failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    /** Local day helper for the worker's once-per-day gate. */
    fun todayLocal(): String = localDay(System.currentTimeMillis())

    /** Local hour-of-day for the after-03:00 gate. */
    fun localHour(): Int {
        val cal = java.util.Calendar.getInstance(TimeZone.getDefault())
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }
}
