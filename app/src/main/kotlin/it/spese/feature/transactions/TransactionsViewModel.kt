package it.spese.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.app.common.UiMessenger
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import it.spese.domain.finance.FilterEngine
import it.spese.domain.finance.ReviewEngine
import it.spese.domain.finance.ReviewItem
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import it.spese.domain.repository.TransactionRepository
import it.spese.domain.usecase.AssignCategoryUseCase
import it.spese.domain.usecase.ConfirmReviewUseCase
import it.spese.domain.usecase.SetSplitsUseCase
import it.spese.domain.usecase.DuplicateTransactionsUseCase
import it.spese.domain.usecase.ExportTransactionsUseCase
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortKey(val label: String) { DATE("Data"), DESCRIPTION("Descrizione"), CATEGORY("Categoria"), AMOUNT("Importo") }

data class TransactionsState(
    val data: AppData = AppData(), val items: List<Transaction> = emptyList(), val filter: TxFilter = TxFilter(),
    val sort: SortKey = SortKey.DATE, val descending: Boolean = true, val selection: Set<Long> = emptySet(),
    val income: BigDecimal = BigDecimal.ZERO, val expense: BigDecimal = BigDecimal.ZERO, val today: LocalDate = LocalDate.now(),
    /** Movimenti da controllare (sezione Verifica) e se la sezione è aperta. */
    val review: List<ReviewItem> = emptyList(), val reviewMode: Boolean = false,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel @Inject constructor(
    data: StateFlow<AppData>, private val filters: FilterStore, private val repo: TransactionRepository, private val assign: AssignCategoryUseCase,
    private val duplicate: DuplicateTransactionsUseCase, private val exporter: ExportTransactionsUseCase, private val messenger: UiMessenger,
    private val confirmReview: ConfirmReviewUseCase, private val setSplits: SetSplitsUseCase,
) : ViewModel() {
    private val reviewMode = MutableStateFlow(false)
    private val sort = MutableStateFlow(SortKey.DATE to true)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private var current: TransactionsState = TransactionsState()

    val state: StateFlow<TransactionsState> = combine(data, filters.filter, sort, selection, reviewMode) { d, f, s, sel, rm -> arrayOf(d, f, s, sel, rm) }
        .mapLatest { a ->
            @Suppress("UNCHECKED_CAST")
            val d = a[0] as AppData; val f = a[1] as TxFilter; val s = a[2] as Pair<SortKey, Boolean>; val sel = a[3] as Set<Long>; val rm = a[4] as Boolean
            withContext(Dispatchers.Default) {
                // i filtri guardano le parti (un prelievo diviso compare cercando una sua categoria), l'elenco mostra il movimento intero
                val parts = FilterEngine.apply(d.transactions, f, d.categoriesById, d.accounts.associate { it.id to it.name })
                val ids = parts.mapTo(HashSet()) { it.id }
                val filtered = d.ledger.filter { it.id in ids }
                val cmp: Comparator<Transaction> = when (s.first) {
                    SortKey.DATE -> compareBy<Transaction> { it.bookingDate }.thenBy { it.id }
                    SortKey.DESCRIPTION -> compareBy { it.description.lowercase() }
                    SortKey.CATEGORY -> compareBy { d.categoryName(it.categoryId) }
                    SortKey.AMOUNT -> compareBy { it.amount }
                }
                val sorted = filtered.sortedWith(if (s.second) cmp.reversed() else cmp)
                TransactionsState(d, sorted, f, s.first, s.second, sel.intersect(sorted.map { it.id }.toSet()),
                    parts.filter { it.kind == TxKind.ENTRATA }.fold(BigDecimal.ZERO) { a2, x -> a2 + x.amount },
                    parts.filter { it.kind == TxKind.SPESA }.fold(BigDecimal.ZERO) { a2, x -> a2 + x.amount },
                    it.spese.domain.finance.AlertEngine.referenceToday(LocalDate.now(), d.ledger),
                    ReviewEngine.items(d.ledger, d.splits, d.categories, d.rules), rm)
            }.also { current = it }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionsState())

    val searchRequested = filters.searchRequested
    fun consumeSearch() = filters.consumeSearch()
    fun setReviewMode(on: Boolean) { reviewMode.value = on }
    fun confirm(ids: Collection<Long>) = viewModelScope.launch { confirmReview(ids); messenger.show(if (ids.size == 1) "Confermato" else "Confermati ${ids.size} movimenti") }
    fun confirmAllReview() = confirm(current.review.map { it.tx.id })
    /** Cambia categoria dalla Verifica: la scelta dell'utente sostituisce divisioni e provvisorietà, quindi il movimento è controllato. */
    fun reassign(txId: Long, categoryId: Long) = viewModelScope.launch {
        try { setSplits(txId, emptyList()); assign(listOf(txId), categoryId); confirmReview(listOf(txId)) } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
    fun split(txId: Long, parts: List<Pair<Long?, BigDecimal>>) = viewModelScope.launch {
        try { setSplits(txId, parts); messenger.show(if (parts.isEmpty()) "Movimento ricomposto" else "Movimento diviso in ${parts.size} parti") } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
    fun setQuery(q: String) = filters.update { it.copy(query = q) }
    fun setSort(k: SortKey) { val (ck, desc) = sort.value; sort.value = if (ck == k) k to !desc else k to (k == SortKey.DATE || k == SortKey.AMOUNT) }
    fun toggle(id: Long) { selection.value = selection.value.let { if (id in it) it - id else it + id } }
    fun selectAll() { selection.value = current.items.map { it.id }.toSet() }
    fun clearSelection() { selection.value = emptySet() }

    fun assignToSelection(categoryId: Long) = viewModelScope.launch {
        val ids = selection.value; assign(ids, categoryId); messenger.show("Categoria assegnata a ${ids.size} movimenti"); clearSelection()
    }
    fun assignToAllFiltered(categoryId: Long) = viewModelScope.launch {
        val ids = current.items.map { it.id }; assign(ids, categoryId); messenger.show("Categoria assegnata a ${ids.size} movimenti")
    }
    fun deleteSelection() = viewModelScope.launch { val ids = selection.value; repo.delete(ids); messenger.show("Eliminati ${ids.size} movimenti"); clearSelection() }
    fun duplicateSelection() = viewModelScope.launch { val n = duplicate(selection.value); messenger.show("Duplicati $n movimenti"); clearSelection() }

    fun csvBytes(): ByteArray = exporter.csv(current.data, current.items)
    fun xlsxBytes(): ByteArray = exporter.xlsx(current.data, current.items)
}
