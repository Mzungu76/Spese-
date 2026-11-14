package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CategoryRankingTest {
    private val cats = listOf(
        Category(1, "Spesa", TxKind.SPESA, false, 0), Category(2, "Bar", TxKind.SPESA, true, 0),
        Category(3, "Stipendio", TxKind.ENTRATA, false, 0), Category(4, "Giroconti", TxKind.TRASFERIMENTO, false, 0),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long, kind: TxKind) =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), "x$n", BigDecimal(amount), kind, cat)
    private val data = AppData(
        transactions = listOf(
            tx("2026-09-05", -200, 1, TxKind.SPESA), tx("2026-09-10", -40, 2, TxKind.SPESA), tx("2026-09-25", -500, 1, TxKind.SPESA), // settembre: oltre il 14 non conta nel confronto
            tx("2026-10-05", -300, 1, TxKind.SPESA), tx("2026-10-08", -150, 2, TxKind.SPESA), tx("2026-10-12", -60, 2, TxKind.SPESA),
            tx("2026-09-01", 1500, 3, TxKind.ENTRATA), tx("2026-10-01", 1800, 3, TxKind.ENTRATA), tx("2026-10-02", -900, 4, TxKind.TRASFERIMENTO),
        ),
        categories = cats, accounts = listOf(Account(1, "C")), loaded = true,
    )

    private fun model() = DashboardCalculator.compute(data, TxFilter(period = Period.CurrentMonth), LocalDate.parse("2026-10-14"))

    @Test fun `il mese si confronta con gli stessi giorni del mese prima`() {
        val m = model()
        // 1-12 ottobre vs 1-12 settembre: Spesa 300 vs 200 (i 500 del 25 settembre non contano), Bar 210 vs 40
        val spesa = m.categories.first { it.category?.id == 1L }; val bar = m.categories.first { it.category?.id == 2L }
        assertEquals(0, BigDecimal(300).compareTo(spesa.total)); assertEquals(0, BigDecimal(200).compareTo(spesa.previousTotal))
        assertEquals(0, BigDecimal(100).compareTo(spesa.delta)); assertEquals(0, BigDecimal("50.0").compareTo(spesa.changePercent))
        assertEquals(0, BigDecimal(170).compareTo(bar.delta))
        assertEquals("rispetto a Settembre 2026", m.previousLabel)
    }

    @Test fun `la classifica e ordinata, gli aumenti significativi sono segnalati e i giroconti restano fuori`() {
        val m = model()
        assertEquals(listOf(1L, 2L), m.categories.map { it.category?.id })
        assertTrue(m.categories.first { it.category?.id == 1L }.surging)   // +100 € e +50%
        assertTrue(m.categories.first { it.category?.id == 2L }.surging)   // +170 € e +425%
        assertEquals(listOf(3L), m.incomeCategories.map { it.category?.id })
        assertEquals(0, BigDecimal(1800).compareTo(m.incomeCategories[0].total))
        assertEquals(0, BigDecimal(1500).compareTo(m.incomeCategories[0].previousTotal))
        assertTrue(m.categories.none { it.category?.id == 4L })
        assertEquals(0, BigDecimal(-900).compareTo(m.stats.transfers))
    }

    @Test fun `senza periodo precedente non c'e confronto e con tutto il periodo nemmeno`() {
        val m = DashboardCalculator.compute(data, TxFilter(period = Period.All), LocalDate.parse("2026-10-14"))
        assertNull(m.previousLabel); assertTrue(m.categories.all { it.previousTotal == null && !it.surging })
    }

    @Test fun `periodi non mensili usano un tratto di pari durata subito prima`() {
        val r = DashboardCalculator.previousRange(Period.Last3Months, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-10-31"))
        assertEquals(LocalDate.parse("2026-05-01") to LocalDate.parse("2026-07-31"), r)
        // il mese di marzo confrontato con febbraio (28 giorni) non sconfina
        assertEquals(LocalDate.parse("2026-02-01") to LocalDate.parse("2026-02-28"),
            DashboardCalculator.previousRange(Period.Month(java.time.YearMonth.of(2026, 3)), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31")))
    }
}
