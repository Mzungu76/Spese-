package it.spese.feature.advice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.spese.app.common.UiMessenger
import it.spese.domain.advice.Insight
import it.spese.domain.advice.InsightEngine
import it.spese.domain.advice.LearnContext
import it.spese.domain.advice.MonthReview
import it.spese.domain.advice.MonthReviewEngine
import it.spese.domain.advice.ReviewAction
import it.spese.domain.finance.SubscriptionReviewRepository
import it.spese.domain.goals.GoalEngine
import it.spese.domain.networth.NetWorthEngine
import it.spese.domain.repository.AppData
import it.spese.domain.repository.CategoryRepository
import it.spese.domain.repository.GoalRepository
import it.spese.domain.repository.RecurringRepository
import it.spese.domain.repository.ValuationRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AdviceState(val loaded: Boolean = false, val review: MonthReview? = null, val insights: List<Insight> = emptyList(), val learn: LearnContext = LearnContext())

@HiltViewModel
class AdviceViewModel @Inject constructor(
    data: StateFlow<AppData>, private val goals: GoalRepository, valuations: ValuationRepository, recurring: RecurringRepository, subReview: SubscriptionReviewRepository,
    private val categories: CategoryRepository, private val messenger: UiMessenger,
) : ViewModel() {
    val state: StateFlow<AdviceState> = combine(data, goals.observeAll(), valuations.observeAll(), recurring.observeAll(), subReview.observeIgnored()) { d, g, v, r, ign ->
        if (!d.loaded) AdviceState()
        else runCatching {
            val review = MonthReviewEngine.compute(d, g)
            val net = NetWorthEngine.compute(d, g, v)
            AdviceState(true, review, InsightEngine.forApp(d, g, v, r, ign, limit = 8), LearnContext(net, review, GoalEngine.monthlyTotal(g)))
        }.getOrDefault(AdviceState(true))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AdviceState())

    private val appliedFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Azioni già applicate in questa schermata (per non proporle due volte). */
    val applied: StateFlow<Set<String>> = appliedFlow

    fun apply(a: ReviewAction) = viewModelScope.launch {
        try {
            when (a) {
                is ReviewAction.SetBudget -> {
                    val c = categories.getAll().firstOrNull { it.id == a.categoryId } ?: return@launch
                    categories.update(c.copy(budgetMonthly = a.amount.takeIf { it.signum() > 0 }))
                    messenger.show("Budget di «${a.categoryName}» impostato")
                }
                is ReviewAction.MoveToGoal -> {
                    val g = goals.getAll().firstOrNull { it.id == a.goalId } ?: return@launch
                    goals.save(GoalEngine.deposit(g, a.amount))
                    messenger.show("Versamento su «${a.goalName}» registrato")
                }
            }
            appliedFlow.value = appliedFlow.value + a.text
        } catch (e: Exception) { messenger.show(e.message ?: "Operazione non riuscita") }
    }
}
