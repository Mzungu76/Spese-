package it.spese.domain.finance

import it.spese.core.model.Category
import it.spese.core.model.Due
import it.spese.core.model.DueFrequency
import it.spese.core.model.SavingsGoal
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class IncomeSource { GOAL, HISTORY, THIS_MONTH, NONE }

/** GOOD = in linea; CAUTION = stai spendendo più in fretta del previsto; OVER = budget variabile esaurito; SETUP = mancano le entrate attese. */
enum class DailyStatus { GOOD, CAUTION, OVER, SETUP }

/**
 * "Quanto posso spendere oggi?" Dal reddito atteso del mese si tolgono obiettivo di risparmio, accantonamento per le spese annuali
 * e uscite fisse (già pagate o ancora da pagare): quello che resta è il budget per le spese variabili.
 */
data class DailyBudget(
    val today: LocalDate,
    val month: YearMonth,
    /** Giorni rimasti nel mese, oggi compreso. */
    val daysLeft: Int,
    val daysInMonth: Int,
    val expectedIncome: BigDecimal,
    val incomeSource: IncomeSource,
    val savingsTarget: BigDecimal,
    val annualAccrual: BigDecimal,
    val fixedPaid: BigDecimal,
    val fixedRemaining: BigDecimal,
    /** Voci fisse ancora da pagare nel mese (nome, importo). */
    val fixedRemainingItems: List<Pair<String, BigDecimal>>,
    val variableBudget: BigDecimal,
    val variableSpent: BigDecimal,
    /** Quanto resta del budget variabile (negativo = sforato). */
    val available: BigDecimal,
    /** Quanto puoi spendere al giorno da oggi a fine mese. */
    val perDay: BigDecimal,
    /** Quanto sarebbe stato al giorno a inizio mese. */
    val typicalPerDay: BigDecimal,
    val status: DailyStatus,
    /** Giorni dall'ultimo movimento registrato (rispetto alla data reale). */
    val staleDays: Int,
    /** Vero se fisse + obiettivo + accantonamenti superano da soli le entrate attese. */
    val overcommitted: Boolean,
) {
    val fixedTotal: BigDecimal get() = fixedPaid + fixedRemaining
    /** Quota del budget variabile già spesa, 0..1+ (per la barra di avanzamento). */
    val usedFraction: Float get() = if (variableBudget.signum() > 0) (variableSpent.toFloat() / variableBudget.toFloat()).coerceAtLeast(0f) else if (variableSpent.signum() > 0) 1f else 0f
}

object DailyBudgetEngine {
    private fun negSum(l: List<Transaction>) = l.fold(BigDecimal.ZERO) { a, t -> a - t.amount }

    fun compute(
        accTx: List<Transaction>, categories: List<Category>, goal: SavingsGoal, dues: List<Due>,
        today: LocalDate, realToday: LocalDate = today,
    ): DailyBudget? {
        if (accTx.isEmpty()) return null
        val ym = YearMonth.from(today)
        val dim = ym.lengthOfMonth()
        val daysLeft = dim - today.dayOfMonth + 1
        val monthStart = ym.atDay(1); val monthEnd = ym.atEndOfMonth()
        val byId = categories.associateBy { it.id }
        val excluded = RecurringDetector.defaultExclusions(categories)
        val monthSpese = accTx.filter { it.kind == TxKind.SPESA && YearMonth.from(it.bookingDate) == ym }

        // uscite fisse: ricorrenze rilevate + categorie "fisse" + scadenze manuali mensili/una tantum
        val rec = RecurringDetector.detect(accTx, excluded)
        val recMerchants = rec.map { it.merchant }.toSet()
        val dueNames = dues.map { it.name.lowercase() }.filter { it.isNotBlank() }
        fun matchesDue(m: String) = m.lowercase().let { l -> dueNames.any { n -> l.contains(n) || n.contains(l) } }
        val accrual = AnnualAccruals.plan(dues, accTx, today, excluded)
        val isFixed = { t: Transaction -> t.merchant in recMerchants || byId[CategoryTree.rootId(t.categoryId, byId)]?.nature == SpendNature.FIXED }
        val fixedTx = monthSpese.filter(isFixed)
        val covered = monthSpese.filter { !isFixed(it) && accrual.covers(it.merchant) }
        val fixedPaid = negSum(fixedTx)

        val monthMerchants = monthSpese.map { it.merchant }.toSet()
        val remaining = ArrayList<Pair<String, BigDecimal>>()
        rec.filter { it.merchant !in monthMerchants && !it.next.isAfter(monthEnd) && !it.next.isBefore(monthStart.minusDays(10)) && !matchesDue(it.merchant) }
            .forEach { remaining.add(it.merchant to it.mean) }
        dues.filter { it.frequency != DueFrequency.YEARLY && it.amount.signum() > 0 && !it.next.isAfter(monthEnd) }
            .forEach { remaining.add(it.name to it.amount) }
        val fixedRemaining = remaining.fold(BigDecimal.ZERO) { a, p -> a + p.second }

        val variableSpent = negSum(monthSpese.filter { !isFixed(it) && it !in covered })

        // entrate attese: quelle indicate nel piano, altrimenti la media degli ultimi 3 mesi chiusi, altrimenti ciò che è entrato nel mese
        val stats = StatsEngine.compute(accTx)
        val prevMonths = stats.byMonth.keys.filter { it < ym }.sorted().takeLast(3).filter { stats.byMonth.getValue(it).income.signum() > 0 }
        val thisIncome = stats.byMonth[ym]?.income ?: BigDecimal.ZERO
        val (income, source) = when {
            goal.expectedIncome != null && goal.expectedIncome!!.signum() > 0 -> goal.expectedIncome!! to IncomeSource.GOAL
            prevMonths.isNotEmpty() -> prevMonths.fold(BigDecimal.ZERO) { a, m -> a + stats.byMonth.getValue(m).income }
                .divide(BigDecimal(prevMonths.size), 2, RoundingMode.HALF_UP) to IncomeSource.HISTORY
            thisIncome.signum() > 0 -> thisIncome to IncomeSource.THIS_MONTH
            else -> BigDecimal.ZERO to IncomeSource.NONE
        }

        val target = PlanEngine.goalMonthly(goal)
        val annual = accrual.monthlyTotal
        val variableBudget = income - target - annual - fixedPaid - fixedRemaining
        val available = variableBudget - variableSpent
        val perDay = if (available.signum() > 0) available.divide(BigDecimal(daysLeft), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
        val typical = if (variableBudget.signum() > 0) variableBudget.divide(BigDecimal(dim), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
        val status = when {
            source == IncomeSource.NONE -> DailyStatus.SETUP
            available.signum() <= 0 -> DailyStatus.OVER
            perDay < typical.multiply(BigDecimal("0.7")) -> DailyStatus.CAUTION
            else -> DailyStatus.GOOD
        }
        val stale = accTx.maxOf { it.bookingDate }.let { ChronoUnit.DAYS.between(it, realToday).toInt().coerceAtLeast(0) }
        return DailyBudget(
            today, ym, daysLeft, dim, income.setScale(2, RoundingMode.HALF_UP), source, target, annual, fixedPaid, fixedRemaining, remaining.sortedByDescending { it.second },
            variableBudget.setScale(2, RoundingMode.HALF_UP), variableSpent.setScale(2, RoundingMode.HALF_UP), available.setScale(2, RoundingMode.HALF_UP),
            perDay, typical, status, stale, source != IncomeSource.NONE && variableBudget.signum() <= 0,
        )
    }
}
