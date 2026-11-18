package it.spese.domain.simulation

import it.spese.core.model.Scenario
import it.spese.domain.finance.RecurringDetector
import it.spese.domain.finance.RecurringItem
import it.spese.domain.finance.StatsEngine
import it.spese.domain.finance.TxFilter
import it.spese.domain.finance.FilterEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Dati di partenza della simulazione, ricavati dai movimenti filtrati e indipendenti dallo scenario. */
data class PlanContext(
    val base: PlanBase,
    val recurring: List<RecurringItem>,
    val commitments: List<RecurringCommitment>,
    val scheduled: List<ScheduledOutflow>,
    val startingBalance: BigDecimal,
    val startMonth: YearMonth,
)

object SimulationFactory {
    fun context(data: AppData, filter: TxFilter, scenario: Scenario, realToday: LocalDate = LocalDate.now()): PlanContext {
        val filtered = FilterEngine.apply(data.transactions, filter, data.categoriesById)
        val base = PlanEngine.base(filtered, data.categories, scenario.goal)
        val accTx = data.transactions.filter { filter.accountId == null || it.accountId == filter.accountId }
        val rec = RecurringDetector.detect(accTx, RecurringDetector.defaultExclusions(data.categories))
        val commitments = rec.map { RecurringCommitment(it.key, it.merchant, it.mean, it.categoryId) }
        val scheduled = data.dues.map { ScheduledOutflow("due:${it.id}", it.name, it.amount, YearMonth.from(it.next), it.frequency) }
        val opening = data.accounts.filter { (filter.accountId == null || it.id == filter.accountId) && it.id !in data.managedAccountIds }.fold(BigDecimal.ZERO) { a, c -> a + c.openingBalance }
        val last = StatsEngine.lastDate(accTx)
        val start = YearMonth.from(last ?: realToday)
        return PlanContext(base, rec, commitments, scheduled, StatsEngine.balance(accTx, opening), start)
    }

    fun input(ctx: PlanContext, scenario: Scenario, horizonMonths: Int = 12): SimulationInput = SimulationInput(
        monthlyIncome = ctx.base.income,
        categories = ctx.base.categories.map { CategoryBaseline(it.categoryId, it.name, it.monthlyAvg, it.flexible) },
        goal = scenario.goal,
        cuts = scenario.cuts.mapKeys { it.key as Long? },
        incomeDelta = scenario.incomeDelta,
        recurring = ctx.commitments,
        removedRecurring = scenario.removedRecurring,
        scheduled = ctx.scheduled,
        startingBalance = ctx.startingBalance,
        startMonth = ctx.startMonth,
        horizonMonths = horizonMonths,
    )

    fun run(ctx: PlanContext, scenario: Scenario, horizonMonths: Int = 12): SimulationResult =
        SimulationEngine.run(input(ctx, scenario, horizonMonths))
}
