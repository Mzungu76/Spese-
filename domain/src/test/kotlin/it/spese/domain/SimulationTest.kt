package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.StatsEngine
import it.spese.domain.simulation.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class SimulationTest {
    private fun d(s: String) = BigDecimal(s)
    private val cats = listOf(
        CategoryBaseline(1, "Alimentari", d("400")), CategoryBaseline(2, "Bar", d("100"), true), CategoryBaseline(3, "Abbonamenti", d("50"), true),
    )
    private val base = SimulationInput(monthlyIncome = d("1500"), categories = cats, startMonth = YearMonth.of(2024, 1), startingBalance = d("1000"))

    @Test fun `senza modifiche il risparmio e entrate meno spese`() {
        val r = SimulationEngine.run(base)
        assertEquals(0, d("950").compareTo(r.simulatedMonthlySaving)); assertEquals(0, r.baselineMonthlySaving.compareTo(r.simulatedMonthlySaving))
        assertEquals(0, d("1000").compareTo(r.projection[0].balance))
        assertEquals(0, (d("1000") + d("950") * BigDecimal(12)).compareTo(r.balanceAt(12)))
        assertEquals(13, r.projection.size)
    }

    @Test fun `riducendo le spese alimentari del 10 percento`() {
        val r = SimulationEngine.run(base.copy(cuts = mapOf(1L to 10)))
        assertEquals(0, d("40").compareTo(r.cutSavings)); assertEquals(0, d("990").compareTo(r.simulatedMonthlySaving))
        assertEquals(0, d("1000").add(d("990").multiply(BigDecimal(6))).compareTo(r.balanceAt(6)))
    }

    @Test fun `aumentando le entrate di 200 euro`() {
        val r = SimulationEngine.run(base.copy(incomeDelta = d("200")))
        assertEquals(0, d("1150").compareTo(r.simulatedMonthlySaving)); assertEquals(0, d("200").compareTo(r.simulatedMonthlySaving - r.baselineMonthlySaving))
    }

    @Test fun `tempo per raggiungere un obiettivo`() {
        val goal = SavingsGoal(GoalMode.TOTAL, d("5000"), 12)
        assertEquals(6, SimulationEngine.run(base.copy(goal = goal)).monthsToGoal)            // 5000/950 = 5,26 -> 6
        assertEquals(4, SimulationEngine.run(base.copy(goal = goal, incomeDelta = d("400"))).monthsToGoal) // 5000/1350 = 3,7 -> 4
        assertNull(SimulationEngine.monthsToReach(d("100"), d("-5")))
        assertEquals(0, SimulationEngine.monthsToReach(d("100"), d("10"), d("200")))
    }

    @Test fun `eliminando una spesa ricorrente`() {
        val rec = RecurringCommitment("rec:Netflix", "Netflix", d("15"), 3)
        val r = SimulationEngine.run(base.copy(recurring = listOf(rec), removedRecurring = setOf("rec:Netflix")))
        assertEquals(0, d("15").compareTo(r.recurringRemovedSavings)); assertEquals(0, d("965").compareTo(r.simulatedMonthlySaving))
        // non si può togliere più della spesa della categoria
        val big = rec.copy(monthlyAmount = d("500"))
        assertEquals(0, d("50").compareTo(SimulationEngine.run(base.copy(recurring = listOf(big), removedRecurring = setOf(big.key))).recurringRemovedSavings))
    }

    @Test fun `taglio dopo l'eliminazione agisce sul residuo`() {
        val rec = RecurringCommitment("rec:A", "A", d("20"), 3)
        val r = SimulationEngine.run(base.copy(recurring = listOf(rec), removedRecurring = setOf(rec.key), cuts = mapOf(3L to 50)))
        assertEquals(0, d("15").compareTo(r.cutSavings))      // (50-20)*50%
        assertEquals(0, d("20").compareTo(r.recurringRemovedSavings))
        assertEquals(0, d("515").compareTo(r.simulatedExpense))   // 400 + 100 + (50-20)*50%
    }

    @Test fun `scadenze future riducono il saldo nei mesi in cui cadono`() {
        val sched = listOf(
            ScheduledOutflow("due:1", "Assicurazione", d("420"), YearMonth.of(2024, 3), DueFrequency.YEARLY),
            ScheduledOutflow("due:2", "Affitto", d("600"), YearMonth.of(2024, 2), DueFrequency.MONTHLY),
            ScheduledOutflow("due:3", "Bollo", d("100"), YearMonth.of(2024, 4), DueFrequency.ONCE),
        )
        val r = SimulationEngine.run(base.copy(scheduled = sched))
        assertEquals(0, d("1020").compareTo(r.projection[2].scheduledOutflows))  // marzo: 420 + 600
        assertEquals(0, d("700").compareTo(r.projection[3].scheduledOutflows))   // aprile: 600 + 100
        assertEquals(0, d("600").compareTo(r.projection[4].scheduledOutflows))
        val removed = SimulationEngine.run(base.copy(scheduled = sched, removedRecurring = setOf("due:2")))
        assertEquals(0, d("420").compareTo(removed.projection[2].scheduledOutflows))
    }

    @Test fun `obiettivo mensile e confronto con il risparmio`() {
        val r = SimulationEngine.run(base.copy(goal = SavingsGoal(GoalMode.MONTHLY, d("1000"))))
        assertFalse(r.goalReached); assertEquals(0, d("-50").compareTo(r.vsGoal))
        assertTrue(SimulationEngine.run(base.copy(goal = SavingsGoal(GoalMode.MONTHLY, d("900")))).goalReached)
        assertEquals(63, r.savingsRatePercent)
    }

    @Test fun `confronto tra scenari`() {
        val a = ScenarioOutcome("Base", SimulationEngine.run(base)); val b = ScenarioOutcome("Taglio", SimulationEngine.run(base.copy(cuts = mapOf(1L to 25))))
        val rows = ScenarioComparison.compare(listOf(a, b))
        assertEquals(0, BigDecimal.ZERO.compareTo(rows[0].deltaVsFirst)); assertEquals(0, d("100").compareTo(rows[1].deltaVsFirst))
        assertEquals(0, d("1000").add(d("1050").multiply(BigDecimal(12))).compareTo(rows[1].balance12!!))
    }

    @Test fun `piano - distribuzione automatica del taglio e suggerimenti`() {
        val p = PlanBase(StatsEngine.EMPTY, BigDecimal.ONE, d("1500"), d("1500"), d("550"),
            listOf(CategoryAverage(1, "Alimentari", false, d("400"), d("400")), CategoryAverage(2, "Bar", true, d("100"), d("100")), CategoryAverage(3, "Abb", true, d("50"), d("50"))))
        assertEquals(d("150"), p.flexibleAvg)
        val cut = PlanEngine.autoCut(p, d("1000"))!!         // gap = 50 -> 50/150 = 33,3% -> 35%
        assertEquals(35, cut.percent); assertTrue(cut.sufficient)
        val no = PlanEngine.autoCut(p, d("5000"))!!
        assertEquals(100, no.percent); assertFalse(no.sufficient)
        assertEquals(listOf(10 to d("150"), 15 to d("230"), 20 to d("300")), PlanEngine.suggestions(d("1500")).map { it.first to it.second.stripTrailingZeros().setScale(0) })
        assertEquals(d("30.00"), PlanEngine.potentialSavings(p).setScale(2))
        assertEquals(d("90"), PlanEngine.suggestedBudget(d("101"), 10).stripTrailingZeros().setScale(0))
        val (lo, hi) = PlanEngine.emergencyFund(d("1000")); assertEquals(d("3000"), lo); assertEquals(d("6000"), hi)
    }

    @Test fun `scadenze - avanzamento alla data successiva`() {
        fun due(f: DueFrequency) = Due(1, "x", "x", d("1"), LocalDate.of(2024, 1, 31), f)
        assertEquals(LocalDate.of(2024, 2, 29), PlanEngine.advanceDue(due(DueFrequency.MONTHLY)))
        assertEquals(LocalDate.of(2025, 1, 31), PlanEngine.advanceDue(due(DueFrequency.YEARLY)))
        assertNull(PlanEngine.advanceDue(due(DueFrequency.ONCE)))
    }

    @Test fun `tendenze e previsioni`() {
        val v = listOf(100, 110, 120, 130).map { BigDecimal(it) }
        val l = Trends.linear(v)!!
        assertEquals(0, BigDecimal(10).compareTo(l.slope)); assertEquals(0, BigDecimal(100).compareTo(l.intercept))
        assertEquals(listOf(140, 150), Trends.forecast(v, 2).map { it.toInt() })
        assertEquals(listOf(100, 105, 115, 125), Trends.movingAverage(v, 2).map { it.toInt() })
        assertEquals(d("10.0"), Trends.changePercent(d("100"), d("110")))
        assertNull(Trends.changePercent(BigDecimal.ZERO, d("5")))
        assertEquals(listOf(BigDecimal(0)), Trends.forecast(listOf(BigDecimal(10), BigDecimal(5), BigDecimal(0)), 1).map { it.stripTrailingZeros().setScale(0) }) // non scende sotto zero
        val var_ = Trends.categoryVariation(mapOf(1L to d("100")), mapOf(1L to d("150"), 2L to d("30")))
        assertEquals(1L, var_[0].categoryId); assertEquals(d("50.0"), var_[0].percent)
    }
}
