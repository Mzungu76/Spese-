package it.spese.feature.simulation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.FilterStore
import it.spese.app.common.UiMessenger
import it.spese.core.common.Money
import it.spese.core.model.*
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.*
import it.spese.domain.simulation.*
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PlanState(
    val data: AppData = AppData(),
    val filter: TxFilter = TxFilter(),
    /** Scenario in modifica (le modifiche sono salvate subito, come nella versione HTML). */
    val scenario: Scenario? = null,
    val context: PlanContext? = null,
    val result: SimulationResult? = null,
    val comparison: List<ComparisonRow> = emptyList(),
    val compareResults: List<ScenarioOutcome> = emptyList(),
    val compareIds: Set<Long> = emptySet(),
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlanViewModel @Inject constructor(
    data: StateFlow<AppData>, filters: FilterStore, private val scenarios: ScenarioRepository, private val categories: CategoryRepository,
    private val messenger: UiMessenger,
) : ViewModel() {
    private val activeId = MutableStateFlow<Long?>(null)
    private val draft = MutableStateFlow<Scenario?>(null)
    private val compareIds = MutableStateFlow<Set<Long>>(emptySet())
    private var saveJob: Job? = null

    val state: StateFlow<PlanState> = combine(data, filters.filter, activeId, draft, compareIds) { d, f, id, dr, cmp -> arrayOf(d, f, id, dr, cmp) }
        .mapLatest { a ->
            @Suppress("UNCHECKED_CAST")
            val d = a[0] as AppData; val f = a[1] as TxFilter; val id = a[2] as Long?; val dr = a[3] as Scenario?; val cmp = a[4] as Set<Long>
            if (!d.loaded) return@mapLatest PlanState(d, f)
            withContext(Dispatchers.Default) {
                val stored = d.scenarios.firstOrNull { it.id == id } ?: d.baseScenario
                val sc = dr?.takeIf { it.id == stored?.id } ?: stored ?: return@withContext PlanState(d, f)
                val ctx = SimulationFactory.context(d, f, sc)
                val result = SimulationFactory.run(ctx, sc)
                val outcomes = d.scenarios.filter { it.id in cmp || it.isBase && cmp.isNotEmpty() }.map { ScenarioOutcome(it.name, SimulationFactory.run(SimulationFactory.context(d, f, it), it)) }
                PlanState(d, f, sc, ctx, result, ScenarioComparison.compare(outcomes), outcomes, cmp)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlanState())

    /** Applica una modifica allo scenario attivo: aggiornamento immediato dell'anteprima, salvataggio dopo 400 ms. */
    fun edit(t: (Scenario) -> Scenario) {
        val cur = state.value.scenario ?: return
        val next = t(draft.value?.takeIf { it.id == cur.id } ?: cur)
        draft.value = next
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(400); scenarios.save(next) }
    }

    fun setCut(categoryId: Long?, pct: Int) { val id = categoryId ?: return; edit { s -> s.copy(cuts = s.cuts.toMutableMap().also { if (pct == 0) it.remove(id) else it[id] = pct }) } }
    fun resetCuts() = edit { it.copy(cuts = emptyMap()) }
    fun setGoal(g: SavingsGoal) = edit { it.copy(goal = g) }
    fun setIncomeDelta(v: BigDecimal) = edit { it.copy(incomeDelta = v) }
    fun toggleRemoved(key: String) = edit { s -> s.copy(removedRecurring = if (key in s.removedRecurring) s.removedRecurring - key else s.removedRecurring + key) }

    fun autoCut() {
        val st = state.value; val ctx = st.context ?: return; val sc = st.scenario ?: return
        val ac = PlanEngine.autoCut(ctx.base, PlanEngine.goalMonthly(sc.goal)) ?: return
        edit { s -> s.copy(cuts = ctx.base.categories.filter { it.flexible && it.categoryId != null }.associate { it.categoryId!! to ac.percent }) }
        messenger.show(if (!ac.sufficient) "Anche azzerando le voci comprimibili non basta: serve agire su altre voci o sulle entrate" else "Taglio del ${ac.percent}% sulle voci comprimibili")
    }

    /** Cambi di budget che il pulsante `mode` («avg», «cut», «clear») farebbe, con le spese fisse segnate (vanno confermate). */
    fun budgetChanges(mode: String): List<it.spese.domain.finance.BudgetChange> {
        val st = state.value; val ctx = st.context ?: return emptyList()
        return PlanEngine.budgetChanges(ctx.base, st.scenario?.cuts ?: emptyMap(), mode, st.data.categories)
    }

    fun applyBudgetChanges(changes: List<it.spese.domain.finance.BudgetChange>) = viewModelScope.launch {
        if (changes.isEmpty()) { messenger.show("Nessun budget da cambiare"); return@launch }
        changes.forEach { categories.setBudget(it.categoryId, it.new) }
        messenger.show(if (changes.size == 1) "Budget di «${changes[0].name}» aggiornato" else "Budget aggiornati su ${changes.size} categorie")
    }
    fun setBudget(categoryId: Long, v: BigDecimal?) = viewModelScope.launch { categories.setBudget(categoryId, v) }

    fun select(id: Long) { saveJob?.cancel(); draft.value = null; activeId.value = id }
    fun saveAsNew(name: String) = viewModelScope.launch {
        val cur = state.value.scenario ?: return@launch
        val id = scenarios.save(cur.copy(id = 0, name = name.trim(), isBase = false))
        draft.value = null; activeId.value = id; messenger.show("Scenario \"${name.trim()}\" salvato")
    }
    fun rename(name: String) = edit { it.copy(name = name) }
    fun delete(id: Long) = viewModelScope.launch { scenarios.delete(id); if (activeId.value == id) { draft.value = null; activeId.value = null }; compareIds.value = compareIds.value - id }
    fun toggleCompare(id: Long) { compareIds.value = compareIds.value.let { if (id in it) it - id else it + id } }
}
