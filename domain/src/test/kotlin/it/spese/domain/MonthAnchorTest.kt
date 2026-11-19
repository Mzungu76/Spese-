package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.MonthAnchor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MonthAnchorTest {
    private fun d(s: String) = LocalDate.parse(s)
    private var n = 0L
    private val salary = Category(5, "Stipendio", TxKind.ENTRATA, false, 0xFF00AA00)
    private val other = Category(6, "Extra", TxKind.ENTRATA, false, 0xFF0000AA)
    private val byId = mapOf(5L to salary, 6L to other)
    private fun inc(date: String, cat: Long = 5) = Transaction(++n, "e$n", 1L, d(date), d(date), "Entrata", BigDecimal(1500), TxKind.ENTRATA, cat)

    @Test fun `senza categoria scelta vale il giorno fisso`() {
        val cal = MonthAnchor.calendar(21, 0, listOf(inc("2026-10-27")), byId, d("2026-11-10"))
        assertTrue(cal.starts.isEmpty()); assertEquals(d("2026-11-21"), cal.start(YearMonth.of(2026, 11)))
    }

    @Test fun `il mese scatta il giorno in cui arriva l'entrata scelta`() {
        // data presunta 21; lo stipendio di novembre arriva il 17, quello di dicembre il 24
        val tx = listOf(inc("2026-10-21"), inc("2026-11-17"), inc("2026-12-24"), inc("2026-11-18", cat = 6))
        val cal = MonthAnchor.calendar(21, 5, tx, byId, d("2026-12-30"))
        assertEquals(d("2026-11-17"), cal.start(YearMonth.of(2026, 11)))
        assertEquals(d("2026-12-24"), cal.start(YearMonth.of(2026, 12)))
        assertEquals(d("2026-11-16"), cal.end(YearMonth.of(2026, 10)))                 // ottobre finisce il giorno prima dell'arrivo
        assertEquals(YearMonth.of(2026, 10), cal.monthOf(d("2026-11-16")))
        assertEquals(YearMonth.of(2026, 11), cal.monthOf(d("2026-11-17")))
        assertEquals(YearMonth.of(2026, 11), cal.monthOf(d("2026-12-23")))
        assertEquals(YearMonth.of(2026, 12), cal.monthOf(d("2026-12-24")))
    }

    @Test fun `finche l'entrata non arriva il mese precedente continua`() {
        val tx = listOf(inc("2026-10-21"))
        // oggi 25/11: la data presunta (21) è passata ma lo stipendio non è arrivato: si è ancora in ottobre
        val waiting = MonthAnchor.calendar(21, 5, tx, byId, d("2026-11-25"))
        assertEquals(YearMonth.of(2026, 10), waiting.monthOf(d("2026-11-25")))
        // dopo la finestra (10 giorni) senza entrata, vale la data presunta
        val late = MonthAnchor.calendar(21, 5, tx, byId, d("2026-12-05"))
        assertEquals(YearMonth.of(2026, 11), late.monthOf(d("2026-11-25")))
        // quando arriva, il mese parte da lì
        val arrived = MonthAnchor.calendar(21, 5, tx + inc("2026-11-26"), byId, d("2026-11-26"))
        assertEquals(YearMonth.of(2026, 11), arrived.monthOf(d("2026-11-26")))
        assertEquals(YearMonth.of(2026, 10), arrived.monthOf(d("2026-11-25")))
    }
}
