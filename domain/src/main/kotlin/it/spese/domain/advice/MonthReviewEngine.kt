package it.spese.domain.advice

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Goal
import it.spese.core.model.SpendNature
import it.spese.core.model.TxKind
import it.spese.domain.finance.AlertEngine
import it.spese.domain.finance.CategoryTree
import it.spese.domain.finance.SavingsOpportunities
import it.spese.domain.finance.StatsEngine
import it.spese.domain.goals.GoalEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** Una categoria principale nel mese: quanto, quanto il mese prima e che quota della spesa. */
data class CategoryMonth(val category: Category, val amount: BigDecimal, val previous: BigDecimal, val sharePercent: Int) { val delta: BigDecimal get() = amount - previous }

/** Ripartizione reale 50/30/20: bisogni (fisse e necessarie), desideri (discrezionali), non classificate e risparmio, in % delle entrate. */
data class Split503020(val needs: BigDecimal, val wants: BigDecimal, val other: BigDecimal, val savings: BigDecimal, val base: BigDecimal) {
    private fun pct(v: BigDecimal) = if (base.signum() <= 0) 0 else v.multiply(BigDecimal(100)).divide(base, 0, RoundingMode.HALF_UP).toInt()
    val needsPercent: Int get() = pct(needs)
    val wantsPercent: Int get() = pct(wants)
    val otherPercent: Int get() = pct(other)
    val savingsPercent: Int get() = pct(savings.max(BigDecimal.ZERO))
}

/** Azione proposta: si applica con un tocco (`SetBudget`, `MoveToGoal`) o è un invito a guardare qualcosa (`Look`). */
sealed class ReviewAction(open val text: String) {
    data class SetBudget(val categoryId: Long, val categoryName: String, val amount: BigDecimal, override val text: String) : ReviewAction(text)
    data class MoveToGoal(val goalId: Long, val goalName: String, val amount: BigDecimal, override val text: String) : ReviewAction(text)
}

data class MonthReview(
    val month: YearMonth, val start: LocalDate, val end: LocalDate,
    val income: BigDecimal, val expense: BigDecimal, val saved: BigDecimal, val savingsRatePercent: Int?,
    /** Quota di risparmio mensile voluta (obiettivi) e differenza: positivo = hai risparmiato più del dovuto. */
    val goalQuota: BigDecimal, val vsGoal: BigDecimal,
    val previousExpense: BigDecimal?,
    val topCategories: List<CategoryMonth>, val split: Split503020,
    /** Quota delle spese senza una natura assegnata (se alta, la ripartizione è poco affidabile). */
    val unclassifiedPercent: Int,
    val actions: List<ReviewAction>,
) { val goalReached: Boolean get() = vsGoal.signum() >= 0 }

object MonthReviewEngine {
    private val FIVE = BigDecimal(5)
    private fun floor5(v: BigDecimal): BigDecimal = v.max(BigDecimal.ZERO).divide(FIVE, 0, RoundingMode.DOWN).multiply(FIVE)

    /** Il mese contabile concluso più recente con movimenti (di norma quello prima del corrente), null se non c'è. */
    fun reviewMonth(data: AppData, today: LocalDate = LocalDate.now()): YearMonth? {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        if (tx.isEmpty()) return null
        val cal = data.calendar
        val pm = cal.monthOf(AlertEngine.referenceToday(today, tx)).minusMonths(1)
        return pm.takeIf { m -> tx.any { cal.monthOf(it.bookingDate) == m } }
    }

