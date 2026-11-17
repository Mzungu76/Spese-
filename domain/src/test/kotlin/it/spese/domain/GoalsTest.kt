package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.goals.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GoalsTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    private val today = d("2026-03-15")

    @Test fun `quota dalla scadenza e avanzamento`() {
        val g = Goal(1, "Vacanza", BigDecimal(1200), BigDecimal(300), deadline = d("2026-09-20"))
        val p = GoalEngine.progress(g, today)
        eq("900", p.remaining); assertEquals(25, p.percent)
        eq("150", p.monthlyQuota)                 // 900 in 6 mesi (marzo → settembre)
        assertEquals(GoalStatus.ON_TRACK, p.status); assertEquals(d("2026-09-15"), p.eta)
    }

    @Test fun `quota scelta più bassa del necessario - in ritardo, con quanto manca`() {
        val g = Goal(1, "Auto", BigDecimal(6000), BigDecimal.ZERO, deadline = d("2027-03-15"), monthly = BigDecimal(300))
        val p = GoalEngine.progress(g, today)
        eq("300", p.monthlyQuota); eq("500", p.neededForDeadline); assertEquals(GoalStatus.LATE, p.status); eq("200", p.shortBy)
        assertEquals(d("2027-11-15"), p.eta)      // 20 mesi
    }

    @Test fun `completato, in pausa e senza piano non accantonano nulla`() {
        eq("0", GoalEngine.progress(Goal(1, "A", BigDecimal(100), BigDecimal(120)), today).monthlyQuota)
        assertEquals(GoalStatus.COMPLETED, GoalEngine.progress(Goal(1, "A", BigDecimal(100), BigDecimal(120)), today).status)
        assertEquals(GoalStatus.PAUSED, GoalEngine.progress(Goal(1, "B", BigDecimal(100), monthly = BigDecimal(10), enabled = false), today).status)
        assertEquals(GoalStatus.NO_PLAN, GoalEngine.progress(Goal(1, "C", BigDecimal(100)), today).status)
    }

    @Test fun `totale mensile e versamenti`() {
        val gs = listOf(Goal(1, "Fondo", BigDecimal(3000), monthly = BigDecimal(200)), Goal(2, "Viaggio", BigDecimal(600), deadline = d("2026-06-30")), Goal(3, "Pausa", BigDecimal(500), monthly = BigDecimal(50), enabled = false))
        eq("400", GoalEngine.monthlyTotal(gs, today))   // 200 + 600/3
        eq("100", GoalEngine.deposit(Goal(1, "x", BigDecimal(500)), BigDecimal(100)).saved)
        eq("0", GoalEngine.deposit(Goal(1, "x", BigDecimal(500), BigDecimal(30)), BigDecimal(-100)).saved)
    }

    @Test fun `salvataggio e lettura degli obiettivi`() {
        val gs = listOf(Goal(7, "Fondo \"emergenza\"", BigDecimal("3000.50"), BigDecimal("120"), d("2027-01-31"), BigDecimal("150"), 2, false), Goal(8, "B", BigDecimal(10)))
        val back = GoalCodec.decode(GoalCodec.encode(gs))
        assertEquals(gs.map { it.copy(target = it.target.stripTrailingZeros()) }.size, back.size)
        assertEquals("Fondo \"emergenza\"", back[0].name); eq("3000.50", back[0].target); assertEquals(d("2027-01-31"), back[0].deadline); eq("150", back[0].monthly); assertFalse(back[0].enabled); assertNull(back[1].monthly)
        assertTrue(GoalCodec.decode("non json").isEmpty()); assertTrue(GoalCodec.decode(null).isEmpty())
    }

    // --- «Disponibile oggi» e budget ---
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val cats = listOf(
        Category(1, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED), Category(2, "Stipendio", TxKind.ENTRATA, false, 0),
        Category(3, "Ristoranti", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY), Category(4, "Spesa", TxKind.SPESA, false, 0, nature = SpendNature.ESSENTIAL),
        Category(5, "Altro", TxKind.SPESA, true, 0, systemKey = Category.FALLBACK_EXPENSE),
    )
    private fun history() = listOf("2025-12", "2026-01", "2026-02").flatMap { m -> listOf(tx("$m-01", 2000, 2), tx("$m-02", -800, 1), tx("$m-10", -400, 4), tx("$m-15", -500, 3), tx("$m-20", -3, 5)) }
    private val data get() = AppData(transactions = history(), categories = cats, accounts = listOf(Account(1, "C")), loaded = true)

    @Test fun `gli obiettivi decidono la quota di risparmio di Disponibile oggi`() {
        val goals = listOf(Goal(1, "Fondo", BigDecimal(5000), monthly = BigDecimal(250)), Goal(2, "Viaggio", BigDecimal(900), deadline = d("2026-06-30")))
        val r = SafeToSpendEngine.compute(data, goals = goals, realToday = d("2026-03-05"))!!
        eq("550", r.savingsTarget); assertTrue(r.savingsFromGoals)        // 250 + 900/3
        val legacy = SafeToSpendEngine.compute(data, realToday = d("2026-03-05"))!!
        eq("300", legacy.savingsTarget); assertFalse(legacy.savingsFromGoals)
    }

    @Test fun `budget - il comprimibile si riduce quanto serve, il resto resta alla media`() {
        // entrate 2.000; fisse 800 + necessarie 400; obiettivi 500 → restano 300 per i ristoranti (media 500): taglio ≥ 40%
        val goals = listOf(Goal(1, "Fondo", BigDecimal(9000), monthly = BigDecimal(500)))
        val plan = BudgetSuggester.suggest(data, goals, d("2026-03-05"))!!
        eq("2000", plan.income); eq("500", plan.savings); assertEquals(3, plan.months)
        assertEquals(setOf("Affitto", "Ristoranti", "Spesa"), plan.proposals.map { it.category.name }.toSet())       // «Altro» (sistema) e sotto 5 € esclusi
        eq("800", plan.proposals.first { it.category.name == "Affitto" }.proposed)
        eq("400", plan.proposals.first { it.category.name == "Spesa" }.proposed)
        val rist = plan.proposals.first { it.category.name == "Ristoranti" }
        assertEquals(40, plan.cutPercent); eq("300", rist.proposed); assertTrue(rist.compressible); assertEquals(40, rist.cutPercent)
        eq("0", plan.shortfall)
    }

    @Test fun `budget - se non serve tagliare, la proposta è la media`() {
        val plan = BudgetSuggester.suggest(data, listOf(Goal(1, "Fondo", BigDecimal(9000), monthly = BigDecimal(100))), d("2026-03-05"))!!
        assertEquals(0, plan.cutPercent); eq("500", plan.proposals.first { it.category.name == "Ristoranti" }.proposed)
    }

    @Test fun `budget - obiettivi troppo alti, manca anche azzerando il comprimibile`() {
        val plan = BudgetSuggester.suggest(data, listOf(Goal(1, "Fondo", BigDecimal(90000), monthly = BigDecimal(1000))), d("2026-03-05"))!!
        assertEquals(100, plan.cutPercent); eq("200", plan.shortfall)      // 2000 − 1000 − 1200
        eq("0", plan.proposals.first { it.category.name == "Ristoranti" }.proposed)
    }

    @Test fun `budget - senza storico non propone nulla`() {
        assertNull(BudgetSuggester.suggest(AppData(transactions = listOf(tx("2026-03-02", -40, 4)), categories = cats, loaded = true), emptyList(), d("2026-03-05")))
    }
}

