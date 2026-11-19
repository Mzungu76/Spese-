package it.spese.feature.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.finance.BudgetOverview
import it.spese.domain.finance.BudgetOverviewBuilder
import it.spese.domain.finance.BudgetPlan
import it.spese.domain.finance.BudgetSuggester
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.repository.GoalRepository
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    data: StateFlow<AppData>, goals: GoalRepository, private val categories: CategoryRepository, private val messenger: UiMessenger,
    natures: it.spese.domain.health.IncomeNatureRepository, settings: it.spese.domain.repository.SettingsRepository,
) : ViewModel() {
    /** Tutte le categorie principali di spesa con media mensile e budget attuale. */
    val overview: StateFlow<BudgetOverview?> = combine(data, goals.observeAll(), natures.observe()) { d, g, nat -> runCatching { BudgetOverviewBuilder.build(d, g, incomeOverrides = nat) }.getOrNull() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Proposta dagli obiettivi (null senza almeno un mese completo di movimenti). */
    val plan: StateFlow<BudgetPlan?> = combine(data, goals.observeAll(), natures.observe(), settings.observe().map { s -> s.budgetMarginPct }.distinctUntilChanged()) { d, g, nat, margin -> if (!d.loaded) null else runCatching { BudgetSuggester.suggest(d, g, incomeOverrides = nat, marginPct = margin) }.getOrNull() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Salva i budget scelti: `null` = nessun budget. Solo le categorie indicate cambiano. */
    fun save(changes: Map<Long, BigDecimal?>) = viewModelScope.launch {
        try {
            changes.forEach { (id, v) -> categories.setBudget(id, v?.takeIf { it.signum() > 0 }) }
            messenger.show(if (changes.size == 1) "Budget salvato" else "${changes.size} budget salvati")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
