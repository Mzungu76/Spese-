package it.spese.core.common

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Dates {
    val MESI = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
    val MESI_LUNGHI = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre")
    private val IT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** "dd/MM/yyyy". */
    fun fmt(d: LocalDate): String = d.format(IT)

    /** "gen 24". */
    fun monthLabel(m: YearMonth): String = MESI[m.monthValue - 1] + " " + (m.year % 100).toString().padStart(2, '0')

    fun monthLabelLong(m: YearMonth): String = MESI_LUNGHI[m.monthValue - 1].replaceFirstChar { it.uppercase() } + " " + m.year

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(b, a) // a - b

    fun parseIso(s: String?): LocalDate? = try { if (s.isNullOrBlank()) null else LocalDate.parse(s.take(10)) } catch (e: Exception) { null }

    /** Elenco continuo di mesi da [from] a [to] inclusi. */
    fun monthsBetween(from: YearMonth, to: YearMonth): List<YearMonth> {
        val out = ArrayList<YearMonth>()
        var m = from
        while (!m.isAfter(to)) { out.add(m); m = m.plusMonths(1) }
        return out
    }
}
