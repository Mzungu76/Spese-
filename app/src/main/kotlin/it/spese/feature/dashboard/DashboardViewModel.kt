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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardState(
    val data: AppData = AppData(), val model: DashboardModel? = null, val filter: TxFilter = TxFilter(), val buckets: List<Bucket> = emptyList(),
    /** Confronto di categorie: modalità attiva, scelte e risultato (null senza scelte). */
    val compareMode: Boolean = false, val selected: Set<Long> = emptySet(), val compare: CategoryComparison? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel @Inject constructor(data: StateFlow<AppData>, private val filters: FilterStore, private val categories: it.spese.domain.repository.CategoryRepository, private val messenger: it.spese.app.common.UiMessenger,
    captureConfig: it.spese.domain.repository.CaptureConfigRepository) : ViewModel() {
    /** Notifiche non riconosciute accumulate (0 se sono poche: il promemoria compare solo oltre la soglia). */
    val pendingNotifications: StateFlow<Int> = captureConfig.observe().map { if (it.needsReview) it.unrecognized.size else 0 }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val compareMode = MutableStateFlow(false)

    val state: StateFlow<DashboardState> = combine(data, filters.filter, selected, compareMode) { d, f, sel, cm -> arrayOf(d, f, sel, cm) }
        .mapLatest { a ->
            @Suppress("UNCHECKED_CAST")
            val d = a[0] as AppData; val f = a[1] as TxFilter; val sel = a[2] as Set<Long>; val cm = a[3] as Boolean
            if (!d.loaded) DashboardState(d, null, f)
            else withContext(Dispatchers.Default) {
                val m = DashboardCalculator.compute(d, f)
                DashboardState(d, m, f, TrendBuckets.compute(m.filtered, f.calendar), cm, sel, CategoryComparisonEngine.compute(d, f, sel))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    /** Entra o esce dal confronto di categorie; uscendo si azzera la scelta. */
    fun setCompareMode(on: Boolean) { compareMode.value = on; if (!on) selected.value = emptySet() }
    fun toggleCategory(id: Long) { selected.value = selected.value.let { if (id in it) it - id else it + id }; compareMode.value = true }
    fun clearSelection() { selected.value = emptySet() }

    fun setPeriod(p: Period) = filters.update { it.copy(period = p) }

    /** Imposta la natura (fissa / necessaria / discrezionale) a più categorie in una volta. */
    fun applyNatures(natures: Map<Long, it.spese.core.model.SpendNature>) = viewModelScope.launch {
        try {
            categories.getAll().filter { it.id in natures }.forEach { c -> categories.update(c.copy(nature = natures[c.id])) }
            messenger.show(if (natures.size == 1) "Natura impostata" else "Natura impostata su ${natures.size} categorie")
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
