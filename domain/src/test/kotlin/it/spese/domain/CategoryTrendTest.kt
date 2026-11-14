package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CategoryTrendTest {
    private val cats = listOf(
        Category(1, "Svago", TxKind.SPESA, true, 0), Category(2, "Casa", TxKind.SPESA, false, 0), Category(3, "Bollette", TxKind.SPESA, false, 0, parentId = 2),
        Category(4, "Stipendio", TxKind.ENTRATA, false, 0), Category(5, "Calo", TxKind.SPESA, true, 0),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), "x$n", BigDecimal(amount), if (cat == 4L) TxKind.ENTRATA else TxKind.SPESA, cat)
    // gennaio-agosto completi, settembre appena iniziato: Svago sale di 10 € al mese (100..170), Casa è fissa (400+100), Calo scende (400..260), stipendio costante
    private val txs = (1..8).flatMap { m ->
        val d = "2026-%02d-10".format(m)
        listOf(tx(d, -(90 + 10 * m), 1), tx(d, -400, 2), tx(d, -100, 3), tx(d, 2000, 4), tx(d, -(420 - 20 * m), 5))
    } + listOf(tx("2026-09-02", -50, 1), tx("2026-09-03", 2000, 4))
    private val data = AppData(transactions = txs, categories = cats, accounts = listOf(Account(1, "C")), loaded = true)
    private val today = LocalDate.parse("2026-09-05")
    private fun report(p: Period = Period.Last3Months) = CategoryTrendEngine.compute(data, TxFilter(period = p), today)!!

    @Test fun `tendenza crescente, livello attuale e proiezioni a 1, 3 e 6 mesi`() {
        val t = report().categories.first { it.category?.id == 1L }
        assertEquals(TrendDirection.UP, t.direction); assertEquals(8, t.months.size)       // settembre, ancora in corso, non entra
        assertEquals(0, BigDecimal("10.00").compareTo(t.slope)); assertEquals(0, BigDecimal("170.00").compareTo(t.level))
        assertEquals(listOf(180, 200, 230), CategoryTrendEngine.HORIZONS.map { t.projection(it)!!.monthly.toInt() })
        assertEquals(0, BigDecimal(-60).compareTo(t.projection(3)!!.balanceImpact))      // (10+20+30) € in più in tre mesi
        assertEquals(0, BigDecimal(-210).compareTo(t.projection(6)!!.balanceImpact))     // 10+20+...+60
        assertEquals(Confidence.HIGH, t.confidence)
    }

    @Test fun `spesa stabile e spesa in calo, con le sottocategorie dentro la principale`() {
        val r = report()
        val casa = r.categories.first { it.category?.id == 2L }
        assertEquals(TrendDirection.STABLE, casa.direction); assertEquals(0, BigDecimal(500).compareTo(casa.level)); assertEquals(0, BigDecimal.ZERO.compareTo(casa.projection(6)!!.balanceImpact))
        assertNull(r.categories.firstOrNull { it.category?.id == 3L })                    // la figlia non compare da sola
        val calo = r.categories.first { it.category?.id == 5L }
        assertEquals(TrendDirection.DOWN, calo.direction); assertTrue(calo.projection(6)!!.balanceImpact.signum() > 0)   // spendere meno migliora il bilancio
    }

    @Test fun `confronto del periodo con lo storico precedente`() {
        val t = report().categories.first { it.category?.id == 1L }
        // storico gen-giu = (100+...+150) in circa 6 mesi (181 giorni / 30,44)
        assertTrue(t.historyAvg!!.subtract(BigDecimal(126)).abs() < BigDecimal(2))
        assertTrue(t.changeVsHistory!!.signum() > 0)
        assertNull(report(Period.All).categories.first { it.category?.id == 1L }.historyAvg)   // con "Tutto" non c'è storico precedente
    }

    @Test fun `bilancio atteso, con entrate costanti e spese che cambiano`() {
        val r = report()
        assertEquals(0, BigDecimal(2000).compareTo(r.currentIncome)); assertEquals(0, BigDecimal(930).compareTo(r.currentExpense))   // 170 + 500 + 260
        assertEquals(0, BigDecimal(1070).compareTo(r.currentNet))
        val b6 = r.balance.first { it.monthsAhead == 6 }
        assertEquals(0, BigDecimal(2000).compareTo(b6.income)); assertEquals(0, BigDecimal(870).compareTo(b6.expense))     // 230 + 500 + 140
        assertEquals(0, BigDecimal(60).compareTo(b6.deltaMonthly))                                                         // si spende di meno: +60 € al mese
        assertEquals(0, BigDecimal(210).compareTo(b6.cumulativeImpact))                                                    // -210 (Svago) + 420 (Calo)
        // effetto cumulato = somma degli effetti per categoria (le entrate costanti non contano)
        assertEquals(0, r.categories.fold(BigDecimal.ZERO) { a, c -> a + c.projection(6)!!.balanceImpact }.compareTo(b6.cumulativeImpact))
    }

    @Test fun `pochi mesi, niente movimenti e valori che non scendono sotto zero`() {
        val few = AppData(transactions = listOf(tx("2026-08-10", -100, 1), tx("2026-09-02", -50, 1)), categories = cats, accounts = listOf(Account(1, "C")), loaded = true)
        val t = CategoryTrendEngine.compute(few, TxFilter(), today)!!.categories.first()
        assertEquals(Confidence.LOW, t.confidence); assertEquals(TrendDirection.UNKNOWN, t.direction)
        assertNull(CategoryTrendEngine.compute(AppData(loaded = true), TxFilter(), today))
        val down = CategoryTrendEngine.build(cats[0], TxKind.SPESA, (1..6).map { java.time.YearMonth.of(2026, it) to BigDecimal(300 - 55 * it) }, BigDecimal.ZERO, null)
        assertTrue(down.projections.all { it.monthly.signum() >= 0 }); assertEquals(0, BigDecimal.ZERO.compareTo(down.projection(6)!!.monthly))
    }
}
