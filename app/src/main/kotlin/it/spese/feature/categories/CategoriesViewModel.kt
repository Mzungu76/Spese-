package it.spese.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.model.*
import it.spese.domain.classification.Merchant
import it.spese.domain.classification.merchant
import it.spese.domain.repository.*
import it.spese.domain.usecase.*
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UnclassifiedRow(val merchant: String, val count: Int, val total: BigDecimal, val sampleDescription: String, val keyword: String)

data class CategoriesState(
    val data: AppData = AppData(),
    val txCount: Map<Long?, Int> = emptyMap(),
    val txTotal: Map<Long?, BigDecimal> = emptyMap(),
    val ruleHits: Map<Long, Int> = emptyMap(),
    val unclassified: List<UnclassifiedRow> = emptyList(),
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModel @Inject constructor(
    data: StateFlow<AppData>, private val categories: CategoryRepository, private val rules: RuleRepository,
    private val saveCategory: SaveCategoryUseCase, private val deleteCategory: DeleteCategoryUseCase, private val createRule: CreateRuleUseCase,
    private val reclassify: ReclassifyAllUseCase, private val messenger: UiMessenger,
) : ViewModel() {
    val state: StateFlow<CategoriesState> = data.mapLatest { d ->
        withContext(Dispatchers.Default) {
            val tx = d.transactions
            val unc = tx.filter { !it.manualCategory && it.ruleId == null && it.kind != TxKind.TRASFERIMENTO }.groupBy { it.merchant }
                .map { (m, l) -> UnclassifiedRow(m, l.size, l.fold(BigDecimal.ZERO) { a, t -> a + t.amount }, l.first().description, Merchant.suggestKeyword(m)) }
                .sortedByDescending { it.total.abs() }.take(25)
            CategoriesState(d, tx.groupingBy { it.categoryId }.eachCount(), tx.groupBy { it.categoryId }.mapValues { e -> e.value.fold(BigDecimal.ZERO) { a, t -> a + t.amount } },
                tx.mapNotNull { it.ruleId }.groupingBy { it }.eachCount(), unc)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoriesState())

    private fun run(block: suspend () -> Unit) = viewModelScope.launch { try { block() } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") } }

    fun save(c: Category) = run { saveCategory(c) }
    fun delete(id: Long) = run { deleteCategory(id); messenger.show("Categoria eliminata") }
    fun moveCategory(id: Long, delta: Int) = run {
        val ids = categories.getAll().map { it.id }.toMutableList(); val i = ids.indexOf(id); val j = i + delta
        if (i >= 0 && j in ids.indices) { ids.add(j, ids.removeAt(i)); categories.reorder(ids) }
    }
    fun nextColor(count: Int) = saveCategory.nextColor(count)

    fun saveRule(r: Rule) = run {
        require(r.keyword.isNotBlank() || r.min.signum() != 0 || r.max.signum() != 0) { "Indica una parola chiave e/o un importo." }
        if (r.id == 0L) createRule(r.keyword, r.categoryId, r.sign, r.min, r.max) else { rules.update(r); reclassify() }
    }
    fun deleteRule(id: Long) = run { rules.delete(id); reclassify() }
    fun moveRule(id: Long, delta: Int) = run {
        val ids = rules.getAll().map { it.id }.toMutableList(); val i = ids.indexOf(id); val j = i + delta
        if (i >= 0 && j in ids.indices) { ids.add(j, ids.removeAt(i)); rules.reorder(ids); reclassify() }
    }
    fun assignUnclassified(keyword: String, categoryId: Long) = run { createRule(keyword, categoryId); messenger.show("Regola \"$keyword\" creata") }
}
