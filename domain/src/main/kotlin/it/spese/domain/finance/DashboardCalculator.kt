package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Account
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import it.spese.domain.simulation.PlanEngine
import it.spese.domain.simulation.Trends

data class CategoryRow(val category: Category?, val total: BigDecimal, val percentOfExpense: BigDecimal, val monthlyAvg: BigDecimal, val count: Int)
data class AccountRow(val account: Account, val income: BigDecimal, val expense: BigDecimal, val transfers: BigDecimal, val balance: BigDecimal)

data class UpcomingItem(val name: String, val amount: BigDecimal, val date: LocalDate, val daysLeft: Int, val manual: Boolean)

/** Ritmo di spesa del mese di riferimento: speso finora e proiezione a fine mese. */
data class MonthPace(val month: YearMonth, val spent: BigDecimal, val dailyAverage: BigDecimal, val projected: BigDecimal, val dayOfMonth: Int, val daysInMonth: Int)

/** Tutti i numeri della Panoramica, calcolati in un solo punto (nessuna logica nei composable). */
data class DashboardModel(
    val filtered: List<Transaction>,
    val stats: PeriodStats,
    val months: BigDecimal,
    val monthlyAvgExpense: BigDecimal,
    val categories: List<CategoryRow>,
    val biggestExpenses: List<Transaction>,
    val merchants: List<Pair<String, MerchantStat>>,
    val perAccount: List<AccountRow>,
    val alerts: List<AlertItem>,
    val cumulative: List<Pair<YearMonth, BigDecimal>>,
    val totalBalance: BigDecimal,
    val today: LocalDate,
    /** Periodo immediatamente precedente di pari durata (null con "Tutto"). */
    val previous: PeriodStats? = null,
    val monthPace: MonthPace? = null,
    val upcoming: List<UpcomingItem> = emptyList(),
    val upcoming30: BigDecimal = BigDecimal.ZERO,
    val budgets: List<BudgetUsage> = emptyList(),
    val goalMonthly: BigDecimal = BigDecimal.ZERO,
    val savingsPerMonth: BigDecimal = BigDecimal.ZERO,
    val unclassified: Int = 0,
    val topChanges: List<Trends.CategoryDelta> = emptyList(),
) {
    /** Variazioni percentuali rispetto al periodo precedente (null se non confrontabile). */
    val incomeChange: BigDecimal? get() = previous?.let { Trends.changePercent(it.income, stats.income) }
    val expenseChange: BigDecimal? get() = previous?.let { Trends.changePercent(it.expense, stats.expense) }
    /** Quota di obiettivo di risparmio raggiunta (0..1+). */
    val goalProgress: Float get() = if (goalMonthly.signum() > 0) (savingsPerMonth.toFloat() / goalMonthly.toFloat()).coerceAtLeast(0f) else 0f
}

object DashboardCalculator {
    fun compute(data: AppData, filter: TxFilter, realToday: LocalDate = LocalDate.now()): DashboardModel {
        val filtered = FilterEngine.apply(data.transactions, filter, data.categoriesById)
        val s = StatsEngine.compute(filtered)
        val mo = StatsEngine.monthsSpan(filtered)
        val cats = s.topCategories.map { (id, v) ->
            CategoryRow(data.categoriesById[id], v, Money.percent(v, s.expense), v.divide(mo, 10, RoundingMode.HALF_UP), filtered.count { it.categoryId == id })
        }
        val accRows = data.accounts.map { a ->
            val q = StatsEngine.compute(filtered.filter { it.accountId == a.id })
            AccountRow(a, q.income, q.expense, q.transfers, q.net)
        }
        val accTx = data.transactions.filter { filter.accountId == null || it.accountId == filter.accountId }
        val today = AlertEngine.referenceToday(realToday, accTx)
        val alerts = AlertEngine.compute(accTx, data.categories, data.goal, data.dues, today, RecurringDetector.defaultExclusions(data.categories))
        val opening = data.accounts.filter { filter.accountId == null || it.id == filter.accountId }.fold(BigDecimal.ZERO) { a, c -> a + c.openingBalance }
        // periodo precedente di pari durata
        val range = if (data.transactions.isEmpty()) null else PeriodResolver.resolve(filter.period, data.transactions.minOf { it.bookingDate }, data.transactions.maxOf { it.bookingDate })
        val previous = if (filter.period == Period.All || range?.from == null || range.to == null) null else {
            val days = ChronoUnit.DAYS.between(range.from, range.to) + 1
            StatsEngine.compute(FilterEngine.apply(data.transactions, filter.copy(period = Period.Custom(range.from.minusDays(days), range.from.minusDays(1))), data.categoriesById))
        }
        // ritmo del mese di riferimento
        val ym = YearMonth.from(today)
        val spent = AlertEngine.monthlySpending(accTx, ym).values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val dd = today.dayOfMonth; val dim = ym.lengthOfMonth()
        val pace = if (accTx.isEmpty()) null else MonthPace(ym, spent, spent.divide(BigDecimal(dd), 10, RoundingMode.HALF_UP),
            if (dd < dim) spent.multiply(BigDecimal(dim)).divide(BigDecimal(dd), 10, RoundingMode.HALF_UP) else spent, dd, dim)
        // prossime scadenze (manuali + ricorrenze rilevate)
        val rec = RecurringDetector.detect(accTx, RecurringDetector.defaultExclusions(data.categories))
        val upcomingAll = (data.dues.map { UpcomingItem(it.name, it.amount, it.next, ChronoUnit.DAYS.between(today, it.next).toInt(), true) } +
            rec.map { UpcomingItem(it.merchant, it.mean, it.next, ChronoUnit.DAYS.between(today, it.next).toInt(), false) })
            .filter { it.daysLeft in -3..30 }.sortedBy { it.date }
        val goalM = PlanEngine.goalMonthly(data.goal)
        return DashboardModel(
            filtered, s, mo, s.expense.divide(mo, 10, RoundingMode.HALF_UP), cats,
            StatsEngine.biggestExpenses(filtered, 10), StatsEngine.topMerchants(s, 12), accRows, alerts,
            StatsEngine.cumulativeNet(s), StatsEngine.balance(accTx, opening), today,
            previous = previous, monthPace = pace, upcoming = upcomingAll.take(5), upcoming30 = upcomingAll.filter { it.daysLeft >= 0 }.fold(BigDecimal.ZERO) { a, b -> a + b.amount },
            budgets = AlertEngine.budgetUsage(accTx, data.categories, ym).sortedByDescending { it.percent }, goalMonthly = goalM,
            savingsPerMonth = s.net.divide(mo, 10, RoundingMode.HALF_UP),
            unclassified = data.transactions.count { !it.manualCategory && it.ruleId == null && it.kind != TxKind.TRASFERIMENTO },
            topChanges = if (previous == null) emptyList() else Trends.categoryVariation(previous.byCategory, s.byCategory).take(3),
        )
    }
}
