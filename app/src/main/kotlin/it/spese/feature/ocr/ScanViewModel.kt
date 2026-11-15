package it.spese.feature.ocr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.domain.repository.AppData
import it.spese.domain.classification.Classifier
import it.spese.domain.finance.CategoryTree
import it.spese.domain.ocr.ItemSuggester
import it.spese.domain.ocr.ParentChooser
import it.spese.domain.usecase.*
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
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
    /** Categoria scelta per ogni voce dello scontrino (stesso ordine delle voci; null = categoria principale). */
    val itemCategories: List<Long?> = emptyList(),
    /** false = l'utente rifiuta il collegamento a un movimento già presente e vuole comunque crearne uno nuovo. */
    val attach: Boolean = true,
    /** Categoria che le regole darebbero allo scontrino (serve a sapere dove proporre le nuove sottocategorie). */
    val autoCategoryId: Long? = null,
    /** Per ogni voce, la sottocategoria che non esiste ancora e che il dizionario propone di creare (null = nessuna). */
    val itemNew: List<String?> = emptyList(),
    /** Nomi delle sottocategorie da creare che l'utente ha lasciato selezionati. */
    val createNames: Set<String> = emptySet(),
    /** Categoria principale scelta per le nuove sottocategorie (null = quella della categoria dello scontrino). */
    val newParentId: Long? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val app: Application, val data: StateFlow<AppData>, private val scan: ScanReceiptUseCase, private val saveReceipt: SaveReceiptUseCase, private val memory: it.spese.domain.ocr.ItemMemoryRepository,
    private val deleteDoc: DeleteDocumentUseCase, private val messenger: UiMessenger, private val saveCategory: SaveCategoryUseCase,
) : AndroidViewModel(app) {
    private val _stage = MutableStateFlow<ScanStage>(ScanStage.Capture)
    val stage: StateFlow<ScanStage> = _stage.asStateFlow()
    private val _form = MutableStateFlow(ScanForm())
    val form: StateFlow<ScanForm> = _form.asStateFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /** Foto scattate o scelte per lo scontrino corrente, dall'alto verso il basso (file nella cache, eliminati a fine lavoro). */
    private val _pages = MutableStateFlow<List<String>>(emptyList())
    val pages: StateFlow<List<String>> = _pages.asStateFlow()

    /** Aggiunge una foto: un'immagine della galleria (URI) viene copiata nella cache così la miniatura e la lettura funzionano allo stesso modo. */
    fun addPage(source: String) = viewModelScope.launch {
        val path = if (source.startsWith("content:") || source.startsWith("file:")) withContext(Dispatchers.IO) {
            val f = File(app.cacheDir, "scan_${System.currentTimeMillis()}_${_pages.value.size}.jpg")
            app.contentResolver.openInputStream(android.net.Uri.parse(source))?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.absolutePath
        } else source
        _pages.value = _pages.value + path
    }
    fun removePage(index: Int) { _pages.value.getOrNull(index)?.let { File(it).delete() }; _pages.value = _pages.value.filterIndexed { i, _ -> i != index } }
    fun movePage(index: Int, delta: Int) {
        val l = _pages.value.toMutableList(); val to = index + delta
        if (index !in l.indices || to !in l.indices) return
        l.add(to, l.removeAt(index)); _pages.value = l
    }
    private fun clearPages() { _pages.value.forEach { File(it).delete() }; _pages.value = emptyList() }

    /** Movimenti già presenti (estratto conto, notifica…) che sembrano lo stesso acquisto: collegarli evita il doppione. */
    val candidates: StateFlow<List<it.spese.core.model.Transaction>> = combine(_form, data) { f, d ->
        val amt = NumberParser.parseMoney(f.amountText)?.abs()
        if (amt == null || amt.signum() == 0) emptyList()
        else ReceiptMatcher.candidates(d.ledger, if (f.isIncome) amt else amt.negate(), f.date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Legge tutte le foto aggiunte (una o più): archivia localmente, riconosce il testo sul dispositivo, unisce le righe e le interpreta. Nessun movimento è creato qui. */
    fun readPages() = viewModelScope.launch {
        val files = _pages.value
        if (files.isEmpty()) return@launch
        _stage.value = ScanStage.Processing
        try {
            val r = scan(files)
            val d = r.data
            val notes = buildString {
                d.vat?.let { append("IVA: ").append(Money.eur(it)).append('\n') }
                if (d.items.isNotEmpty()) {
                    val shown = d.items.take(12)
                    append("Voci: ").append(shown.joinToString("; ") { "${it.description} ${Money.eur(it.amount)}" })
                    if (d.items.size > shown.size) append("; … e altre ${d.items.size - shown.size} voci")
                }
            }.trim()
            val cats = data.value.categories; val byId = data.value.categoriesById
            val auto = Classifier(cats, data.value.rules).classify(d.description, (d.total ?: java.math.BigDecimal.ONE).negate(), null).categoryId
            val parent = CategoryTree.rootId(auto, byId)?.takeIf { p -> byId[p]?.let { c -> c.kind == it.spese.core.model.TxKind.SPESA && c.systemKey == null } == true }
            val descs = d.items.map { item -> item.description }
            // spesa fatta di cibo: la principale è «Supermercato/Alimentari», anche se le regole davano altro per il nome del negozio
            val chosenParent = ParentChooser.choose(descs, cats, parent)
            val sugg = ItemSuggester.suggest(descs, memory.get(), cats, chosenParent)
            _form.value = ScanForm(d.description, d.total?.toPlainString()?.replace('.', ',') ?: "", false, d.date ?: LocalDate.now(),
                data.value.accounts.firstOrNull { !it.archived }?.id, chosenParent?.takeIf { it != parent }, notes, itemCategories = sugg.map { it.categoryId },
                autoCategoryId = auto, itemNew = sugg.map { it.newName }, createNames = sugg.mapNotNull { it.newName }.toSet(), newParentId = chosenParent)
            _stage.value = ScanStage.Review(r)
            clearPages()
        } catch (e: Exception) { _stage.value = ScanStage.Failed(e.message ?: "Riconoscimento non riuscito") }
    }

    /** Una sola foto (percorso o URI): la aggiunge e la legge subito. */
    fun process(source: String, deleteSource: Boolean = false) = viewModelScope.launch {
        addPage(source).join()   // i file nella cache si eliminano da soli a fine lettura
        readPages().join()
    }

    /** Categoria principale in cui creare le nuove sottocategorie: quella scelta (o la sua principale), altrimenti quella che darebbero le regole. */
    fun parentFor(f: ScanForm): it.spese.core.model.Category? {
        val byId = data.value.categoriesById
        val root = f.newParentId ?: CategoryTree.rootId(f.categoryId ?: f.autoCategoryId, byId) ?: return null
        return byId[root]?.takeIf { c -> c.kind == it.spese.core.model.TxKind.SPESA && c.systemKey == null }
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
            val signed = if (f.isIncome) amt else amt.negate()
            // sottocategorie proposte dal dizionario: si creano ora (solo quelle lasciate selezionate) e si assegnano alle voci
            val parent = parentFor(f)
            val created = HashMap<String, Long>()
            if (parent != null && !f.isIncome) f.createNames.filter { n -> f.itemNew.contains(n) }.forEach { n ->
                val id = try { saveCategory(it.spese.core.model.Category(0, n, it.spese.core.model.TxKind.SPESA, false, parent.color, parentId = parent.id, nature = parent.nature)) }
                catch (e: Exception) { data.value.categories.firstOrNull { c -> c.name.equals(n, ignoreCase = true) }?.id ?: throw e }
                created[n] = id
            }
            val itemCats = r.data.items.indices.map { i -> f.itemCategories.getOrNull(i) ?: f.itemNew.getOrNull(i)?.let { created[it] } }
            val parts = ReceiptSplitPlanner.plan(amt, r.data.items, itemCats, f.categoryId ?: f.autoCategoryId, !f.isIncome)
            // l'app ricorda le scelte per le voci: la prossima volta le propone da sola
            memory.learn(r.data.items.mapIndexedNotNull { i, item -> itemCats.getOrNull(i)?.let { c -> it.spese.domain.ocr.ItemMemory.key(item.description).takeIf { k -> k.isNotBlank() }?.let { k -> k to c } } }.toMap())
            val existing = candidates.value.firstOrNull()?.takeIf { f.attach }
            saveReceipt(TransactionDraft(0, acc, f.date, f.date, f.description, signed, f.categoryId, null, f.notes, r.document.id, it.spese.core.model.TxOrigin.OCR), r.document.id, parts, existing?.id)
            messenger.show(when {
                existing != null -> "Scontrino collegato al movimento già presente: nessun doppione"
                parts.size >= 2 -> "Scontrino salvato e diviso in ${parts.size} categorie; l'estratto conto lo sostituirà senza perdere i dettagli"
                else -> "Movimento salvato con il documento allegato"
            })
            _stage.value = ScanStage.Capture; _saved.tryEmit(Unit)
        } catch (e: Exception) { _form.value = f.copy(error = e.message) }
    }

    fun discard() = viewModelScope.launch {
        (_stage.value as? ScanStage.Review)?.result?.let { deleteDoc(it.document.id) }
        _stage.value = ScanStage.Capture
    }
    fun back() { _stage.value = ScanStage.Capture }
    override fun onCleared() { clearPages(); super.onCleared() }
}
