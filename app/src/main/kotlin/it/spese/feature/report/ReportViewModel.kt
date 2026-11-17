package it.spese.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class ReportState(
    val loaded: Boolean = false,
    val months: List<YearMonth> = emptyList(),
    val selected: YearMonth? = null,
    val report: MonthlyReport? = null,
    val model: DashboardModel? = null,
)

/** "Il tuo mese": riepilogo di un mese chiuso, fondo di emergenza, spese annuali, consigli e lezione del giorno. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModel @Inject constructor(data: StateFlow<AppData>) : ViewModel() {
    private val month = MutableStateFlow<YearMonth?>(null)

    val state: StateFlow<ReportState> = combine(data, month) { d, m -> d to m }.mapLatest { (d, m) ->
        if (!d.loaded) ReportState()
        else withContext(Dispatchers.Default) {
            val model = DashboardCalculator.compute(d, TxFilter(period = Period.All))
            val tx = d.transactions
            val today = model.today
            val last = MonthlyReportBuilder.lastCompleteMonth(tx, today)
            val months = if (last == null) emptyList() else MonthlyReportBuilder.history(tx, last, 6).map { it.month }
            val sel = m?.takeIf { it in months } ?: last
            ReportState(true, months, sel, sel?.let { MonthlyReportBuilder.build(tx, d.categories, d.goal, it) }, model)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportState())

    fun select(m: YearMonth) { month.value = m }
}
