package it.spese.feature.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.app.common.UiMessenger
import it.spese.core.model.Category
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.usecase.SaveCategoryUseCase
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryDetailState(val data: AppData = AppData(), val detail: CategoryDetail? = null, val filter: TxFilter = TxFilter())

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryDetailViewModel @Inject constructor(
    savedState: SavedStateHandle, data: StateFlow<AppData>, private val filters: FilterStore,
    private val categories: CategoryRepository, private val save: SaveCategoryUseCase, private val messenger: UiMessenger,
    private val scenarios: it.spese.domain.repository.ScenarioRepository,
) : ViewModel() {
    private val latest = data
    val categoryId: Long = savedState.get<Long>("id") ?: 0L

    val state: StateFlow<CategoryDetailState> = combine(data, filters.filter) { d, f -> d to f }
        .mapLatest { (d, f) ->
            if (!d.loaded) CategoryDetailState(d, null, f)
            else withContext(Dispatchers.Default) { CategoryDetailState(d, CategoryDetailCalculator.compute(d, categoryId, f), f) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoryDetailState())

    fun setPeriod(p: Period) = filters.update { it.copy(period = p) }
    /** Mostra in Movimenti solo questa categoria (e le sue sottocategorie). */
    fun showInMovements(query: String = "") = filters.update { it.copy(categoryId = categoryId, kind = null, query = query) }

    fun setBudget(value: BigDecimal?) = viewModelScope.launch {
        try { categories.setBudget(categoryId, value); messenger.show(if (value == null) "Budget rimosso" else "Budget impostato") } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
    /** Salva il taglio nello scenario di base del Piano (0 = rimuove il taglio). */
    fun saveCutToPlan(percent: Int) = viewModelScope.launch {
        val base = latest.value.baseScenario ?: run { messenger.show("Nessuno scenario di base nel Piano"); return@launch }
        try {
            scenarios.save(base.copy(cuts = base.cuts.toMutableMap().also { if (percent == 0) it.remove(categoryId) else it[categoryId] = percent }))
            messenger.show(if (percent == 0) "Taglio rimosso dal Piano" else "Taglio del $percent% salvato nel Piano")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }

    fun setNature(n: it.spese.core.model.SpendNature?) = viewModelScope.launch {
        val c = categories.getAll().firstOrNull { it.id == categoryId } ?: return@launch
        try { categories.update(c.copy(nature = n)) } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
    fun update(c: Category) = viewModelScope.launch { try { save(c) } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") } }
}
