package it.spese.domain.networth

import it.spese.core.model.Account
import it.spese.core.model.AccountType
import it.spese.core.model.DueFrequency
import it.spese.core.model.Goal
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.core.model.Valuation
import it.spese.domain.finance.AlertEngine
import it.spese.domain.finance.CategoryTree
import it.spese.domain.finance.StatsEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class AccountBalance(val account: Account, val balance: BigDecimal, val valuationDate: LocalDate?) { val isInvestment: Boolean get() = account.type == AccountType.INVESTIMENTO }

data class NetWorthPoint(val date: LocalDate, val liquidity: BigDecimal, val investments: BigDecimal) { val total: BigDecimal get() = liquidity + investments }

enum class FundStatus { UNKNOWN, LOW, BUILDING, OK }

/** Fondo di emergenza: quanti mesi di spese essenziali copre la liquidità (minimo 3, consigliato 6). */
data class EmergencyFund(
    val monthlyEssential: BigDecimal,
    /** false = nessuna categoria ha la natura «fissa» o «necessaria»: si usa la spesa totale. */
    val essentialKnown: Boolean,
    val months: BigDecimal?, val minTarget: BigDecimal, val target: BigDecimal, val status: FundStatus,
)

/** Quanto resta da parte della liquidità oltre fondo di emergenza, soldi già destinati agli obiettivi e scadenze grandi dei prossimi mesi. */
data class InvestableMargin(val liquidity: BigDecimal, val emergencyTarget: BigDecimal, val earmarked: BigDecimal, val upcomingDues: BigDecimal, val margin: BigDecimal)

data class NetWorthReport(
    val today: LocalDate, val accounts: List<AccountBalance>,
    val liquidity: BigDecimal, val investments: BigDecimal,
    val series: List<NetWorthPoint>,
    /** Variazione del patrimonio rispetto al primo punto della serie (null con un solo punto). */
    val change: BigDecimal?,
    val fund: EmergencyFund, val margin: InvestableMargin,
    /** Saldo dei conti gestiti da altri (denaro di famiglia): nel totale, fuori da liquidità, fondo di emergenza e margine. */
    val managed: BigDecimal = BigDecimal.ZERO,
) { val total: BigDecimal get() = liquidity + investments + managed }

object NetWorthEngine {
    const val FUND_MIN_MONTHS = 3
    const val FUND_TARGET_MONTHS = 6
    private const val SERIES_MONTHS = 12
    private const val HISTORY_MONTHS = 6
    private const val DUES_HORIZON_MONTHS = 6L

    /** Saldo di un conto a una data: per gli investimenti l'ultimo valore inserito (≤ data) più i movimenti successivi. */
    fun balanceAt(a: Account, tx: List<Transaction>, valuations: List<Valuation>, date: LocalDate): BigDecimal {
        val v = if (a.type == AccountType.INVESTIMENTO) valuations.filter { it.accountId == a.id && !it.date.isAfter(date) }.maxByOrNull { it.date } else null
        val base = v?.value ?: a.openingBalance
        val from = v?.date
        return tx.filter { it.accountId == a.id && !it.bookingDate.isAfter(date) && (from == null || it.bookingDate.isAfter(from)) }.fold(base) { s, t -> s + t.amount }
    }

