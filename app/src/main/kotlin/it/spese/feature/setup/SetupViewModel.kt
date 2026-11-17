package it.spese.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.model.GoalMode
import it.spese.core.model.Scenario
import it.spese.core.model.SavingsGoal
import it.spese.domain.finance.BudgetProposal
import it.spese.domain.finance.SetupAdvisor
import it.spese.domain.finance.SetupProposal
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.repository.ScenarioRepository
import it.spese.domain.repository.SettingsRepository
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupState(val loaded: Boolean = false, val proposal: SetupProposal? = null)

/** Configurazione guidata: propone entrate, obiettivo e budget dai movimenti; nulla è salvato finché l'utente non conferma. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModel @Inject constructor(
    private val data: StateFlow<AppData>, private val scenarios: ScenarioRepository, private val categories: CategoryRepository,
    private val settings: SettingsRepository, private val messenger: UiMessenger,
) : ViewModel() {
    val state: StateFlow<SetupState> = data.mapLatest { d ->
        if (!d.loaded) SetupState() else withContext(Dispatchers.Default) { SetupState(true, SetupAdvisor.propose(d)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SetupState())

    fun apply(income: BigDecimal, goal: BigDecimal, budgets: List<BudgetProposal>, onDone: () -> Unit) = viewModelScope.launch {
        try {
            val base = data.value.baseScenario ?: Scenario(0, "Piano attuale", isBase = true)
            scenarios.save(base.copy(goal = SavingsGoal(GoalMode.MONTHLY, goal, base.goal.months, income)))
            budgets.forEach { categories.setBudget(it.categoryId, it.suggested) }
            settings.update { it.copy(setupDone = true) }
            messenger.show("Piano impostato" + if (budgets.isNotEmpty()) " con ${budgets.size} budget" else "")
            onDone()
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }

    fun skip(onDone: () -> Unit) = viewModelScope.launch { settings.update { it.copy(setupDone = true) }; onDone() }
}
