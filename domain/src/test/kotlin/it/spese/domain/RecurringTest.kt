package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.capture.NotificationReconciler
import it.spese.domain.finance.*
import it.spese.domain.recurring.RecurringEngine
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RecurringTest {
    private fun rule(start: String, day: Int = 27, every: Int = 1, end: String? = null, last: String? = null, enabled: Boolean = true) =
        RecurringRule(1, "Stipendio", TxKind.ENTRATA, BigDecimal(2000), null, null, every, day, LocalDate.parse(start), end?.let { LocalDate.parse(it) }, enabled, false, last?.let { LocalDate.parse(it) })
    private fun due(r: RecurringRule, to: String) = RecurringEngine.due(r, LocalDate.parse(to)).map { it.toString() }

    @Test fun `ogni mese dal mese di inizio fino a oggi, senza date future`() {
        assertEquals(listOf("2026-07-27", "2026-08-27", "2026-09-27"), due(rule("2026-07-27"), "2026-10-14"))
        assertEquals(listOf("2026-07-27", "2026-08-27", "2026-09-27", "2026-10-27"), due(rule("2026-07-27"), "2026-10-27"))   // il giorno stesso conta
        assertTrue(due(rule("2026-10-28"), "2026-10-14").isEmpty())                                             // parte da domani
        // il primo mese: se il giorno è già passato rispetto alla data di inizio, non si crea all'indietro
        assertEquals(listOf("2026-10-27"), due(rule("2026-10-15"), "2026-10-30"))
        assertEquals(listOf("2026-11-27"), due(rule("2026-10-28"), "2026-11-30"))
    }

    @Test fun `giorno 31 nei mesi corti, frequenze, fine e ultima data generata`() {
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"), due(rule("2026-01-01", day = 31), "2026-04-30"))
        assertEquals(listOf("2028-02-29"), due(rule("2028-02-01", day = 31), "2028-02-29"))                     // anno bisestile
        assertEquals(listOf("2026-01-10", "2026-04-10", "2026-07-10"), due(rule("2026-01-01", day = 10, every = 3), "2026-08-01"))
        assertEquals(listOf("2025-03-05", "2026-03-05"), due(rule("2025-03-01", day = 5, every = 12), "2026-12-31"))
        assertEquals(listOf("2026-07-27", "2026-08-27"), due(rule("2026-07-27", end = "2026-08-31"), "2026-12-31"))   // fine
        assertEquals(listOf("2026-09-27"), due(rule("2026-07-27", last = "2026-08-27"), "2026-09-30"))                // dopo l'ultima già creata
        assertTrue(due(rule("2026-07-27", enabled = false), "2026-12-31").isEmpty())
    }

    @Test fun `prossima data e descrizione`() {
        assertEquals(LocalDate.parse("2026-10-27"), RecurringEngine.next(rule("2026-07-27"), LocalDate.parse("2026-10-14")))
        assertEquals(LocalDate.parse("2026-11-27"), RecurringEngine.next(rule("2026-07-27"), LocalDate.parse("2026-10-27")))
        assertNull(RecurringEngine.next(rule("2026-07-27", end = "2026-08-31"), LocalDate.parse("2026-10-14")))
        assertEquals("ogni mese, il giorno 27", RecurringEngine.describe(rule("2026-07-27"))); assertEquals("ogni 3 mesi, il giorno 10", RecurringEngine.describe(rule("2026-01-01", day = 10, every = 3)))
    }

    private fun prov(id: Long, amount: Int, date: String, origin: TxOrigin) = Transaction(id, "e$id", 1, LocalDate.parse(date), LocalDate.parse(date), "x", BigDecimal(amount), TxKind.ENTRATA, null, origin = origin)

    @Test fun `abbinamento con l'estratto, 5 giorni di tolleranza per i ricorrenti e 3 per le notifiche`() {
        val rec = prov(1, 2000, "2026-10-27", TxOrigin.RECURRING); val notif = prov(2, 50, "2026-10-27", TxOrigin.NOTIFICATION)
        val rows = listOf(NotificationReconciler.Row(0, BigDecimal(2000), LocalDate.parse("2026-11-01")), NotificationReconciler.Row(1, BigDecimal(50), LocalDate.parse("2026-11-01")))
        val m = NotificationReconciler.match(listOf(rec, notif), rows)
        assertEquals(1L, m[0]!!.id); assertFalse(1 in m)              // 5 giorni: il ricorrente sì; 5 giorni: la notifica no
        assertTrue(NotificationReconciler.match(listOf(rec), listOf(NotificationReconciler.Row(0, BigDecimal(2000), LocalDate.parse("2026-11-04")))).isEmpty())   // 8 giorni: troppi
    }

    @Test fun `un movimento ricorrente finisce in verifica finche non e confermato`() {
        val t = prov(1, 2000, "2026-10-27", TxOrigin.RECURRING)
        val items = ReviewEngine.items(listOf(t), emptyMap(), emptyList(), emptyList())
        assertEquals(listOf(ReviewReason.RECURRING), items.single().notes.map { it.reason })
        assertTrue(ReviewEngine.items(listOf(t.copy(reviewed = true)), emptyMap(), emptyList(), emptyList()).isEmpty())
    }
}
