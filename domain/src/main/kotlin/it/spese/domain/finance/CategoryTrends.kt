package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData
import it.spese.domain.simulation.Trends
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

enum class TrendDirection { UP, DOWN, STABLE, UNKNOWN }
enum class Confidence { LOW, MEDIUM, HIGH }

/** Valore atteso tra `monthsAhead` mesi se la tendenza continua, e suo effetto cumulato rispetto al livello attuale. */
data class Projection(
    val monthsAhead: Int,
    /** Importo mensile atteso in quel mese. */
    val monthly: BigDecimal,
    /** Somma degli importi attesi dal prossimo mese a quello. */
    val cumulative: BigDecimal,
    /** Effetto cumulato sul bilancio rispetto a restare al livello attuale (negativo = si spende di più / si incassa di meno). */
    val balanceImpact: BigDecimal,
)

/** Tendenza di una categoria (principale, con le sottocategorie) o delle entrate totali. `category` è null solo per le entrate totali. */
data class CategoryTrend(
    val category: Category?,
    val kind: TxKind,
    /** Mesi completi considerati (ultimi 12 al massimo), dal più vecchio. */
    val months: List<Pair<YearMonth, BigDecimal>>,
    /** Media mensile nel periodo scelto. */
    val periodAvg: BigDecimal,
    /** Media mensile prima del periodo (null se non c'è storico precedente). */
    val historyAvg: BigDecimal?,
    val changeVsHistory: BigDecimal?,
    /** Variazione mensile della tendenza (€ al mese) e livello attuale stimato dalla retta. */
    val slope: BigDecimal,
    val level: BigDecimal,
    val direction: TrendDirection,
    val confidence: Confidence,
    val projections: List<Projection>,
) {
    fun projection(months: Int): Projection? = projections.firstOrNull { it.monthsAhead == months }
}

data class BalanceProjection(
    val monthsAhead: Int, val income: BigDecimal, val expense: BigDecimal, val net: BigDecimal,
    /** Differenza del bilancio mensile atteso rispetto a quello attuale. */
    val deltaMonthly: BigDecimal,
    /** Effetto cumulato su `monthsAhead` mesi rispetto a restare al livello attuale. */
    val cumulativeImpact: BigDecimal,
)

data class TrendReport(
    val referenceMonth: YearMonth, val periodLabel: String,
    val categories: List<CategoryTrend>, val income: CategoryTrend?,
    /** Livello mensile attuale stimato di entrate, spese e risparmio. */
    val currentIncome: BigDecimal, val currentExpense: BigDecimal,
    val balance: List<BalanceProjection>,
) {
    val currentNet: BigDecimal get() = currentIncome - currentExpense
}

/**
 * Tendenze per categoria e proiezioni a 1, 3 e 6 mesi "se la tendenza continua".
 * Si usano i mesi completi (ultimi 12): una retta ai minimi quadrati sugli importi mensili dà il livello attuale e la variazione al mese;
 * le proiezioni non scendono sotto zero. Il periodo scelto viene confrontato con la media storica precedente.
 * È una stima statistica semplice, non una previsione: con pochi mesi o molta variabilità la fiducia è bassa e lo dice.
 */
object CategoryTrendEngine {
    val HORIZONS = listOf(1, 3, 6)
    private const val WINDOW = 12

    fun compute(data: AppData, filter: TxFilter, realToday: LocalDate = LocalDate.now()): TrendReport? {
        val all = data.transactions.filter { filter.accountId == null || it.accountId == filter.accountId }
        if (all.isEmpty()) return null
        val cal = filter.calendar
        val ref = cal.monthOf(AlertEngine.referenceToday(realToday, all))
        val first = cal.monthOf(all.minOf { it.bookingDate })
        // mesi completi; se ce ne sono meno di 3 si include anche quello in corso
        var last = ref.minusMonths(1)
        if (ChronoCount.months(first, last) < 3) last = ref
        val start = maxOf(first, last.minusMonths(WINDOW - 1L))
        if (start.isAfter(last)) return null
        val window = generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()

        val byId = data.categoriesById
        val range = PeriodResolver.resolve(filter.period, all.minOf { it.bookingDate }, all.maxOf { it.bookingDate }, cal)
        val periodTx = all.filter { it.bookingDate in range }
        val histTx = if (filter.period == Period.All || range.from == null) emptyList() else all.filter { it.bookingDate < range.from }
        // mesi coperti: dai giorni dell'intervallo (non dalle date dei movimenti, che potrebbero essere rade)
        fun monthsOf(from: LocalDate, to: LocalDate): BigDecimal =
            BigDecimal(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1).divide(BigDecimal("30.44"), 10, RoundingMode.HALF_UP).max(BigDecimal.ONE)
        val periodMonths = if (range.from == null || range.to == null) BigDecimal.ONE else monthsOf(range.from, range.to)
        val histMonths = if (range.from == null) BigDecimal.ONE else monthsOf(cal.start(first), range.from.minusDays(1))

        fun mag(t: Transaction, k: TxKind) = if (k == TxKind.ENTRATA) t.amount else t.amount.negate()
        fun trendOf(cat: Category?, kind: TxKind, match: (Transaction) -> Boolean): CategoryTrend {
            val mine = all.filter { it.kind == kind && match(it) }
            val perMonth = mine.groupBy { cal.monthOf(it.bookingDate) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + mag(t, kind) } }
            val series = window.map { it to (perMonth[it] ?: BigDecimal.ZERO) }
            val pAvg = periodTx.filter { it.kind == kind && match(it) }.fold(BigDecimal.ZERO) { a, t -> a + mag(t, kind) }.divide(periodMonths, 2, RoundingMode.HALF_UP)
            val hAvg = if (histTx.isEmpty()) null else histTx.filter { it.kind == kind && match(it) }.fold(BigDecimal.ZERO) { a, t -> a + mag(t, kind) }.divide(histMonths, 2, RoundingMode.HALF_UP)
            return build(cat, kind, series, pAvg, hAvg)
        }

