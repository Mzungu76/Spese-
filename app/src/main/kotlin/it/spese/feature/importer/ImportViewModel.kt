package it.spese.feature.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.importing.*
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.*
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportStep { PICK, COLUMNS, REVIEW, DONE }

data class ImportState(
    val queue: List<List<StatementTable>> = emptyList(),
    val names: List<String> = emptyList(),
    val index: Int = 0,
    val sheet: Int = 0,
    val step: ImportStep = ImportStep.PICK,
    /** Riga delle intestazioni (-1 = nessuna); i dati iniziano nella riga successiva. */
    val headerRow: Int = 0,
    val roles: List<ColumnRole> = emptyList(),
    val options: ImportOptions = ImportOptions(),
    val analysis: TableAnalysis? = null,
    /** Nome del formato ricordato applicato automaticamente (null = riconosciuto dall'analisi). */
    val profileName: String? = null,
    val accountName: String = "",
    val preview: ImportPreview? = null,
    val remember: Boolean = true,
    val rememberName: String = "",
    val log: List<String> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val table: StatementTable? get() = queue.getOrNull(index)?.getOrNull(sheet)
    val fileName: String get() = names.getOrNull(index) ?: ""
    val mapping: ColumnMapping get() = ColumnMapping.fromRoles(roles)
    val firstDataRow: Int get() = headerRow + 1
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val app: Application, val data: StateFlow<AppData>, private val read: ReadStatementUseCase, private val previewUc: PreviewImportUseCase,
    private val commit: CommitImportUseCase, private val resolvePending: it.spese.domain.usecase.ResolvePendingAmountsUseCase, private val profiles: ImportProfilesUseCase, private val messenger: UiMessenger,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var lastAccount = ""
    private var job: Job? = null

    private fun upd(t: (ImportState) -> ImportState) { _state.value = t(_state.value) }

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
        if (tables.isEmpty()) _state.value = ImportState(log = log, step = ImportStep.DONE)
        else { _state.value = ImportState(queue = tables, names = names, log = log); startFile(0) }
    }

    private fun queryName(u: Uri): String =
        app.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"

    private suspend fun startFile(i: Int, sheet: Int = 0) {
        val s = _state.value
        val table = s.queue[i][sheet]
        val a = table.analysis
        val prof = profiles.match(table)
        val acc = lastAccount.ifBlank { data.value.accounts.firstOrNull { !it.archived }?.name ?: "Conto principale" }
        _state.value = s.copy(index = i, sheet = sheet, step = ImportStep.COLUMNS, analysis = a, profileName = prof?.name,
            headerRow = prof?.headerRow ?: a.headerRow, roles = prof?.roles ?: a.roles, options = prof?.options ?: a.options,
            accountName = acc, rememberName = prof?.name ?: suggestName(names = s.names[i]), remember = prof == null, preview = null, error = null, busy = false)
        refresh()
    }

    private fun suggestName(names: String) = names.substringBeforeLast('.').replace(Regex("[_\\-]+"), " ").replace(Regex("\\d{2,}"), "").trim().ifBlank { "Il mio estratto conto" }

    fun selectSheet(i: Int) = viewModelScope.launch { startFile(_state.value.index, i) }

    fun reanalyze() { val t = _state.value.table ?: return; val a = t.analysis
        upd { it.copy(headerRow = a.headerRow, roles = a.roles, options = a.options, profileName = null) }; refresh() }

    /** Assegna un ruolo a una colonna; i ruoli "unici" (data, importo…) passano da una colonna all'altra. */
    fun setRole(col: Int, role: ColumnRole) {
        val single = setOf(ColumnRole.DATE, ColumnRole.VALUE_DATE, ColumnRole.DESCRIPTION, ColumnRole.AMOUNT, ColumnRole.DEBIT, ColumnRole.CREDIT, ColumnRole.SIGN)
        upd { s ->
            val r = s.roles.toMutableList()
            if (role in single) for (i in r.indices) if (r[i] == role) r[i] = ColumnRole.IGNORE
            r[col] = role
            // se si sceglie un importo unico, dare/avere non hanno più senso (e viceversa)
            if (role == ColumnRole.AMOUNT) for (i in r.indices) if (r[i] == ColumnRole.DEBIT || r[i] == ColumnRole.CREDIT) r[i] = ColumnRole.IGNORE
            if (role == ColumnRole.DEBIT || role == ColumnRole.CREDIT) for (i in r.indices) if (r[i] == ColumnRole.AMOUNT) r[i] = ColumnRole.IGNORE
            s.copy(roles = r, profileName = null)
        }
        refresh()
    }

    fun setFirstDataRow(row: Int) { val max = (_state.value.table?.rows?.size ?: 1) - 1; upd { it.copy(headerRow = (row - 1).coerceIn(-1, max)) }; refresh() }
    fun setOptions(o: ImportOptions) { upd { it.copy(options = o) }; refresh() }
    fun setAccount(n: String) { upd { it.copy(accountName = n) }; refresh() }
    fun setRemember(b: Boolean) = upd { it.copy(remember = b) }
    fun setRememberName(n: String) = upd { it.copy(rememberName = n) }
    fun toReview() = upd { it.copy(step = ImportStep.REVIEW) }
    fun toColumns() = upd { it.copy(step = ImportStep.COLUMNS) }

    private fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val s = _state.value; val t = s.table ?: return@launch
            if (!s.mapping.isUsable) { upd { it.copy(preview = null, error = null) }; return@launch }
            try {
                val p = withContext(Dispatchers.Default) { previewUc(t, s.headerRow, s.mapping, s.accountName.ifBlank { "Conto principale" }, s.options) }
                upd { it.copy(preview = p, error = if (p.rows.isEmpty()) "Con queste colonne non trovo nessun movimento valido." else null) }
            } catch (e: Exception) { upd { it.copy(preview = null, error = "Anteprima non disponibile: ${e.message}") } }
        }
    }

    fun confirm() = viewModelScope.launch {
        val s = _state.value; val t = s.table ?: return@launch
        upd { it.copy(busy = true) }
        try {
            val o = commit(t, s.headerRow, s.mapping, s.accountName, s.options)
            lastAccount = s.accountName.trim()
            if (s.remember && s.rememberName.isNotBlank()) profiles.remember(s.rememberName.trim(), t, s.roles, s.options, s.headerRow)
            val closed = resolvePending()
            val line0 = "${s.fileName} → ${s.accountName.trim()}: ${o.added} importati" + (if (o.duplicates > 0) ", ${o.duplicates} già presenti" else "") + (if (o.skipped > 0) ", ${o.skipped} righe scartate" else "") + (if (o.reconciled > 0) ", ${o.reconciled} sostituiti ai movimenti da notifica" else "")
            val line = line0 + (if (closed > 0) ", $closed movimenti Poste senza importo chiusi" else "")
            val log = s.log + line
            if (s.index + 1 < s.queue.size) { upd { it.copy(log = log) }; startFile(s.index + 1) }
            else { upd { it.copy(log = log, step = ImportStep.DONE, busy = false) }; messenger.show("Importazione completata") }
        } catch (e: Exception) { upd { it.copy(busy = false, error = e.message ?: "Importazione non riuscita") } }
    }

    fun skipFile() = viewModelScope.launch {
        val s = _state.value; val log = s.log + "${s.fileName}: saltato"
        if (s.index + 1 < s.queue.size) { upd { it.copy(log = log) }; startFile(s.index + 1) } else upd { it.copy(log = log, step = ImportStep.DONE) }
    }

    fun reset() { _state.value = ImportState() }
}
