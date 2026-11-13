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
import kotlinx.coroutines.withContext

data class DashboardState(
    val data: AppData = AppData(), val model: DashboardModel? = null, val filter: TxFilter = TxFilter(), val buckets: List<Bucket> = emptyList(),
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(data: StateFlow<AppData>, private val filters: FilterStore) : ViewModel() {
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
}
