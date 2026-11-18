package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PaceTest {
    private var n = 0L
    private val cats = listOf(Category(1, "Casa", TxKind.SPESA, false, 0), Category(2, "Bar", TxKind.SPESA, true, 0), Category(3, "Bollette", TxKind.SPESA, false, 0, parentId = 1))
    private fun tx(date: LocalDate, amount: Int, cat: Long? = 1) = Transaction(++n, "e$n", 1, date, date, "x", BigDecimal(amount), if (amount < 0) TxKind.SPESA else TxKind.ENTRATA, cat)
    private fun d(s: String) = LocalDate.parse(s)

    /** Da luglio a ottobre: 1.000 € di spesa al mese (100 € il 5, 10, 15, 20 e 25 del mese più 500 € di casa il 1°) e 2.000 € di entrate il 27. Novembre: più spesa. */
    private fun history(): MutableList<Transaction> {
        val l = ArrayList<Transaction>()
        for (m in 7..10) {
            l.add(tx(d("2026-%02d-01".format(m)), -500, 1)); listOf(5, 10, 15, 20, 25).forEach { day -> l.add(tx(d("2026-%02d-%02d".format(m, day)), -100, 2)) }
            l.add(tx(d("2026-%02d-27".format(m)), 2000, null))
        }
        return l
    }
    private fun data(l: List<Transaction>) = AppData(transactions = l, ledger = l, categories = cats, accounts = listOf(Account(1, "C")), loaded = true)

    @Test fun `il mese in corso e confrontato con la media dei mesi passati giorno per giorno`() {
        val l = history()
        l.add(tx(d("2026-11-01"), -500, 1)); l.add(tx(d("2026-11-05"), -300, 2)); l.add(tx(d("2026-11-10"), -200, 2))     // 1.000 € già al 10 (di solito 700)
        val r = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentMonth), d("2026-11-10"))!!
        assertEquals(PaceUnit.MONTH, r.unit); assertEquals(30, r.days); assertEquals(9, r.dayIndex)
        assertEquals(0, BigDecimal(1000).compareTo(r.spentToDate)); assertEquals(0, BigDecimal(700).compareTo(r.averageToDate)); assertEquals(0, BigDecimal(1000).compareTo(r.averageTotal))
        assertEquals(4, r.pastWindows.size); assertEquals(PaceVerdict.ABOVE, r.verdict)
        assertEquals(0, BigDecimal(300).compareTo(r.deltaToDate)); assertEquals(0, BigDecimal(43).compareTo(r.deltaPercent))
        assertEquals(0, BigDecimal(1300).compareTo(r.projectedWithAverage))            // 1.000 spesi + 300 che di solito restano da spendere
        assertEquals(0, BigDecimal(3000).compareTo(r.projectedAtCurrentPace))          // 1.000 in 10 giorni → 3.000 in 30
        assertEquals(0, BigDecimal(2000).compareTo(r.averageIncome)); assertEquals(0, BigDecimal(700).compareTo(r.expectedMargin))
        assertEquals("Bar", r.drivers.first().category?.name); assertEquals(0, BigDecimal(300).compareTo(r.drivers.first().delta))
        assertEquals(10, r.current.size); assertEquals(30, r.average.size)
    }

    @Test fun `in linea quando la spesa e come la media e sotto quando e minore`() {
        val l = history(); l.add(tx(d("2026-11-01"), -500, 1)); l.add(tx(d("2026-11-05"), -100, 2)); l.add(tx(d("2026-11-10"), -100, 2))
        assertEquals(PaceVerdict.IN_LINE, PaceEngine.compute(data(l), TxFilter(period = Period.CurrentMonth), d("2026-11-10"))!!.verdict)
        val l2 = history(); l2.add(tx(d("2026-11-01"), -200, 1))
        assertEquals(PaceVerdict.BELOW, PaceEngine.compute(data(l2), TxFilter(period = Period.CurrentMonth), d("2026-11-10"))!!.verdict)
    }

    @Test fun `la settimana si confronta con le settimane passate e il trimestre con i trimestri passati`() {
        val l = ArrayList<Transaction>()
        // 8 settimane complete (da lunedì 21 set) con 70 € a settimana (10 € al giorno); la settimana in corso (lunedì 16 nov) spende 50 € in 2 giorni
        var day = d("2026-09-21"); while (day < d("2026-11-16")) { l.add(tx(day, -10)); day = day.plusDays(1) }
        l.add(tx(d("2026-11-16"), -30)); l.add(tx(d("2026-11-17"), -20))
        val w = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentWeek), d("2026-11-17"))!!
        assertEquals(PaceUnit.WEEK, w.unit); assertEquals(7, w.days); assertEquals(1, w.dayIndex)
        assertEquals(0, BigDecimal(20).compareTo(w.averageToDate)); assertEquals(0, BigDecimal(70).compareTo(w.averageTotal)); assertEquals(PaceVerdict.ABOVE, w.verdict)
        assertEquals(0, BigDecimal(3).compareTo(BigDecimal(w.remainingDays - 2)))
        assertEquals(PaceUnit.QUARTER, PaceEngine.unitFor(Period.Last3Months)); assertEquals(PaceUnit.MONTH, PaceEngine.unitFor(Period.All))
    }

    @Test fun `con un solo periodo passato lo storico e insufficiente`() {
        val l = ArrayList<Transaction>(); l.add(tx(d("2026-10-05"), -100)); l.add(tx(d("2026-11-05"), -50))
        val r = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentMonth), d("2026-11-10"))
        assertEquals(PaceVerdict.NOT_ENOUGH, r!!.verdict)       // ottobre è parziale (i dati iniziano il 5): nessun mese completo
        assertNull(PaceEngine.compute(data(emptyList()), TxFilter(), d("2026-11-10")))
    }

    @Test fun `la spesa giornaliera sostenibile tiene conto delle entrate e del risparmio medio`() {
        val l = history(); l.add(tx(d("2026-11-01"), -500, 1)); l.add(tx(d("2026-11-10"), -300, 2))
        val r = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentMonth), d("2026-11-10"))!!
        // entrate attese 2.000, risparmio medio 1.000, già speso 800 → restano 200 € per 20 giorni
        assertEquals(0, BigDecimal("10.00").compareTo(r.dailyAllowance)); assertEquals(0, BigDecimal(1000).compareTo(r.averageMargin))
    }

    @Test fun `se nella settimana di oggi non c e ancora nessun movimento il periodo in corso e quello dell ultimo movimento`() {
        val l = ArrayList<Transaction>()
        var day = d("2026-09-21"); while (day <= d("2026-11-15")) { l.add(tx(day, -10)); day = day.plusDays(1) }       // fino a domenica 15 novembre
        // oggi è martedì 17: la settimana corrente non ha movimenti, quella dell'ultimo movimento (9–15 nov) sì
        val r = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentWeek), d("2026-11-17"))!!
        assertEquals(d("2026-11-09"), r.start); assertEquals(6, r.dayIndex); assertEquals(0, BigDecimal(70).compareTo(r.spentToDate)); assertEquals(PaceVerdict.IN_LINE, r.verdict)
    }

    @Test fun `con categorie scelte il ritmo riguarda solo la loro spesa, anche piu categorie insieme`() {
        val l = history()
        l.add(tx(d("2026-11-01"), -500, 1)); l.add(tx(d("2026-11-05"), -300, 2)); l.add(tx(d("2026-11-10"), -200, 2))
        val bar = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentMonth), d("2026-11-10"), setOf(2L))!!
        assertEquals(0, BigDecimal(500).compareTo(bar.spentToDate)); assertEquals(0, BigDecimal(200).compareTo(bar.averageToDate))
        assertEquals(listOf("Bar"), bar.filtered.map { it.name }); assertEquals(0, bar.averageIncome.signum())
        val both = PaceEngine.compute(data(l), TxFilter(period = Period.CurrentMonth), d("2026-11-10"), setOf(1L, 2L))!!
        assertEquals(0, BigDecimal(1000).compareTo(both.spentToDate)); assertEquals(2, both.filtered.size)
        // la sottocategoria segue la principale
        val sub = PaceEngine.compute(data(l + tx(d("2026-11-03"), -40, 3)), TxFilter(period = Period.CurrentMonth), d("2026-11-10"), setOf(1L))!!
        assertEquals(0, BigDecimal(540).compareTo(sub.spentToDate))
    }
}
