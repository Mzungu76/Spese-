package it.spese.domain.finance

import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Periodi predefiniti (stessi della versione HTML: Tutto, Anno, 3 mesi, 6 mesi, Mese corrente + mese singolo e intervallo). */
sealed interface Period {
    data object All : Period
    data object YearToDate : Period
    data object Last3Months : Period
    data object Last6Months : Period
    data object CurrentMonth : Period
    data class Month(val month: YearMonth) : Period
    data class Custom(val from: LocalDate?, val to: LocalDate?) : Period
}

data class DateRange(val from: LocalDate?, val to: LocalDate?) {
    operator fun contains(d: LocalDate) = (from == null || d >= from) && (to == null || d <= to)
}

object PeriodResolver {
    /** Risolve un periodo rispetto all'intervallo dei dati (come `setPeriod` dell'HTML: l'estremo finale è l'ultimo movimento). */
    fun resolve(period: Period, dataMin: LocalDate?, dataMax: LocalDate?): DateRange {
        if (dataMin == null || dataMax == null) return when (period) {
            is Period.Custom -> DateRange(period.from, period.to)
            else -> DateRange(null, null)
        }
        return when (period) {
            Period.All -> DateRange(dataMin, dataMax)
            Period.YearToDate -> DateRange(maxOf(LocalDate.of(dataMax.year, 1, 1), dataMin), dataMax)
            Period.CurrentMonth -> DateRange(maxOf(dataMax.withDayOfMonth(1), dataMin), dataMax)
            Period.Last3Months -> DateRange(maxOf(YearMonth.from(dataMax).minusMonths(2).atDay(1), dataMin), dataMax)
            Period.Last6Months -> DateRange(maxOf(YearMonth.from(dataMax).minusMonths(5).atDay(1), dataMin), dataMax)
            is Period.Month -> DateRange(period.month.atDay(1), period.month.atEndOfMonth())
            is Period.Custom -> DateRange(period.from, period.to)
        }
    }
}

/** Filtri applicabili a tutta l'applicazione: periodo, conto, categoria, tipo, testo, importo. */
data class TxFilter(
    val period: Period = Period.All,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val kind: TxKind? = null,
    val query: String = "",
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
) {
    val activeCount: Int
        get() = listOfNotNull(accountId, categoryId, kind, minAmount, maxAmount).size +
            (if (period is Period.Custom) 1 else 0) + (if (query.isNotBlank()) 1 else 0)
}

object FilterEngine {
    /**
     * Applica il filtro. Con un testo di ricerca si cerca in TUTTO lo storico (il periodo scelto viene ignorato: altrimenti
     * un esercente di un altro mese sembrerebbe "non trovato") in descrizione, esercente, note, categoria (e principale), conto, data e importo.
     */
    fun apply(all: List<Transaction>, filter: TxFilter, categories: Map<Long, Category>, accounts: Map<Long, String> = emptyMap()): List<Transaction> {
        val tokens = SearchMatcher.tokens(filter.query)
        val searching = tokens.isNotEmpty()
        val period = if (searching) Period.All else filter.period
        val range = if (all.isEmpty()) PeriodResolver.resolve(period, null, null)
        else PeriodResolver.resolve(period, all.minOf { it.bookingDate }, all.maxOf { it.bookingDate })
        // filtrando una categoria principale sono incluse anche le sue sottocategorie
        val catIds = filter.categoryId?.let { CategoryTree.withChildren(it, categories.values) }
        return all.filter { x ->
            (searching || x.bookingDate in range) &&
                (filter.accountId == null || x.accountId == filter.accountId) &&
                (catIds == null || x.categoryId in catIds) &&
                (filter.kind == null || x.kind == filter.kind) &&
                (filter.minAmount == null || x.amount.abs() >= filter.minAmount) &&
                (filter.maxAmount == null || x.amount.abs() <= filter.maxAmount) &&
                (!searching || SearchMatcher.matches(tokens, SearchMatcher.haystack(x, x.merchant, categoryText(x.categoryId, categories), accounts[x.accountId] ?: "")))
        }
    }

    private fun categoryText(id: Long?, categories: Map<Long, Category>): String {
        val c = id?.let { categories[it] } ?: return ""
        return c.name + (c.parentId?.let { categories[it]?.name }?.let { " $it" } ?: "")
    }
}
