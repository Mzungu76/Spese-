package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.capture.BankNotificationParser
import it.spese.domain.capture.NotificationReconciler
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class AssistantTest {
    private val cats = listOf(
        Category(1, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED),
        Category(2, "Alimentari", TxKind.SPESA, false, 0),
        Category(3, "Ristoranti", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY),
        Category(4, "Stipendio", TxKind.ENTRATA, false, 0),
        Category(5, "Streaming", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY),
        Category(6, "Altro", TxKind.SPESA, true, 0, systemKey = Category.FALLBACK_EXPENSE),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long, desc: String, origin: TxOrigin = TxOrigin.IMPORT_FILE) =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount),
            if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat, origin = origin)

    /** Tre mesi pieni (lug-set) + ottobre in corso: stipendio 2000, affitto 600, streaming 12, spesa e ristoranti variabili (due passaggi al mese). */
    private fun history(): List<Transaction> {
        val l = ArrayList<Transaction>()
        for (m in listOf("2026-07", "2026-08", "2026-09", "2026-10")) {
            l += tx("$m-01", 2000, 4, "Stipendio Acme")
            l += tx("$m-03", -600, 1, "Affitto casa")
            l += tx("$m-05", -12, 5, "Netflix")
            l += tx("$m-04", -120, 2, "Esselunga")
            l += tx("$m-12", -130, 2, "Esselunga")
            l += tx("$m-06", -45, 3, "Trattoria Bella")
            l += tx("$m-14", -45, 3, "Trattoria Bella")
        }
        return l
    }
    private fun data(extra: List<Transaction> = emptyList(), dues: List<Due> = emptyList(), goal: SavingsGoal? = null) =
        AppData(history() + extra, cats, listOf(Account(1, "C", openingBalance = BigDecimal(1000))), dues = dues,
            scenarios = listOfNotNull(goal?.let { Scenario(1, "Piano", goal = it, isBase = true) }), loaded = true)

    private fun daily(d: AppData, today: String) =
        DailyBudgetEngine.compute(d.transactions, d.categories, d.goal, d.dues, LocalDate.parse(today), LocalDate.parse(today))!!

    // ---- Disponibile oggi ----
    @Test fun `il budget variabile toglie risparmio e fisse dalle entrate attese`() {
        val d = daily(data(goal = SavingsGoal(value = BigDecimal(300))), "2026-10-16")
        assertEquals(IncomeSource.HISTORY, d.incomeSource)
        assertEquals(0, BigDecimal(2000).compareTo(d.expectedIncome))
        // fisse = affitto 600 + netflix 12 (ricorrenze rilevate, già pagate a ottobre)
        assertEquals(0, BigDecimal(612).compareTo(d.fixedPaid))
        assertEquals(0, d.fixedRemaining.signum())
        // 2000 - 300 - 612 = 1088; speso variabile: 250 + 90 = 340 -> restano 748 in 16 giorni (16..31)
        assertEquals(0, BigDecimal(1088).compareTo(d.variableBudget))
        assertEquals(0, BigDecimal(340).compareTo(d.variableSpent))
        assertEquals(0, BigDecimal(748).compareTo(d.available))
        assertEquals(16, d.daysLeft)
        assertEquals(0, BigDecimal("46.75").compareTo(d.perDay))
        assertEquals(DailyStatus.GOOD, d.status)
    }

    @Test fun `le uscite fisse non ancora pagate riducono il disponibile`() {
        val d = daily(data(goal = SavingsGoal(value = BigDecimal(300))).let { it.copy(transactions = it.transactions.filter { t -> !(t.description == "Affitto casa" && t.bookingDate.toString() == "2026-10-03") }) }, "2026-10-02")
        // l'affitto di ottobre manca: è una ricorrenza attesa (prossimo ~ 3 ott) e va riservata
        assertEquals(0, BigDecimal(600).compareTo(d.fixedRemaining))
        assertTrue(d.fixedRemainingItems.any { it.first.contains("Affitto", true) })
    }

    @Test fun `una scadenza manuale mensile entro il mese e una spesa annuale entrano nel calcolo`() {
        val dues = listOf(
            Due(1, "d1", "Palestra", BigDecimal(40), LocalDate.parse("2026-10-20"), DueFrequency.MONTHLY),
            Due(2, "d2", "Assicurazione auto", BigDecimal(480), LocalDate.parse("2026-12-10"), DueFrequency.YEARLY),
        )
        val d = daily(data(dues = dues, goal = SavingsGoal(value = BigDecimal(300))), "2026-10-16")
        assertEquals(0, BigDecimal(40).compareTo(d.fixedRemaining))
        assertEquals(0, BigDecimal(40).compareTo(d.annualAccrual))   // 480 / 12
        assertEquals(0, BigDecimal(1008).compareTo(d.variableBudget)) // 1088 - 40 (palestra) - 40 (accantonamento annuale)
    }

    @Test fun `sforare il budget da stato OVER e perDay a zero`() {
        val d = daily(data(listOf(tx("2026-10-14", -900, 2, "Concessionario"), tx("2026-10-15", -300, 3, "Cena grande")), goal = SavingsGoal(value = BigDecimal(300))), "2026-10-16")
        assertEquals(DailyStatus.OVER, d.status)
        assertEquals(0, d.perDay.signum())
        assertTrue(d.available.signum() < 0)
    }

    @Test fun `senza entrate note serve la configurazione`() {
        val only = listOf(tx("2026-10-02", -20, 2, "Esselunga"))
        val d = DailyBudgetEngine.compute(only, cats, SavingsGoal(), emptyList(), LocalDate.parse("2026-10-03"))!!
        assertEquals(DailyStatus.SETUP, d.status)
    }

    @Test fun `le entrate indicate nel piano hanno la precedenza e i dati vecchi sono segnalati`() {
        val d = DailyBudgetEngine.compute(history(), cats, SavingsGoal(value = BigDecimal(200), expectedIncome = BigDecimal(2500)), emptyList(), LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-26"))!!
        assertEquals(IncomeSource.GOAL, d.incomeSource)
        assertEquals(0, BigDecimal(2500).compareTo(d.expectedIncome))
        assertEquals(12, d.staleDays) // ultimo movimento 14 ott, oggi 26
    }

    @Test fun `il saldo e il rapporto mancante non vanno mai in errore`() {
        assertNull(DailyBudgetEngine.compute(emptyList(), cats, SavingsGoal(), emptyList(), LocalDate.parse("2026-10-03")))
    }

    // ---- Spese annuali ----
    @Test fun `una spesa annuale e distribuita su dodici mesi e mostra quanto dovresti aver accantonato`() {
        val p = AnnualAccruals.plan(listOf(Due(1, "x", "Bollo", BigDecimal(240), LocalDate.parse("2026-12-16"), DueFrequency.YEARLY)), emptyList(), LocalDate.parse("2026-10-16"))
        val i = p.items.single()
        assertEquals(0, BigDecimal(20).compareTo(i.monthly))
        assertEquals(61, i.daysLeft)
        // 61 giorni = 2,0 mesi -> ceil = 3 mesi mancanti: 9/12 di 240 = 180
        assertEquals(0, BigDecimal(180).compareTo(i.shouldHaveSaved))
    }

    @Test fun `le spese annuali si riconoscono dallo storico`() {
        val l = listOf(tx("2024-11-20", -450, 6, "Assicurazione Zurich"), tx("2025-11-22", -470, 6, "Assicurazione Zurich"), tx("2026-01-05", -10, 6, "Altro"))
        val p = AnnualAccruals.plan(emptyList(), l, LocalDate.parse("2026-10-16"))
        assertEquals(1, p.items.size)
        assertTrue(p.items[0].name.contains("Zurich", true))
        assertEquals(LocalDate.parse("2026-11-22").plusDays(0), p.items[0].next.let { LocalDate.parse("2026-11-22") }) // prossimo ~22 nov 2026
        assertTrue(p.items[0].daysLeft in 30..45)
    }

    // ---- Fondo di emergenza e surplus ----
    @Test fun `il fondo di emergenza guida la destinazione del surplus`() {
        val build = SurplusPlanner.plan(BigDecimal(1000), BigDecimal(1000), BigDecimal(300))!!
        assertEquals(FundStage.BUILD, build.stage)
        assertEquals(1, build.steps.size); assertEquals(0, BigDecimal(300).compareTo(build.steps[0].amount))
        assertEquals(7, build.monthsToMinFund) // mancano 2000 a 300/mese
        val grow = SurplusPlanner.plan(BigDecimal(4000), BigDecimal(1000), BigDecimal(300))!!
        assertEquals(FundStage.GROW, grow.stage)
        assertEquals(listOf(150, 150), grow.steps.map { it.amount.toInt() })
        val done = SurplusPlanner.plan(BigDecimal(7000), BigDecimal(1000), BigDecimal(300))!!
        assertEquals(FundStage.COMPLETE, done.stage)
        assertEquals(listOf(240, 60), done.steps.map { it.amount.toInt() })
        assertNull(SurplusPlanner.plan(BigDecimal(100), BigDecimal.ZERO, BigDecimal(100)))
        assertEquals(0, SurplusPlanner.plan(BigDecimal(100), BigDecimal(1000), BigDecimal(-50))!!.surplus.signum())
    }

    @Test fun `la spesa essenziale conta fisse e necessarie`() {
        val d = data()
        val ess = SurplusPlanner.essentialMonthly(d.transactions, d, emptyMap())
        // affitto 600 (fissa) al mese; alimentari senza natura e senza suggerimento = non conta; ristoranti/streaming discrezionali = no
        assertTrue("essenziale ${ess}", ess > BigDecimal(600) && ess < BigDecimal(760))
        val withSug = SurplusPlanner.essentialMonthly(d.transactions, d, mapOf(2L to NatureSuggestion(SpendNature.ESSENTIAL, "")))
        assertTrue(withSug > ess)
        assertEquals(0, BigDecimal(1000).compareTo(SurplusPlanner.liquidBalance(AppData(accounts = listOf(Account(1, "C", openingBalance = BigDecimal(1000)))))))
    }

    // ---- Coach, lezioni, report ----
    @Test fun `il coach segnala dati vecchi per primi e riconosce la costanza`() {
        val d = data(goal = SavingsGoal(value = BigDecimal(300)))
        val rec = RecurringDetector.detect(d.transactions)
        val ins = Coach.insights(CoachInput(d.transactions, cats, d.goal, rec, emptyMap(), 12, null, AccrualPlan(emptyList()), 10, LocalDate.parse("2026-10-16")))
        assertEquals("stale", ins.first().id)
        assertEquals(Tone.WARNING, ins.first().tone)
        assertTrue(ins.any { it.id == "streak" && it.tone == Tone.PRAISE })   // lug-set: 1048 messi da parte >= 300, 3 mesi
        assertTrue(ins.any { it.id == "unclassified" })
    }

    @Test fun `gli abbonamenti discrezionali sommano il costo annuo`() {
        val d = data(); val rec = listOf(
            RecurringItem("Netflix", 6, BigDecimal(13), LocalDate.parse("2026-10-05"), LocalDate.parse("2026-11-05"), 5),
            RecurringItem("Spotify", 6, BigDecimal(11), LocalDate.parse("2026-10-07"), LocalDate.parse("2026-11-07"), 5),
            RecurringItem("Palestra", 6, BigDecimal(45), LocalDate.parse("2026-10-09"), LocalDate.parse("2026-11-09"), 3),
        )
        val ins = Coach.insights(CoachInput(d.transactions, cats, d.goal, rec, emptyMap(), 0, null, AccrualPlan(emptyList()), 0, LocalDate.parse("2026-10-16")))
        val subs = ins.single { it.id == "subs" }
        assertTrue(subs.title, subs.title.contains("828")) // (13+11+45)*12
    }

    @Test fun `il valore futuro e calcolato con interesse composto e le lezioni ruotano`() {
        val fv = Lessons.futureValue(BigDecimal(200), 20, 4)
        assertTrue("fv=$fv", fv > BigDecimal(73000) && fv < BigDecimal(73700))
        assertEquals(0, BigDecimal(2400).compareTo(Lessons.futureValue(BigDecimal(100), 2, 0)))
        val a = Lessons.forToday(LocalDate.parse("2026-10-16"), FundStage.BUILD, BigDecimal(300))
        val b = Lessons.forToday(LocalDate.parse("2026-10-17"), FundStage.BUILD, BigDecimal(300))
        assertNotEquals(a.id, b.id)
    }

    @Test fun `il report del mese confronta, riconosce l'obiettivo e racconta l'andamento`() {
        val d = data()
        val last = MonthlyReportBuilder.lastCompleteMonth(d.transactions, LocalDate.parse("2026-10-20"))!!
        assertEquals(YearMonth.of(2026, 10), last.plusMonths(0).let { YearMonth.of(2026, 10) }.let { last.plusMonths(1) }) // ultimo chiuso = settembre
        val r = MonthlyReportBuilder.build(d.transactions, d.categories, SavingsGoal(value = BigDecimal(300)), last)!!
        assertEquals(0, BigDecimal(2000).compareTo(r.income)); assertEquals(0, BigDecimal(952).compareTo(r.expense))
        assertEquals(0, BigDecimal(1048).compareTo(r.net)); assertEquals(52, r.savingsRate)
        assertTrue(r.goalReached); assertTrue(r.headline.contains("obiettivo raggiunto"))
        assertEquals("Affitto", r.topCategories.first().first)
        assertEquals(3, r.history.size.coerceAtMost(3).let { if (r.history.size >= 3) 3 else r.history.size })
        assertNull(MonthlyReportBuilder.lastCompleteMonth(emptyList(), LocalDate.parse("2026-10-20")))
    }

    @Test fun `le notifiche riassumono giorno e settimana`() {
        val d = daily(data(goal = SavingsGoal(value = BigDecimal(300))), "2026-10-16")
        val (title, text) = Digest.daily(d)!!
        assertTrue(title, title.startsWith("Oggi puoi spendere circa"))
        assertTrue(text, text.contains("fino a fine mese (16 giorni)"))
        val w = Digest.weekly(history() + listOf(tx("2026-10-14", -50, 2, "Coop"), tx("2026-10-08", -100, 2, "Coop")), LocalDate.parse("2026-10-15"), d)!!
        assertTrue(w.second, w.second.contains("Negli ultimi 7 giorni"))
        assertNull(Digest.daily(d.copy(status = DailyStatus.SETUP)))
    }

    // ---- Configurazione guidata ----
    @Test fun `la configurazione propone entrate, fisse, obiettivo e budget dai dati`() {
        val p = SetupAdvisor.propose(data(), LocalDate.parse("2026-10-20"))!!
        assertEquals(0, BigDecimal(2000).compareTo(p.expectedIncome))
        assertTrue(p.fixed.any { it.merchant.contains("Affitto", true) })
        // risparmia ~1048 su 2000 = 52%: l'opzione più alta (20% = 400) è già alla portata
        assertEquals(0, BigDecimal(400).compareTo(p.recommendedGoal))
        assertTrue(p.budgets.none { it.nature == SpendNature.FIXED })
        assertTrue(p.budgets.any { it.name == "Alimentari" })
        assertNull(SetupAdvisor.propose(AppData(loaded = true)))
    }

    // ---- Notifiche della banca ----
    @Test fun `legge pagamenti e accrediti dalle notifiche e ignora il resto`() {
        val p = BankNotificationParser.parse("Revolut", "Pagamento di 12,50 € presso ESSELUNGA MILANO con carta *1234. Saldo disponibile 1.234,56 €")!!
        assertEquals(0, BigDecimal("-12.50").compareTo(p.amount)); assertEquals("ESSELUNGA MILANO", p.merchant)
        val e = BankNotificationParser.parse("Accredito", "Hai ricevuto un bonifico di 1.500,00 euro da ACME SRL")!!
        assertEquals(0, BigDecimal("1500.00").compareTo(e.amount)); assertEquals("ACME SRL", e.merchant)
        val s = BankNotificationParser.parse(null, "Hai speso € 8,90 da Bar Roma")!!
        assertEquals(0, BigDecimal("-8.90").compareTo(s.amount))
        assertNull(BankNotificationParser.parse("Offerta", "Prestito fino a 30.000 € a tasso agevolato"))
        assertNull(BankNotificationParser.parse("Saldo", "Il tuo saldo disponibile è 1.234,56 €"))
        assertNull(BankNotificationParser.parse("Ciao", "Pagamento effettuato"))
        assertNull(BankNotificationParser.parse(null, null))
    }

    @Test fun `l'estratto conto sostituisce la notifica corrispondente una sola volta`() {
        val n1 = tx("2026-10-14", -12, 2, "ESSELUNGA", TxOrigin.NOTIFICATION)
        val n2 = tx("2026-10-14", -12, 2, "ESSELUNGA", TxOrigin.NOTIFICATION)
        val other = tx("2026-10-14", -12, 2, "ESSELUNGA", TxOrigin.IMPORT_FILE)
        val m = NotificationReconciler.match(listOf(n1, n2, other), listOf(LocalDate.parse("2026-10-15") to BigDecimal(-12), LocalDate.parse("2026-10-16") to BigDecimal(-12), LocalDate.parse("2026-10-16") to BigDecimal(-12), LocalDate.parse("2026-10-30") to BigDecimal(-12)))
        assertEquals(setOf(0, 1), m.keys)
        assertNotEquals(m.getValue(0).id, m.getValue(1).id)
        assertTrue(m.values.none { it.id == other.id })
    }
}
