package it.spese.data.repository

import androidx.room.withTransaction
import it.spese.core.common.Money
import it.spese.core.database.AppDatabase
import it.spese.core.database.entity.*
import it.spese.core.model.*
import it.spese.data.mapper.*
import it.spese.domain.repository.*
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@Singleton
class TransactionRepositoryImpl @Inject constructor(private val db: AppDatabase) : TransactionRepository {
    private val dao = db.transactions()
    override fun observeAll(): Flow<List<Transaction>> = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()

    override suspend fun insertAll(items: List<NewTransaction>): Int {
        if (items.isEmpty()) return 0
        val now = System.currentTimeMillis()
        return db.withTransaction { items.chunked(500).sumOf { c -> dao.insertAll(c.map { it.toEntity(now) }).count { it != -1L } } }
    }

    override suspend fun update(tx: Transaction, dedupKey: String) {
        val old = dao.get(tx.id) ?: return
        val manualAt = if (tx.manualCategory && (!old.manualCategory || old.categoryId != tx.categoryId)) System.currentTimeMillis() else old.manualUpdatedAt
        dao.update(old.copy(
            accountId = tx.accountId, dedupKey = dedupKey, bookingDate = tx.bookingDate.toEpochDay(), valueDate = tx.valueDate.toEpochDay(),
            description = tx.description, amountCents = Money.toCents(tx.amount), kind = tx.kind.key, categoryId = tx.categoryId,
            manualCategory = tx.manualCategory, manualUpdatedAt = manualAt, ruleId = tx.ruleId, merchantOverride = tx.merchantOverride,
            notes = tx.notes, documentId = tx.documentId,
        ))
    }

    override suspend fun markProvisional(ids: Collection<Long>) { db.withTransaction { ids.chunked(500).forEach { dao.markProvisional(it, TxOrigin.OCR.name) } } }
    override suspend fun delete(ids: Collection<Long>) { db.withTransaction { ids.chunked(500).forEach { dao.deleteByIds(it) } } }

    override suspend fun applyClassifications(updates: List<ClassificationUpdate>) {
        val now = System.currentTimeMillis()
        db.withTransaction { updates.forEach { dao.updateClassification(it.txId, it.categoryId, it.kind.key, it.manual, it.ruleId, now) } }
    }

    override suspend fun dedupKeys(accountId: Long) = dao.dedupKeys(accountId)
    override suspend fun getDedupKey(id: Long): String? = dao.get(id)?.dedupKey
    override suspend fun setDocument(txId: Long, documentId: Long?) = dao.setDocument(txId, documentId)
    override suspend fun createImportBatch(label: String, count: Int) = db.importBatches().insert(ImportBatchEntity(label = label, createdAt = System.currentTimeMillis(), rowCount = count))
    override suspend fun deleteImportBatch(batchId: Long): Int = db.withTransaction {
        val n = dao.deleteByBatch(batchId); db.importBatches().delete(batchId); n
    }

    override fun observeSplits(): Flow<List<TransactionSplit>> = db.splits().observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getSplits() = db.splits().getAll().map { it.toDomain() }
    override suspend fun replaceSplits(txId: Long, parts: List<TransactionSplit>) {
        db.withTransaction {
            db.splits().deleteForTx(txId)
            if (parts.isNotEmpty()) db.splits().insertAll(parts.mapIndexed { i, p -> SplitEntity(0, txId, p.categoryId, Money.toCents(p.amount), i, p.auto, p.quotaId) })
        }
    }
    override suspend fun setReviewed(ids: Collection<Long>, reviewed: Boolean) { db.withTransaction { ids.chunked(500).forEach { dao.setReviewed(it, reviewed) } } }
}

@Singleton
class RecurringRepositoryImpl @Inject constructor(private val db: AppDatabase) : RecurringRepository {
    private val dao = db.recurring()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }
    override suspend fun save(r: RecurringRule): Long {
        val e = RecurringEntity(r.id, r.name.trim(), r.kind.key, Money.toCents(r.amount), r.accountId, r.categoryId, r.everyMonths.coerceIn(1, 12), r.dayOfMonth.coerceIn(1, 31),
            r.startDate.toEpochDay(), r.endDate?.toEpochDay(), r.enabled, r.autoConfirm, r.lastGenerated?.toEpochDay())
        return if (r.id == 0L) dao.insert(e) else { dao.update(e); r.id }
    }
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun markGenerated(id: Long, day: java.time.LocalDate) = dao.setLastGenerated(id, day.toEpochDay())
}

