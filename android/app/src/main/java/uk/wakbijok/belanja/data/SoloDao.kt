package uk.wakbijok.belanja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: ReceiptLocal)

    /** Drives the paperclip indicators in solo mode (txnId → receipt id). */
    @Query("SELECT * FROM receiptLocal")
    fun observeAll(): Flow<List<ReceiptLocal>>

    @Query("SELECT * FROM receiptLocal WHERE id = :id")
    suspend fun byId(id: String): ReceiptLocal?

    @Query("SELECT * FROM receiptLocal WHERE txnId = :txnId LIMIT 1")
    suspend fun byTxnId(txnId: String): ReceiptLocal?

    @Query("SELECT * FROM receiptLocal WHERE txnId IN (:txnIds)")
    suspend fun byTxnIds(txnIds: List<String>): List<ReceiptLocal>

    /** Rows whose SAF write-through has not landed yet; the worker retries. */
    @Query("SELECT * FROM receiptLocal WHERE backupUri IS NULL")
    suspend fun pending(): List<ReceiptLocal>

    @Query("UPDATE receiptLocal SET backupUri = :uri WHERE id = :id")
    suspend fun setBackupUri(id: String, uri: String)

    @Query("DELETE FROM receiptLocal WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface StatementLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStatement(statement: StatementLocal)

    @Query("SELECT * FROM statementLocal ORDER BY month DESC")
    suspend fun statements(): List<StatementLocal>

    @Query("SELECT * FROM statementLocal WHERE id = :id")
    suspend fun byId(id: String): StatementLocal?

    @Query("SELECT * FROM statementLocal WHERE month = :month")
    suspend fun byMonth(month: String): StatementLocal?

    @Query("DELETE FROM statementLocal WHERE id = :id")
    suspend fun deleteStatement(id: String)

    @Insert
    suspend fun insertRows(rows: List<StatementRowLocal>)

    @Query("SELECT * FROM statementRowLocal WHERE statementId = :statementId ORDER BY txnDate")
    suspend fun rows(statementId: String): List<StatementRowLocal>

    @Query("SELECT * FROM statementRowLocal WHERE id = :rowId AND statementId = :statementId")
    suspend fun rowById(statementId: String, rowId: Long): StatementRowLocal?

    @Query("DELETE FROM statementRowLocal WHERE statementId = :statementId")
    suspend fun deleteRows(statementId: String)

    @Query("UPDATE statementRowLocal SET resolved = :resolved, matchedTxnId = :matchedTxnId WHERE id = :rowId")
    suspend fun resolveRow(rowId: Long, resolved: String, matchedTxnId: String?)
}
