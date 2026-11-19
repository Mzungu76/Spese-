package it.spese.domain.finance

import it.spese.core.common.Dates
import it.spese.core.common.MonthCalendar
import it.spese.core.common.Money
import it.spese.core.model.SpendNature
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

/**
 * Riga di classifica di una categoria. `percentOfExpense` è la quota sul totale del suo tipo (spese o entrate);
 * `previousTotal` è lo stesso importo nel periodo precedente (null se il periodo non è confrontabile).
 */
data class CategoryRow(
    val category: Category?, val total: BigDecimal, val percentOfExpense: BigDecimal, val monthlyAvg: BigDecimal, val count: Int,
    val previousTotal: BigDecimal? = null,
    /** Dettaglio per sottocategoria (vuoto se la categoria non ne ha o non ne usa nel periodo). */
    val children: List<ChildRow> = emptyList(),
) {
    val delta: BigDecimal? get() = previousTotal?.let { total - it }
    val changePercent: BigDecimal? get() = previousTotal?.let { Trends.changePercent(it, total) }
    /** Aumento significativo (>= 25 € e >= 15%; se prima era zero, almeno 50 €): da tenere d'occhio. */
    val surging: Boolean get() {
        val d = delta ?: return false
        if (d < BigDecimal(25)) return false
        val p = changePercent
        return if (p == null) total >= BigDecimal(50) else p >= BigDecimal(15)
    }
}
/** Parte di una categoria principale: una sua sottocategoria, oppure (`own`) i movimenti messi direttamente nella principale. */
data class ChildRow(val category: Category, val total: BigDecimal, val percentOfParent: BigDecimal, val previousTotal: BigDecimal?, val own: Boolean = false) {
    val delta: BigDecimal? get() = previousTotal?.let { total - it }
}

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
    /** Classifica delle categorie di entrata (stesso formato di [categories]). */
    val incomeCategories: List<CategoryRow> = emptyList(),
    /** Titolo del confronto ("mese precedente" / "periodo precedente"); null se non confrontabile. */
    val previousLabel: String? = null,
    /** Spesa del periodo per natura (chiave null = natura non definita). */
    val natureTotals: Map<SpendNature?, BigDecimal> = emptyMap(),
    /** Natura proposta per le categorie di spesa (per id). */
    val natureSuggestions: Map<Long, NatureSuggestion> = emptyMap(),
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
        val cal = filter.calendar
        val s = StatsEngine.compute(filtered, cal)
        val mo = StatsEngine.monthsSpan(filtered)
        // periodo precedente: per i mesi, lo stesso tratto del mese prima; altrimenti un periodo di pari durata
        val range = if (data.transactions.isEmpty()) null else PeriodResolver.resolve(filter.period, data.transactions.minOf { it.bookingDate }, data.transactions.maxOf { it.bookingDate }, cal)
        val prevRange = if (filter.period == Period.All || range?.from == null || range.to == null) null else previousRange(filter.period, range.from, range.to, cal)
        val previous = prevRange?.let { StatsEngine.compute(FilterEngine.apply(data.transactions, filter.copy(period = Period.Custom(it.first, it.second)), data.categoriesById)) }
        val prevLabel = when {
            prevRange == null -> null
            filter.period == Period.CurrentWeek -> "rispetto alla settimana scorsa"
            filter.period == Period.CurrentMonth || filter.period is Period.Month -> "rispetto a " + cal.label(cal.monthOf(prevRange.first))
            else -> "rispetto al periodo precedente"
        }
        // le sottocategorie confluiscono nella categoria principale
        val byId = data.categoriesById
        fun top(m: Map<Long?, BigDecimal>) = CategoryTree.rollup(m, byId).entries.filter { it.value.signum() > 0 }.sortedByDescending { it.value }
        val prevExp = previous?.let { CategoryTree.rollup(it.byCategory, byId) }
        val prevInc = previous?.let { CategoryTree.rollup(it.incomeByCategory, byId) }
        fun childrenOf(id: Long?, parentTotal: BigDecimal, cur: Map<Long?, BigDecimal>, prev: Map<Long?, BigDecimal>?): List<ChildRow> {
            val parent = byId[id] ?: return emptyList()
            val kids = cur.filter { (cid, v) -> cid != id && v.signum() > 0 && CategoryTree.rootId(cid, byId) == id }
            if (kids.isEmpty()) return emptyList()
            val rows = kids.mapNotNull { (cid, v) -> byId[cid]?.let { ChildRow(it, v, Money.percent(v, parentTotal), prev?.let { p -> p[cid] ?: BigDecimal.ZERO }) } }.toMutableList()
            cur[id]?.takeIf { it.signum() > 0 }?.let { rows += ChildRow(parent, it, Money.percent(it, parentTotal), prev?.let { p -> p[id] ?: BigDecimal.ZERO }, own = true) }
            return rows.sortedByDescending { it.total }
        }
        val cats = top(s.byCategory).map { (id, v) ->
            CategoryRow(byId[id], v, Money.percent(v, s.expense), v.divide(mo, 10, RoundingMode.HALF_UP),
                filtered.count { CategoryTree.rootId(it.categoryId, byId) == id && it.kind == TxKind.SPESA }, prevExp?.let { it[id] ?: BigDecimal.ZERO },
                childrenOf(id, v, s.byCategory, previous?.byCategory))
        }
        val incomeCats = top(s.incomeByCategory).map { (id, v) ->
            CategoryRow(byId[id], v, Money.percent(v, s.income), v.divide(mo, 10, RoundingMode.HALF_UP),
                filtered.count { CategoryTree.rootId(it.categoryId, byId) == id && it.kind == TxKind.ENTRATA }, prevInc?.let { it[id] ?: BigDecimal.ZERO },
                childrenOf(id, v, s.incomeByCategory, previous?.incomeByCategory))
        }
        val accRows = data.accounts.map { a ->
            val q = StatsEngine.compute(filtered.filter { it.accountId == a.id })
            AccountRow(a, q.income, q.expense, q.transfers, q.net)
        }
        val accTx = data.transactions.filter { filter.accountId == null || it.accountId == filter.accountId }
        val today = AlertEngine.referenceToday(realToday, accTx)
        val alerts = AlertEngine.compute(accTx, data.categories, data.goal, data.dues, today, RecurringDetector.defaultExclusions(data.categories), cal)
        val opening = data.accounts.filter { (filter.accountId == null || it.id == filter.accountId) && it.id !in data.managedAccountIds }.fold(BigDecimal.ZERO) { a, c -> a + c.openingBalance }
        // ritmo del mese di riferimento
        val ym = cal.monthOf(today)
        val spent = AlertEngine.monthlySpending(accTx, ym, cal).values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val dd = cal.dayIndex(today); val dim = cal.length(ym)
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
            budgets = AlertEngine.budgetUsage(accTx, data.categories, ym, cal).sortedByDescending { it.percent }, goalMonthly = goalM,
            savingsPerMonth = s.net.divide(mo, 10, RoundingMode.HALF_UP),
            unclassified = data.transactions.count { !it.manualCategory && it.ruleId == null && it.kind != TxKind.TRASFERIMENTO },
            topChanges = if (previous == null) emptyList() else Trends.categoryVariation(previous.byCategory, s.byCategory).take(3),
            incomeCategories = incomeCats, previousLabel = prevLabel,
            natureTotals = NatureSuggester.breakdown(s.byCategory, byId), natureSuggestions = NatureSuggester.suggest(data.categories, accTx, rec),
        )
    }

    /** Periodo di confronto: per un mese, gli stessi giorni del mese prima; altrimenti i giorni immediatamente precedenti, in pari numero. */
    internal fun previousRange(period: Period, from: LocalDate, to: LocalDate, cal: MonthCalendar = MonthCalendar.SOLAR): Pair<LocalDate, LocalDate> =
        if (period == Period.CurrentWeek) {
            // stessi giorni della settimana scorsa (lunedì-… fino allo stesso giorno)
            val monday = to.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            monday.minusWeeks(1) to monday.minusWeeks(1).plusDays(ChronoUnit.DAYS.between(monday, to))
        } else if (period == Period.CurrentMonth || period is Period.Month) {
            val cur = cal.monthOf(from); val pm = cur.minusMonths(1)
            val pFrom = cal.start(pm)
            val pTo = minOf(pFrom.plusDays(to.toEpochDay() - cal.start(cur).toEpochDay()), cal.end(pm))
            pFrom to pTo
        } else {
            val days = ChronoUnit.DAYS.between(from, to) + 1
            from.minusDays(days) to from.minusDays(1)
        }
}
