package it.spese.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.domain.classification.Merchant
import it.spese.domain.repository.*
import it.spese.domain.usecase.*
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class EditForm(
    val description: String = "", val amountText: String = "", val isIncome: Boolean = false, val accountId: Long? = null,
    val date: LocalDate = LocalDate.now(), val valueDate: LocalDate = LocalDate.now(), val categoryId: Long? = null, val manualCategory: Boolean = false,
    val merchant: String = "", val notes: String = "", val documentId: Long? = null, val origin: String = "", val error: String? = null, val loaded: Boolean = false,
)

@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    savedState: SavedStateHandle, val data: StateFlow<AppData>, private val saveTx: SaveTransactionUseCase, private val repo: TransactionRepository,
    private val duplicate: DuplicateTransactionsUseCase, private val createRule: CreateRuleUseCase, private val store: DocumentStore,
    private val docs: DocumentRepository, private val deleteDoc: DeleteDocumentUseCase, private val messenger: UiMessenger,
) : ViewModel() {
    val txId: Long = savedState.get<Long>("id") ?: 0L
    private val _form = MutableStateFlow(EditForm())
    val form: StateFlow<EditForm> = _form.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done.asSharedFlow()
    private val _docPath = MutableStateFlow<String?>(null)
    val documentPath: StateFlow<String?> = _docPath.asStateFlow()

    init {
        viewModelScope.launch {
            val d = data.first { it.loaded && it.accounts.isNotEmpty() }
            if (txId == 0L) {
                _form.value = EditForm(accountId = d.accounts.firstOrNull { !it.archived }?.id, loaded = true)
            } else {
                val t = repo.get(txId)
                if (t == null) { _done.tryEmit(Unit); return@launch }
                _form.value = EditForm(t.description, t.amount.abs().toPlainString().replace('.', ','), t.amount.signum() > 0, t.accountId, t.bookingDate, t.valueDate,
                    t.categoryId, t.manualCategory, t.merchantOverride ?: "", t.notes, t.documentId, t.origin.name, loaded = true)
                refreshDoc(t.documentId)
            }
        }
    }

    private suspend fun refreshDoc(id: Long?) { _docPath.value = id?.let { docs.get(it) }?.let { store.pathOf(it.fileName) } }
    fun edit(t: (EditForm) -> EditForm) { _form.value = t(_form.value).copy(error = null) }

    fun save() = viewModelScope.launch {
        val f = _form.value
        val amt = NumberParser.parseMoney(f.amountText)?.abs()
        when {
            f.description.isBlank() -> { edit { it.copy() }; _form.value = f.copy(error = "Inserisci una descrizione."); return@launch }
            amt == null || amt.signum() == 0 -> { _form.value = f.copy(error = "Inserisci un importo valido."); return@launch }
            f.accountId == null -> { _form.value = f.copy(error = "Scegli un conto."); return@launch }
        }
        try {
            saveTx(TransactionDraft(txId, f.accountId!!, f.date, f.valueDate, f.description, if (f.isIncome) amt!! else amt!!.negate(),
                f.categoryId.takeIf { f.manualCategory }, f.merchant, f.notes, f.documentId))
            messenger.show(if (txId == 0L) "Movimento aggiunto" else "Movimento salvato")
            _done.tryEmit(Unit)
        } catch (e: Exception) { _form.value = f.copy(error = e.message ?: "Salvataggio non riuscito.") }
    }

    fun delete() = viewModelScope.launch { repo.delete(listOf(txId)); messenger.show("Movimento eliminato"); _done.tryEmit(Unit) }
    fun duplicateIt() = viewModelScope.launch { duplicate(listOf(txId)); messenger.show("Movimento duplicato"); _done.tryEmit(Unit) }

    fun suggestedKeyword() = Merchant.suggestKeyword(_form.value.merchant.ifBlank { Merchant.extract(_form.value.description) })

    fun createRule(keyword: String, categoryId: Long) = viewModelScope.launch {
        try { createRule(keyword, categoryId, releaseTxId = txId.takeIf { it != 0L }); messenger.show("Regola \"$keyword\" creata"); _done.tryEmit(Unit) }
        catch (e: Exception) { messenger.show(e.message ?: "Regola non creata") }
    }

    fun attachImage(uri: String) = viewModelScope.launch {
        try {
            val ref = store.saveImage(uri); val id = docs.add(ref)
            if (txId != 0L) repo.setDocument(txId, id)
            _form.value = _form.value.copy(documentId = id); refreshDoc(id)
        } catch (e: Exception) { messenger.show("Immagine non valida") }
    }

    fun removeDocument() = viewModelScope.launch {
        val id = _form.value.documentId ?: return@launch
        if (txId != 0L) repo.setDocument(txId, null)
        deleteDoc(id); _form.value = _form.value.copy(documentId = null); _docPath.value = null
    }
}
