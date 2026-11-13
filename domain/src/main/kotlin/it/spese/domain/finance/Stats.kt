package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.TreeMap

data class MonthStat(
    val income: BigDecimal,
    val expense: BigDecimal,
    /** Spese del mese per categoria (valori positivi). Chiave null = senza categoria. */
    val byCategory: Map<Long?, BigDecimal>,
) {
    val net: BigDecimal get() = income - expense
}

data class MerchantStat(val count: Int, val total: BigDecimal)

/**
 * Statistiche aggregate. I trasferimenti (giroconti) sono sommati a parte e non entrano mai in entrate/spese.
 * `expense` e `byCategory` sono valori positivi (spesa = -importo): un rimborso in una categoria di spesa li riduce.
 */
data class PeriodStats(
    val income: BigDecimal,
    val expense: BigDecimal,
    val transfers: BigDecimal,
    val byCategory: Map<Long?, BigDecimal>,
    val byMonth: Map<YearMonth, MonthStat>,
    val byMerchant: Map<String, MerchantStat>,
) {
    val net: BigDecimal get() = income - expense

    /** Tasso di risparmio in % intera (null se non ci sono entrate). */
    val savingsRatePercent: Int?
        get() = if (income.signum() > 0) net.multiply(Money.HUNDRED).divide(income, 0, RoundingMode.HALF_UP).toInt() else null

    /** Categorie con spesa positiva, in ordine decrescente. */
    val topCategories: List<Pair<Long?, BigDecimal>>
        get() = byCategory.entries.filter { it.value.signum() > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
}

object StatsEngine {
    val EMPTY = compute(emptyList())

    fun compute(list: List<Transaction>): PeriodStats {
        var inc = BigDecimal.ZERO; var exp = BigDecimal.ZERO; var tr = BigDecimal.ZERO
        val byCat = HashMap<Long?, BigDecimal>()
        class MB { var inc = BigDecimal.ZERO; var exp = BigDecimal.ZERO; val cats = HashMap<Long?, BigDecimal>() }
        val months = TreeMap<YearMonth, MB>()
        class MM { var n = 0; var t = BigDecimal.ZERO }
        val merch = HashMap<String, MM>()
        for (x in list) {
            val ym = YearMonth.from(x.bookingDate)
            val bm = months.getOrPut(ym) { MB() }
            when (x.kind) {
                TxKind.TRASFERIMENTO -> tr += x.amount
                TxKind.ENTRATA -> { inc += x.amount; bm.inc += x.amount }
                TxKind.SPESA -> {
                    exp -= x.amount; bm.exp -= x.amount
                    byCat.merge(x.categoryId, x.amount.negate()) { a, b -> a + b }
                    bm.cats.merge(x.categoryId, x.amount.negate()) { a, b -> a + b }
                    val e = merch.getOrPut(x.merchant) { MM() }
                    e.n++; e.t -= x.amount
                }
            }
        }
        return PeriodStats(
            inc, exp, tr, byCat,
            months.mapValuesTo(TreeMap()) { MonthStat(it.value.inc, it.value.exp, it.value.cats) },
            merch.mapValues { MerchantStat(it.value.n, it.value.t) },
        )
    }

    /** Numero di mesi coperti dalla lista (giorni / 30,44, minimo 1), come `monthsSpan` della versione HTML. */
    fun monthsSpan(list: List<Transaction>): BigDecimal {
        if (list.isEmpty()) return BigDecimal.ONE
        val a = list.minOf { it.bookingDate }; val b = list.maxOf { it.bookingDate }
        val days = ChronoUnit.DAYS.between(a, b) + 1
        return BigDecimal(days).divide(BigDecimal("30.44"), 10, RoundingMode.HALF_UP).max(BigDecimal.ONE)
    }

    /** Elenco continuo dei mesi coperti dalle statistiche. */
    fun allMonths(byMonth: Map<YearMonth, MonthStat>): List<YearMonth> {
        if (byMonth.isEmpty()) return emptyList()
        val ks = byMonth.keys.sorted()
        val out = ArrayList<YearMonth>()
        var m = ks.first()
        while (!m.isAfter(ks.last())) { out.add(m); m = m.plusMonths(1) }
        return out
    }

    /** Saldo cumulato (entrate − spese) mese per mese. */
    fun cumulativeNet(stats: PeriodStats): List<Pair<YearMonth, BigDecimal>> {
        var run = BigDecimal.ZERO
        return allMonths(stats.byMonth).map { m ->
            run += stats.byMonth[m]?.net ?: BigDecimal.ZERO
            m to run
        }
    }

    /** Le N spese singole più alte. */
    fun biggestExpenses(list: List<Transaction>, n: Int = 10): List<Transaction> =
        list.filter { it.kind == TxKind.SPESA }.sortedBy { it.amount }.take(n)

    /** Principali esercenti per spesa totale. */
    fun topMerchants(stats: PeriodStats, n: Int = 12): List<Pair<String, MerchantStat>> =
        stats.byMerchant.entries.sortedByDescending { it.value.total }.take(n).map { it.key to it.value }

    /** Saldo reale dei conti: saldo iniziale + somma di tutti i movimenti (trasferimenti compresi). */
    fun balance(list: List<Transaction>, opening: BigDecimal = BigDecimal.ZERO): BigDecimal =
        list.fold(opening) { acc, t -> acc + t.amount }

    fun lastDate(list: List<Transaction>): LocalDate? = list.maxOfOrNull { it.bookingDate }
}
