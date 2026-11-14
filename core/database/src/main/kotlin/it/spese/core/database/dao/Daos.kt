package it.spese.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import it.spese.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE") suspend fun getAll(): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun findByName(name: String): AccountEntity?
    @Insert suspend fun insert(a: AccountEntity): Long
    @Update suspend fun update(a: AccountEntity)
    @Query("DELETE FROM accounts WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :id") suspend fun txCount(id: Long): Int
    @Query("UPDATE transactions SET accountId = :to WHERE accountId = :from") suspend fun moveTransactions(from: Long, to: Long)
    @Query("DELETE FROM accounts") suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, id") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories ORDER BY sortOrder, id") suspend fun getAll(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1") suspend fun findByName(name: String): CategoryEntity?
    @Insert suspend fun insert(c: CategoryEntity): Long
    @Update suspend fun update(c: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE categories SET parentId = NULL WHERE parentId = :id") suspend fun detachChildren(id: Long)
    @Query("UPDATE categories SET parentId = :parentId WHERE id = :id") suspend fun setParent(id: Long, parentId: Long?)
    @Query("UPDATE categories SET sortOrder = :order WHERE id = :id") suspend fun setOrder(id: Long, order: Int)
    @Query("UPDATE categories SET budgetCents = :cents, budgetUpdatedAt = :now WHERE id = :id") suspend fun setBudget(id: Long, cents: Long?, now: Long)
    @Query("UPDATE categories SET budgetCents = NULL, budgetUpdatedAt = :now WHERE budgetCents IS NOT NULL") suspend fun clearBudgets(now: Long)
    @Query("SELECT COUNT(*) FROM categories") suspend fun count(): Int
    @Query("DELETE FROM categories") suspend fun deleteAll()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority, id") fun observeAll(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules ORDER BY priority, id") suspend fun getAll(): List<RuleEntity>
    @Insert suspend fun insert(r: RuleEntity): Long
    @Update suspend fun update(r: RuleEntity)
    @Query("DELETE FROM rules WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE rules SET priority = :p WHERE id = :id") suspend fun setPriority(id: Long, p: Int)
    @Query("UPDATE rules SET priority = priority + 1") suspend fun shiftDown()
    @Query("SELECT COALESCE(MAX(priority), -1) FROM rules") suspend fun maxPriority(): Int
    @Query("DELETE FROM rules") suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY bookingDate DESC, id DESC") fun observeAll(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions ORDER BY bookingDate DESC, id DESC") suspend fun getAll(): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE externalId = :ext") suspend fun findByExternalId(ext: String): TransactionEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(items: List<TransactionEntity>): List<Long>
    @Update suspend fun update(t: TransactionEntity)
    @Query("DELETE FROM transactions WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
    @Query("DELETE FROM transactions") suspend fun deleteAll()
    @Query("SELECT dedupKey FROM transactions WHERE accountId = :accountId") suspend fun dedupKeys(accountId: Long): List<String>
    @Query(
        "UPDATE transactions SET categoryId = :categoryId, kind = :kind, manualCategory = :manual, ruleId = :ruleId, " +
            "manualUpdatedAt = CASE WHEN :manual = 1 THEN :now ELSE manualUpdatedAt END WHERE id = :id",
    )
    suspend fun updateClassification(id: Long, categoryId: Long?, kind: String, manual: Boolean, ruleId: Long?, now: Long)
    @Query("UPDATE transactions SET documentId = :docId WHERE id = :id") suspend fun setDocument(id: Long, docId: Long?)
    @Query("DELETE FROM transactions WHERE importBatchId = :batchId") suspend fun deleteByBatch(batchId: Long): Int
    @Query("SELECT COUNT(*) FROM transactions") suspend fun count(): Int
}

@Dao
interface ImportBatchDao {
    @Insert suspend fun insert(b: ImportBatchEntity): Long
    @Query("DELETE FROM import_batches WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM import_batches") suspend fun deleteAll()
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC") fun observeAll(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents") suspend fun getAll(): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: Long): DocumentEntity?
    @Insert suspend fun insert(d: DocumentEntity): Long
    @Update suspend fun update(d: DocumentEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM documents") suspend fun deleteAll()
}

@Dao
interface DueDao {
    @Query("SELECT * FROM dues ORDER BY nextDate, id") fun observeAll(): Flow<List<DueEntity>>
    @Query("SELECT * FROM dues ORDER BY nextDate, id") suspend fun getAll(): List<DueEntity>
    @Query("SELECT * FROM dues WHERE legacyId = :legacyId") suspend fun findByLegacyId(legacyId: String): DueEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(d: DueEntity): Long
    @Update suspend fun update(d: DueEntity)
    @Query("DELETE FROM dues WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM dues") suspend fun deleteAll()
}

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios ORDER BY isBase DESC, id") fun observeScenarios(): Flow<List<ScenarioEntity>>
    @Query("SELECT * FROM scenarios ORDER BY isBase DESC, id") suspend fun getScenarios(): List<ScenarioEntity>
    @Query("SELECT * FROM scenario_cuts") fun observeCuts(): Flow<List<ScenarioCutEntity>>
    @Query("SELECT * FROM scenario_cuts") suspend fun getCuts(): List<ScenarioCutEntity>
    @Query("SELECT * FROM scenario_removed") fun observeRemoved(): Flow<List<ScenarioRemovedEntity>>
    @Query("SELECT * FROM scenario_removed") suspend fun getRemoved(): List<ScenarioRemovedEntity>
    @Insert suspend fun insert(s: ScenarioEntity): Long
    @Update suspend fun update(s: ScenarioEntity)
    @Query("DELETE FROM scenarios WHERE id = :id AND isBase = 0") suspend fun delete(id: Long)
    @Query("DELETE FROM scenarios") suspend fun deleteAll()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCuts(c: List<ScenarioCutEntity>)
    @Query("DELETE FROM scenario_cuts WHERE scenarioId = :id") suspend fun clearCuts(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRemoved(r: List<ScenarioRemovedEntity>)
    @Query("DELETE FROM scenario_removed WHERE scenarioId = :id") suspend fun clearRemoved(id: Long)
}
