package it.spese.domain.finance

import it.spese.core.common.Dates
import it.spese.core.common.MonthCalendar
import it.spese.core.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Un punto del grafico entrate/spese: un mese (periodi lunghi) o una settimana (periodi brevi). Giroconti esclusi. */
data class Bucket(val label: String, val start: LocalDate, val weekly: Boolean, val income: BigDecimal, val expense: BigDecimal, val daily: Boolean = false) {
    val net: BigDecimal get() = income - expense

    /** Frase mostrata quando si tocca il punto. */
    val title: String
        get() = if (daily) Dates.fmt(start) else if (weekly) "settimana dal ${Dates.fmt(start).take(5)}" else Dates.monthLabelLong(YearMonth.from(start)).lowercase()
}

object TrendBuckets {
    /** Mensile se i dati coprono almeno 2,5 mesi, altrimenti settimanale (lunedì-domenica). */
    fun compute(list: List<Transaction>, cal: MonthCalendar = MonthCalendar.SOLAR): List<Bucket> {
        if (list.isEmpty()) return emptyList()
        return if (StatsEngine.monthsSpan(list) >= BigDecimal("2.5")) monthly(list, cal) else weekly(list)
    }

    /** Un punto per ogni giorno da [from] a [to] (per la settimana in corso: lunedì-domenica, anche i giorni ancora senza movimenti). */
    fun daily(list: List<Transaction>, from: LocalDate, to: LocalDate): List<Bucket> {
        val by = list.groupBy { it.bookingDate }
        val names = listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom")
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val st = StatsEngine.compute(by[d].orEmpty())
            Bucket(names[d.dayOfWeek.value - 1], d, false, st.income, st.expense, daily = true)
        }.toList()
    }

    fun monthly(list: List<Transaction>, cal: MonthCalendar = MonthCalendar.SOLAR): List<Bucket> {
        val s = StatsEngine.compute(list, cal)
        return StatsEngine.allMonths(s.byMonth).map { m ->
            val b = s.byMonth[m]
            Bucket(Dates.monthLabel(m).substringBefore(' '), cal.start(m), false, b?.income ?: BigDecimal.ZERO, b?.expense ?: BigDecimal.ZERO)
        }
    }

    fun weekly(list: List<Transaction>): List<Bucket> {
        val monday = { d: LocalDate -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        val first = monday(list.minOf { it.bookingDate }); val last = monday(list.maxOf { it.bookingDate })
        val by = list.groupBy { monday(it.bookingDate) }
        val out = ArrayList<Bucket>()
        var w = first
        while (!w.isAfter(last)) {
            val st = StatsEngine.compute(by[w].orEmpty())
            out.add(Bucket(Dates.fmt(w).take(5), w, true, st.income, st.expense))
            w = w.plusWeeks(1)
        }
        return out
    }

    /** Quota di risparmio sul totale incassato (0..100, 1 decimale). */
    fun savingsShare(income: BigDecimal, net: BigDecimal): BigDecimal =
        if (income.signum() <= 0) BigDecimal.ZERO else net.multiply(BigDecimal(100)).divide(income, 0, RoundingMode.HALF_UP)
}
