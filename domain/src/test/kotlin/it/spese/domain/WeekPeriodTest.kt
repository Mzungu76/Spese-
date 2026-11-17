package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeekPeriodTest {
    private var n = 0L
    private fun tx(date: String, amount: Int) = Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), "x", BigDecimal(amount), if (amount < 0) TxKind.SPESA else TxKind.ENTRATA, null)
    // 2026-11-18 è un mercoledì: la settimana in corso va da lunedì 16
    private val txs = listOf(tx("2026-11-09", -10), tx("2026-11-11", -20), tx("2026-11-16", -30), tx("2026-11-17", -5), tx("2026-11-18", -15), tx("2026-11-17", 100))
    private val data = AppData(transactions = txs, ledger = txs, categories = emptyList(), accounts = listOf(Account(1, "C")), loaded = true)

    @Test fun `la settimana in corso parte dal lunedi dell ultimo movimento`() {
        val r = PeriodResolver.resolve(Period.CurrentWeek, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-11-18"))
        assertEquals(LocalDate.parse("2026-11-16"), r.from); assertEquals(LocalDate.parse("2026-11-18"), r.to)
    }

    @Test fun `il confronto e con gli stessi giorni della settimana scorsa`() {
        val (from, to) = DashboardCalculator.previousRange(Period.CurrentWeek, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-18"))
        assertEquals(LocalDate.parse("2026-11-09"), from); assertEquals(LocalDate.parse("2026-11-11"), to)
    }

    @Test fun `il riepilogo della settimana conta solo i suoi movimenti e confronta con la scorsa`() {
        val m = DashboardCalculator.compute(data, TxFilter(period = Period.CurrentWeek), LocalDate.parse("2026-11-18"))
        assertEquals(0, BigDecimal(50).compareTo(m.stats.expense)); assertEquals(0, BigDecimal(100).compareTo(m.stats.income))
        assertEquals("rispetto alla settimana scorsa", m.previousLabel)
        assertEquals(0, BigDecimal(30).compareTo(m.categories.sumOf { it.previousTotal ?: BigDecimal.ZERO }))   // 9-11 nov: 10 + 20
    }

    @Test fun `il grafico della settimana ha un punto per ogni giorno`() {
        val b = TrendBuckets.daily(txs.filter { it.bookingDate >= LocalDate.parse("2026-11-16") }, LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-22"))
        assertEquals(listOf("Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom"), b.map { it.label })
        assertEquals(0, BigDecimal(30).compareTo(b[0].expense)); assertEquals(0, BigDecimal(100).compareTo(b[1].income)); assertEquals(0, BigDecimal.ZERO.compareTo(b[6].expense))
        assertTrue(b.all { it.daily })
    }
}
