package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Una categoria scelta per il confronto: andamento nel periodo e incidenza su entrate e spese. */
data class ComparedCategory(
    val category: Category,
    val total: BigDecimal,
    /** Quota sulle entrate del periodo (null senza entrate). */
    val percentOfIncome: BigDecimal?,
    /** Quota sulle spese del periodo (solo per le categorie di spesa). */
    val percentOfExpense: BigDecimal?,
    /** Importo per ogni punto del grafico (stesso ordine di [CategoryComparison.labels]). */
    val series: List<BigDecimal>,
)

data class CategoryComparison(
    val labels: List<String>, val titles: List<String>, val weekly: Boolean,
    /** Entrate totali del periodo per ogni punto: la linea di riferimento. */
    val income: List<BigDecimal>,
    val categories: List<ComparedCategory>,
    val periodIncome: BigDecimal, val periodExpense: BigDecimal,
    /** Somma delle categorie di spesa scelte e sua incidenza sulle entrate. */
    val selectedExpense: BigDecimal, val selectedExpenseOfIncome: BigDecimal?,
)

/**
 * Confronto di una o più categorie (principali, con le sottocategorie) nel periodo scelto: andamento punto per punto accanto alle
 * entrate del periodo, e incidenza di ciascuna (e del totale) sulle entrate. Le entrate non dipendono dalla selezione.
 */
object CategoryComparisonEngine {
    fun compute(data: AppData, filter: TxFilter, selected: Set<Long>): CategoryComparison? {
        val cats = selected.mapNotNull { data.categoriesById[it] }.filter { it.parentId == null || data.categoriesById[it.parentId] == null }
        if (cats.isEmpty()) return null
        val base = filter.copy(categoryId = null, kind = null, query = "", minAmount = null, maxAmount = null)
        val period = FilterEngine.apply(data.transactions, base, data.categoriesById)
        if (period.isEmpty()) return null
        val cal = filter.calendar
        val buckets = TrendBuckets.compute(period, cal)
        val weekly = buckets.firstOrNull()?.weekly ?: false
        fun key(d: LocalDate): LocalDate = if (weekly) d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else cal.start(cal.monthOf(d))
        val idx = buckets.withIndex().associate { it.value.start to it.index }
        val stats = StatsEngine.compute(period)
        fun magnitude(t: Transaction, kind: TxKind) = if (kind == TxKind.ENTRATA) t.amount else t.amount.negate()

        val compared = cats.map { c ->
            val ids = CategoryTree.withChildren(c.id, data.categories)
            val own = period.filter { it.categoryId in ids && it.kind == c.kind }
            val series = MutableList(buckets.size) { BigDecimal.ZERO }
            own.forEach { t -> idx[key(t.bookingDate)]?.let { i -> series[i] = series[i] + magnitude(t, c.kind) } }
            val total = own.fold(BigDecimal.ZERO) { a, t -> a + magnitude(t, c.kind) }
            ComparedCategory(c, total,
                if (stats.income.signum() > 0) Money.percent(total, stats.income) else null,
                if (c.kind == TxKind.SPESA && stats.expense.signum() > 0) Money.percent(total, stats.expense) else null, series)
        }.sortedByDescending { it.total }
        val selExp = compared.filter { it.category.kind == TxKind.SPESA }.fold(BigDecimal.ZERO) { a, c -> a + c.total }
        return CategoryComparison(
            buckets.map { it.label }, buckets.map { it.title }, weekly, buckets.map { it.income }, compared, stats.income, stats.expense,
            selExp, if (stats.income.signum() > 0) Money.percent(selExp, stats.income) else null,
        )
    }
}
