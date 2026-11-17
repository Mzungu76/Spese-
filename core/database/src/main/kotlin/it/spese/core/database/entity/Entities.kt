package it.spese.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema normalizzato. Gli importi sono centesimi interi (Long): nessun Float/Double.
 * Le date sono giorni epoch (Long). `updatedAt` serve all'unione dei backup (vince la modifica più recente).
 */

@Entity(tableName = "accounts", indices = [Index(value = ["name"], unique = true)])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val openingCents: Long = 0,
    val archived: Boolean = false,
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true), Index("sortOrder")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val flexible: Boolean,
    val color: Long,
    val budgetCents: Long? = null,
    val active: Boolean = true,
    val sortOrder: Int = 0,
    val systemKey: String? = null,
    val updatedAt: Long = 0,
    val budgetUpdatedAt: Long = 0,
    /** Chiave icona scelta dall'utente (null = default dal nome). Aggiunta nello schema 2. */
    val icon: String? = null,
    /** Categoria padre (nessuna chiave esterna: all'eliminazione le figlie tornano principali). Schema 3. */
    val parentId: Long? = null,
    /** Natura della spesa: 'f' / 'e' / 'd' oppure null. Schema 4. */
    val nature: String? = null,
)

@Entity(
    tableName = "rules",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId"), Index("priority")],
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val categoryId: Long,
    val sign: String,
    val minCents: Long = 0,
    val maxCents: Long = 0,
    val priority: Int = 0,
    val active: Boolean = true,
    val updatedAt: Long = 0,
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val createdAt: Long,
    val rowCount: Int,
)

@Entity(tableName = "documents", indices = [Index(value = ["fileName"], unique = true)])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val ocrText: String? = null,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(DocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(ImportBatchEntity::class, ["id"], ["importBatchId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["externalId"], unique = true),
        Index("accountId", "dedupKey"),
        Index("bookingDate"),
        Index("categoryId"),
        Index("documentId"),
        Index("importBatchId"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val externalId: String,
    val dedupKey: String,
    /** Nullo = movimento senza conto (in Verifica). Il vincolo RESTRICT impedisce di eliminare un conto che ha ancora movimenti. */
    val accountId: Long?,
    val bookingDate: Long,
    val valueDate: Long,
    val description: String,
    val amountCents: Long,
    val kind: String,
    val categoryId: Long? = null,
    val manualCategory: Boolean = false,
    val manualUpdatedAt: Long = 0,
    val ruleId: Long? = null,
    val merchantOverride: String? = null,
    val notes: String = "",
    val origin: String,
    val documentId: Long? = null,
    val importBatchId: Long? = null,
    val createdAt: Long = 0,
    /** 1 = controllato dall'utente (esce da Verifica). Schema 5. */
    @ColumnInfo(defaultValue = "0") val reviewed: Boolean = false,
)

/** Parti di un movimento diviso su più categorie. Eliminando il movimento si eliminano anche le parti. Schema 5. */
@Entity(
    tableName = "transaction_splits",
    foreignKeys = [
        ForeignKey(TransactionEntity::class, ["id"], ["txId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("txId"), Index("categoryId")],
)
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txId: Long,
    val categoryId: Long? = null,
    val amountCents: Long,
    val position: Int = 0,
    val auto: Boolean = false,
    val quotaId: Long? = null,
)

/** Quote fisse pagate in contanti (es. affitto). Schema 5. */
@Entity(
    tableName = "cash_quotas",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("categoryId")],
)
data class CashQuotaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val categoryId: Long,
    val amountCents: Long,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "'c'") val mode: String = "c",
    @ColumnInfo(defaultValue = "0") val toleranceCents: Long = 0,
    @ColumnInfo(defaultValue = "1") val everyMonths: Int = 1,
    @ColumnInfo(defaultValue = "0") val dayFrom: Int = 0,
    @ColumnInfo(defaultValue = "0") val dayTo: Int = 0,
)

@Entity(tableName = "dues", indices = [Index(value = ["legacyId"], unique = true), Index("nextDate")])
data class DueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val legacyId: String,
    val name: String,
    val amountCents: Long,
    val nextDate: Long,
    val frequency: String,
    val updatedAt: Long = 0,
)

@Entity(tableName = "scenarios")
data class ScenarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isBase: Boolean = false,
    val goalMode: String,
    val goalValueCents: Long,
    val goalMonths: Int,
    val expectedIncomeCents: Long? = null,
    val incomeDeltaCents: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "scenario_cuts",
    primaryKeys = ["scenarioId", "categoryId"],
    foreignKeys = [
        ForeignKey(ScenarioEntity::class, ["id"], ["scenarioId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("categoryId")],
)
data class ScenarioCutEntity(val scenarioId: Long, val categoryId: Long, val percent: Int)

@Entity(
    tableName = "scenario_removed",
    primaryKeys = ["scenarioId", "itemKey"],
    foreignKeys = [ForeignKey(ScenarioEntity::class, ["id"], ["scenarioId"], onDelete = ForeignKey.CASCADE)],
)
data class ScenarioRemovedEntity(val scenarioId: Long, @ColumnInfo(name = "itemKey") val itemKey: String)

/** Entrate e spese ricorrenti: generano movimenti alla data giusta. Schema 7. */
@Entity(
    tableName = "recurring_rules",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId")],
)
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val amountCents: Long,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val everyMonths: Int = 1,
    val dayOfMonth: Int = 1,
    val startDate: Long,
    val endDate: Long? = null,
    val enabled: Boolean = true,
    val autoConfirm: Boolean = false,
    val lastGenerated: Long? = null,
)
