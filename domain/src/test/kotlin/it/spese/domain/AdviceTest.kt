package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.advice.*
import it.spese.domain.finance.*
import it.spese.domain.networth.NetWorthEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class AdviceTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    private val today = d("2026-03-10")
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val cats = listOf(
        Category(1, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED), Category(2, "Svago", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY),
        Category(3, "Stipendio", TxKind.ENTRATA, false, 0),
    )
    // svago in crescita: 100 → 150 → 300; affitto 800; entrate 2.000
    private fun txs() = listOf("2025-12" to 100, "2026-01" to 150, "2026-02" to 300).flatMap { (m, svago) -> listOf(tx("$m-01", 2000, 3), tx("$m-02", -800, 1), tx("$m-03", -svago, 2)) } + tx("2026-03-05", -20, 2)
    private fun data(cats: List<Category> = this.cats) = AppData(transactions = txs(), categories = cats, loaded = true, accounts = listOf(Account(1, "Conto", AccountType.CONTO, BigDecimal.ZERO)))
    private fun goal(monthly: Int, saved: Int = 0, target: Int = 9000, prio: Int = 0, id: Long = 1) = Goal(id, "Fondo", BigDecimal(target), BigDecimal(saved), monthly = BigDecimal(monthly), priority = prio)

    @Test fun `il mese da chiudere è il precedente al corrente`() {
        assertEquals(YearMonth.of(2026, 2), MonthReviewEngine.reviewMonth(data(), today))
        assertNull(MonthReviewEngine.reviewMonth(AppData(loaded = true), today))
    }

    @Test fun `chiusura - risparmio, confronto con la quota, categorie e 50-30-20`() {
        val r = MonthReviewEngine.compute(data(), listOf(goal(500)), today)!!
        eq("2000", r.income); eq("1100", r.expense); eq("900", r.saved); assertEquals(45, r.savingsRatePercent)
        eq("500", r.goalQuota); eq("400", r.vsGoal); assertTrue(r.goalReached)
        eq("950", r.previousExpense)                                      // gennaio: 800 + 150
        assertEquals(listOf("Affitto", "Svago"), r.topCategories.map { it.category.name }); assertEquals(73, r.topCategories[0].sharePercent)
        eq("150", r.topCategories[1].previous); eq("150", r.topCategories[1].delta)
        assertEquals(40, r.split.needsPercent); assertEquals(15, r.split.wantsPercent); assertEquals(0, r.split.otherPercent); assertEquals(45, r.split.savingsPercent)
        assertEquals(0, r.unclassifiedPercent)
    }

    @Test fun `risparmio oltre la quota - proposta di spostare il surplus sull'obiettivo e un tetto alla voce in crescita`() {
        val r = MonthReviewEngine.compute(data(), listOf(goal(500, target = 9000)), today)!!
        val move = r.actions.filterIsInstance<ReviewAction.MoveToGoal>().single()
        eq("400", move.amount); assertEquals(1L, move.goalId)
        val cap = r.actions.filterIsInstance<ReviewAction.SetBudget>().single()
        assertEquals("Svago", cap.categoryName); eq("125", cap.amount)    // media di dicembre e gennaio
        // il surplus non supera quanto manca all'obiettivo
        eq("100", MonthReviewEngine.compute(data(), listOf(goal(500, saved = 8900, target = 9000)), today)!!.actions.filterIsInstance<ReviewAction.MoveToGoal>().single().amount)
        // obiettivo già raggiunto: niente da spostare
        assertTrue(MonthReviewEngine.compute(data(), listOf(goal(500, saved = 9000, target = 9000)), today)!!.actions.none { it is ReviewAction.MoveToGoal })
    }

    @Test fun `risparmio sotto la quota - si propone di ridurre la voce discrezionale più pesante`() {
        val r = MonthReviewEngine.compute(data(), listOf(goal(1000)), today)!!          // risparmiati 900, quota 1.000
        assertFalse(r.goalReached); eq("-100", r.vsGoal)
        val cut = r.actions.filterIsInstance<ReviewAction.SetBudget>().single()
        assertEquals("Svago", cut.categoryName); eq("200", cut.amount)
        assertTrue(r.actions.none { it is ReviewAction.MoveToGoal })
        // se la riduzione non può bastare (sotto 5 €), non si propone un budget assurdo
        assertTrue(MonthReviewEngine.compute(data(), listOf(goal(1500)), today)!!.actions.filterIsInstance<ReviewAction.SetBudget>().none { it.amount.signum() <= 0 })
    }

    @Test fun `senza natura assegnata la ripartizione lo dichiara`() {
        val r = MonthReviewEngine.compute(data(cats.map { it.copy(nature = null) }), listOf(goal(500)), today)!!
        assertEquals(100, r.unclassifiedPercent); assertEquals(0, r.split.needsPercent)
    }

    @Test fun `consigli - fondo in costruzione, categoria in crescita e abbonamento aumentato, dal piu importante`() {
        val sub = RecurringPattern("netflix", "Netflix", Cadence.MONTHLY, PatternKind.SUBSCRIPTION, 6, d("2025-09-01"), d("2026-02-01"), d("2026-03-01"), BigDecimal("12.99"), BigDecimal("11.50"), 2, 1, previousAmount = BigDecimal("10.99"))
        val ins = InsightEngine.compute(data(), listOf(goal(500)), emptyList(), listOf(sub), today)
        val ids = ins.map { it.id }
        assertTrue(ids.toString(), "fund-building" in ids && "growing-2" in ids && "sub-up-netflix" in ids)
        assertTrue(ins.all { it.why.isNotBlank() && it.text.isNotBlank() })
        assertEquals(ins.sortedByDescending { it.level }, ins)
        assertTrue(ins.first { it.id == "sub-up-netflix" }.title.contains("Netflix"))
        assertEquals(LearnTopic.EMERGENCY_FUND, ins.first { it.id == "fund-building" }.topic)
    }

    @Test fun `consigli - obiettivo in ritardo`() {
        val late = Goal(1, "Auto", BigDecimal(6000), BigDecimal.ZERO, deadline = d("2027-03-15"), monthly = BigDecimal(300))
        val ins = InsightEngine.compute(data(), listOf(late), emptyList(), emptyList(), today)
        assertEquals(2, ins.first { it.id == "goal-late-1" }.level); assertEquals(InsightTarget.GOALS, ins.first { it.id == "goal-late-1" }.target)
    }

    @Test fun `guida - frasi legate ai tuoi numeri e valore futuro illustrativo`() {
        val net = NetWorthEngine.compute(data(), emptyList(), emptyList(), today)
        val review = MonthReviewEngine.compute(data(), listOf(goal(500)), today)
        val ctx = LearnContext(net, review, BigDecimal(200))
        assertTrue(LearnTopic.EMERGENCY_FUND.personal(ctx)!!.contains("4.800"))       // 6 × 800
        assertTrue(LearnTopic.RULE_50_30_20.personal(ctx)!!.contains("bisogni 40%"))
        assertTrue(LearnTopic.FIXED_VS_DISCRETIONARY.personal(ctx)!!.contains("40%"))
        assertNull(LearnTopic.EMERGENCY_FUND.personal(LearnContext()))
        val fv = LearnCardsFv.of(100)
        assertTrue("valore futuro $fv", fv in BigDecimal(14700)..BigDecimal(14760))
        assertTrue(LearnTopic.COMPOUND_INTEREST.personal(LearnContext())!!.contains("100"))
    }
}

private object LearnCardsFv { fun of(m: Int): BigDecimal = LearnTopic.futureValue(BigDecimal(m), 4, 10) }
