package it.spese.feature.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.app.work.Notifier
import it.spese.app.work.WorkScheduler
import it.spese.domain.backup.BackupEnvelope
import it.spese.domain.backup.BackupIssue
import it.spese.domain.backup.LegacyBackupCodec
import it.spese.domain.backup.MergePlan
import it.spese.domain.repository.*
import it.spese.domain.usecase.*
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RestorePrompt(val fileName: String, val envelope: BackupEnvelope, val issues: List<BackupIssue>, val plan: MergePlan, val files: Map<String, ByteArray>, val localTx: Int)

data class StorageInfo(val documents: Int = 0, val bytes: Long = 0, val autoBackups: Int = 0)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: Application, private val settings: SettingsRepository, private val backup: BackupRepository, private val docs: DocumentRepository,
    private val store: DocumentStore, private val deleteDoc: DeleteDocumentUseCase, private val inspect: InspectBackupUseCase, private val restoreUc: RestoreBackupUseCase,
    private val exporter: ExportTransactionsUseCase, val data: StateFlow<AppData>, private val scheduler: WorkScheduler, val notifier: Notifier,
    private val messenger: UiMessenger, private val reclassify: ReclassifyAllUseCase, private val incoming: it.spese.app.common.IncomingFile,
    private val restartUc: it.spese.domain.usecase.RestartFromStatementsUseCase,
) : AndroidViewModel(app) {
    val state: StateFlow<AppSettings> = settings.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    private val _pending = MutableStateFlow<RestorePrompt?>(null)
    val pending: StateFlow<RestorePrompt?> = _pending.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    init {
        refreshStorage()
        viewModelScope.launch { incoming.uri.filterNotNull().collect { u -> incoming.consume(); inspectUri(u) } }
    }
    fun refreshStorage() = viewModelScope.launch { _storage.value = StorageInfo(docs.observeAll().first().size, store.totalBytes(), backup.listAutoBackups().size) }

    fun update(t: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(t); scheduler.apply(settings.get()) }

    private fun run(msg: String? = null, block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try { block(); msg?.let { messenger.show(it) } } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") } finally { _busy.value = false }
    }

    private fun write(uri: Uri, bytes: ByteArray) = app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }

    // ---- backup ed esportazioni ----
    suspend fun backupJsonBytes(): ByteArray = backup.buildBackupJson().toByteArray(Charsets.UTF_8)
    fun saveBackupJson(uri: Uri) = run("Backup salvato") { withContext(Dispatchers.IO) { write(uri, backupJsonBytes()) } }
    fun saveFullBackup(uri: Uri) = run("Backup completo salvato") { withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.use { backup.writeFullBackup(it) } } }
    fun saveCsv(uri: Uri) = run("File CSV salvato") { withContext(Dispatchers.IO) { write(uri, exporter.csv(data.value, data.value.ledger)) } }
    fun saveXlsx(uri: Uri) = run("File Excel salvato") { withContext(Dispatchers.IO) { write(uri, exporter.xlsx(data.value, data.value.ledger)) } }

    // ---- ripristino con verifica, unione o sostituzione ----
    fun inspectUri(uri: Uri) = run {
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "backup"
        val bytes = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
        prepare(name, bytes)
    }

    fun inspectFile(f: File) = run { prepare(f.name, withContext(Dispatchers.IO) { f.readBytes() }) }

    private suspend fun prepare(name: String, bytes: ByteArray) {
        var json: String? = null; val files = HashMap<String, ByteArray>()
        if (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            withContext(Dispatchers.IO) {
                ZipInputStream(bytes.inputStream()).use { z ->
                    while (true) { val e = z.nextEntry ?: break; if (e.isDirectory) continue
                        val b = z.readBytes()
                        if (e.name == "backup.json" || (e.name.endsWith(".json") && !e.name.contains('/') && json == null)) json = String(b, Charsets.UTF_8) else files[e.name] = b }
                }
            }
        } else json = CsvLike.decode(bytes)
        val text = json ?: throw IllegalArgumentException("Il file non contiene un backup.")
        val r = try { inspect(text) } catch (e: it.spese.domain.backup.BackupFormatException) { throw IllegalArgumentException(e.message) }
        _pending.value = RestorePrompt(name, r.envelope, r.issues, r.plan, files, data.value.ledger.size)
    }

    fun cancelRestore() { _pending.value = null }

    fun confirmRestore(replace: Boolean) {
        val p = _pending.value ?: return
        _pending.value = null
        run(null) {
            val r = restoreUc(p.envelope, if (replace) RestoreMode.Replace else RestoreMode.Merge, p.files)
            messenger.show(if (replace) "Dati sostituiti dal backup" else if (r.summary.isEmpty()) "Nessuna modifica necessaria" else "Backup unito: ${r.txAdded} movimenti aggiunti")
            refreshStorage()
        }
    }

    // ---- sincronizzazione tramite file condiviso (cartella Drive/OneDrive/Dropbox raggiunta dal selettore file di Android) ----
    fun link(uri: Uri, create: Boolean) = run {
        app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings.update { it.copy(syncUri = uri.toString()) }
        syncNowInternal()
    }
    fun syncNow() = run { syncNowInternal() }
    fun unlink() = viewModelScope.launch { settings.update { it.copy(syncUri = "", lastSyncAt = 0) } }

    private suspend fun syncNowInternal() {
        val uri = Uri.parse(settings.get().syncUri.ifBlank { return })
        val existing = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0) }
        var msg = "File di sincronizzazione creato"
        if (existing.any { !it.toInt().toChar().isWhitespace() }) {
            val r = inspect(CsvLike.decode(existing))
            if (!r.plan.isEmpty) { restoreUc(r.envelope, RestoreMode.Merge); msg = "Sincronizzato: " + r.plan.summary().joinToString(" · ") } else msg = "Già allineato"
        }
        withContext(Dispatchers.IO) { write(uri, backupJsonBytes()) }
        settings.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
        messenger.show(msg)
    }

    // ---- spazio e dati ----
    fun deleteAllDocuments() = run("Documenti eliminati") { docs.observeAll().first().forEach { deleteDoc(it.id) }; refreshStorage() }
    fun deleteAutoBackups() = run("Backup automatici eliminati") { backup.listAutoBackups().forEach { it.delete() }; refreshStorage() }
    fun autoBackupNow() = run("Backup automatico creato") { backup.autoBackup(settings.get().autoBackupKeep); settings.update { it.copy(lastAutoBackupAt = System.currentTimeMillis()) }; refreshStorage() }
    fun autoBackups(): List<File> = backup.listAutoBackups()
    fun resetAll() = run("Dati azzerati: puoi annullare dal menu Dati") { backup.resetAll() }

    /** Cosa si tiene e cosa si cancella se si ricomincia dagli estratti (nessuna modifica). */
    suspend fun restartPreview(keepManual: Boolean) = restartUc.preview(keepManual)

    /** Cancella le righe importate tenendo i dettagli degli scontrini (che tornano «in attesa dell'estratto»); prima salva uno snapshot per «Annulla». */
    fun restartFromStatements(keepManual: Boolean) = viewModelScope.launch {
        try {
            val r = restartUc(keepManual)
            messenger.show("Fatto: ${r.deleted} movimenti cancellati, ${r.converted} con dettagli in attesa dell'estratto, ${r.keptProvisional + r.keptManual} tenuti. Ora importa gli estratti da Altro › Importa. Per tornare indietro: menu ⋮ › Annulla l'ultima operazione")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}

private object CsvLike { fun decode(b: ByteArray): String = it.spese.domain.importing.CsvParser.decode(b) }
