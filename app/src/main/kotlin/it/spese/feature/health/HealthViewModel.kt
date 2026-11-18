package it.spese.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.core.common.Money
import it.spese.core.model.Goal
import it.spese.core.model.RecurringRule
import it.spese.core.model.Valuation
import it.spese.domain.health.HealthEngine
import it.spese.domain.health.HealthReport
import it.spese.domain.health.IncomeNature
import it.spese.domain.health.IncomeNatureRepository
import it.spese.domain.finance.SubscriptionReviewRepository
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.repository.GoalRepository
import it.spese.domain.repository.RecurringRepository
import it.spese.domain.repository.ValuationRepository
import java.math.BigDecimal
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Il parere sulla salute del bilancio, calcolato fuori dal thread principale; condiviso da Home e dalla schermata di dettaglio. */
class HealthSource @Inject constructor(
    data: StateFlow<AppData>, goals: GoalRepository, valuations: ValuationRepository, recurring: RecurringRepository, review: SubscriptionReviewRepository, incomeNature: IncomeNatureRepository,
) {
    private data class Inputs(val d: AppData, val g: List<Goal>, val v: List<Valuation>, val r: List<RecurringRule>, val ignored: Set<String>)
    private val inputs = combine(data, goals.observeAll(), valuations.observeAll(), recurring.observeAll(), review.observeIgnored()) { d, g, v, r, ign -> Inputs(d, g, v, r, ign) }

    val report: Flow<HealthReport?> = combine(inputs, incomeNature.observe()) { i, ov -> runCatching { HealthEngine.forApp(i.d, i.g, i.v, i.r, i.ignored, ov) }.getOrNull() }.flowOn(Dispatchers.Default)
    val history: Flow<List<Pair<YearMonth, Int>>> = combine(data, valuations.observeAll(), incomeNature.observe()) { d, v, ov ->
        if (!d.loaded) emptyList() else runCatching { HealthEngine.history(d, v, ov) }.getOrDefault(emptyList())
    }.flowOn(Dispatchers.Default)
}

@HiltViewModel
class HealthViewModel @Inject constructor(
    source: HealthSource, private val incomeNature: IncomeNatureRepository, private val categories: CategoryRepository, private val messenger: UiMessenger,
) : ViewModel() {
    val report: StateFlow<HealthReport?> = source.report.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val history: StateFlow<List<Pair<YearMonth, Int>>> = source.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Correggi la natura di una entrata (fissa/variabile); `null` = torna al riconoscimento automatico. */
    fun setIncomeNature(categoryId: Long, nature: IncomeNature?) = viewModelScope.launch { incomeNature.set(categoryId, nature) }

    /** Imposta il budget suggerito su una categoria: solo quando l'utente lo chiede. */
    fun setBudget(categoryId: Long, name: String, amount: BigDecimal) = viewModelScope.launch {
        try { categories.setBudget(categoryId, amount); messenger.show("Budget di «$name» impostato a ${Money.eur0(amount)} al mese") }
        catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
