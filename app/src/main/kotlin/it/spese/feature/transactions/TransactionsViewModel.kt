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
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import it.spese.domain.repository.TransactionRepository
import it.spese.domain.usecase.AssignCategoryUseCase
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
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel @Inject constructor(
    data: StateFlow<AppData>, private val filters: FilterStore, private val repo: TransactionRepository, private val assign: AssignCategoryUseCase,
    private val duplicate: DuplicateTransactionsUseCase, private val exporter: ExportTransactionsUseCase, private val messenger: UiMessenger,
) : ViewModel() {
    private val sort = MutableStateFlow(SortKey.DATE to true)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private var current: TransactionsState = TransactionsState()

    val state: StateFlow<TransactionsState> = combine(data, filters.filter, sort, selection) { d, f, s, sel -> arrayOf(d, f, s, sel) }
        .mapLatest { a ->
            @Suppress("UNCHECKED_CAST")
            val d = a[0] as AppData; val f = a[1] as TxFilter; val s = a[2] as Pair<SortKey, Boolean>; val sel = a[3] as Set<Long>
            withContext(Dispatchers.Default) {
                val filtered = FilterEngine.apply(d.transactions, f, d.categoriesById)
                val cmp: Comparator<Transaction> = when (s.first) {
                    SortKey.DATE -> compareBy<Transaction> { it.bookingDate }.thenBy { it.id }
                    SortKey.DESCRIPTION -> compareBy { it.description.lowercase() }
                    SortKey.CATEGORY -> compareBy { d.categoryName(it.categoryId) }
                    SortKey.AMOUNT -> compareBy { it.amount }
                }
                val sorted = filtered.sortedWith(if (s.second) cmp.reversed() else cmp)
                TransactionsState(d, sorted, f, s.first, s.second, sel.intersect(sorted.map { it.id }.toSet()),
                    filtered.filter { it.kind == TxKind.ENTRATA }.fold(BigDecimal.ZERO) { a2, x -> a2 + x.amount },
                    filtered.filter { it.kind == TxKind.SPESA }.fold(BigDecimal.ZERO) { a2, x -> a2 + x.amount },
                    it.spese.domain.finance.AlertEngine.referenceToday(LocalDate.now(), d.transactions))
            }.also { current = it }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransactionsState())

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
