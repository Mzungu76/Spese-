package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTest {
    private var n = 0L
    private fun tx(date: String, amount: String, desc: String, cat: Long? = null) =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount), TxKind.SPESA, cat)
    private val today = LocalDate.parse("2026-11-20")
    private fun find(l: List<RecurringPattern>, k: String) = l.first { it.key.contains(k) }

    private val txs = listOf(
        // abbonamento mensile, con aumento di prezzo l'ultimo mese
        tx("2026-07-05", "-12.99", "NETFLIX.COM"), tx("2026-08-05", "-12.99", "NETFLIX.COM"), tx("2026-09-06", "-12.99", "NETFLIX.COM"), tx("2026-10-05", "-12.99", "NETFLIX.COM"), tx("2026-11-05", "-15.49", "NETFLIX.COM"),
        // rata con numerazione
        tx("2026-08-27", "-150.00", "FINANZIAMENTO AUTO rata 7/12"), tx("2026-09-27", "-150.00", "FINANZIAMENTO AUTO rata 8/12"), tx("2026-10-27", "-150.00", "FINANZIAMENTO AUTO rata 9/12"),
        // assicurazione annuale
        tx("2025-03-12", "-480.00", "ASSICURAZIONE AUTO UNIPOL"), tx("2026-03-13", "-480.00", "ASSICURAZIONE AUTO UNIPOL"),
        // bolletta trimestrale variabile
        tx("2026-02-10", "-82.10", "ACQUA SPA bolletta"), tx("2026-05-12", "-95.40", "ACQUA SPA bolletta"), tx("2026-08-11", "-74.00", "ACQUA SPA bolletta"),
        // abbonamento sparito: ultimo addebito a luglio, ora siamo a novembre
        tx("2026-05-20", "-9.99", "SPOTIFY AB"), tx("2026-06-20", "-9.99", "SPOTIFY AB"), tx("2026-07-20", "-9.99", "SPOTIFY AB"),
        // rumore: spese casuali
        tx("2026-09-02", "-25.00", "BAR CENTRALE"), tx("2026-10-19", "-18.00", "BAR CENTRALE"), tx("2026-11-01", "-7.50", "BAR CENTRALE"),
    )
    private val found = SubscriptionDetector.detect(txs, today = today)

    @Test fun `riconosce cadenze mensile, trimestrale e annuale`() {
        assertEquals(Cadence.MONTHLY, find(found, "netflix").cadence)
        assertEquals(Cadence.QUARTERLY, find(found, "acqua").cadence)
        assertEquals(Cadence.ANNUAL, find(found, "assicurazione").cadence)
        assertTrue(found.none { it.key.contains("bar") })
    }

    @Test fun `distingue abbonamento, rata e spesa variabile`() {
        assertEquals(PatternKind.SUBSCRIPTION, find(found, "spotify").kind)
        assertEquals(PatternKind.INSTALLMENT, find(found, "finanziamento").kind)
        assertEquals(PatternKind.VARIABLE, find(found, "acqua").kind)
    }

    @Test fun `le rate dicono quante ne restano e quando finiscono`() {
        val r = find(found, "finanziamento")
        assertEquals(3, r.remainingInstallments); assertEquals(LocalDate.parse("2027-01-27"), r.endDate)
    }

    @Test fun `segnala l aumento di prezzo e gli addebiti che non arrivano`() {
        val n = find(found, "netflix")
        assertTrue(n.priceChanged); assertEquals(0, BigDecimal("12.99").compareTo(n.previousAmount)); assertEquals(0, BigDecimal("2.50").compareTo(n.priceDelta))
        val s = find(found, "spotify")
        assertTrue(s.missing); assertTrue(s.stale)          // dal 20 agosto nessun addebito: 4 mesi
        assertFalse(find(found, "finanziamento").missing)
    }

    @Test fun `costo mensile e annuo equivalente e totali senza i disdetti`() {
        val a = find(found, "assicurazione")
        assertEquals(0, BigDecimal("40.00").compareTo(a.perMonth)); assertEquals(0, BigDecimal("480.00").compareTo(a.perYear))
        val sum = SubscriptionDetector.summary(found)
        assertTrue(found.any { it.stale })
        assertEquals(found.count { !it.stale && !it.finished }, sum.count)
    }

    @Test fun `una ricorrenza manuale equivalente risulta gia gestita`() {
        val rule = RecurringRule(1, "Netflix", TxKind.SPESA, BigDecimal("15.49"), everyMonths = 1)
        assertTrue(find(SubscriptionDetector.detect(txs, today = today, manual = listOf(rule)), "netflix").tracked)
        assertFalse(find(found, "netflix").tracked)
    }
}