@Singleton
class CashQuotaRepositoryImpl @Inject constructor(private val db: AppDatabase) : CashQuotaRepository {
    private val dao = db.quotas()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }
    override suspend fun save(q: CashQuota): Long {
        val e = CashQuotaEntity(q.id, q.name.trim(), q.categoryId, Money.toCents(q.amount), q.enabled, q.mode.key, Money.toCents(q.tolerance), q.everyMonths.coerceIn(1, 12), q.dayFrom.coerceIn(0, 31), q.dayTo.coerceIn(0, 31))
        return if (q.id == 0L) dao.insert(e) else { dao.update(e); q.id }
    }
    override suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class CategoryRepositoryImpl @Inject constructor(private val db: AppDatabase) : CategoryRepository {
    private val dao = db.categories()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }

    override suspend fun insert(c: Category): Long {
        val now = System.currentTimeMillis()
        return dao.insert(CategoryEntity(name = c.name, kind = c.kind.key, flexible = c.flexible, color = c.color,
            budgetCents = c.budgetMonthly?.let { Money.toCents(it) }, active = c.active, sortOrder = c.sortOrder, systemKey = c.systemKey, icon = c.icon, parentId = c.parentId, nature = c.nature?.key,
            updatedAt = now, budgetUpdatedAt = if (c.budgetMonthly != null) now else 0))
    }

    override suspend fun update(c: Category) {
        val old = dao.getAll().firstOrNull { it.id == c.id } ?: return
        val now = System.currentTimeMillis()
        val newBudget = c.budgetMonthly?.let { Money.toCents(it) }
        val changed = old.name != c.name || old.kind != c.kind.key || old.flexible != c.flexible || old.color != c.color || old.active != c.active || old.icon != c.icon || old.parentId != c.parentId || old.nature != c.nature?.key
        dao.update(old.copy(name = c.name, kind = c.kind.key, flexible = c.flexible, color = c.color, active = c.active, sortOrder = c.sortOrder, icon = c.icon, parentId = c.parentId, nature = c.nature?.key,
            budgetCents = newBudget, updatedAt = if (changed) now else old.updatedAt, budgetUpdatedAt = if (newBudget != old.budgetCents) now else old.budgetUpdatedAt))
    }

    override suspend fun delete(id: Long) { db.withTransaction { dao.detachChildren(id); dao.delete(id) } }
    override suspend fun reorder(idsInOrder: List<Long>) { db.withTransaction { idsInOrder.forEachIndexed { i, id -> dao.setOrder(id, i) } } }
    override suspend fun setBudget(id: Long, budget: BigDecimal?) = dao.setBudget(id, budget?.takeIf { it.signum() > 0 }?.let { Money.toCents(it) }, System.currentTimeMillis())
    override suspend fun clearBudgets() = dao.clearBudgets(System.currentTimeMillis())
}

@Singleton
class RuleRepositoryImpl @Inject constructor(private val db: AppDatabase) : RuleRepository {
    private val dao = db.rules()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }
    private fun Rule.entity(priority: Int) = RuleEntity(if (id == 0L) 0 else id, keyword, categoryId, sign.key, Money.toCents(min), Money.toCents(max), priority, active, System.currentTimeMillis())

    override suspend fun insertFirst(r: Rule): Long = db.withTransaction { dao.shiftDown(); dao.insert(r.entity(0).copy(id = 0)) }
    override suspend fun insertLast(r: Rule): Long = db.withTransaction { dao.insert(r.entity(dao.maxPriority() + 1).copy(id = 0)) }
    override suspend fun update(r: Rule) = dao.update(r.entity(r.priority))
    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun reorder(idsInOrder: List<Long>) { db.withTransaction { idsInOrder.forEachIndexed { i, id -> dao.setPriority(id, i) } } }
}

@Singleton
class AccountRepositoryImpl @Inject constructor(private val db: AppDatabase) : AccountRepository {
    private val dao = db.accounts()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun getAll() = dao.getAll().map { it.toDomain() }

    override suspend fun getOrCreate(name: String): Account = db.withTransaction {
        val n = name.trim()
        require(n.isNotEmpty()) { "Indica il nome del conto." }
        dao.findByName(n)?.toDomain() ?: Account(dao.insert(AccountEntity(name = n, type = AccountType.CONTO.name)), n)
    }

