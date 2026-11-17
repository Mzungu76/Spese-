package it.spese.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

enum class TrendSort(val label: String) { IMPACT("Impatto"), AMOUNT("Importo"), CHANGE("Variazione") }

data class TrendsState(val data: AppData = AppData(), val filter: TxFilter = TxFilter(), val report: TrendReport? = null, val sort: TrendSort = TrendSort.IMPACT)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel @Inject constructor(data: StateFlow<AppData>, private val filters: FilterStore) : ViewModel() {
    private val sort = MutableStateFlow(TrendSort.IMPACT)

    val state: StateFlow<TrendsState> = combine(data, filters.filter, sort) { d, f, s -> Triple(d, f, s) }.mapLatest { (d, f, s) ->
        if (!d.loaded) TrendsState(d, f)
        else withContext(Dispatchers.Default) {
            val r = CategoryTrendEngine.compute(d, f)
            TrendsState(d, f, r?.copy(categories = sorted(r.categories, s)), s)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrendsState())

    private fun sorted(l: List<CategoryTrend>, s: TrendSort) = when (s) {
        TrendSort.IMPACT -> l.sortedByDescending { it.projection(6)?.balanceImpact?.abs() }
        TrendSort.AMOUNT -> l.sortedByDescending { it.periodAvg }
        TrendSort.CHANGE -> l.sortedByDescending { it.changeVsHistory?.abs() ?: java.math.BigDecimal.ZERO }
    }

    fun setSort(s: TrendSort) { sort.value = s }
    fun setPeriod(p: Period) = filters.update { it.copy(period = p) }
}
