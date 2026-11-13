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

data class CategoryRow(val category: Category?, val total: BigDecimal, val percentOfExpense: BigDecimal, val monthlyAvg: BigDecimal, val count: Int)
data class AccountRow(val account: Account, val income: BigDecimal, val expense: BigDecimal, val transfers: BigDecimal, val balance: BigDecimal)

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
)

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
        return DashboardModel(
            filtered, s, mo, s.expense.divide(mo, 10, RoundingMode.HALF_UP), cats,
            StatsEngine.biggestExpenses(filtered, 10), StatsEngine.topMerchants(s, 12), accRows, alerts,
            StatsEngine.cumulativeNet(s), StatsEngine.balance(accTx, opening), today,
        )
    }
}