    fun compute(data: AppData, goals: List<Goal>, today: LocalDate = LocalDate.now(), month: YearMonth? = null): MonthReview? {
        val pm = month ?: reviewMonth(data, today) ?: return null
        val cal = data.calendar
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        val byMonth = StatsEngine.compute(tx, cal).byMonth
        val ms = byMonth[pm] ?: return null
        val prev = byMonth[pm.minusMonths(1)]
        val byId = data.categoriesById
        val saved = ms.income - ms.expense
        val rate = if (ms.income.signum() > 0) saved.multiply(BigDecimal(100)).divide(ms.income, 0, RoundingMode.HALF_UP).toInt() else null
        val ref = AlertEngine.referenceToday(today, tx)
        val quota = (if (goals.isNotEmpty()) GoalEngine.monthlyTotal(goals, ref) else AlertEngine.goalMonthly(data.goal)).max(BigDecimal.ZERO)

        val cur = CategoryTree.rollup(ms.byCategory, byId)
        val before = prev?.let { CategoryTree.rollup(it.byCategory, byId) } ?: emptyMap()
        val rows = cur.mapNotNull { (id, v) ->
            val c = id?.let { byId[it] } ?: return@mapNotNull null
            if (v.signum() <= 0) null else CategoryMonth(c, v, before[id] ?: BigDecimal.ZERO, if (ms.expense.signum() > 0) v.multiply(BigDecimal(100)).divide(ms.expense, 0, RoundingMode.HALF_UP).toInt() else 0)
        }.sortedByDescending { it.amount }

        fun sumOf(pred: (SpendNature?) -> Boolean) = cur.filterKeys { pred(CategoryTree.rootNature(it, byId)) }.values.fold(BigDecimal.ZERO) { a, v -> a + v }
        val needs = sumOf { it == SpendNature.FIXED || it == SpendNature.ESSENTIAL }
        val wants = sumOf { it == SpendNature.DISCRETIONARY }
        val other = ms.expense - needs - wants
        val base = if (ms.income.signum() > 0) ms.income else ms.expense
        val unclassified = if (ms.expense.signum() > 0) other.multiply(BigDecimal(100)).divide(ms.expense, 0, RoundingMode.HALF_UP).toInt() else 0

        val gap = saved - quota
        val actions = ArrayList<ReviewAction>()
        // 1) risparmio oltre la quota → spostalo su un obiettivo; sotto la quota → riduci la voce discrezionale più pesante
        if (gap.signum() > 0) {
            val g = goals.filter { it.enabled && GoalEngine.remaining(it).signum() > 0 }.minWithOrNull(compareBy({ it.priority }, { it.id }))
            if (g != null) {
                val amount = floor5(gap.min(GoalEngine.remaining(g)))
                if (amount >= FIVE) actions.add(ReviewAction.MoveToGoal(g.id, g.name, amount, "Hai risparmiato ${Money.eur0(gap)} più della quota: sposta ${Money.eur0(amount)} su «${g.name}»."))
            }
        } else if (gap.signum() < 0) {
            rows.firstOrNull { SavingsOpportunities.isCandidate(it.category) }?.let { r ->
                val target = floor5(r.amount + gap)      // gap negativo
                if (target >= FIVE && target < r.amount && (r.category.budgetMonthly == null || r.category.budgetMonthly!! > target))
                    actions.add(ReviewAction.SetBudget(r.category.id, r.category.name, target, "Per rientrare nella quota di risparmio, porta «${r.category.name}» da ${Money.eur0(r.amount)} a ${Money.eur0(target)} al mese."))
            }
        }
        // 2) voci discrezionali in crescita e senza budget → un tetto alla tua media recente
        rows.filter { SavingsOpportunities.isCandidate(it.category) && it.category.budgetMonthly == null && it.amount >= BigDecimal(30) && it.previous.signum() > 0 && it.amount > it.previous.multiply(BigDecimal("1.15")) && actions.none { a -> a is ReviewAction.SetBudget && a.categoryId == it.category.id } }
            .take(2).forEach { r ->
                val hist = (1..3).map { k -> byMonth[pm.minusMonths(k.toLong())]?.let { m -> CategoryTree.rollup(m.byCategory, byId)[r.category.id] } }.filterNotNull()
                if (hist.isNotEmpty()) {
                    val avg = hist.fold(BigDecimal.ZERO) { a, v -> a + v }.divide(BigDecimal(hist.size), 2, RoundingMode.HALF_UP)
                    val cap = floor5(avg)
                    if (cap >= FIVE) actions.add(ReviewAction.SetBudget(r.category.id, r.category.name, cap, "«${r.category.name}» è salita a ${Money.eur0(r.amount)} (era ${Money.eur0(r.previous)}): un budget di ${Money.eur0(cap)}, la tua media, la tiene sotto controllo."))
                }
            }
        return MonthReview(pm, cal.start(pm), cal.end(pm), ms.income, ms.expense, saved, rate, quota, gap, prev?.expense, rows.take(3), Split503020(needs, wants, other, saved, base), unclassified, actions.take(3))
    }
}
