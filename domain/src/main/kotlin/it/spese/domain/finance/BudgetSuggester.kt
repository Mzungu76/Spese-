package it.spese.domain.finance

import it.spese.core.model.Category
import it.spese.core.model.Goal
import it.spese.core.model.TxKind
import it.spese.domain.goals.GoalEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Budget proposto per una categoria principale: media storica, proposta (arrotondata a 5 €) e budget attuale, se c'è. */
data class BudgetProposal(val category: Category, val average: BigDecimal, val proposed: BigDecimal, val current: BigDecimal?, val compressible: Boolean) {
    val cutPercent: Int get() = if (average.signum() <= 0 || proposed >= average) 0 else average.subtract(proposed).multiply(BigDecimal(100)).divide(average, 0, RoundingMode.HALF_UP).toInt()
}

/**
 * Proposta di budget mensili: le spese che non si possono comprimere restano alla media storica, le discrezionali si riducono in modo uniforme
 * quanto basta perché entrate − risparmio degli obiettivi − spese essenziali lasci spazio. `shortfall` > 0 dice che neanche azzerandole basta.
 */
data class BudgetPlan(val income: BigDecimal, val savings: BigDecimal, val months: Int, val proposals: List<BudgetProposal>, val cutPercent: Int, val shortfall: BigDecimal)

object BudgetSuggester {
    private const val HISTORY_MONTHS = 6
    private val MIN_BUDGET = BigDecimal(5)

    private fun round5(v: BigDecimal, mode: RoundingMode): BigDecimal = v.divide(MIN_BUDGET, 0, mode).multiply(MIN_BUDGET)

    fun suggest(data: AppData, goals: List<Goal>, today: LocalDate = LocalDate.now()): BudgetPlan? {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        if (tx.isEmpty()) return null
        val cal = data.calendar
        val ref = AlertEngine.referenceToday(today, tx)
        val current = cal.monthOf(ref)
        val byMonth = StatsEngine.compute(tx.filter { cal.monthOf(it.bookingDate) < current }, cal).byMonth
        val months = byMonth.keys.sorted().takeLast(HISTORY_MONTHS)
        if (months.isEmpty()) return null
        val n = BigDecimal(months.size)
        val income = months.fold(BigDecimal.ZERO) { a, m -> a + byMonth.getValue(m).income }.divide(n, 2, RoundingMode.HALF_UP)
        val savings = if (goals.isNotEmpty()) GoalEngine.monthlyTotal(goals, ref) else AlertEngine.goalMonthly(data.goal)
        val byId = data.categoriesById
        val spent = HashMap<Long?, BigDecimal>()
        months.forEach { m -> byMonth.getValue(m).byCategory.forEach { (id, v) -> spent.merge(CategoryTree.rootId(id, byId), v) { a, b -> a + b } } }
        val rows = spent.mapNotNull { (id, total) ->
            val c = id?.let { byId[it] } ?: return@mapNotNull null
            if (c.systemKey != null || c.kind != TxKind.SPESA) return@mapNotNull null
            val avg = total.divide(n, 2, RoundingMode.HALF_UP)
            if (avg < MIN_BUDGET) null else c to avg
        }
        if (rows.isEmpty()) return null
        val (flex, keep) = rows.partition { SavingsOpportunities.isCandidate(it.first) }
        val keepTotal = keep.fold(BigDecimal.ZERO) { a, r -> a + r.second }
        val flexTotal = flex.fold(BigDecimal.ZERO) { a, r -> a + r.second }
        val room = income - savings.max(BigDecimal.ZERO) - keepTotal
        // taglio uniforme (multipli di 5%) sulle voci comprimibili, solo se serve
        val cut = if (flexTotal.signum() <= 0 || room >= flexTotal) 0
            else if (room.signum() <= 0) 100
            else BigDecimal(100).subtract(room.multiply(BigDecimal(100)).divide(flexTotal, 10, RoundingMode.HALF_UP)).divide(BigDecimal(5), 0, RoundingMode.CEILING).toInt() * 5
        val factor = BigDecimal(100 - cut).divide(BigDecimal(100), 10, RoundingMode.HALF_UP)
        val proposals = rows.map { (c, avg) ->
            val isFlex = SavingsOpportunities.isCandidate(c)
            val proposed = if (isFlex) round5(avg.multiply(factor), RoundingMode.HALF_UP) else round5(avg, RoundingMode.CEILING)
            BudgetProposal(c, avg, proposed, c.budgetMonthly?.takeIf { it.signum() > 0 }, isFlex)
        }.sortedByDescending { it.average }
        return BudgetPlan(income, savings, months.size, proposals, cut, (-room).max(BigDecimal.ZERO))
    }
}
