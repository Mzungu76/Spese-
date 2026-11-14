package it.spese.domain.usecase

import it.spese.core.model.TxOrigin
import it.spese.domain.backup.*
import it.spese.domain.capture.NotificationReconciler
import it.spese.domain.classification.Classifier
import it.spese.domain.classification.Duplicates
import it.spese.domain.export.Exporters
import it.spese.domain.importing.*
import it.spese.domain.repository.*
import javax.inject.Inject

/** Un foglio/tabella pronto per la mappatura delle colonne. */
data class StatementTable(val fileName: String, val sheetName: String, val rows: List<RawRow>, val detected: DetectedTable?, val analysis: TableAnalysis = TableAnalyzer.analyze(rows))

/** Legge un file (XLSX/CSV/TXT) e riconosce intestazioni e colonne. */
class ReadStatementUseCase @Inject constructor() {
    operator fun invoke(bytes: ByteArray, fileName: String): List<StatementTable> {
        val sheets = TableReader.read(bytes, fileName)
        val tables = sheets.filter { it.rows.isNotEmpty() }.map { StatementTable(fileName, it.name, it.rows, HeaderDetector.detect(it.rows)) }
        // preferisce i fogli con intestazione riconosciuta
        return tables.sortedByDescending { it.detected != null }
    }
}

data class ImportOutcome(val added: Int, val duplicates: Int, val skipped: Int, val batchId: Long, val reconciled: Int = 0)

class PreviewImportUseCase @Inject constructor(private val tx: TransactionRepository, private val accounts: AccountRepository) {
    suspend operator fun invoke(table: StatementTable, headerRow: Int, mapping: ColumnMapping, accountName: String, options: ImportOptions = ImportOptions()): ImportPreview {
        val result = ImportParser.parse(table.rows, headerRow, mapping, options)
        val acc = accounts.getAll().firstOrNull { it.name.equals(accountName.trim(), true) }
        val existing = acc?.let { tx.dedupKeys(it.id) } ?: emptyList()
        return ImportPlanner.preview(result, acc?.id?.toString() ?: "new", existing)
    }
}

class CommitImportUseCase @Inject constructor(
    private val tx: TransactionRepository, private val accounts: AccountRepository,
    private val categories: CategoryRepository, private val rules: RuleRepository, private val settings: SettingsRepository,
) {
    /** Importa solo le righe nuove (le duplicate sono scartate); registra il lotto per poterlo annullare. */
    suspend operator fun invoke(table: StatementTable, headerRow: Int, mapping: ColumnMapping, accountName: String, options: ImportOptions = ImportOptions()): ImportOutcome {
        val name = accountName.trim().ifBlank { "Conto principale" }
        val acc = accounts.getOrCreate(name)
        val result = ImportParser.parse(table.rows, headerRow, mapping, options)
        val preview = ImportPlanner.preview(result, acc.id.toString(), tx.dedupKeys(acc.id))
        val classifier = Classifier(categories.getAll(), rules.getAll())
        val fresh = preview.rows.filter { !it.duplicate }
        // le righe dell'estratto sostituiscono i movimenti provvisori nati da notifiche (stesso importo, data vicina)
        val provisional = tx.getAll().filter { it.accountId == acc.id && it.origin == TxOrigin.NOTIFICATION }
        val matches = NotificationReconciler.match(provisional, fresh.mapIndexed { i, p -> NotificationReconciler.Row(i, p.row.amount, p.row.date) })
        val batch = tx.createImportBatch(table.fileName, fresh.size)
        val items = fresh.mapIndexed { i, p ->
            val r = p.row; val prov = matches[i]
            // categoria scelta a mano e note dell'utente sul provvisorio non si perdono
            val c = if (prov != null && prov.manualCategory) it.spese.domain.classification.Classification(prov.categoryId, prov.kind, true, null) else classifier.classify(r.text, r.amount, null)
            val notes = prov?.notes?.takeIf { it.isNotBlank() && !it.startsWith("Da notifica") } ?: ""
            NewTransaction(Duplicates.key(name, r.date, r.valueDate, r.amount, r.text) + "#" + p.occurrence, p.key, acc.id, r.date, r.valueDate, r.text,
                r.amount, c.kind, c.categoryId, c.manual, c.ruleId, prov?.merchantOverride, notes, TxOrigin.IMPORT_FILE, null, batch)
        }
        if (matches.isNotEmpty()) tx.delete(matches.values.map { it.id })
        val added = tx.insertAll(items)
        settings.update { it.copy(lastUndo = "batch:$batch") }
        return ImportOutcome(added, preview.duplicateCount, preview.skipped.size, batch, matches.size)
    }
}

class InspectBackupUseCase @Inject constructor(private val backup: BackupRepository) {
    data class Inspection(val envelope: BackupEnvelope, val issues: List<BackupIssue>, val plan: MergePlan)

    suspend operator fun invoke(text: String): Inspection {
        val env = LegacyBackupCodec.parse(text)
        return Inspection(env, LegacyBackupCodec.validate(env), backup.planMerge(env))
    }
}

class RestoreBackupUseCase @Inject constructor(private val backup: BackupRepository, private val reclassify: ReclassifyAllUseCase) {
    suspend operator fun invoke(env: BackupEnvelope, mode: RestoreMode, files: Map<String, ByteArray> = emptyMap()): RestoreResult {
        val r = backup.restore(env, mode, files)
        reclassify()
        return r
    }
}

/** Annulla l'ultima importazione (lotto di file o ripristino di backup) usando lo snapshot salvato prima dell'operazione. */
class UndoImportUseCase @Inject constructor(
    private val tx: TransactionRepository, private val backup: BackupRepository, private val settings: SettingsRepository,
) {
    suspend operator fun invoke(): Boolean {
        val u = settings.get().lastUndo
        val ok = when {
            u.startsWith("batch:") -> tx.deleteImportBatch(u.removePrefix("batch:").toLong()) >= 0
            u == "snapshot" -> backup.undoLast()
            else -> false
        }
        if (ok) settings.update { it.copy(lastUndo = "") }
        return ok
    }
}

class ExportTransactionsUseCase @Inject constructor() {
    fun csv(data: AppData, list: List<it.spese.core.model.Transaction>): ByteArray =
        Exporters.csv(list, data.accounts.associate { it.id to it.name }, data.categoriesById).toByteArray(Charsets.UTF_8)

    fun xlsx(data: AppData, list: List<it.spese.core.model.Transaction>): ByteArray =
        Exporters.xlsx(list, data.accounts.associate { it.id to it.name }, data.categoriesById)
}

/** Cerca un formato già ricordato per questo file e permette di ricordarne di nuovi. */
class ImportProfilesUseCase @Inject constructor(private val repo: ImportProfileRepository) {
    suspend fun match(table: StatementTable): ImportProfile? {
        val sig = table.analysis.signature(table.rows)
        return repo.all().firstOrNull { it.signature == sig && it.roles.size == table.analysis.columns.size }
    }

    suspend fun remember(name: String, table: StatementTable, roles: List<ColumnRole>, options: ImportOptions, headerRow: Int) =
        repo.save(ImportProfile(name.ifBlank { "Formato personalizzato" }, TableAnalyzer.signature(table.rows, headerRow, roles.size), roles, options, headerRow))
}
