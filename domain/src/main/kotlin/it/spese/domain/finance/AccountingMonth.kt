package it.spese.domain.finance

import it.spese.core.common.MonthCalendar
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import java.time.LocalDate
import java.time.YearMonth

/**
 * Mese contabile scelto per un'entrata: uno stipendio che arriva il 16 con il mese che finisce il 20 conta
 * per il mese in corso, ma l'utente può volerlo nel successivo. Solo le analisi usano la data «spostata»;
 * saldi, patrimonio ed elenchi continuano a usare la data reale.
 */
object AccountingMonth {
    /** Quanti giorni prima della fine del mese contabile un'entrata viene sottoposta alla scelta. */
    const val DEFAULT_ASK_DAYS = 7

    /** Data usata dalle analisi: quella reale, portata dentro il mese scelto se cade prima del suo inizio (o dopo la fine). */
    fun effectiveDate(t: Transaction, cal: MonthCalendar): LocalDate {
        val m = t.accountingMonth ?: return t.bookingDate
        val start = cal.start(m)
        val end = cal.end(m)
        return when {
            t.bookingDate < start -> start
            t.bookingDate > end -> end
            else -> t.bookingDate
        }
    }

    /** Applica la scelta alle sole entrate con un mese scelto (le altre restano identiche). */
    fun shift(list: List<Transaction>, cal: MonthCalendar): List<Transaction> =
        if (list.none { it.accountingMonth != null && it.kind == TxKind.ENTRATA }) list
        else list.map { t ->
            if (t.accountingMonth == null || t.kind != TxKind.ENTRATA) t
            else effectiveDate(t, cal).let { d -> if (d == t.bookingDate) t else t.copy(bookingDate = d, valueDate = d) }
        }

    /** I mesi tra cui scegliere: quello della data e il successivo. */
    fun choices(date: LocalDate, cal: MonthCalendar): List<YearMonth> = cal.monthOf(date).let { listOf(it, it.plusMonths(1)) }

    /**
     * Un'entrata va chiesta se non ha ancora una scelta, non è stata già controllata e cade negli ultimi
     * [askDays] giorni del suo mese contabile (o è comunque futura rispetto alla sua fine).
     */
    fun needsChoice(t: Transaction, cal: MonthCalendar, askDays: Int = DEFAULT_ASK_DAYS): Boolean {
        if (t.kind != TxKind.ENTRATA || t.accountingMonth != null || t.reviewed || t.accountId == null) return false
        if (askDays <= 0) return false
        val end = cal.end(cal.monthOf(t.bookingDate))
        return t.bookingDate >= end.minusDays(askDays.toLong() - 1)
    }
}
