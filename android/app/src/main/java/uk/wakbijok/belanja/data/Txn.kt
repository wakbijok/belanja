package uk.wakbijok.belanja.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "txn")
data class Txn(
    @PrimaryKey val id: String,
    val sourcePkg: String,
    val amountCents: Long?,
    val merchant: String?,
    val rawTitle: String,
    val rawText: String,
    val capturedAt: Long,
    val needsReview: Boolean,
    val synced: Boolean = false,
    val category: String = "Lain-lain",
    val categoryOverridden: Boolean = false,
    /**
     * LHDN relief tag: null = untagged, "none" = confirmed not claimable,
     * else a relief code ("lifestyle", "medical_self", …).
     */
    val relief: String? = null,
    /**
     * The brain's proposal — never the ledger's answer. [category] above stays
     * whatever the rules decided until the user accepts this in the review
     * queue, so a wrong guess can never silently move money between buckets.
     */
    val brainCategory: String? = null,
    /** One short Malay sentence explaining the guess; auditable, unlike a score. */
    val brainReason: String? = null,
    /**
     * 0 = never asked. Set even when the brain answers "not sure", so an
     * ambiguous merchant is asked about once and never billed for again.
     */
    val brainAskedAt: Long = 0,
)

/**
 * A transaction deleted on the phone. Without this, the next pull would simply
 * re-download the row from the server and it would reappear. The tombstone is
 * dropped once the server confirms the delete (or reports it already gone).
 */
@Entity(tableName = "deleted_txn")
data class DeletedTxn(
    @PrimaryKey val id: String,
    val deletedAt: Long,
)

/** Learned merchant→category override (from manual reassignment in txn detail). */
@Entity(tableName = "merchant_category")
data class MerchantCategory(
    @PrimaryKey val merchantKey: String,
    val category: String,
)

/**
 * One of the user's own money pockets (bank, wallet, card). Any captured
 * notification that reads as money moving between these is a lateral
 * transfer — ignored at the ledger door, because a one-sided ledger where
 * every row means "spending" cannot host both directions of a transfer.
 */
@Entity(tableName = "own_account")
data class OwnAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display label, e.g. "MAE", "TnG", "CIMB", "AEON". */
    val label: String,
    /** Last 4 digits of the account/card, if known; matching is stricter with it. */
    val last4: String = "",
)

/** Optional per-category monthly budget. */
@Entity(tableName = "category_budget")
data class CategoryBudget(
    @PrimaryKey val category: String,
    val amountCents: Long,
)
