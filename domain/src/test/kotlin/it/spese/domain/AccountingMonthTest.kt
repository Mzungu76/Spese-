package it.spese.domain

import it.spese.core.common.MonthCalendar
import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import it.spese.domain.report.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class AccountingMonthTest {
    private val c20 = MonthCalendar(20)
    private fun d(s: String) = LocalDate.parse(s)

    @Test fun `il mese contabile dal 20 al 19, con il nome del mese in cui inizia`() {
        assertEquals(YearMonth.of(2026, 1), c20.monthOf(d("2026-01-20"))); assertEquals(YearMonth.of(2025, 12), c20.monthOf(d("2026-01-19")))
        assertEquals(YearMonth.of(2026, 1), c20.monthOf(d("2026-02-19"))); assertEquals(YearMonth.of(2026, 2), c20.monthOf(d("2026-02-20")))
        assertEquals(d("2026-01-20"), c20.start(YearMonth.of(2026, 1))); assertEquals(d("2026-02-19"), c20.end(YearMonth.of(2026, 1)))
        assertEquals(d("2026-01-19"), c20.end(YearMonth.of(2025, 12)))                           // a cavallo d'anno
        assertEquals(31, c20.length(YearMonth.of(2026, 1))); assertEquals(28, c20.length(YearMonth.of(2026, 2)))
        assertEquals(1, c20.dayIndex(d("2026-01-20"))); assertEquals(31, c20.dayIndex(d("2026-02-19"))); assertEquals(6, c20.dayIndex(d("2026-02-25")))
        assertEquals("Gennaio 2026 (20/01 – 19/02)", c20.label(YearMonth.of(2026, 1))); assertEquals("Gennaio 2026", MonthCalendar.SOLAR.label(YearMonth.of(2026, 1)))
        assertTrue(MonthCalendar().isSolar); assertEquals(YearMonth.of(2026, 3), MonthCalendar.SOLAR.monthOf(d("2026-03-31")))
        assertTrue(runCatching { MonthCalendar(29) }.isFailure); assertTrue(runCatching { MonthCalendar(0) }.isFailure)
    }

    @Test fun `i periodi seguono il mese contabile, e il calendario resta com'era`() {
        val min = d("2025-10-01"); val max = d("2026-02-25")
        fun r(p: Period, cal: MonthCalendar) = PeriodResolver.resolve(p, min, max, cal).let { it.from to it.to }
        assertEquals(d("2026-02-20") to max, r(Period.CurrentMonth, c20))
        assertEquals(d("2025-12-20") to max, r(Period.Last3Months, c20))
        assertEquals(d("2025-10-01") to max, r(Period.Last6Months, c20))                     // non prima del primo movimento
        assertEquals(d("2026-01-20") to max, r(Period.YearToDate, c20))
        assertEquals(d("2026-01-20") to d("2026-02-19"), r(Period.Month(YearMonth.of(2026, 1)), c20))
        assertEquals(d("2026-02-01") to max, r(Period.CurrentMonth, MonthCalendar.SOLAR)); assertEquals(d("2026-01-01") to max, r(Period.YearToDate, MonthCalendar.SOLAR))
        assertEquals(d("2026-01-01") to d("2026-01-31"), r(Period.Month(YearMonth.of(2026, 1)), MonthCalendar.SOLAR))
    }

    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long = 1) = Transaction(++n, "e$n", 1, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val cats = listOf(Category(1, "Spesa", TxKind.SPESA, false, 0, budgetMonthly = BigDecimal(200)), Category(2, "Stipendio", TxKind.ENTRATA, false, 0), Category(3, "Contanti", TxKind.SPESA, true, 0), Category(4, "Affitto", TxKind.SPESA, false, 0))
    private val txs = listOf(tx("2025-12-22", 2000, 2), tx("2025-12-28", -80), tx("2026-01-05", -30), tx("2026-01-20", 2000, 2), tx("2026-01-25", -100), tx("2026-02-15", -50), tx("2026-02-20", 2000, 2), tx("2026-02-25", -40))
    private fun data(cal: MonthCalendar) = AppData(transactions = txs, categories = cats, accounts = listOf(Account(1, "C")), loaded = true, calendar = cal)

    @Test fun `statistiche per mese contabile`() {
        val s = StatsEngine.compute(txs, c20)
        assertEquals(listOf(YearMonth.of(2025, 12), YearMonth.of(2026, 1), YearMonth.of(2026, 2)), s.byMonth.keys.toList().sorted())
        assertEquals(0, BigDecimal(110).compareTo(s.byMonth[YearMonth.of(2025, 12)]!!.expense))   // 28/12 e 5/1 (prima del 20)
        assertEquals(0, BigDecimal(150).compareTo(s.byMonth[YearMonth.of(2026, 1)]!!.expense))    // 25/1 e 15/2
        assertEquals(0, BigDecimal(2000).compareTo(s.byMonth[YearMonth.of(2026, 1)]!!.income))    // lo stipendio del 20/1 apre il mese
        val solar = StatsEngine.compute(txs); assertEquals(0, BigDecimal(130).compareTo(solar.byMonth[YearMonth.of(2026, 1)]!!.expense))
    }

    @Test fun `Home - periodo, confronto col mese contabile prima e budget`() {
        val m = DashboardCalculator.compute(data(c20), TxFilter(period = Period.CurrentMonth, calendar = c20), d("2026-02-26"))
        assertEquals(0, BigDecimal(40).compareTo(m.stats.expense)); assertEquals(0, BigDecimal(2000).compareTo(m.stats.income))        // dal 20/02 al 25/02
        assertEquals("rispetto a Gennaio 2026 (20/01 – 19/02)", m.previousLabel)
        assertEquals(0, BigDecimal(100).compareTo(m.previous!!.expense))                         // 20/01 – 25/01 (stesso tratto): solo i 100 del 25/01
        // ritmo del mese: il 26/02 è il 7° giorno di 28
        assertEquals(7, m.monthPace!!.dayOfMonth); assertEquals(28, m.monthPace!!.daysInMonth); assertEquals(YearMonth.of(2026, 2), m.monthPace!!.month)
        // il mese di calendario dà un'altra risposta
        val sol = DashboardCalculator.compute(data(MonthCalendar.SOLAR), TxFilter(period = Period.CurrentMonth), d("2026-02-26"))
        assertEquals(0, BigDecimal(90).compareTo(sol.stats.expense))                             // 15/02 e 25/02
        val whole = DashboardCalculator.compute(data(c20), TxFilter(period = Period.Month(YearMonth.of(2026, 1)), calendar = c20), d("2026-02-26"))
        assertEquals(0, BigDecimal(150).compareTo(whole.stats.expense))                          // il mese "gennaio" intero: 20/01 – 19/02
        val b = AlertEngine.budgetUsage(txs, cats, YearMonth.of(2026, 1), c20).single()
        assertEquals(0, BigDecimal(150).compareTo(b.spent))
    }

    @Test fun `grafico mensile con i mesi contabili e tendenze sui mesi completi`() {
        val buckets = TrendBuckets.monthly(txs, c20)
        assertEquals(listOf(d("2025-12-20"), d("2026-01-20"), d("2026-02-20")), buckets.map { it.start })
        assertEquals("gennaio 2026", buckets[1].title)
        val many = (0..8).flatMap { i -> val m = YearMonth.of(2025, 4).plusMonths(i.toLong()); listOf(tx(c20.start(m).plusDays(2).toString(), -(100 + 10 * i)), tx(c20.start(m).toString(), 2000, 2)) }
        val rep = CategoryTrendEngine.compute(data(c20).copy(transactions = many, ledger = many), TxFilter(period = Period.Last3Months, calendar = c20), d("2026-01-10"))!!
        assertEquals(YearMonth.of(2025, 12), rep.referenceMonth)                                   // il 10/01 è ancora nel mese contabile di dicembre
        assertEquals(8, rep.categories.first().months.size)                                        // aprile-novembre: dicembre è in corso
    }

    @Test fun `le quote in contanti contano per mese contabile`() {
        val quota = CashQuota(7, "Affitto", 4, BigDecimal(500))
        val w = listOf(tx("2026-01-25", -800, 3).copy(description = "Prelievo ATM"), tx("2026-02-10", -800, 3).copy(description = "Prelievo ATM"))
        val cm = cats.associateBy { it.id }
        assertEquals(2, CashQuotaEngine.plan(w, emptyMap(), cm, listOf(quota)).size)                    // calendario: gennaio e febbraio
        assertEquals(1, CashQuotaEngine.plan(w, emptyMap(), cm, listOf(quota), c20).size)               // dal 20: sono lo stesso mese
    }

    @Test fun `report - mese precedente e anno scorso secondo il mese contabile, e budget`() {
        val max = d("2026-02-25")
        assertEquals(Period.Month(YearMonth.of(2026, 1)), ReportPeriod(ReportPeriodKind.PREVIOUS_MONTH).resolve(max, c20))
        assertEquals(Period.Month(YearMonth.of(2026, 1)), ReportPeriod(ReportPeriodKind.PREVIOUS_MONTH).resolve(max))     // calendario: gennaio
        assertEquals(Period.Custom(d("2025-01-20"), d("2026-01-19")), ReportPeriod(ReportPeriodKind.PREVIOUS_YEAR).resolve(max, c20))
        val doc = ReportBuilder.build(data(c20), ReportSpec(period = ReportPeriod(ReportPeriodKind.MONTH, YearMonth.of(2026, 1)), sections = listOf(ReportSection.SUMMARY, ReportSection.BUDGETS)), d("2026-02-26"))
        assertTrue(doc.subtitle.contains("20/01/2026") && doc.subtitle.contains("19/02/2026"))
        val k = doc.blocks.filterIsInstance<Block.Kpis>().first().items
        assertEquals("2.000 €", k[0].value); assertEquals("150 €", k[1].value)
    }
}
