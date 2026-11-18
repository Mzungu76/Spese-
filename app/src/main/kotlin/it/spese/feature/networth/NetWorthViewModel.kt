package it.spese.feature.networth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.model.Valuation
import it.spese.domain.networth.NetWorthEngine
import it.spese.domain.networth.NetWorthReport
import it.spese.domain.repository.AppData
import it.spese.domain.repository.GoalRepository
import it.spese.domain.repository.ValuationRepository
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NetWorthViewModel @Inject constructor(
    data: StateFlow<AppData>, goals: GoalRepository, private val valuations: ValuationRepository, private val messenger: UiMessenger,
    layouts: it.spese.app.common.PageLayouts,
) : ViewModel() {
    val layout: StateFlow<List<it.spese.domain.widgets.WidgetInstance>> = layouts.state(it.spese.domain.widgets.WidgetPage.NETWORTH, viewModelScope)
    /** Null finché i dati non sono pronti o se non c'è alcun conto. */
    val report: StateFlow<NetWorthReport?> = combine(data, goals.observeAll(), valuations.observeAll()) { d, g, v ->
        if (!d.loaded) null else runCatching { NetWorthEngine.compute(d, g, v) }.getOrNull()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun saveValuation(accountId: Long, date: LocalDate, value: BigDecimal) = viewModelScope.launch {
        try { valuations.save(Valuation(accountId, date, value)); messenger.show("Valore aggiornato") } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
