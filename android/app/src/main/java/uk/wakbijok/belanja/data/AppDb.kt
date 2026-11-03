package uk.wakbijok.belanja.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Txn::class,
        MerchantCategory::class,
        CategoryBudget::class,
        OwnAccount::class,
        DeletedTxn::class,
        ReceiptLocal::class,
        StatementLocal::class,
        StatementRowLocal::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun txnDao(): TxnDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun categoryBudgetDao(): CategoryBudgetDao
    abstract fun ownAccountDao(): OwnAccountDao
    abstract fun receiptLocalDao(): ReceiptLocalDao
    abstract fun statementLocalDao(): StatementLocalDao

    companion object {
        @Volatile private var instance: AppDb? = null

        /** Adds the delete tombstone table; no existing data is touched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deleted_txn (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "deletedAt INTEGER NOT NULL)"
                )
            }
        }

        /** Adds the nullable LHDN relief tag column; existing rows stay untagged. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN relief TEXT")
            }
        }

        /**
         * Solo mode tables: local receipts (SAF-backed) and local statement
         * runs. Additive only — v1-v4 tables are untouched.
         */
        /**
         * Brain proposal columns. Additive and nullable: every existing row
         * reads as "never asked", and the ledger's own category column is
         * untouched — the brain proposes into its own lane.
         */
        /** User's own accounts for lateral-transfer detection. Additive. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS own_account (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "label TEXT NOT NULL, " +
                        "last4 TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN brainCategory TEXT")
                db.execSQL("ALTER TABLE txn ADD COLUMN brainReason TEXT")
                db.execSQL("ALTER TABLE txn ADD COLUMN brainAskedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS receiptLocal (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "txnId TEXT NOT NULL, " +
                        "merchant TEXT, " +
                        "amountCents INTEGER, " +
                        "txnCapturedAt INTEGER NOT NULL, " +
                        "filename TEXT NOT NULL, " +
                        "mime TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, " +
                        "sha256 TEXT NOT NULL, " +
                        "uploadedAt INTEGER NOT NULL, " +
                        "backupUri TEXT)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS statementLocal (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "month TEXT NOT NULL, " +
                        "statementDate TEXT NOT NULL, " +
                        "filename TEXT NOT NULL, " +
                        "uploadedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS statementRowLocal (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "statementId TEXT NOT NULL, " +
                        "postingDate TEXT NOT NULL, " +
                        "txnDate TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "amountCents INTEGER NOT NULL, " +
                        "credit INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "matchedTxnId TEXT, " +
                        "resolved TEXT)"
                )
            }
        }

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "belanja.db"
                )
                    // The ledger is real data now: migrate it, never drop it.
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { instance = it }
            }
    }
}
