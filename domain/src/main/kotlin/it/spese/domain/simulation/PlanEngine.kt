package it.spese.domain.simulation

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Due
import it.spese.core.model.DueFrequency
import it.spese.core.model.GoalMode
import it.spese.core.model.SavingsGoal
import it.spese.core.model.Transaction
import it.spese.domain.finance.PeriodStats
import it.spese.domain.finance.StatsEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class CategoryAverage(val categoryId: Long?, val name: String, val flexible: Boolean, val monthlyAvg: BigDecimal, val total: BigDecimal)

/** Base del piano: medie mensili sul periodo filtrato (porta di `planBase`). */
data class PlanBase(
    val stats: PeriodStats,
    val months: BigDecimal,
    /** Entrate mensili usate (quelle attese, se impostate, altrimenti la media del periodo). */
    val income: BigDecimal,
    val incomeAuto: BigDecimal,
    val expense: BigDecimal,
    val categories: List<CategoryAverage>,
) {
    val currentSaving: BigDecimal get() = income - expense
    val flexibleAvg: BigDecimal get() = categories.filter { it.flexible }.fold(BigDecimal.ZERO) { a, c -> a + c.monthlyAvg }
}

object PlanEngine {
    private val UNCATEGORIZED = "Senza categoria"

    fun base(list: List<Transaction>, categories: List<Category>, goal: SavingsGoal): PlanBase {
        val s = StatsEngine.compute(list)
        val mo = StatsEngine.monthsSpan(list)
        val incAuto = s.income.divide(mo, 10, RoundingMode.HALF_UP)
        val exp = s.expense.divide(mo, 10, RoundingMode.HALF_UP)
        val byId = categories.associateBy { it.id }
        val cats = s.byCategory.entries.filter { it.value.signum() > 0 }.map { (id, v) ->
            val c = byId[id]
            CategoryAverage(id, c?.name ?: UNCATEGORIZED, c?.flexible ?: false, v.divide(mo, 10, RoundingMode.HALF_UP), v)
        }.sortedByDescending { it.monthlyAvg }
        return PlanBase(s, mo, goal.expectedIncome ?: incAuto, incAuto, exp, cats)
    }

    fun goalMonthly(goal: SavingsGoal): BigDecimal =
        if (goal.mode == GoalMode.MONTHLY) goal.value
        else goal.value.divide(BigDecimal(maxOf(1, goal.months)), 10, RoundingMode.HALF_UP)

    /** Suggerimenti di obiettivo: 10%, 15%, 20% delle entrate, arrotondati alla decina. */
    fun suggestions(income: BigDecimal): List<Pair<Int, BigDecimal>> = listOf(10, 15, 20).map { p ->
        p to Money.pctOf(income, p).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN)
    }

    /** Fondo di emergenza consigliato: 3-6 mesi di spese. */
    fun emergencyFund(monthlyExpense: BigDecimal): Pair<BigDecimal, BigDecimal> =
        monthlyExpense.multiply(BigDecimal(3)) to monthlyExpense.multiply(BigDecimal(6))

    /** Risparmio potenziale tagliando `pct`% sulle voci comprimibili. */
    fun potentialSavings(base: PlanBase, pct: Int = 20): BigDecimal = Money.pctOf(base.flexibleAvg, pct)

    /**
     * "Distribuisci il taglio": percentuale uniforme (multipli di 5) sulle voci comprimibili per colmare il gap.
     * Null se non ci sono voci comprimibili. `sufficient` è false se anche il 100% non basta.
     */
    data class AutoCut(val percent: Int, val sufficient: Boolean)

    fun autoCut(base: PlanBase, goalMonthly: BigDecimal): AutoCut? {
        val fa = base.flexibleAvg
        if (fa.signum() <= 0) return null
        val gap = (goalMonthly - base.currentSaving).max(BigDecimal.ZERO)
        val raw = gap.multiply(Money.HUNDRED).divide(fa, 10, RoundingMode.HALF_UP).divide(BigDecimal(5), 0, RoundingMode.CEILING).toInt() * 5
        return AutoCut(minOf(100, raw), !(raw >= 100 && gap > fa))
    }

    /** Budget proposto = media mensile dopo l'eventuale taglio, arrotondato a 5 €. */
    fun suggestedBudget(avg: BigDecimal, cutPercent: Int = 0): BigDecimal =
        avg.multiply(BigDecimal(100 - cutPercent)).divide(Money.HUNDRED, 10, RoundingMode.HALF_UP)
            .divide(BigDecimal(5), 0, RoundingMode.HALF_UP).multiply(BigDecimal(5))

    /** Prossima occorrenza di una scadenza quando viene segnata come pagata; null = da eliminare (una tantum). */
    fun advanceDue(due: Due): LocalDate? = when (due.frequency) {
        DueFrequency.ONCE -> null
        DueFrequency.MONTHLY -> due.next.plusMonths(1)
        DueFrequency.YEARLY -> due.next.plusMonths(12)
    }
}
