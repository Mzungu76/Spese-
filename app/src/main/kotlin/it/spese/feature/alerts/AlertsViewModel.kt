package it.spese.feature.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.app.common.UiMessenger
import it.spese.core.model.Due
import it.spese.core.model.DueFrequency
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import it.spese.domain.repository.DueRepository
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScheduleRow(val name: String, val amount: BigDecimal, val date: LocalDate, val dueId: Long?, val daysLeft: Int, val frequency: DueFrequency?)

data class AlertsState(
    val data: AppData = AppData(), val alerts: List<AlertItem> = emptyList(), val usage: List<BudgetUsage> = emptyList(),
    val schedule: List<ScheduleRow> = emptyList(), val next30: BigDecimal = BigDecimal.ZERO, val today: LocalDate = LocalDate.now(), val accountName: String? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModel @Inject constructor(data: StateFlow<AppData>, filters: FilterStore, private val dues: DueRepository, private val messenger: UiMessenger) : ViewModel() {
    val state: StateFlow<AlertsState> = combine(data, filters.filter) { d, f -> d to f }.mapLatest { (d, f) ->
        withContext(Dispatchers.Default) {
            val tx = d.transactions.filter { f.accountId == null || it.accountId == f.accountId }
            val today = AlertEngine.referenceToday(LocalDate.now(), tx)
            val excl = RecurringDetector.defaultExclusions(d.categories)
            val alerts = AlertEngine.compute(tx, d.categories, d.goal, d.dues, today, excl, d.calendar)
            val usage = AlertEngine.budgetUsage(tx, d.categories, d.calendar.monthOf(today), d.calendar)
            val rec = RecurringDetector.detect(tx, excl)
            val rows = (d.dues.map { ScheduleRow(it.name, it.amount, it.next, it.id, ChronoUnit.DAYS.between(today, it.next).toInt(), it.frequency) } +
                rec.map { ScheduleRow(it.merchant, it.mean, it.next, null, ChronoUnit.DAYS.between(today, it.next).toInt(), null) }).sortedBy { it.date }
            AlertsState(d, alerts, usage, rows, rows.filter { it.daysLeft in 0..30 }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }, today, d.accountsById[f.accountId]?.name)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertsState())

    fun addDue(name: String, amount: BigDecimal, date: LocalDate, freq: DueFrequency) = viewModelScope.launch { dues.insert(Due(0, "", name.trim(), amount, date, freq)); messenger.show("Scadenza aggiunta") }
    fun delete(id: Long) = viewModelScope.launch { dues.delete(id) }
    fun markPaid(id: Long) = viewModelScope.launch {
        val d = state.value.data.dues.firstOrNull { it.id == id } ?: return@launch
        val next = PlanEngine.advanceDue(d)
        if (next == null) dues.delete(id) else dues.update(d.copy(next = next))
        messenger.show(if (next == null) "Scadenza completata" else "Prossima scadenza: ${it.spese.core.common.Dates.fmt(next)}")
    }
}
