package it.spese.domain.sharing

import it.spese.core.model.Goal
import it.spese.core.model.RecurringRule
import it.spese.domain.finance.SubscriptionReviewRepository
import it.spese.domain.health.IncomeNature
import it.spese.domain.health.IncomeNatureRepository
import it.spese.domain.repository.GoalRepository
import it.spese.domain.repository.RecurringRepository
import javax.inject.Inject

/** Ciò che serve oltre ai movimenti per il budget giornaliero: ricorrenze, abbonamenti ignorati, obiettivi e correzioni sulla natura delle entrate. */
data class BudgetInputs(
    val rules: List<RecurringRule> = emptyList(), val ignoredPatterns: Set<String> = emptySet(),
    val goals: List<Goal> = emptyList(), val incomeOverrides: Map<Long, IncomeNature> = emptyMap(),
    /** Margine di sicurezza scelto nelle impostazioni dell'app. */
    val marginPct: Int = 10,
)

interface BudgetInputsSource { suspend fun get(): BudgetInputs }

object EmptyBudgetInputs : BudgetInputsSource { override suspend fun get() = BudgetInputs() }

class RepoBudgetInputs @Inject constructor(
    private val recurring: RecurringRepository, private val review: SubscriptionReviewRepository, private val goals: GoalRepository, private val natures: IncomeNatureRepository, private val settings: it.spese.domain.repository.SettingsRepository,
) : BudgetInputsSource {
    override suspend fun get() = BudgetInputs(recurring.getAll(), review.getIgnored(), goals.getAll(), natures.get(), settings.get().budgetMarginPct)
}
