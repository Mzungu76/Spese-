package it.spese.domain.repository

import it.spese.core.model.Account
import it.spese.core.model.Category
import it.spese.core.model.DocumentRef
import it.spese.core.model.Due
import it.spese.core.model.Rule
import it.spese.core.model.Scenario
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.domain.backup.BackupEnvelope
import it.spese.domain.backup.MergePlan
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Dati di un nuovo movimento (l'id è assegnato dal database). */
data class NewTransaction(
    val externalId: String,
    val dedupKey: String,
    val accountId: Long,
    val bookingDate: LocalDate,
    val valueDate: LocalDate,
    val description: String,
    val amount: BigDecimal,
    val kind: TxKind,
    val categoryId: Long?,
    val manualCategory: Boolean,
    val ruleId: Long?,
    val merchantOverride: String? = null,
    val notes: String = "",
    val origin: TxOrigin = TxOrigin.MANUAL,
    val documentId: Long? = null,
    val importBatchId: Long? = null,
)

data class ClassificationUpdate(val txId: Long, val categoryId: Long?, val kind: TxKind, val manual: Boolean, val ruleId: Long?)

interface TransactionRepository {
    fun observeAll(): Flow<List<Transaction>>
    suspend fun getAll(): List<Transaction>
    suspend fun get(id: Long): Transaction?
    /** Inserisce i movimenti ignorando quelli con `externalId` già presente; restituisce il numero di righe inserite. */
    suspend fun insertAll(items: List<NewTransaction>): Int
    suspend fun update(tx: Transaction, dedupKey: String)
    suspend fun delete(ids: Collection<Long>)
    suspend fun applyClassifications(updates: List<ClassificationUpdate>)
    /** Chiavi di deduplica dei movimenti esistenti di un conto (una per movimento). */
    suspend fun dedupKeys(accountId: Long): List<String>
    suspend fun setDocument(txId: Long, documentId: Long?)
    suspend fun createImportBatch(label: String, count: Int): Long
    suspend fun deleteImportBatch(batchId: Long): Int
}

interface CategoryRepository {
    fun observeAll(): Flow<List<Category>>
    suspend fun getAll(): List<Category>
    suspend fun insert(c: Category): Long
    suspend fun update(c: Category)
    /** Elimina la categoria; le regole collegate vengono eliminate e i movimenti restano da riclassificare. */
    suspend fun delete(id: Long)
    suspend fun reorder(idsInOrder: List<Long>)
    suspend fun setBudget(id: Long, budget: BigDecimal?)
    suspend fun clearBudgets()
}

interface RuleRepository {
    fun observeAll(): Flow<List<Rule>>
    suspend fun getAll(): List<Rule>
    /** Inserisce in cima alla lista (massima priorità), come fa la versione HTML. */
    suspend fun insertFirst(r: Rule): Long
    suspend fun insertLast(r: Rule): Long
    suspend fun update(r: Rule)
    suspend fun delete(id: Long)
    suspend fun reorder(idsInOrder: List<Long>)
}

interface AccountRepository {
    fun observeAll(): Flow<List<Account>>
    suspend fun getAll(): List<Account>
    suspend fun getOrCreate(name: String): Account
    suspend fun insert(a: Account): Long
    suspend fun update(a: Account)
    /** Rinomina; se esiste già un conto con quel nome i movimenti vengono uniti (come "Rinomina" nella versione HTML). */
    suspend fun renameOrMerge(id: Long, newName: String)
    /** Elimina un conto solo se non ha movimenti; restituisce false altrimenti. */
    suspend fun deleteIfEmpty(id: Long): Boolean
}

interface DueRepository {
    fun observeAll(): Flow<List<Due>>
    suspend fun insert(d: Due): Long
    suspend fun update(d: Due)
    suspend fun delete(id: Long)
}

interface ScenarioRepository {
    fun observeAll(): Flow<List<Scenario>>
    /** Salva (inserisce o aggiorna) e restituisce l'id. */
    suspend fun save(s: Scenario): Long
    suspend fun delete(id: Long)
}

interface DocumentRepository {
    fun observeAll(): Flow<List<DocumentRef>>
    suspend fun get(id: Long): DocumentRef?
    suspend fun add(ref: DocumentRef): Long
    suspend fun update(ref: DocumentRef)
    suspend fun delete(id: Long)
}

/** Archivio dei file dei documenti (fotografie) nella memoria privata dell'app. */
interface DocumentStore {
    /** Copia/comprime l'immagine nella memoria privata e restituisce metadati (id = 0). */
    suspend fun saveImage(source: String): DocumentRef
    suspend fun deleteFile(fileName: String)
    suspend fun totalBytes(): Long
    fun pathOf(fileName: String): String
}

/** Motore OCR sostituibile (oggi ML Kit on-device; domani anche estratti conto fotografati e documenti multipagina). */
interface TextRecognizer {
    suspend fun recognize(imagePath: String): String
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val notificationsEnabled: Boolean = false,
    val notifyBudgets: Boolean = true,
    val notifyDues: Boolean = true,
    val notifyRecurring: Boolean = true,
    val notifyUnexpected: Boolean = true,
    val notifyHour: Int = 9,
    val autoBackupEnabled: Boolean = false,
    val autoBackupEveryDays: Int = 7,
    val autoBackupKeep: Int = 5,
    val lastAutoBackupAt: Long = 0,
    val onboardingDone: Boolean = false,
    val lastUndo: String = "",
    /** File condiviso collegato per la sincronizzazione (URI SAF) e ultimo allineamento. */
    val syncUri: String = "",
    val lastSyncAt: Long = 0,
    /** Riepilogo giornaliero "oggi puoi spendere…" e riepilogo del lunedì. */
    val notifyDaily: Boolean = false,
    val notifyWeekly: Boolean = false,
    /** Lettura delle notifiche di pagamento (banche e wallet): solo sul dispositivo, solo dalle app indicate. */
    val captureEnabled: Boolean = false,
    /** Pacchetti delle app abilitate, separati da virgola. */
    val capturePackages: String = "",
    /** Conto a cui assegnare i movimenti letti dalle notifiche (vuoto = il primo conto). */
    val captureAccount: String = "",
    /** Configurazione guidata del piano completata (o saltata). */
    val setupDone: Boolean = false,
)

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

sealed interface RestoreMode { data object Merge : RestoreMode; data object Replace : RestoreMode }

data class RestoreResult(val summary: List<String>, val txAdded: Int)

interface BackupRepository {
    /** Backup JSON compatibile con la versione HTML (con estensioni native). */
    suspend fun buildBackupJson(): String
    /** Backup completo: JSON + fotografie dei documenti, in un unico file ZIP. */
    suspend fun writeFullBackup(out: java.io.OutputStream)
    suspend fun planMerge(env: BackupEnvelope): MergePlan
    /** Applica il backup. Prima crea sempre uno snapshot per l'annullamento. */
    suspend fun restore(env: BackupEnvelope, mode: RestoreMode, extraFiles: Map<String, ByteArray> = emptyMap()): RestoreResult
    suspend fun hasUndo(): Boolean
    suspend fun undoLast(): Boolean
    suspend fun resetAll()
    /** Scrive un backup automatico nella cartella interna e ruota i vecchi file. */
    suspend fun autoBackup(keep: Int): String
    fun listAutoBackups(): List<java.io.File>
}

/** Formato di estratto conto ricordato: alla prossima importazione con le stesse intestazioni la mappatura è già pronta. */
data class ImportProfile(
    val name: String,
    /** Impronta delle intestazioni ([it.spese.domain.importing.TableAnalysis.signature]). */
    val signature: String,
    val roles: List<it.spese.domain.importing.ColumnRole>,
    val options: it.spese.domain.importing.ImportOptions,
    val headerRow: Int,
)

interface ImportProfileRepository {
    suspend fun all(): List<ImportProfile>
    /** Salva o sostituisce il profilo con la stessa impronta. */
    suspend fun save(profile: ImportProfile)
    suspend fun delete(signature: String)
}
