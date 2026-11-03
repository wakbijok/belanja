package uk.wakbijok.belanja.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MerchantCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MerchantCategory)

    @Query("SELECT * FROM merchant_category WHERE merchantKey = :merchantKey")
    suspend fun byKey(merchantKey: String): MerchantCategory?

    @Query("SELECT * FROM merchant_category")
    suspend fun all(): List<MerchantCategory>
}

@Dao
interface CategoryBudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: CategoryBudget)

    @Query("DELETE FROM category_budget WHERE category = :category")
    suspend fun delete(category: String)

    @Query("SELECT * FROM category_budget")
    fun observeAll(): Flow<List<CategoryBudget>>
}

@Dao
interface OwnAccountDao {
    @Query("SELECT * FROM own_account ORDER BY label")
    fun observeAll(): Flow<List<OwnAccount>>

    @Query("SELECT * FROM own_account")
    suspend fun all(): List<OwnAccount>

    @Insert
    suspend fun insert(account: OwnAccount)

    @Query("DELETE FROM own_account WHERE id = :id")
    suspend fun delete(id: Long)
}
