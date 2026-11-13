package it.spese.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.domain.finance.DashboardCalculator
import it.spese.domain.finance.DashboardModel
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class DashboardState(val data: AppData = AppData(), val model: DashboardModel? = null, val filter: TxFilter = TxFilter())

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(data: StateFlow<AppData>, filters: FilterStore) : ViewModel() {
    val state: StateFlow<DashboardState> = combine(data, filters.filter) { d, f -> d to f }
        .mapLatest { (d, f) -> DashboardState(d, if (d.loaded) withContext(Dispatchers.Default) { DashboardCalculator.compute(d, f) } else null, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
}