    override suspend fun insert(a: Account): Long = dao.insert(a.toEntity().copy(id = 0))
    override suspend fun update(a: Account) = dao.update(a.toEntity())

    override suspend fun renameOrMerge(id: Long, newName: String) {
        val n = newName.trim(); if (n.isEmpty()) return
        db.withTransaction {
            val other = dao.findByName(n)
            if (other != null && other.id != id) {
                // stesso nome di un altro conto: unione. Le chiavi di deduplica contengono l'id conto, quindi si ricalcolano.
                val moved = db.transactions().getAll().filter { it.accountId == id }
                dao.moveTransactions(id, other.id)
                moved.forEach { t ->
                    val key = it.spese.domain.classification.Duplicates.key(other.id.toString(), java.time.LocalDate.ofEpochDay(t.bookingDate), java.time.LocalDate.ofEpochDay(t.valueDate), Money.fromCents(t.amountCents), t.description)
                    db.transactions().update(t.copy(accountId = other.id, dedupKey = key))
                }
                dao.delete(id)
            } else dao.getAll().firstOrNull { it.id == id }?.let { dao.update(it.copy(name = n)) }
        }
    }

    override suspend fun deleteIfEmpty(id: Long): Boolean = db.withTransaction { if (dao.txCount(id) == 0) { dao.delete(id); true } else false }
}

@Singleton
class DueRepositoryImpl @Inject constructor(private val db: AppDatabase) : DueRepository {
    private val dao = db.dues()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun insert(d: Due): Long = dao.insert(DueEntity(legacyId = d.legacyId.ifBlank { "d" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (Math.random() * 1000).toInt() },
        name = d.name, amountCents = Money.toCents(d.amount), nextDate = d.next.toEpochDay(), frequency = d.frequency.key, updatedAt = System.currentTimeMillis()))
    override suspend fun update(d: Due) {
        val old = dao.getAll().firstOrNull { it.id == d.id } ?: return
        dao.update(old.copy(name = d.name, amountCents = Money.toCents(d.amount), nextDate = d.next.toEpochDay(), frequency = d.frequency.key, updatedAt = System.currentTimeMillis()))
    }
    override suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class ScenarioRepositoryImpl @Inject constructor(private val db: AppDatabase) : ScenarioRepository {
    private val dao = db.scenarios()
    override fun observeAll(): Flow<List<Scenario>> =
        combine(dao.observeScenarios(), dao.observeCuts(), dao.observeRemoved()) { s, c, r ->
            val cuts = c.groupBy { it.scenarioId }; val rem = r.groupBy { it.scenarioId }
            s.map { e ->
                Scenario(e.id, e.name,
                    SavingsGoal(GoalMode.fromKey(e.goalMode), Money.fromCents(e.goalValueCents), e.goalMonths, e.expectedIncomeCents?.let { Money.fromCents(it) }),
                    cuts[e.id].orEmpty().associate { it.categoryId to it.percent }, Money.fromCents(e.incomeDeltaCents),
                    rem[e.id].orEmpty().map { it.itemKey }.toSet(), e.isBase)
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun save(s: Scenario): Long = db.withTransaction {
        val e = ScenarioEntity(s.id, s.name, s.isBase, s.goal.mode.key, Money.toCents(s.goal.value), s.goal.months,
            s.goal.expectedIncome?.let { Money.toCents(it) }, Money.toCents(s.incomeDelta), System.currentTimeMillis())
        val id = if (s.id == 0L) dao.insert(e) else { dao.update(e); s.id }
        dao.clearCuts(id); dao.clearRemoved(id)
        dao.insertCuts(s.cuts.filter { it.value != 0 }.map { ScenarioCutEntity(id, it.key, it.value) })
        dao.insertRemoved(s.removedRecurring.map { ScenarioRemovedEntity(id, it) })
        id
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class DocumentRepositoryImpl @Inject constructor(private val db: AppDatabase) : DocumentRepository {
    private val dao = db.documents()
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }.flowOn(Dispatchers.Default)
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun add(ref: DocumentRef): Long = dao.insert(DocumentEntity(fileName = ref.fileName, mimeType = ref.mimeType, sizeBytes = ref.sizeBytes, createdAt = ref.createdAt, ocrText = ref.ocrText))
    override suspend fun update(ref: DocumentRef) = dao.update(DocumentEntity(ref.id, ref.fileName, ref.mimeType, ref.sizeBytes, ref.createdAt, ref.ocrText))
    override suspend fun delete(id: Long) = dao.delete(id)
}
