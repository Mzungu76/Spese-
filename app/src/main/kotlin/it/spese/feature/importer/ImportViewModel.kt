package it.spese.feature.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.importing.*
import it.spese.domain.repository.AccountRepository
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.*
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportState(
    val queue: List<List<StatementTable>> = emptyList(),   // tabelle per file
    val names: List<String> = emptyList(),
    val index: Int = 0,
    val sheet: Int = 0,
    val headerRow: Int = 0,
    val mapping: ColumnMapping = ColumnMapping(),
    val autoDetected: Boolean = false,
    val accountName: String = "",
    val preview: ImportPreview? = null,
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val table: StatementTable? get() = queue.getOrNull(index)?.getOrNull(sheet)
    val fileName: String get() = names.getOrNull(index) ?: ""
    val columns: Int get() = table?.rows?.take(60)?.maxOfOrNull { it.size } ?: 0
    fun columnTitle(i: Int): String {
        val h = table?.rows?.getOrNull(headerRow)?.getOrNull(i)?.toString()?.trim().orEmpty()
        return "${('A' + (i % 26))}${if (i >= 26) i / 26 else ""} · ${h.ifEmpty { "(vuota)" }}"
    }
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val app: Application, val data: StateFlow<AppData>, private val read: ReadStatementUseCase, private val previewUc: PreviewImportUseCase,
    private val commit: CommitImportUseCase, private val messenger: UiMessenger,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var lastAccount = ""

    fun pick(uris: List<Uri>) = viewModelScope.launch {
        _state.value = ImportState(busy = true)
        val tables = ArrayList<List<StatementTable>>(); val names = ArrayList<String>(); val log = ArrayList<String>()
        for (u in uris) {
            val name = withContext(Dispatchers.IO) { queryName(u) }
            try {
                val bytes = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(u)!!.use { it.readBytes() } }
                val t = withContext(Dispatchers.Default) { read(bytes, name) }
                if (t.isEmpty()) log.add("$name: nessun dato trovato") else { tables.add(t); names.add(name) }
            } catch (e: UnsupportedFormatException) { log.add("$name: ${e.message}") } catch (e: Exception) { log.add("$name: file non leggibile") }
        }
        _state.value = ImportState(queue = tables, names = names, log = log, finished = tables.isEmpty())
        if (tables.isNotEmpty()) startFile(0)
    }

    private fun queryName(u: Uri): String =
        app.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private fun startFile(i: Int) {
        val tables = _state.value.queue[i]; val t = tables[0]
        val det = t.detected
        val defaultAcc = lastAccount.ifBlank { data.value.accounts.firstOrNull { !it.archived }?.name ?: "Conto principale" }
        _state.value = _state.value.copy(index = i, sheet = 0, headerRow = det?.headerRow ?: 0, mapping = det?.mapping ?: ColumnMapping(), autoDetected = det != null,
            accountName = defaultAcc, preview = null, error = null, busy = false)
        refresh()
    }

    fun selectSheet(i: Int) {
        val t = _state.value.queue[_state.value.index][i]
        _state.value = _state.value.copy(sheet = i, headerRow = t.detected?.headerRow ?: 0, mapping = t.detected?.mapping ?: ColumnMapping(), autoDetected = t.detected != null)
        refresh()
    }
    fun setHeaderRow(r: Int) {
        val t = _state.value.table ?: return
        val g = HeaderDetector.guess(t.rows.getOrNull(r)?.map { it?.toString() ?: "" } ?: emptyList())
        _state.value = _state.value.copy(headerRow = r, mapping = g, autoDetected = false); refresh()
    }
    fun setMapping(m: ColumnMapping) { _state.value = _state.value.copy(mapping = m, autoDetected = false); refresh() }
    fun setAccount(n: String) { _state.value = _state.value.copy(accountName = n); refresh() }

    private var job: kotlinx.coroutines.Job? = null
    private fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val s = _state.value; val t = s.table ?: return@launch
            if (!s.mapping.isUsable) { _state.value = s.copy(preview = null, error = "Associa almeno data, descrizione e importo (oppure dare/avere)."); return@launch }
            try {
                val p = withContext(Dispatchers.Default) { previewUc(t, s.headerRow, s.mapping, s.accountName.ifBlank { "Conto principale" }) }
                _state.value = _state.value.copy(preview = p, error = if (p.rows.isEmpty()) "Nessuna riga valida con questa associazione di colonne." else null)
            } catch (e: Exception) { _state.value = _state.value.copy(preview = null, error = "Anteprima non disponibile: ${e.message}") }
        }
    }

    fun confirm() = viewModelScope.launch {
        val s = _state.value; val t = s.table ?: return@launch
        _state.value = s.copy(busy = true)
        try {
            val o = commit(t, s.headerRow, s.mapping, s.accountName)
            lastAccount = s.accountName.trim()
            val line = "${s.fileName} → ${s.accountName.trim()}: ${o.added} importati" + (if (o.duplicates > 0) ", ${o.duplicates} già presenti" else "") + (if (o.skipped > 0) ", ${o.skipped} righe scartate" else "")
            val log = s.log + line
            if (s.index + 1 < s.queue.size) { _state.value = _state.value.copy(log = log); startFile(s.index + 1) }
            else { _state.value = _state.value.copy(log = log, finished = true, busy = false); messenger.show("Importazione completata") }
        } catch (e: Exception) { _state.value = _state.value.copy(busy = false, error = e.message ?: "Importazione non riuscita") }
    }

    fun skipFile() { val s = _state.value; val log = s.log + "${s.fileName}: saltato"
        if (s.index + 1 < s.queue.size) { _state.value = s.copy(log = log); startFile(s.index + 1) } else _state.value = s.copy(log = log, finished = true) }

    fun reset() { _state.value = ImportState() }
}
