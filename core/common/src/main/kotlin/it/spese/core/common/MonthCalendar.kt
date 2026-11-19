package it.spese.core.common

import java.time.LocalDate
import java.time.YearMonth

/**
 * Mese contabile. Di norma coincide con il mese di calendario (`startDay` = 1); con `startDay` = 20 un "mese" va dal 20 al 19 del
 * successivo (utile se lo stipendio arriva il 20). Il mese prende il nome da quello in cui **inizia**: il periodo 20/01 – 19/02 è "gennaio".
 * Il giorno iniziale è limitato a 1–28 così esiste in ogni mese.
 */
data class MonthCalendar(val startDay: Int = 1, val starts: Map<YearMonth, LocalDate> = emptyMap(),
    /** Mese ancorato a un'entrata (data presunta + importo/categoria). */ val anchored: Boolean = false,
    /** Mese la cui entrata di scavalco è attesa ma non ancora arrivata (si è ancora in quello precedente). */ val waiting: YearMonth? = null) {
    init { require(startDay in 1..28) { "Il giorno iniziale del mese contabile va da 1 a 28." } }

    val isSolar: Boolean get() = startDay == 1 && starts.isEmpty() && !anchored

    /** Mese contabile (identificato dal mese in cui inizia) a cui appartiene la data. */
    fun monthOf(d: LocalDate): YearMonth {
        if (starts.isEmpty()) return if (d.dayOfMonth >= startDay) YearMonth.from(d) else YearMonth.from(d).minusMonths(1)
        val c = YearMonth.from(d)
        return listOf(c.plusMonths(1), c, c.minusMonths(1)).firstOrNull { m -> start(m) <= d } ?: c.minusMonths(1)
    }

    /** Inizio del mese: la data in cui è arrivata l'entrata che lo fa scattare ([starts]), altrimenti il giorno impostato. */
    fun start(m: YearMonth): LocalDate = starts[m] ?: m.atDay(startDay)
    fun end(m: YearMonth): LocalDate = start(m.plusMonths(1)).minusDays(1)
    /** Quanti giorni dura il mese contabile. */
    fun length(m: YearMonth): Int = (end(m).toEpochDay() - start(m).toEpochDay() + 1).toInt()
    /** Giorno del mese contabile (1 = primo giorno) in cui cade la data. */
    fun dayIndex(d: LocalDate): Int = (d.toEpochDay() - start(monthOf(d)).toEpochDay() + 1).toInt()

    /** "Gennaio 2026", oppure "Gennaio 2026 (20/01 – 19/02)" se il mese non segue il calendario. */
    fun label(m: YearMonth): String = if (isSolar) Dates.monthLabelLong(m) else Dates.monthLabelLong(m) + " (" + short(start(m)) + " – " + short(end(m)) + ")"
    private fun short(d: LocalDate) = Dates.fmt(d).take(5)

    companion object { val SOLAR = MonthCalendar(1) }
}
