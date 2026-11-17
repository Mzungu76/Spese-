package it.spese.feature.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

enum class AnalysisType(val label: String) {
    DONUT("Ciambella"), BARS("Barre"), STACKED("Impilate"), LINES("Linee"), CUMULATIVE("Cumulato"),
    PERIODS("Periodi"), CATEGORIES("Categorie"), TREND("Tendenze"), DISTRIBUTION("Distribuzione"), FORECAST("Previsioni"),
}

data class CompareWindow(val currentLabel: String, val previousLabel: String, val current: PeriodStats, val previous: PeriodStats)

data class AnalyticsState(
    val data: AppData = AppData(), val filter: TxFilter = TxFilter(), val type: AnalysisType = AnalysisType.DONUT, val selected: Set<Long?> = emptySet(),
    val model: DashboardModel? = null, val compare: CompareWindow? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel @Inject constructor(data: StateFlow<AppData>, filters: FilterStore) : ViewModel() {
    private val type = MutableStateFlow(AnalysisType.DONUT)
    private val selected = MutableStateFlow<Set<Long?>>(emptySet())

    val state: StateFlow<AnalyticsState> = combine(data, filters.filter, type, selected) { d, f, t, s -> arrayOf(d, f, t, s) }.mapLatest { a ->
        @Suppress("UNCHECKED_CAST")
        val d = a[0] as AppData; val f = a[1] as TxFilter; val t = a[2] as AnalysisType; val s = a[3] as Set<Long?>
        if (!d.loaded) return@mapLatest AnalyticsState(d, f, t, s)
        withContext(Dispatchers.Default) {
            val model = DashboardCalculator.compute(d, f)
            AnalyticsState(d, f, t, s, model, if (t == AnalysisType.PERIODS) compareWindow(d, f) else null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsState())

    fun setType(t: AnalysisType) { type.value = t }
    fun toggle(id: Long?) { selected.value = selected.value.let { if (id in it) it - id else it + id } }
    fun clear() { selected.value = emptySet() }

    /** Periodo selezionato contro il periodo immediatamente precedente della stessa durata (con "Tutto": ultimi 3 mesi contro i 3 precedenti). */
    private fun compareWindow(d: AppData, f: TxFilter): CompareWindow? {
        if (d.transactions.isEmpty()) return null
        val mn = d.transactions.minOf { it.bookingDate }; val mx = d.transactions.maxOf { it.bookingDate }
        var r = PeriodResolver.resolve(f.period, mn, mx, f.calendar)
        if (f.period == Period.All) r = PeriodResolver.resolve(Period.Last3Months, mn, mx, f.calendar)
        val from = r.from ?: return null; val to = r.to ?: return null
        val days = ChronoUnit.DAYS.between(from, to) + 1
        val pFrom = from.minusDays(days); val pTo = from.minusDays(1)
        fun stats(a: LocalDate, b: LocalDate) = StatsEngine.compute(FilterEngine.apply(d.transactions, f.copy(period = Period.Custom(a, b)), d.categoriesById))
        return CompareWindow("${it.spese.core.common.Dates.fmt(from)} – ${it.spese.core.common.Dates.fmt(to)}", "${it.spese.core.common.Dates.fmt(pFrom)} – ${it.spese.core.common.Dates.fmt(pTo)}", stats(from, to), stats(pFrom, pTo))
    }
}
