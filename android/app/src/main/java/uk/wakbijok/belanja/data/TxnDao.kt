package uk.wakbijok.belanja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TxnDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: Txn)

    /** Restore path: server rows win, so an existing local row is replaced. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(txns: List<Txn>)

    @Query("SELECT * FROM txn ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<Txn>>

    @Query(
        "SELECT COUNT(*) FROM txn WHERE sourcePkg = :sourcePkg AND rawTitle = :rawTitle " +
            "AND (amountCents IS :amountCents OR (amountCents IS NULL AND :amountCents IS NULL)) " +
            "AND capturedAt >= :since"
    )
    suspend fun countSimilar(
        sourcePkg: String,
        rawTitle: String,
        amountCents: Long?,
        since: Long,
    ): Int

    /**
     * One tap can notify twice from different apps — Samsung Wallet fires
     * immediately, Maybank follows minutes later for the same purchase.
     * Same amount inside the window from ANOTHER package means we already
     * hold this transaction.
     */
    @Query(
        "SELECT COUNT(*) FROM txn WHERE amountCents = :amountCents " +
            "AND sourcePkg != :sourcePkg AND capturedAt >= :since AND capturedAt <= :until"
    )
    suspend fun countCrossSource(
        sourcePkg: String,
        amountCents: Long,
        since: Long,
        until: Long,
    ): Int

    /**
     * Every other-source capture of [amountCents] inside the window, oldest
     * first; parser/CrossSource decides which (if any) is the same money.
     * Statement imports are excluded — the late-settlement echo check owns
     * that overlap and flags rather than drops.
     */
    @Query(
        "SELECT * FROM txn WHERE amountCents = :amountCents " +
            "AND sourcePkg != :sourcePkg AND sourcePkg != 'statement' " +
            "AND capturedAt >= :since AND capturedAt <= :until ORDER BY capturedAt"
    )
    suspend fun crossSourceCandidates(
        sourcePkg: String,
        amountCents: Long,
        since: Long,
        until: Long,
    ): List<Txn>

    /** Upgrade an existing row to a better source (Samsung Wallet names the
     *  merchant more cleanly than the bank's follow-up). Keeps id, category,
     *  relief and any attached receipt. */
    @Query(
        "UPDATE txn SET sourcePkg = :sourcePkg, merchant = :merchant, " +
            "rawTitle = :rawTitle, rawText = :rawText, synced = 0 WHERE id = :id"
    )
    suspend fun upgradeSource(
        id: String,
        sourcePkg: String,
        merchant: String?,
        rawTitle: String,
        rawText: String,
    )

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun deleteById(id: String)

    // ---- Two-way sync bookkeeping ----------------------------------------

    /** Ids with local edits not yet pushed; a pull must not clobber these. */
    @Query("SELECT id FROM txn WHERE synced = 0")
    suspend fun unsyncedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstone(tombstone: DeletedTxn)

    @Query("SELECT id FROM deleted_txn")
    suspend fun tombstoneIds(): List<String>

    @Query("DELETE FROM deleted_txn WHERE id = :id")
    suspend fun clearTombstone(id: String)

    @Query("SELECT * FROM txn WHERE synced = 0 ORDER BY capturedAt ASC")
    suspend fun unsynced(): List<Txn>

    @Query("UPDATE txn SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query(
        "SELECT COALESCE(SUM(amountCents), 0) FROM txn " +
            "WHERE capturedAt >= :from AND capturedAt < :until AND amountCents IS NOT NULL"
    )
    fun observeTotalBetween(from: Long, until: Long): Flow<Long>

    @Query("SELECT * FROM txn WHERE capturedAt >= :from AND capturedAt < :until ORDER BY capturedAt DESC")
    fun observeBetween(from: Long, until: Long): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE id = :id")
    fun observeById(id: String): Flow<Txn?>

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: String): Txn?

    @Query(
        "UPDATE txn SET category = :category, categoryOverridden = 1, synced = 0 WHERE id = :id"
    )
    suspend fun updateCategory(id: String, category: String)

    /** Re-categorize past txns of a merchant that were never manually overridden. */
    @Query(
        "UPDATE txn SET category = :category, synced = 0 " +
            "WHERE categoryOverridden = 0 AND UPPER(TRIM(COALESCE(merchant, rawTitle))) = :merchantKey"
    )
    suspend fun recategorizeMerchant(merchantKey: String, category: String)

    /**
     * Variant catcher for "Ya, ingat": statement descriptors mangle the same
     * kedai into many spellings, so also sweep rows whose merchant contains the
     * distinctive token. Overridden rows and rows already in the category stay.
     */
    @Query(
        "UPDATE txn SET category = :category, synced = 0 " +
            "WHERE categoryOverridden = 0 AND category != :category " +
            "AND UPPER(COALESCE(merchant, rawTitle)) LIKE '%' || :token || '%'"
    )
    suspend fun recategorizeMerchantContaining(token: String, category: String)

    @Query(
        "SELECT COUNT(*) FROM txn WHERE categoryOverridden = 0 " +
            "AND UPPER(COALESCE(merchant, rawTitle)) LIKE '%' || :token || '%'"
    )
    suspend fun countMerchantContaining(token: String): Int

    @Query(
        "SELECT COALESCE(SUM(amountCents), 0) FROM txn " +
            "WHERE category = :category AND capturedAt >= :from AND capturedAt < :until " +
            "AND amountCents IS NOT NULL"
    )
    fun observeTotalForCategoryBetween(category: String, from: Long, until: Long): Flow<Long>

    // ---- UI edits (all mark the row dirty so the next push carries them) ---

    /** One-off category change: overrides on this txn only, no merchant learn. */
    @Query("UPDATE txn SET needsReview = :needsReview, synced = 0 WHERE id = :id")
    suspend fun updateNeedsReview(id: String, needsReview: Boolean)

    @Query("UPDATE txn SET amountCents = :amountCents, synced = 0 WHERE id = :id")
    suspend fun updateAmount(id: String, amountCents: Long)

    /** Relief tag change (code, "none", or null to clear); dirty until pushed. */
    @Query("UPDATE txn SET relief = :relief, synced = 0 WHERE id = :id")
    suspend fun updateRelief(id: String, relief: String?)

    /** Statement-imported rows carrying [amountCents], captured on/after [since]. */
    @Query(
        "SELECT * FROM txn WHERE amountCents = :amountCents " +
            "AND sourcePkg = 'statement' AND capturedAt >= :since"
    )
    suspend fun statementRowsWithAmount(amountCents: Long, since: Long): List<Txn>

    // ---- Otak: proposals live in their own lane, never in `category` -------

    /**
     * Rows worth asking the brain about: still awaiting review and never asked
     * before. Newest first — a merchant you just paid at is the one you can
     * still remember when the answer arrives.
     */
    @Query(
        "SELECT * FROM txn WHERE brainAskedAt = 0 AND (" +
            "needsReview = 1 OR (category = 'Lain-lain' AND categoryOverridden = 0)" +
            ") ORDER BY capturedAt DESC LIMIT :limit"
    )
    suspend fun pendingBrain(limit: Int): List<Txn>

    /**
     * Stores a proposal (or a null category meaning "the brain wasn't sure").
     * Deliberately does NOT set synced = 0: the proposal is local scaffolding,
     * not ledger truth, so it must not generate server churn.
     */
    @Query(
        "UPDATE txn SET brainCategory = :category, brainReason = :reason, " +
            "brainAskedAt = :askedAt WHERE id = :id"
    )
    suspend fun setBrainProposal(id: String, category: String?, reason: String?, askedAt: Long)

    /** Clears a proposal once the user has acted on it, so the card stops showing it. */
    @Query("UPDATE txn SET brainCategory = NULL, brainReason = NULL WHERE id = :id")
    suspend fun clearBrainProposal(id: String)

    // ---- Solo mode: statement matching / local tax / local recategorize ----

    /** Full ledger snapshot for the on-device statement matcher. */
    @Query("SELECT * FROM txn")
    suspend fun all(): List<Txn>

    @Query(
        "SELECT * FROM txn WHERE capturedAt >= :from AND capturedAt < :until " +
            "AND relief IS NOT NULL AND relief != 'none'"
    )
    suspend fun taggedBetween(from: Long, until: Long): List<Txn>

    @Query(
        "SELECT * FROM txn WHERE capturedAt >= :from AND capturedAt < :until " +
            "AND relief = :relief ORDER BY capturedAt DESC"
    )
    suspend fun byReliefBetween(relief: String, from: Long, until: Long): List<Txn>

    /** Learned kedai dictionary for local category suggestions (statement rows). */
    @Query(
        "SELECT DISTINCT merchant, category FROM txn " +
            "WHERE merchant IS NOT NULL AND category != 'Lain-lain'"
    )
    suspend fun merchantCategories(): List<MerchantCat>

    /**
     * Rows whose category was decided by a person, not a rule: manual
     * overrides and statement imports (reviewed on the Penyata screen).
     * One row per visit so the kedai dictionary can vote by majority.
     * Pindahan is excluded — only explicit teaching may make a merchant
     * vanish at the ledger door.
     */
    @Query(
        "SELECT merchant, category FROM txn WHERE merchant IS NOT NULL " +
            "AND category NOT IN ('Lain-lain', 'Pindahan') " +
            "AND (categoryOverridden = 1 OR sourcePkg = 'statement')"
    )
    suspend fun taughtMerchants(): List<MerchantCat>

    @Query("SELECT * FROM txn WHERE category = 'Lain-lain' AND categoryOverridden = 0")
    suspend fun uncategorized(): List<Txn>

    /** Rule re-run result: not a user override, unlike [updateCategory]. */
    @Query("UPDATE txn SET category = :category, synced = 0 WHERE id = :id")
    suspend fun setCategory(id: String, category: String)
}

/** Projection for [TxnDao.merchantCategories]. */
data class MerchantCat(val merchant: String?, val category: String)