class GoalBackupTest {
    @Test fun `gli obiettivi viaggiano nel backup, con scadenza e quota`() {
        val state = it.spese.domain.backup.LegacyState(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(),
            it.spese.domain.backup.LegacyGoal(GoalMode.MONTHLY, BigDecimal(300), 12, null), emptyMap(), emptyMap())
        val goals = listOf(
            it.spese.domain.backup.NativeGoal("Fondo", BigDecimal("3000"), BigDecimal("450.50"), LocalDate.parse("2027-06-30"), BigDecimal("150"), 0, true),
            it.spese.domain.backup.NativeGoal("Viaggio", BigDecimal("800"), BigDecimal.ZERO, null, null, 1, false),
        )
        val json = it.spese.domain.backup.LegacyBackupCodec.encode(it.spese.domain.backup.BackupEnvelope("2026-03-15", "t", state, it.spese.domain.backup.NativeExtras(goals = goals)))
        val back = it.spese.domain.backup.LegacyBackupCodec.parse(json).extras!!.goals
        assertEquals(2, back.size)
        assertEquals("Fondo", back[0].name); assertEquals(0, BigDecimal("450.50").compareTo(back[0].saved)); assertEquals(LocalDate.parse("2027-06-30"), back[0].deadline); assertEquals(0, BigDecimal(150).compareTo(back[0].monthly))
        assertEquals("Viaggio", back[1].name); assertNull(back[1].deadline); assertNull(back[1].monthly); assertFalse(back[1].enabled); assertEquals(1, back[1].priority)
    }
}
