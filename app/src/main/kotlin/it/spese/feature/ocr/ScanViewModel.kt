package it.spese.feature.ocr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.*
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface ScanStage {
    data object Capture : ScanStage
    data object Processing : ScanStage
    data class Review(val result: ScanResult) : ScanStage
    data class Failed(val message: String) : ScanStage
}

data class ScanForm(
    val description: String = "", val amountText: String = "", val isIncome: Boolean = false, val date: LocalDate = LocalDate.now(),
    val accountId: Long? = null, val categoryId: Long? = null, val notes: String = "", val error: String? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val app: Application, val data: StateFlow<AppData>, private val scan: ScanReceiptUseCase, private val saveTx: SaveTransactionUseCase,
    private val deleteDoc: DeleteDocumentUseCase, private val messenger: UiMessenger,
) : AndroidViewModel(app) {
    private val _stage = MutableStateFlow<ScanStage>(ScanStage.Capture)
    val stage: StateFlow<ScanStage> = _stage.asStateFlow()
    private val _form = MutableStateFlow(ScanForm())
    val form: StateFlow<ScanForm> = _form.asStateFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /** Elabora l'immagine (percorso o URI): archivia localmente, esegue l'OCR sul dispositivo e interpreta il testo. Nessun movimento è creato qui. */
    fun process(source: String, deleteSource: Boolean = false) = viewModelScope.launch {
        _stage.value = ScanStage.Processing
        try {
            val r = scan(source)
            val d = r.data
            val notes = buildString {
                d.vat?.let { append("IVA: ").append(Money.eur(it)).append('\n') }
                if (d.items.isNotEmpty()) append("Voci: ").append(d.items.joinToString("; ") { "${it.description} ${Money.eur(it.amount)}" })
            }.trim()
            _form.value = ScanForm(d.description, d.total?.toPlainString()?.replace('.', ',') ?: "", false, d.date ?: LocalDate.now(),
                data.value.accounts.firstOrNull { !it.archived }?.id, null, notes)
            _stage.value = ScanStage.Review(r)
        } catch (e: Exception) { _stage.value = ScanStage.Failed(e.message ?: "Riconoscimento non riuscito") }
        finally { if (deleteSource && !source.startsWith("content:")) File(source).delete() }
    }

    fun edit(t: (ScanForm) -> ScanForm) { _form.value = t(_form.value).copy(error = null) }

    fun confirm() = viewModelScope.launch {
        val r = (_stage.value as? ScanStage.Review)?.result ?: return@launch
        val f = _form.value
        val amt = NumberParser.parseMoney(f.amountText)?.abs()
        if (amt == null || amt.signum() == 0) { _form.value = f.copy(error = "Inserisci l'importo."); return@launch }
        if (f.description.isBlank()) { _form.value = f.copy(error = "Inserisci una descrizione."); return@launch }
        val acc = f.accountId ?: run { _form.value = f.copy(error = "Scegli un conto."); return@launch }
        try {
            saveTx(TransactionDraft(0, acc, f.date, f.date, f.description, if (f.isIncome) amt else amt.negate(), f.categoryId, null, f.notes, r.document.id, it.spese.core.model.TxOrigin.OCR))
            messenger.show("Movimento salvato con il documento allegato"); _stage.value = ScanStage.Capture; _saved.tryEmit(Unit)
        } catch (e: Exception) { _form.value = f.copy(error = e.message) }
    }

    fun discard() = viewModelScope.launch {
        (_stage.value as? ScanStage.Review)?.result?.let { deleteDoc(it.document.id) }
        _stage.value = ScanStage.Capture
    }
    fun back() { _stage.value = ScanStage.Capture }
}
