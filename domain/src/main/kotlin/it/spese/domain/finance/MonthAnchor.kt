package it.spese.domain.finance

import it.spese.core.common.MonthCalendar
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * Mese contabile ancorato a un'entrata (di solito lo stipendio): l'utente indica una data presunta ([startDay]) e la categoria di entrata che fa scattare
 * il nuovo mese. Il mese inizia il giorno in cui l'entrata arriva davvero, se cade entro [WINDOW_DAYS] giorni dalla data presunta; se non è ancora arrivata
 * (e la finestra non è finita) il mese precedente continua. Senza categoria, o senza entrate trovate, vale il giorno fisso (1 = mese solare).
 */
object MonthAnchor {
    const val WINDOW_DAYS = 10

    fun calendar(startDay: Int, anchorCategoryId: Long, transactions: List<Transaction>, byId: Map<Long, Category>, today: LocalDate, anchorMinAmount: java.math.BigDecimal = java.math.BigDecimal.ZERO): MonthCalendar {
        val base = MonthCalendar(startDay.coerceIn(1, 28))
        if (anchorCategoryId <= 0L && anchorMinAmount.signum() <= 0) return base
        val dates = transactions.filter { t -> t.kind == TxKind.ENTRATA && (anchorCategoryId <= 0L || CategoryTree.rootId(t.categoryId, byId) == anchorCategoryId) && (anchorMinAmount.signum() <= 0 || t.amount >= anchorMinAmount) }.map { t -> t.bookingDate }.sorted()
        if (dates.isEmpty()) return MonthCalendar(base.startDay, emptyMap(), true)
        val starts = LinkedHashMap<YearMonth, LocalDate>()
        var m = YearMonth.from(dates.first()).minusMonths(1)
        val last = YearMonth.from(today).plusMonths(1)
        var waiting: YearMonth? = null
        var prev: LocalDate? = null     // inizio effettivo del mese precedente: i mesi devono restare in ordine
        while (m <= last) {
            val p = base.start(m)
            val near = dates.filter { d -> abs(d.toEpochDay() - p.toEpochDay()) <= WINDOW_DAYS }.minByOrNull { d -> abs(d.toEpochDay() - p.toEpochDay()) }
            // entrata non ancora arrivata ma ancora dentro la finestra: il mese precedente continua fino alla fine della finestra
            val s = near ?: if (today >= p && today <= p.plusDays(WINDOW_DAYS.toLong())) p.plusDays(WINDOW_DAYS + 1L) else null
            if (s != null && (prev == null || s > prev)) starts[m] = s
            if (near == null && s != null) waiting = m
            prev = starts[m] ?: p
            m = m.plusMonths(1)
        }
        return MonthCalendar(base.startDay, starts, true, waiting)
    }
}