    fun compute(data: AppData, goals: List<Goal>, valuations: List<Valuation>, realToday: LocalDate = LocalDate.now()): NetWorthReport? {
        val accounts = data.accounts.filter { !it.archived }
        if (accounts.isEmpty()) return null
        val tx = data.everything
        val today = if (tx.isEmpty()) realToday else AlertEngine.referenceToday(realToday, tx)

        fun point(d: LocalDate): NetWorthPoint {
            var liq = BigDecimal.ZERO; var inv = BigDecimal.ZERO
            accounts.forEach { a -> val b = balanceAt(a, tx, valuations, d); if (a.type == AccountType.INVESTIMENTO) inv += b else liq += b }
            return NetWorthPoint(d, liq, inv)
        }

        val balances = accounts.map { a ->
            AccountBalance(a, balanceAt(a, tx, valuations, today), valuations.filter { it.accountId == a.id && !it.date.isAfter(today) }.maxOfOrNull { it.date }.takeIf { a.type == AccountType.INVESTIMENTO })
        }
        // il denaro di conti gestiti da altri conta nel patrimonio, ma non come liquidità propria (fondo di emergenza e margine investibile)
        val liquidity = balances.filter { !it.isInvestment && it.account.id !in data.managedAccountIds }.fold(BigDecimal.ZERO) { s, b -> s + b.balance }
        val managedLiquidity = balances.filter { !it.isInvestment && it.account.id in data.managedAccountIds }.fold(BigDecimal.ZERO) { s, b -> s + b.balance }
        val investments = balances.filter { it.isInvestment }.fold(BigDecimal.ZERO) { s, b -> s + b.balance }

        // serie: fine di ogni mese degli ultimi 12 (l'ultimo punto è oggi), dal mese del primo movimento
        val first = tx.minOfOrNull { it.bookingDate }
        val cur = YearMonth.from(today)
        val series = (SERIES_MONTHS - 1 downTo 1).map { cur.minusMonths(it.toLong()) }.filter { first == null || !it.atEndOfMonth().isBefore(first) }.map { point(it.atEndOfMonth()) } + point(today)

        val fund = fund(data, liquidity, today)
        val earmarked = goals.filter { it.enabled }.fold(BigDecimal.ZERO) { s, g -> s + g.saved }
        val horizon = today.plusMonths(DUES_HORIZON_MONTHS)
        val dues = data.dues.filter { (it.frequency == DueFrequency.ONCE || it.frequency == DueFrequency.YEARLY) && !it.next.isAfter(horizon) }.fold(BigDecimal.ZERO) { s, d -> s + d.amount.abs() }
        val target = fund.monthlyEssential.multiply(BigDecimal(FUND_TARGET_MONTHS))
        val margin = (liquidity - target - earmarked - dues).max(BigDecimal.ZERO)
        return NetWorthReport(today, balances, liquidity, investments, series, if (series.size > 1) series.last().total - series.first().total else null, fund, InvestableMargin(liquidity, target, earmarked, dues, margin), managedLiquidity)
    }

    private fun fund(data: AppData, liquidity: BigDecimal, today: LocalDate): EmergencyFund {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        val cal = data.calendar
        val current = cal.monthOf(today)
        val byMonth = StatsEngine.compute(tx.filter { cal.monthOf(it.bookingDate) < current }, cal).byMonth
        val months = byMonth.keys.sorted().takeLast(HISTORY_MONTHS)
        val minT = BigDecimal(FUND_MIN_MONTHS); val tgt = BigDecimal(FUND_TARGET_MONTHS)
        if (months.isEmpty()) return EmergencyFund(BigDecimal.ZERO, false, null, minT, tgt, FundStatus.UNKNOWN)
        val byId = data.categoriesById
        fun isEssential(id: Long?) = CategoryTree.rootNature(id, byId).let { it == SpendNature.FIXED || it == SpendNature.ESSENTIAL }
        val essentialKnown = data.categories.any { it.nature == SpendNature.FIXED || it.nature == SpendNature.ESSENTIAL }
        val total = months.fold(BigDecimal.ZERO) { s, m ->
            val ms = byMonth.getValue(m)
            s + if (essentialKnown) ms.byCategory.filterKeys { isEssential(it) }.values.fold(BigDecimal.ZERO) { a, v -> a + v } else ms.expense
        }
        val monthly = total.divide(BigDecimal(months.size), 2, RoundingMode.HALF_UP)
        if (monthly.signum() <= 0) return EmergencyFund(BigDecimal.ZERO, essentialKnown, null, minT, tgt, FundStatus.UNKNOWN)
        val covered = liquidity.max(BigDecimal.ZERO).divide(monthly, 1, RoundingMode.DOWN)
        val status = when { covered >= tgt -> FundStatus.OK; covered >= minT -> FundStatus.BUILDING; else -> FundStatus.LOW }
        return EmergencyFund(monthly, essentialKnown, covered, minT, tgt, status)
    }
}
