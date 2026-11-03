package uk.wakbijok.belanja.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SOLO-mode receipt: the same survivability contract as the server's
 * `receipt` table + Nextcloud mirror (server/app/mirror.py), with the SAF
 * tree standing in for the NC mirror.
 *
 * Identity contract (mirrors mirror.py, LOCKED):
 * - canonical identity = [id] (UUID, minted once) — never the filename
 * - the backup document URI is STORED at write time, never re-derived
 * - [sha256] anchors the content for post-disaster re-linking
 * - null [backupUri] = pending; the hourly BackupWorker retries it
 *
 * In SERVER mode this table is unused — receipts live server-side.
 */
@Entity(tableName = "receiptLocal")
data class ReceiptLocal(
    @PrimaryKey val id: String,
    val txnId: String,
    /** Snapshot of the txn at attach time: deleting the txn later must not
     *  orphan the tax evidence trail. */
    val merchant: String?,
    val amountCents: Long?,
    val txnCapturedAt: Long,
    val filename: String,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: Long,
    /** SAF document URI of the backup copy; null = pending write-through. */
    val backupUri: String?,
)

/** SOLO-mode statement run — same shape as the server `statement` table. */
@Entity(tableName = "statementLocal")
data class StatementLocal(
    @PrimaryKey val id: String,
    val month: String,
    val statementDate: String,
    val filename: String,
    val uploadedAt: Long,
)

/** SOLO-mode statement row — same shape as the server `statement_row` table. */
@Entity(tableName = "statementRowLocal")
data class StatementRowLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statementId: String,
    val postingDate: String,
    val txnDate: String,
    val description: String,
    val amountCents: Long,
    val credit: Boolean,
    /** matched | missing | credit (set at parse time, immutable after). */
    val status: String,
    val matchedTxnId: String?,
    /** null = open; "added" | "ignored" once resolved. */
    val resolved: String?,
)
