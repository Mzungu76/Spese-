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
    /** Nullo = senza conto: il movimento resta in Verifica, fuori dalla contabilità, finché non se ne sceglie uno. */
    val accountId: Long?,
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
    /** Chiave di deduplica salvata di un movimento. */
    suspend fun getDedupKey(id: Long): String?
    suspend fun delete(ids: Collection<Long>)
    /** Rimette i movimenti «in attesa dell'estratto conto» (origine scontrino, chiave di deduplica = identificativo): la prossima importazione li sostituisce con la riga giusta, ereditandone documento, voci e note. */
    suspend fun markProvisional(ids: Collection<Long>)
    suspend fun applyClassifications(updates: List<ClassificationUpdate>)
    /** Chiavi di deduplica dei movimenti esistenti di un conto (una per movimento). */
    suspend fun dedupKeys(accountId: Long): List<String>
    suspend fun setDocument(txId: Long, documentId: Long?)
    suspend fun createImportBatch(label: String, count: Int): Long
    suspend fun deleteImportBatch(batchId: Long): Int

    fun observeSplits(): Flow<List<it.spese.core.model.TransactionSplit>>
    suspend fun getSplits(): List<it.spese.core.model.TransactionSplit>
    /** Sostituisce le parti di un movimento (lista vuota = movimento non più diviso). */
    suspend fun replaceSplits(txId: Long, parts: List<it.spese.core.model.TransactionSplit>)
    suspend fun setReviewed(ids: Collection<Long>, reviewed: Boolean)
    /** Sceglie il mese contabile dei movimenti (`null` = quello della data). */
    suspend fun setAccountingMonth(ids: Collection<Long>, month: java.time.YearMonth?)
    /** Come sopra, per identificativo esterno (ripristino del backup). */
    suspend fun setAccountingMonthByExternal(months: Map<String, java.time.YearMonth>)
}

interface CashQuotaRepository {
    fun observeAll(): Flow<List<it.spese.core.model.CashQuota>>
    suspend fun getAll(): List<it.spese.core.model.CashQuota>
    /** Inserisce (id = 0) o aggiorna; restituisce l'id. */
    suspend fun save(q: it.spese.core.model.CashQuota): Long
    suspend fun delete(id: Long)
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
    /** Il conto con quel nome, creato se manca. Il nome è obbligatorio: non esiste un conto «di ripiego». */
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
    /** Compone più foto (dall'alto verso il basso) in un'unica immagine lunga e restituisce i metadati (id = 0). Non elimina le foto di partenza. */
    suspend fun stitch(pages: List<DocumentRef>): DocumentRef
    suspend fun deleteFile(fileName: String)
    suspend fun totalBytes(): Long
    fun pathOf(fileName: String): String
}

/** Motore OCR sostituibile (oggi ML Kit on-device; domani anche estratti conto fotografati e documenti multipagina). */
interface TextRecognizer {
    suspend fun recognize(imagePath: String): String
    /** Come [recognize], più le righe lette con poca sicurezza (da far controllare all'utente). */
    suspend fun recognizePage(imagePath: String): OcrPage = OcrPage(recognize(imagePath))
}

data class OcrPage(val text: String, val weakRows: List<String> = emptyList())

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
    /** Giorno in cui inizia il mese contabile (1 = mese di calendario). */
    val monthStartDay: Int = 1,
    /** Nella notifica giornaliera, la cifra «disponibile oggi» (solo con le notifiche locali attive). */
    val notifyDailySpend: Boolean = true,
    /** Un'entrata negli ultimi N giorni del mese contabile finisce in Verifica per scegliere il mese (0 = non chiedere). */
    val incomeMonthAskDays: Int = 7,
)

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Impostazioni della cattura automatica da notifiche (fonti attive, conti, mittenti SMS, notifiche non riconosciute). */
interface CaptureConfigRepository {
    fun observe(): Flow<it.spese.domain.capture.CaptureConfig>
    suspend fun get(): it.spese.domain.capture.CaptureConfig
    suspend fun update(transform: (it.spese.domain.capture.CaptureConfig) -> it.spese.domain.capture.CaptureConfig)
}

/** Obiettivi di risparmio (preferenze dell'app, nel backup). */
interface GoalRepository {
    fun observeAll(): Flow<List<it.spese.core.model.Goal>>
    suspend fun getAll(): List<it.spese.core.model.Goal>
    /** Inserisce (id = 0) o aggiorna; restituisce l'id. */
    suspend fun save(g: it.spese.core.model.Goal): Long
    suspend fun delete(id: Long)
    /** Sostituisce tutti gli obiettivi (ripristino da backup). */
    suspend fun setAll(goals: List<it.spese.core.model.Goal>)
}

/** Valori dei conti di investimento (preferenze dell'app, nel backup). Una sola riga per conto e data. */
interface ValuationRepository {
    fun observeAll(): Flow<List<it.spese.core.model.Valuation>>
    suspend fun getAll(): List<it.spese.core.model.Valuation>
    suspend fun save(v: it.spese.core.model.Valuation)
    suspend fun delete(accountId: Long, date: java.time.LocalDate)
    suspend fun setAll(values: List<it.spese.core.model.Valuation>)
}

/** Entrate e spese ricorrenti. */
interface RecurringRepository {
    fun observeAll(): Flow<List<it.spese.core.model.RecurringRule>>
    suspend fun getAll(): List<it.spese.core.model.RecurringRule>
    /** Inserisce (id = 0) o aggiorna; restituisce l'id. */
    suspend fun save(r: it.spese.core.model.RecurringRule): Long
    suspend fun delete(id: Long)
    suspend fun markGenerated(id: Long, day: java.time.LocalDate)
}

/** Modelli di report personalizzati salvati dall'utente (solo sul telefono). */
interface ReportPresetRepository {
    fun observe(): Flow<List<it.spese.domain.report.SavedReport>>
    suspend fun save(r: it.spese.domain.report.SavedReport)
    suspend fun delete(id: String)
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
    /** Salva subito uno snapshot per «Annulla»: da chiamare prima di un'operazione che cancella dati. */
    suspend fun saveUndoSnapshot()
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
