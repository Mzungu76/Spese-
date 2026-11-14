package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SavingsTest {
    private val cats = listOf(
        Category(1, "Ristoranti", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY), Category(2, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED),
        Category(3, "Shopping", TxKind.SPESA, true, 0), Category(4, "Altro", TxKind.SPESA, true, 0, systemKey = Category.FALLBACK_EXPENSE),
        Category(5, "Stipendio", TxKind.ENTRATA, false, 0),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), "x$n", BigDecimal(amount), if (cat == 5L) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val data = AppData(
        transactions = listOf(tx("2026-09-05", -200, 1), tx("2026-09-06", -700, 2), tx("2026-09-07", -100, 3), tx("2026-09-08", -50, 4), tx("2026-09-01", 1500, 5),
            tx("2026-10-05", -300, 1), tx("2026-10-06", -700, 2), tx("2026-10-07", -90, 3), tx("2026-10-08", -50, 4), tx("2026-10-01", 1500, 5)),
        categories = cats, accounts = listOf(Account(1, "C")), loaded = true,
    )

    @Test fun `solo le spese discrezionali (o comprimibili senza natura) sono occasioni di risparmio`() {
        val m = DashboardCalculator.compute(data, TxFilter(period = Period.CurrentMonth), LocalDate.parse("2026-10-14"))
        val o = SavingsOpportunities.compute(m.categories, 20)
        assertEquals(listOf(1L, 3L), o.map { it.row.category?.id })   // Ristoranti (60 €/mese) e Shopping (18 €/mese), non l'affitto fisso né "Altro"
    }

    @Test fun `affitto fisso e categoria di sistema restano fuori`() {
        val m = DashboardCalculator.compute(data, TxFilter(period = Period.All), LocalDate.parse("2026-10-14"))
        val ids = SavingsOpportunities.compute(m.categories, 20, limit = 10, minMonthly = BigDecimal.ONE).map { it.row.category?.id }
        assertTrue(1L in ids && 3L in ids); assertFalse(2L in ids); assertFalse(4L in ids)
        assertEquals(1L, ids.first())                                // Ristoranti pesa di più
    }

    @Test fun `effetto di un taglio sul risparmio e sull'obiettivo`() {
        val d = CategoryDetailCalculator.compute(data, 1, TxFilter(period = Period.All), LocalDate.parse("2026-10-14"))!!
        // media mensile ristoranti = (200 + 300) / 2 = 250 → -20% = 50 €/mese, 600 €/anno
        val e = CutEffect.of(d, 20)
        assertEquals(0, BigDecimal(50).compareTo(e.monthly)); assertEquals(0, BigDecimal(600).compareTo(e.yearly))
        assertEquals(0, d.newSavingsAfter(e).compareTo(e.newSavingsPerMonth))
        assertTrue(e.newSavingsPerMonth > d.savingsPerMonth)
    }

    @Test fun `aumenti significativi in ordine di peso`() {
        val m = DashboardCalculator.compute(data, TxFilter(period = Period.CurrentMonth), LocalDate.parse("2026-10-14"))
        val s = SavingsOpportunities.surging(m.categories)
        assertEquals(listOf(1L), s.map { it.category?.id })          // +100 € e +50% (le altre sono stabili)
    }

    private fun CategoryDetail.newSavingsAfter(e: CutEffect) = savingsPerMonth + e.monthly
}