        val roots = byId.values.filter { it.kind == TxKind.SPESA && (it.parentId == null || byId[it.parentId] == null) }
        val cats = roots.map { c -> val ids = CategoryTree.withChildren(c.id, data.categories); trendOf(c, TxKind.SPESA) { it.categoryId in ids } }
            .filter { t -> t.months.any { it.second.signum() != 0 } || t.periodAvg.signum() != 0 }
        val income = trendOf(null, TxKind.ENTRATA) { true }.takeIf { t -> t.months.any { it.second.signum() != 0 } }

        val curInc = income?.level ?: BigDecimal.ZERO
        val curExp = cats.fold(BigDecimal.ZERO) { a, t -> a + t.level }
        val balance = HORIZONS.map { h ->
            val inc = income?.projection(h)?.monthly ?: BigDecimal.ZERO
            val exp = cats.fold(BigDecimal.ZERO) { a, t -> a + (t.projection(h)?.monthly ?: BigDecimal.ZERO) }
            val cum = (income?.projection(h)?.balanceImpact ?: BigDecimal.ZERO) + cats.fold(BigDecimal.ZERO) { a, t -> a + (t.projection(h)?.balanceImpact ?: BigDecimal.ZERO) }
            BalanceProjection(h, inc, exp, inc - exp, (inc - exp) - (curInc - curExp), cum)
        }
        return TrendReport(ref, periodLabel(filter.period), cats.sortedByDescending { it.projection(6)?.balanceImpact?.abs() ?: BigDecimal.ZERO }, income, curInc, curExp, balance)
    }

    private fun periodLabel(p: Period) = when (p) {
        Period.All -> "tutto il periodo"; Period.YearToDate -> "quest'anno"; Period.Last3Months -> "ultimi 3 mesi"; Period.Last6Months -> "ultimi 6 mesi"
        Period.CurrentMonth -> "mese corrente"; is Period.Month -> "mese scelto"; is Period.Custom -> "intervallo scelto"
    }

    internal fun build(cat: Category?, kind: TxKind, series: List<Pair<YearMonth, BigDecimal>>, periodAvg: BigDecimal, historyAvg: BigDecimal?): CategoryTrend {
        val values = series.map { it.second }
        val n = values.size
        val lin = Trends.linear(values)
        val mean = if (n == 0) BigDecimal.ZERO else values.fold(BigDecimal.ZERO) { a, b -> a + b }.divide(BigDecimal(n), 10, RoundingMode.HALF_UP)
        val slope = lin?.slope ?: BigDecimal.ZERO
        // livello attuale: il valore della retta all'ultimo mese (mai negativo)
        val level = (lin?.at(n - 1) ?: mean).max(BigDecimal.ZERO)
        val flatBase = if (mean.signum() > 0) mean else BigDecimal.ONE
        val slopePct = slope.divide(flatBase, 6, RoundingMode.HALF_UP).toDouble()
        val r2 = rSquared(values, lin)
        val direction = when {
            n < 3 -> TrendDirection.UNKNOWN
            kotlin.math.abs(slopePct) < 0.02 -> TrendDirection.STABLE
            slope.signum() > 0 -> TrendDirection.UP
            else -> TrendDirection.DOWN
        }
        val confidence = when { n < 4 -> Confidence.LOW; n >= 8 && r2 >= 0.5 -> Confidence.HIGH; r2 >= 0.25 -> Confidence.MEDIUM; else -> Confidence.LOW }
        val projected = if (lin == null) List(HORIZONS.last()) { level } else (1..HORIZONS.last()).map { k -> lin.at(n - 1 + k).max(BigDecimal.ZERO) }
        val projections = HORIZONS.map { h ->
            val upTo = projected.take(h)
            val cum = upTo.fold(BigDecimal.ZERO) { a, b -> a + b }
            val diff = upTo.fold(BigDecimal.ZERO) { a, b -> a + (b - level) }
            // spese: crescere peggiora il bilancio; entrate: crescere lo migliora
            Projection(h, projected[h - 1].setScale(2, RoundingMode.HALF_UP), cum.setScale(2, RoundingMode.HALF_UP), (if (kind == TxKind.ENTRATA) diff else diff.negate()).setScale(2, RoundingMode.HALF_UP))
        }
        val change = historyAvg?.let { Trends.changePercent(it, periodAvg) }
        return CategoryTrend(cat, kind, series, periodAvg, historyAvg, change, slope.setScale(2, RoundingMode.HALF_UP), level.setScale(2, RoundingMode.HALF_UP), direction, confidence, projections)
    }

    private fun rSquared(y: List<BigDecimal>, lin: Trends.Linear?): Double {
        if (lin == null || y.size < 3) return 0.0
        val ys = y.map { it.toDouble() }; val mean = ys.average()
        val ssTot = ys.sumOf { (it - mean) * (it - mean) }
        if (ssTot == 0.0) return 1.0
        val ssRes = ys.indices.sumOf { i -> val d = ys[i] - lin.at(i).toDouble(); d * d }
        return (1 - ssRes / ssTot).coerceIn(0.0, 1.0)
    }

    private object ChronoCount { fun months(a: YearMonth, b: YearMonth): Int = if (b.isBefore(a)) 0 else (b.year - a.year) * 12 + (b.monthValue - a.monthValue) + 1 }
}
