package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import it.spese.domain.repository.AppData
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** Voci di una categoria raggruppate per esercente, con il confronto sul periodo precedente. */
data class MerchantGroup(val merchant: String, val total: BigDecimal, val count: Int, val previousTotal: BigDecimal, val transactions: List<Transaction>) {
    val delta: BigDecimal get() = total - previousTotal
}

data class SubcategoryRow(val category: Category, val total: BigDecimal, val previousTotal: BigDecimal?, val count: Int, val percent: BigDecimal)

/** Tutto ciò che serve alla pagina di dettaglio di una categoria (valori positivi: speso o incassato, secondo il tipo). */
data class CategoryDetail(
    val category: Category,
    val children: List<Category>,
    val total: BigDecimal,
    val previousTotal: BigDecimal?,
    val count: Int,
    /** Quota sul totale del tipo (spese o entrate) nel periodo. */
    val share: BigDecimal,
    val previousLabel: String?,
    /** Ultimi 12 mesi al massimo, continui, dal primo mese con movimenti della categoria. */
    val months: List<Pair<YearMonth, BigDecimal>>,
    val monthlyAvg: BigDecimal,
    val merchants: List<MerchantGroup>,
    /** Gli esercenti che hanno spostato di più il totale rispetto al periodo precedente. */
    val drivers: List<MerchantGroup>,
    val subcategories: List<SubcategoryRow>,
    /** Uso del budget nel mese di riferimento (null senza budget). */
    val budget: BudgetUsage?,
    /** Budget proposto: media degli ultimi mesi completi, arrotondata a 5 €. */
    val suggestedBudget: BigDecimal?,
    val referenceMonth: YearMonth,
) {
    val delta: BigDecimal? get() = previousTotal?.let { total - it }
    val changePercent: BigDecimal? get() = previousTotal?.let { prev -> it.spese.domain.simulation.Trends.changePercent(prev, total) }
}

object CategoryDetailCalculator {
    private fun magnitude(t: Transaction, kind: TxKind): BigDecimal = if (kind == TxKind.ENTRATA) t.amount else t.amount.negate()

    fun compute(data: AppData, categoryId: Long, filter: TxFilter, realToday: LocalDate = LocalDate.now()): CategoryDetail? {
        val cat = data.categoriesById[categoryId] ?: return null
        val kids = CategoryTree.children(categoryId, data.categories)
        val ids = kids.map { it.id }.toSet() + categoryId
        val base = filter.copy(categoryId = null, kind = null, query = "", minAmount = null, maxAmount = null)
        val all = data.transactions
        val range = if (all.isEmpty()) null else PeriodResolver.resolve(filter.period, all.minOf { it.bookingDate }, all.maxOf { it.bookingDate })
        val prevRange = if (filter.period == Period.All || range?.from == null || range.to == null) null else DashboardCalculator.previousRange(filter.period, range.from, range.to)
        val periodAll = FilterEngine.apply(all, base, data.categoriesById)
        val cur = periodAll.filter { it.categoryId in ids }
        val prev = prevRange?.let { r -> FilterEngine.apply(all, base.copy(period = Period.Custom(r.first, r.second)), data.categoriesById).filter { it.categoryId in ids } }
        val total = cur.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) }
        val prevTotal = prev?.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) }
        val kindTotal = periodAll.filter { it.kind == cat.kind }.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) }

        fun group(l: List<Transaction>) = l.groupBy { it.merchant }
        val prevByMerchant = prev?.let { p -> group(p).mapValues { (_, v) -> v.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) } } } ?: emptyMap()
        val merchants = group(cur).map { (m, l) ->
            MerchantGroup(m, l.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) }, l.size, prevByMerchant[m] ?: BigDecimal.ZERO, l.sortedByDescending { it.bookingDate })
        }.sortedByDescending { it.total }
        // esercenti presenti solo nel periodo precedente: spiegano un calo
        val vanished = prevByMerchant.filterKeys { m -> merchants.none { it.merchant == m } }.map { (m, v) -> MerchantGroup(m, BigDecimal.ZERO, 0, v, emptyList()) }
        val drivers = if (prev == null) emptyList() else (merchants + vanished).filter { it.delta.abs() >= BigDecimal(5) }.sortedByDescending { it.delta.abs() }.take(3)

        val subs = kids.map { k ->
            val l = cur.filter { it.categoryId == k.id }
            SubcategoryRow(k, l.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) },
                prev?.filter { it.categoryId == k.id }?.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) }, l.size, Money.percent(l.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) }, total))
        }.filter { it.count > 0 || it.total.signum() != 0 }.sortedByDescending { it.total }
        // categoria principale con movimenti propri: li mostra come riga "Altro" se ci sono anche sottocategorie
        // (il totale resta la somma di tutto)

        // andamento mensile sull'intero storico (filtrato per conto), ultimi 12 mesi
        val own = all.filter { it.categoryId in ids && (filter.accountId == null || it.accountId == filter.accountId) }
        val lastMonth = if (all.isEmpty()) YearMonth.from(realToday) else YearMonth.from(all.maxOf { it.bookingDate })
        val byMonth = own.groupBy { YearMonth.from(it.bookingDate) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, cat.kind) } }
        val months = if (byMonth.isEmpty()) emptyList() else {
            val start = maxOf(byMonth.keys.min(), lastMonth.minusMonths(11))
            generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(lastMonth) }.map { it to (byMonth[it] ?: BigDecimal.ZERO) }.toList()
        }
        val avg = if (months.isEmpty()) BigDecimal.ZERO else months.fold(BigDecimal.ZERO) { a, m -> a + m.second }.divide(BigDecimal(months.size), 2, RoundingMode.HALF_UP)

        val accTx = all.filter { filter.accountId == null || it.accountId == filter.accountId }
        val ref = YearMonth.from(AlertEngine.referenceToday(realToday, accTx))
        val budget = cat.budgetMonthly?.takeIf { it.signum() > 0 }?.let { b ->
            BudgetUsage(cat, AlertEngine.spentWithChildren(AlertEngine.monthlySpending(accTx, ref), cat, data.categories), b)
        }
        val complete = months.filter { it.first.isBefore(ref) }.takeLast(3).ifEmpty { months.takeLast(3) }
        val suggested = if (cat.kind != TxKind.SPESA || complete.isEmpty()) null else
            PlanEngine.suggestedBudget(complete.fold(BigDecimal.ZERO) { a, m -> a + m.second }.divide(BigDecimal(complete.size), 10, RoundingMode.HALF_UP)).takeIf { it.signum() > 0 }

        val prevLabel = when {
            prevRange == null -> null
            filter.period == Period.CurrentMonth || filter.period is Period.Month -> "rispetto a " + it.spese.core.common.Dates.monthLabelLong(YearMonth.from(prevRange.first))
            else -> "rispetto al periodo precedente"
        }
        return CategoryDetail(cat, kids, total, prevTotal, cur.size, Money.percent(total, kindTotal), prevLabel, months, avg, merchants, drivers, subs, budget, suggested, ref)
    }
}
