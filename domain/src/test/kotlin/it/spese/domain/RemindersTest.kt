package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.reminders.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class RemindersTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun at(s: String) = LocalDateTime.parse(s)
    private var n = 0L
    private fun tx(acc: Long, date: String, origin: TxOrigin) = Transaction(++n, "e$n", acc, d(date), d(date), "x", BigDecimal(-5), TxKind.SPESA, null, origin = origin)
    private val accounts = listOf(Account(1, "Revolut"), Account(2, "Fineco"))
    private fun r(repeat: ReminderRepeat, kind: ReminderKind = ReminderKind.CUSTOM, h: Int = 9, m: Int = 30, w: Int = 1, dom: Int = 1, date: LocalDate? = null, acc: Long? = null) =
        Reminder(1, kind, "T", "nota", acc, repeat, w, dom, date, h, m)

    @Test fun `una volta sola suona solo se e nel futuro`() {
        val x = r(ReminderRepeat.ONCE, date = d("2026-11-20"))
        assertEquals(at("2026-11-20T09:30"), ReminderEngine.next(x, at("2026-11-18T12:00")))
        assertNull(ReminderEngine.next(x, at("2026-11-20T09:30")))
        assertNull(ReminderEngine.next(x, at("2026-11-21T00:00")))
    }

    @Test fun `ogni giorno oggi se l'ora non e passata, altrimenti domani`() {
        val x = r(ReminderRepeat.DAILY)
        assertEquals(at("2026-11-18T09:30"), ReminderEngine.next(x, at("2026-11-18T08:00")))
        assertEquals(at("2026-11-19T09:30"), ReminderEngine.next(x, at("2026-11-18T09:30")))
    }

    @Test fun `ogni settimana nel giorno scelto`() {
        val x = r(ReminderRepeat.WEEKLY, w = 5)   // venerdì
        assertEquals(at("2026-11-20T09:30"), ReminderEngine.next(x, at("2026-11-18T10:00")))     // 18/11/2026 è mercoledì
        assertEquals(at("2026-11-27T09:30"), ReminderEngine.next(x, at("2026-11-20T09:30")))
    }

    @Test fun `ogni mese, e il 31 nei mesi corti vale l'ultimo giorno`() {
        val x = r(ReminderRepeat.MONTHLY, dom = 31)
        assertEquals(at("2026-11-30T09:30"), ReminderEngine.next(x, at("2026-11-18T10:00")))
        assertEquals(at("2026-12-31T09:30"), ReminderEngine.next(x, at("2026-11-30T09:30")))
        assertEquals(at("2027-02-28T09:30"), ReminderEngine.next(x, at("2027-01-31T09:30")))
    }

    @Test fun `estratto conto - dice da quando importare conto per conto`() {
        val ledger = listOf(tx(1, "2026-10-12", TxOrigin.IMPORT_FILE), tx(1, "2026-11-10", TxOrigin.NOTIFICATION), tx(2, "2026-11-01", TxOrigin.MANUAL))
        val all = ReminderEngine.message(r(ReminderRepeat.MONTHLY, ReminderKind.IMPORT), accounts, ledger, d("2026-11-18"))
        assertTrue(all.text, all.text.contains("Revolut: importa dal 12/10/2026 al 18/11/2026"))      // la notifica provvisoria non sposta la data
        assertTrue(all.text, all.text.contains("Fineco: importa dal 01/11/2026"))                      // senza import da file vale l'ultimo definitivo
        val one = ReminderEngine.message(r(ReminderRepeat.MONTHLY, ReminderKind.IMPORT, acc = 1), accounts, ledger, d("2026-11-18"))
        assertFalse(one.text.contains("Fineco")); assertEquals("T", one.title)
    }

    @Test fun `estratto conto - conto senza movimenti o gia aggiornato`() {
        val ledger = listOf(tx(2, "2026-11-18", TxOrigin.IMPORT_FILE))
        val m = ReminderEngine.message(r(ReminderRepeat.MONTHLY, ReminderKind.IMPORT), accounts, ledger, d("2026-11-18"))
        assertTrue(m.text, m.text.contains("Revolut: nessun movimento ancora")); assertTrue(m.text, m.text.contains("Fineco: già aggiornato"))
    }

    @Test fun `sveglia personale riporta il testo scritto`() {
        val m = ReminderEngine.message(r(ReminderRepeat.DAILY), accounts, emptyList(), d("2026-11-18"))
        assertEquals("T", m.title); assertEquals("nota", m.text)
    }

    @Test fun `salvataggio e lettura restituiscono le stesse sveglie`() {
        val list = listOf(r(ReminderRepeat.ONCE, date = d("2026-12-01")).copy(id = 3), r(ReminderRepeat.WEEKLY, ReminderKind.IMPORT, w = 3, acc = 2).copy(id = 4, enabled = false, note = "a \"b\"\nc"))
        assertEquals(list, ReminderCodec.decode(ReminderCodec.encode(list)))
        assertTrue(ReminderCodec.decode("non json").isEmpty()); assertTrue(ReminderCodec.decode(null).isEmpty())
    }
}
