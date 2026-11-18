package it.spese.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.model.Goal
import it.spese.domain.finance.BudgetPlan
import it.spese.domain.finance.BudgetSuggester
import it.spese.domain.goals.GoalEngine
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.repository.GoalRepository
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class GoalsViewModel @Inject constructor(
    val data: StateFlow<AppData>, private val repo: GoalRepository, private val categories: CategoryRepository, private val messenger: UiMessenger,
) : ViewModel() {
    val goals: StateFlow<List<Goal>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Budget proposti dai dati e dagli obiettivi (null senza almeno un mese completo di movimenti). */
    val plan: StateFlow<BudgetPlan?> = combine(data, goals) { d, g -> if (!d.loaded) null else runCatching { BudgetSuggester.suggest(d, g) }.getOrNull() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun save(g: Goal) = viewModelScope.launch { try { repo.save(g) } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") } }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id); messenger.show("Obiettivo eliminato") }
    fun deposit(g: Goal, amount: BigDecimal) = viewModelScope.launch {
        val n = GoalEngine.deposit(g, amount)
        repo.save(n)
        messenger.show(if (GoalEngine.remaining(n).signum() == 0 && GoalEngine.remaining(g).signum() > 0) "Obiettivo «${g.name}» raggiunto!" else "Versamento registrato")
    }

    /** Imposta i budget scelti dall'utente nella proposta (solo le righe spuntate, con gli importi eventualmente modificati). */
    fun applyBudgets(changes: List<Pair<Long, BigDecimal>>) = viewModelScope.launch {
        try {
            changes.forEach { (id, v) -> categories.setBudget(id, v.takeIf { it.signum() > 0 }) }
            messenger.show("Budget impostati su ${changes.size} categorie")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
