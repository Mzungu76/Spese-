package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import it.spese.domain.sharing.BudgetInputs
import it.spese.domain.sharing.ShareConfig
import it.spese.domain.sharing.ShareSnapshotBuilder
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Il budget giornaliero ha una base verificabile a mano: ogni cifra qui sotto si ricalcola con carta e penna. */
class DailyBudgetTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val cats = listOf(
        Category(1, "Spesa", TxKind.SPESA, true, 0), Category(2, "Stipendio", TxKind.ENTRATA, false, 0), Category(3, "Extra", TxKind.ENTRATA, false, 0),
        Category(4, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED),
    )
    private fun data(txs: List<Transaction>, dues: List<Due> = emptyList()) = AppData(transactions = txs, categories = cats, accounts = listOf(Account(1, "C")), dues = dues, loaded = true)
    private val goal = Goal(1, "Fondo", BigDecimal(3000), BigDecimal.ZERO, monthly = BigDecimal(300))
    private val today = d("2026-11-10")      // 21 giorni compreso oggi: dal 10 al 30 novembre

    /** Quattro mesi di storico: stipendio 2.000 ogni 1°, affitto 600, spesa 400; novembre: stipendio, affitto e 300 di spesa. */
    private fun history(extra: List<Transaction> = emptyList()): List<Transaction> =
        listOf("2026-07", "2026-08", "2026-09", "2026-10").flatMap { m -> listOf(tx("$m-01", 2000, 2), tx("$m-02", -600, 4), tx("$m-10", -400, 1)) } +
            listOf(tx("2026-11-01", 2000, 2), tx("2026-11-02", -600, 4), tx("2026-11-05", -300, 1)) + extra

    @Test fun `base - entrate fisse, risparmio dagli obiettivi, margine del 10 per cento e giorni che restano`() {
        val b = DailyBudgetEngine.compute(data(history()), goals = listOf(goal), realToday = today)!!
        eq("2000", b.income!!.fixed); eq("0", b.income!!.variablePrudent); assertEquals(4, b.income!!.months)
        eq("2000", b.expectedIncome); eq("300", b.savings)
        // dopo risparmio e affitto pagato restano 2000 − 300 − 600 = 1100; il margine è il 10%: 110
        eq("110", b.reserve); eq("1590", b.limit)                                      // 2000 − 300 − 110
        eq("900", b.spent); eq("600", b.fixedPaid)
        eq("690", b.remaining)                                                        // 1590 − 900
        assertEquals(21, b.daysLeft); eq("32.86", b.perDay)                           // 690 / 21
        assertEquals(BudgetStatus.OK, b.status)
    }

    @Test fun `le uscite note ancora da pagare riducono la cifra al giorno e il margine`() {
        val due = Due(1, "d1", "Bolletta", BigDecimal(100), d("2026-11-20"), DueFrequency.ONCE)
        val b = DailyBudgetEngine.compute(data(history(), listOf(due)), goals = listOf(goal), realToday = today)!!
        eq("100", b.upcomingTotal)
        eq("100", b.reserve)                                                          // 10% di (2000 − 300 − 600 − 100) = 100
        eq("1600", b.limit); eq("600", b.remaining); eq("28.57", b.perDay)            // (1600 − 900 − 100) / 21
    }

    @Test fun `le entrate variabili contano solo per la parte prudente, quelle fisse per intero`() {
        val extra = listOf("2026-07", "2026-08", "2026-09", "2026-10").mapIndexed { i, m -> tx("$m-15", listOf(0, 400, 0, 800)[i].takeIf { it > 0 } ?: 1, 3) }
        val est = IncomeEstimator.estimate(data(history(extra)).transactions, data(history(extra)).calendar, java.time.YearMonth.of(2026, 11), cats.associateBy { it.id })!!
        eq("2000", est.fixed)                                                         // lo stipendio resta fisso
        assertTrue(est.variablePrudent <= est.variableTypical)                        // il prudente non supera il tipico
        assertTrue(est.variablePrudent < BigDecimal(400))                             // con mesi senza extra non si conta tutto
        assertTrue(est.prudent < est.fixed + est.variableTypical || est.variableTypical.signum() == 0)
    }

    @Test fun `una correzione dell'utente sulla natura dell'entrata prevale`() {
        val extra = listOf("2026-07", "2026-08", "2026-09", "2026-10").map { m -> tx("$m-15", 300, 3) }       // 300 ogni mese: fissa
        val tx = data(history(extra)).transactions
        val auto = IncomeEstimator.estimate(tx, data(tx).calendar, java.time.YearMonth.of(2026, 11), cats.associateBy { it.id })!!
        eq("2300", auto.fixed)                                                        // riconosciuta come fissa
        val forced = IncomeEstimator.estimate(tx, data(tx).calendar, java.time.YearMonth.of(2026, 11), cats.associateBy { it.id }, mapOf(3L to it.spese.domain.health.IncomeNature.VARIABLE))!!
        eq("2000", forced.fixed)
    }

    @Test fun `mai meno di quanto già incassato, e senza storico si parte da quello che è entrato`() {
        val few = listOf(tx("2026-11-01", 2500, 2), tx("2026-11-03", -200, 1))
        val noPlan = Goal(9, "Senza piano", BigDecimal(1000))                         // nessuna quota: niente risparmio da riservare
        val b = DailyBudgetEngine.compute(data(few), goals = listOf(noPlan), realToday = today)!!
        assertNull(b.income); eq("2500", b.expectedIncome)
        eq("2250", b.limit); eq("250", b.reserve)                                       // 2500 meno il margine del 10%
        // nessuna entrata e nessuno storico: niente limite
        val none = DailyBudgetEngine.compute(data(listOf(tx("2026-11-03", -200, 1))), realToday = today)!!
        assertNull(none.limit); assertNull(none.perDay); assertEquals(BudgetStatus.NOT_ENOUGH, none.status)
    }

    @Test fun `oltre il limite la cifra è zero e lo stato lo dice`() {
        val b = DailyBudgetEngine.compute(data(history(listOf(tx("2026-11-09", -900, 1)))), goals = listOf(goal), realToday = today)!!
        assertEquals(BudgetStatus.OVER, b.status); eq("0", b.perDay)
        assertTrue(b.remaining!!.signum() < 0)
    }

    @Test fun `un tetto fissato a mano sostituisce quello calcolato e non ha margine`() {
        val b = DailyBudgetEngine.compute(data(history()), goals = listOf(goal), manualLimit = BigDecimal(1500), realToday = today)!!
        eq("1500", b.limit); eq("0", b.reserve); eq("600", b.remaining); eq("28.57", b.perDay)     // (1500 − 900) / 21
    }

    @Test fun `ultimo giorno del periodo - nessuna cifra al giorno`() {
        val b = DailyBudgetEngine.compute(data(history(listOf(tx("2026-11-30", -10, 1)))), goals = listOf(goal), realToday = d("2026-11-30"))!!
        assertEquals(1, b.daysLeft); assertNotNull(b.perDay)                          // oggi è ancora un giorno da spendere
        val after = DailyBudgetEngine.compute(data(history(listOf(tx("2026-11-30", -10, 1)))), goals = listOf(goal), realToday = d("2027-03-01"))!!
        assertTrue(after.stale)                                                       // i dati sono fermi al 30/11: la cifra non è di oggi
    }

    @Test fun `margine e giorni sono ben limitati`() {
        val zero = DailyBudgetEngine.compute(data(history()), goals = listOf(goal), marginPct = 0, realToday = today)!!
        eq("0", zero.reserve); eq("1700", zero.limit)
        val huge = DailyBudgetEngine.compute(data(history()), goals = listOf(goal), marginPct = 400, realToday = today)!!
        eq("550", huge.reserve)                                                       // al massimo il 50%
    }

    @Test fun `il riepilogo condiviso ha la stessa cifra al giorno in tutti i periodi e il limite scalato`() {
        val all = ShareSnapshotBuilder.buildAll(data(history()), listOf(1L), ShareConfig(), 1L, today, inputs = BudgetInputs(goals = listOf(goal), fromBalance = false))
        assertEquals(listOf("WEEK", "MONTH", "QUARTER"), all.map { it.unit })
        val m = all.first { it.unit == "MONTH" }
        eq("1590", m.limit); eq("110", m.reserve); eq("0", m.upcomingTotal)
        eq("366.92", all.first { it.unit == "WEEK" }.limit)                           // 1590 × 12 / 52
        eq("4770", all.first { it.unit == "QUARTER" }.limit)                          // 1590 × 3
        assertEquals(1, all.map { it.dailyRemaining!!.toPlainString() }.distinct().size); eq("32.86", m.dailyRemaining)
        // un tetto fissato a mano e un margine diverso cambiano il risultato come previsto
        val manual = ShareSnapshotBuilder.buildAll(data(history()), listOf(1L), ShareConfig(budgetMonthly = BigDecimal(1500)), 1L, today, inputs = BudgetInputs(goals = listOf(goal), fromBalance = false))
        eq("1500", manual.first { it.unit == "MONTH" }.limit); eq("28.57", manual.first { it.unit == "MONTH" }.dailyRemaining)
        val safer = ShareSnapshotBuilder.buildAll(data(history()), listOf(1L), ShareConfig(), 1L, today, inputs = BudgetInputs(goals = listOf(goal), marginPct = 20, fromBalance = false))
        eq("1480", safer.first { it.unit == "MONTH" }.limit)                          // 2000 − 300 − 220
    }

    @Test fun `il budget condiviso guarda solo i conti condivisi - ricorrenze e scadenze di altri non pesano`() {
        val due = Due(1, "d1", "Assicurazione", BigDecimal(500), d("2026-11-20"), DueFrequency.ONCE)
        val mine = RecurringRule(1, "Palestra", TxKind.SPESA, BigDecimal(50), accountId = 1, dayOfMonth = 25, startDate = d("2026-01-01"), lastGenerated = d("2026-10-25"))
        val other = RecurringRule(2, "Mutuo", TxKind.SPESA, BigDecimal(900), accountId = 2, dayOfMonth = 25, startDate = d("2026-01-01"), lastGenerated = d("2026-10-25"))
        val data = data(history(), listOf(due)).copy(accounts = listOf(Account(1, "Carta"), Account(2, "Altro")))
        val b = ShareSnapshotBuilder.budgetFor(data, listOf(1L), ShareConfig(), today, BudgetInputs(rules = listOf(mine, other), goals = listOf(goal), fromBalance = false))!!
        eq("50", b.upcomingTotal)                                  // solo la palestra del conto condiviso: né il mutuo di un altro conto né la scadenza generica
        // senza entrate sui conti condivisi il limite è zero e la spiegazione sta nel calcolo
        val noIncome = AppData(transactions = listOf(tx("2026-10-05", -300, 1), tx("2026-11-03", -120, 1)), categories = cats, accounts = listOf(Account(1, "Carta")), loaded = true)
        val z = ShareSnapshotBuilder.budgetFor(noIncome, listOf(1L), ShareConfig(), today, BudgetInputs(fromBalance = false))!!
        eq("0", z.expectedIncome); eq("0", z.limit); eq("0", z.perDay)
        // con un budget mensile fissato a mano il conto senza entrate funziona
        val manual = ShareSnapshotBuilder.budgetFor(noIncome, listOf(1L), ShareConfig(budgetMonthly = BigDecimal(900)), today, BudgetInputs(fromBalance = false))!!
        eq("900", manual.limit); eq("780", manual.remaining)         // 900 − 120
    }

    // ---- calcolo dai saldi: l'esempio del mese contabile che chiude il 20 ----
    /** Stipendio 1.000 il 27 di ogni mese e spese uguali; mese che chiude il 20; il 15 novembre arrivano 900 di extra. */
    private fun exampleData(extraOn15: Boolean = true): AppData {
        val l = ArrayList<Transaction>()
        listOf("2026-07", "2026-08", "2026-09", "2026-10").forEach { m -> l.add(tx("$m-27", 1000, 2)); l.add(tx("$m-28", -1000, 1)) }
        if (extraOn15) l.add(tx("2026-11-15", 900, 3))
        return AppData(transactions = l, categories = cats, accounts = listOf(Account(1, "Conto")), loaded = true, calendar = it.spese.core.common.MonthCalendar(21))
    }
    private val fondo = Goal(1, "Fondo sicurezza", BigDecimal(5000), BigDecimal(500), accountId = 1)       // 500 già accantonati e bloccati sul conto
    private val twentyFifth = d("2026-11-25")

    @Test fun `esempio - 1000 di stipendio ancora atteso piu 400 disponibili, fino al 20 del mese dopo, con il 10 per cento di margine`() {
        val data = exampleData()
        val cash: (LocalDate) -> it.spese.domain.finance.CashPosition? = { day -> it.spese.domain.finance.CashCalculator.compute(data, null, listOf(fondo), emptyList(), day) }
        val b = DailyBudgetEngine.compute(data, goals = listOf(fondo), realToday = twentyFifth, patterns = emptyList(), cashSource = cash)!!
        assertEquals(d("2026-11-21"), b.periodStart); assertEquals(d("2026-12-20"), b.periodEnd); assertEquals(26, b.daysLeft)    // dal 25/11 al 20/12 compreso
        eq("900", b.cash!!.balance); eq("500", b.cash!!.blocked); eq("400", b.cash!!.spendable)       // 900 − 500
        eq("1000", b.incomeRemaining)                                                                  // lo stipendio atteso resta 1000: l'extra del 15 non lo riduce
        eq("140", b.reserve)                                                                           // 10% di 1.400
        eq("1260", b.remaining); eq("48.46", b.perDay)                                                 // (400 + 1000) × 0,9 / 26
    }

    @Test fun `l'extra già incassato non riduce lo stipendio atteso nemmeno quando arriva nello stesso periodo`() {
        val data = exampleData(extraOn15 = false)
        val tx = data.transactions + tx("2026-11-23", 900, 3)                                           // extra dentro il periodo in corso (dal 21)
        val b = DailyBudgetEngine.compute(data.copy(transactions = tx), realToday = twentyFifth, patterns = emptyList())!!
        eq("900", b.incomeToDate); eq("1000", b.incomeRemaining); eq("1900", b.expectedIncome)         // 900 + 1000, non max(1000, 900)
        // lo stipendio già arrivato, invece, non si conta due volte
        val paid = DailyBudgetEngine.compute(data.copy(transactions = tx + tx("2026-11-22", 1000, 2)), realToday = twentyFifth, patterns = emptyList())!!
        eq("0", paid.incomeRemaining); eq("1900", paid.expectedIncome)
    }

    @Test fun `la quota degli obiettivi già versata nel periodo non si riserva di nuovo`() {
        val data = exampleData()
        val plan = Goal(1, "Fondo", BigDecimal(5000), BigDecimal(500), monthly = BigDecimal(300), accountId = 1)
        val cash: (LocalDate) -> it.spese.domain.finance.CashPosition? = { day -> it.spese.domain.finance.CashCalculator.compute(data, null, listOf(plan), emptyList(), day) }
        val notYet = DailyBudgetEngine.compute(data, goals = listOf(plan), realToday = twentyFifth, patterns = emptyList(), cashSource = cash)!!
        eq("300", notYet.savingsDue)                                                                   // 300 ancora da mettere da parte
        val deposited = plan.copy(deposits = listOf(it.spese.core.model.GoalDeposit(d("2026-11-23"), BigDecimal(200))))
        val half = DailyBudgetEngine.compute(data, goals = listOf(deposited), realToday = twentyFifth, patterns = emptyList(), cashSource = cash)!!
        eq("100", half.savingsDue)                                                                     // 300 − 200 già versati dopo l'inizio del periodo
        val old = DailyBudgetEngine.compute(data, goals = listOf(plan.copy(deposits = listOf(it.spese.core.model.GoalDeposit(d("2026-11-10"), BigDecimal(300))))), realToday = twentyFifth, patterns = emptyList(), cashSource = cash)!!
        eq("300", old.savingsDue)                                                                      // un versamento del periodo precedente non conta
    }

    @Test fun `senza obiettivi i soldi bloccati non esistono e il saldo intero è spendibile`() {
        val data = exampleData()
        val c = it.spese.domain.finance.CashCalculator.compute(data, null, emptyList(), emptyList(), twentyFifth)!!
        eq("900", c.balance); eq("0", c.blocked); eq("900", c.spendable)
        // un obiettivo legato a un altro conto non blocca questo
        val other = Goal(2, "Altro", BigDecimal(100), BigDecimal(300), accountId = 7)
        eq("0", it.spese.domain.finance.CashCalculator.compute(data, setOf(1L), listOf(other), emptyList(), twentyFifth)!!.blocked)
        // il bloccato non supera mai il saldo
        eq("900", it.spese.domain.finance.CashCalculator.compute(data, setOf(1L), listOf(fondo.copy(saved = BigDecimal(5000))), emptyList(), twentyFifth)!!.blocked)
    }

    @Test fun `con un budget fissato a mano il saldo non entra nel calcolo`() {
        val data = exampleData()
        val b = DailyBudgetEngine.compute(data, manualLimit = BigDecimal(1500), realToday = twentyFifth, cashSource = { day -> it.spese.domain.finance.CashCalculator.compute(data, null, emptyList(), emptyList(), day) })!!
        assertNull(b.cash); eq("1500", b.limit)
    }
}
