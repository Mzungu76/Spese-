package it.spese.domain.finance

import it.spese.core.model.SpendNature
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class BudgetProposal(val categoryId: Long, val name: String, val monthlyAvg: BigDecimal, val suggested: BigDecimal, val nature: SpendNature?)

/** Proposta di configurazione costruita dai movimenti: l'utente la rivede e la conferma, nulla è impostato in automatico. */
data class SetupProposal(
    val expectedIncome: BigDecimal,
    val avgExpense: BigDecimal,
    val fixed: List<RecurringItem>,
    val fixedMonthly: BigDecimal,
    val goalOptions: List<Pair<Int, BigDecimal>>,
    val recommendedGoal: BigDecimal,
    val budgets: List<BudgetProposal>,
    /** Quanto resta ogni mese per le spese variabili con l'obiettivo consigliato. */
    val freeMonthly: BigDecimal,
)

object SetupAdvisor {
    fun propose(data: AppData, today: LocalDate = LocalDate.now()): SetupProposal? {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        if (tx.isEmpty()) return null
        val s = StatsEngine.compute(tx)
        val ym = YearMonth.from(AlertEngine.referenceToday(today, tx))
        val closed = s.byMonth.keys.filter { it < ym }.sorted().takeLast(3)
        val months = StatsEngine.monthsSpan(tx)
        val income = if (closed.isNotEmpty()) closed.fold(BigDecimal.ZERO) { a, m -> a + s.byMonth.getValue(m).income }.divide(BigDecimal(closed.size), 2, RoundingMode.HALF_UP)
            else s.income.divide(months, 2, RoundingMode.HALF_UP)
        val expense = if (closed.isNotEmpty()) closed.fold(BigDecimal.ZERO) { a, m -> a + s.byMonth.getValue(m).expense }.divide(BigDecimal(closed.size), 2, RoundingMode.HALF_UP)
            else s.expense.divide(months, 2, RoundingMode.HALF_UP)

        val rec = RecurringDetector.detect(tx, RecurringDetector.defaultExclusions(data.categories))
        val fixedMonthly = rec.fold(BigDecimal.ZERO) { a, r -> a + r.mean }
        val options = PlanEngine.suggestions(income)
        val saving = income - expense
        // l'obiettivo più alto già alla portata; altrimenti il più prudente (10%)
        val recommended = options.lastOrNull { it.second <= saving }?.second ?: options.first().second

        val byId = data.categoriesById
        val sug = NatureSuggester.suggest(data.categories, tx, rec)
        val roll = CategoryTree.rollup(s.byCategory, byId)
        val budgets = roll.entries.filter { it.value.signum() > 0 && it.key != null }.mapNotNull { (id, total) ->
            val c = byId[id] ?: return@mapNotNull null
            if (c.systemKey != null) return@mapNotNull null
            val nature = c.nature ?: sug[c.id]?.nature
            if (nature == SpendNature.FIXED) return@mapNotNull null
            val avg = total.divide(months, 2, RoundingMode.HALF_UP)
            if (avg < BigDecimal(15)) return@mapNotNull null
            BudgetProposal(c.id, c.name, avg, PlanEngine.suggestedBudget(avg, if (nature == SpendNature.DISCRETIONARY) 10 else 0), nature)
        }.sortedByDescending { it.monthlyAvg }.take(8)

        return SetupProposal(income.setScale(0, RoundingMode.HALF_UP), expense, rec, fixedMonthly, options, recommended, budgets, income - recommended - fixedMonthly)
    }
}
