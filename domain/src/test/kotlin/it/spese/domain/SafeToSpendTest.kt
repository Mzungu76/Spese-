package it.spese.domain

import it.spese.core.common.MonthCalendar
import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SafeToSpendTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))

    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long = 1) = Transaction(++n, "e$n", 1, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val cats = listOf(
        Category(1, "Spesa", TxKind.SPESA, true, 0), Category(2, "Stipendio", TxKind.ENTRATA, false, 0),
        Category(4, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED),
        Category(5, "Bollette luce", TxKind.SPESA, false, 0, parentId = 4),
    )
    /** Tre mesi pieni di storico (entrate 2.000, spese 100) e marzo a metà. */
    private fun history() = listOf(tx("2025-12-01", 2000, 2), tx("2025-12-10", -100), tx("2026-01-01", 2000, 2), tx("2026-01-10", -100), tx("2026-02-01", 2000, 2), tx("2026-02-10", -100))
    private fun data(txs: List<Transaction>, cal: MonthCalendar = MonthCalendar.SOLAR, dues: List<Due> = emptyList()) =
        AppData(transactions = txs, categories = cats, accounts = listOf(Account(1, "C")), dues = dues, loaded = true, calendar = cal)
    private val march = listOf(tx("2026-03-01", 2000, 2), tx("2026-03-03", -500, 4), tx("2026-03-05", -300))
    private val rent = RecurringRule(1, "Palestra", TxKind.SPESA, BigDecimal(100), dayOfMonth = 25, startDate = d("2026-01-01"), lastGenerated = d("2026-02-25"))

    @Test fun `disponibile = entrate attese - risparmio - speso - uscite note, diviso per i giorni che restano`() {
        val r = SafeToSpendEngine.compute(data(history() + march), listOf(rent), realToday = d("2026-03-15"))!!
        assertEquals(d("2026-03-01"), r.periodStart); assertEquals(d("2026-03-31"), r.periodEnd)
        assertEquals(17, r.daysLeft); assertEquals(31, r.daysTotal)
        eq("2000", r.expectedIncome); eq("300", r.savingsTarget); eq("800", r.spentToDate); eq("500", r.fixedPaid)
        assertEquals(1, r.upcoming.size); assertEquals(d("2026-03-25"), r.upcoming[0].date); eq("100", r.upcomingTotal)
        eq("800", r.available); eq("47.06", r.perDay)
        // piano: 1.100 di spesa variabile in 31 giorni; al giorno 15 ne spettavano 532,26 e ne hai spesi 300
        eq("1100", r.variableBudget); eq("232.26", r.deltaVsPlan)
        assertEquals(SafeStatus.OK, r.status); assertFalse(r.stale)
    }

    @Test fun `una spesa fissa già pagata non pesa sul ritmo, la variabile resta il confronto`() {
        val r = SafeToSpendEngine.compute(data(history() + march), listOf(rent), realToday = d("2026-03-15"))!!
        eq("300", r.variableSpent)
        val sub = SafeToSpendEngine.compute(data(history() + march + tx("2026-03-06", -50, 5)), listOf(rent), realToday = d("2026-03-15"))!!   // sottocategoria di Affitto: natura ereditata
        eq("550", sub.fixedPaid)
    }

    @Test fun `oltre il margine il disponibile diventa negativo`() {
        val r = SafeToSpendEngine.compute(data(history() + march + tx("2026-03-10", -1200)), listOf(rent), realToday = d("2026-03-15"))!!
        assertTrue(r.available.signum() < 0); assertEquals(SafeStatus.OVER, r.status); assertTrue(r.perDay!!.signum() < 0)
    }

    @Test fun `in ritardo sul piano ma ancora dentro il margine`() {
        // 1.000 di spesa variabile al giorno 5: il margine di marzo regge (available > 0) ma si va più veloci del piano
        val txs = history() + listOf(tx("2026-03-01", 2000, 2), tx("2026-03-02", -900))
        val r = SafeToSpendEngine.compute(data(txs), realToday = d("2026-03-05"))!!
        assertTrue(r.available.signum() > 0); assertTrue(r.deltaVsPlan.signum() < 0); assertEquals(SafeStatus.BEHIND, r.status)
    }

    @Test fun `ricorrenza già generata, abbonamento tracciato e scadenza già pagata non si contano due volte`() {
        val generated = rent.copy(lastGenerated = d("2026-03-25"))
        val tracked = RecurringPattern("netflix", "Netflix", Cadence.MONTHLY, PatternKind.SUBSCRIPTION, 4, d("2025-11-20"), d("2026-02-20"), d("2026-03-20"),
            BigDecimal("12.99"), BigDecimal("12.99"), 1, 1, tracked = true)
        val free = tracked.copy(key = "spotify", merchant = "Spotify", tracked = false)
        val due = Due(1, "d1", "Bollo auto", BigDecimal(150), d("2026-03-28"), DueFrequency.YEARLY)
        val farDue = due.copy(id = 2, name = "Assicurazione", next = d("2026-06-01"))
        val r = SafeToSpendEngine.compute(data(history() + march, dues = listOf(due, farDue)), listOf(generated), listOf(tracked, free), realToday = d("2026-03-15"))!!
        assertEquals(listOf("Spotify", "Bollo auto"), r.upcoming.map { it.name })
        eq("162.99", r.upcomingTotal)
    }

    @Test fun `senza storico e senza entrate il numero non è affidabile`() {
        val r = SafeToSpendEngine.compute(data(listOf(tx("2026-03-02", -40))), realToday = d("2026-03-15"))!!
        assertEquals(SafeStatus.NOT_ENOUGH, r.status)
        assertNull(SafeToSpendEngine.compute(data(emptyList()), realToday = d("2026-03-15")))
    }

    @Test fun `dati vecchi di oltre 45 giorni - si calcola sull'ultimo movimento e lo si dichiara`() {
        val r = SafeToSpendEngine.compute(data(history() + march), realToday = d("2026-06-30"))!!
        assertTrue(r.stale); assertEquals(d("2026-03-05"), r.today)
    }

    @Test fun `mese contabile dal 20`() {
        val c = MonthCalendar(20)
        val txs = listOf(tx("2025-12-20", 2000, 2), tx("2026-01-20", 2000, 2), tx("2026-02-20", 2000, 2), tx("2026-03-20", 2000, 2), tx("2026-03-22", -100))
        val r = SafeToSpendEngine.compute(data(txs, c), realToday = d("2026-03-25"))!!
        assertEquals(d("2026-03-20"), r.periodStart); assertEquals(d("2026-04-19"), r.periodEnd)
        assertEquals(31, r.daysTotal); assertEquals(26, r.daysLeft)
        eq("100", r.spentToDate)
    }
}
