package it.spese.feature.dashboard

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardState(
    val data: AppData = AppData(), val model: DashboardModel? = null, val filter: TxFilter = TxFilter(), val buckets: List<Bucket> = emptyList(),
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(data: StateFlow<AppData>, private val filters: FilterStore, private val categories: it.spese.domain.repository.CategoryRepository, private val messenger: it.spese.app.common.UiMessenger) : ViewModel() {
    val state: StateFlow<DashboardState> = combine(data, filters.filter) { d, f -> d to f }
        .mapLatest { (d, f) ->
            if (!d.loaded) DashboardState(d, null, f)
            else withContext(Dispatchers.Default) {
                val m = DashboardCalculator.compute(d, f)
                DashboardState(d, m, f, TrendBuckets.compute(m.filtered))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    fun setPeriod(p: Period) = filters.update { it.copy(period = p) }

    /** Imposta la natura (fissa / necessaria / discrezionale) a più categorie in una volta. */
    fun applyNatures(natures: Map<Long, it.spese.core.model.SpendNature>) = viewModelScope.launch {
        try {
            categories.getAll().filter { it.id in natures }.forEach { c -> categories.update(c.copy(nature = natures[c.id])) }
            messenger.show(if (natures.size == 1) "Natura impostata" else "Natura impostata su ${natures.size} categorie")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
