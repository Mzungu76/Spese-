package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.health.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class HealthTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)

    // 1 Stipendio, 2 Fatture (entrata variabile), 3 Affitto (fissa), 4 Spesa (necessaria), 5 Svago (discrezionale), 6 Ristoranti (discrezionale), 7 Palestra (natura non indicata), 8 Altro (non classificata)
    private val cats = listOf(
        Category(1, "Stipendio", TxKind.ENTRATA, false, 0), Category(2, "Fatture", TxKind.ENTRATA, false, 0),
        Category(3, "Affitto", TxKind.SPESA, false, 0, nature = SpendNature.FIXED), Category(4, "Spesa", TxKind.SPESA, false, 0, nature = SpendNature.ESSENTIAL),
        Category(5, "Svago", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY), Category(6, "Ristoranti", TxKind.SPESA, true, 0, nature = SpendNature.DISCRETIONARY),
        Category(7, "Palestra", TxKind.SPESA, false, 0), Category(8, "Altro", TxKind.SPESA, false, 0),
    )
    private val months = listOf("2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02")
    private val today = d("2026-03-10")
    private fun data(txs: List<Transaction>, opening: Int = 3000) = AppData(transactions = txs, categories = cats, accounts = listOf(Account(1, "C", AccountType.CONTO, BigDecimal(opening))), loaded = true)
    private fun run(txs: List<Transaction>, overrides: Map<Long, IncomeNature> = emptyMap(), goals: List<Goal> = emptyList(), opening: Int = 3000) = HealthEngine.compute(data(txs, opening), goals, emptyList(), emptyList(), overrides, today)!!

    private fun saver() = months.flatMap { m -> listOf(tx("$m-01", 2000, 1), tx("$m-02", -700, 3), tx("$m-10", -300, 4), tx("$m-15", -150, 5), tx("$m-20", -100, 6)) }
    private fun spender() = months.mapIndexed { i, m -> listOf(tx("$m-01", 2000, 1), tx("$m-02", -800, 3), tx("$m-10", -400, 4), tx("$m-15", -(200 + 50 * i), 5), tx("$m-20", -250, 6)) }.flatten()
    private fun freelancer() = months.zip(listOf(3000, 800, 2600, 1200, 3500, 900)).flatMap { (m, inc) -> listOf(tx("$m-05", inc, 2), tx("$m-02", -700, 3), tx("$m-10", -400, 4), tx("$m-15", -100, 5)) }

    @Test fun `chi risparmia con regolarità è in salute, e il parere lo dimostra con i numeri`() {
        val r = run(saver())
        assertEquals(6, r.monthCount); assertFalse(r.provisional)
        eq("2000", r.incomeFixed); eq("0", r.incomeVariablePrudent)
        eq("700", r.expenseFixed); eq("300", r.expenseNecessary); eq("250", r.expenseDiscretionary)
        eq("750", r.typicalSaving); assertEquals(38, r.savingRatePercent)
        assertTrue(r.gap.signum() < 0); assertTrue(r.levers.isEmpty())                 // il 20% (400) è già coperto: nessun intervento
        assertEquals(Verdict.HEALTHY, r.verdict); assertTrue("punteggio ${r.score}", r.score >= 75)
        assertTrue(r.indicators.first { it.id == "saving" }.level == Level.GOOD)
    }

    @Test fun `le spese fisse non si tagliano mai, e dove agire si deduce dai mesi sobri di ogni categoria`() {
        val r = run(spender())
        assertEquals(1, r.incomeSources.size); assertEquals(IncomeNature.FIXED, r.incomeSources[0].nature)
        eq("800", r.expenseFixed)
        assertTrue(r.gap.signum() > 0)
        assertTrue(r.levers.none { it.cls == ExpenseClass.FIXED })
        val svago = r.levers.single { it.category.name == "Svago" }                    // è cresciuta e ha mesi più sobri
        assertTrue(svago.growing); assertTrue(svago.target < svago.current)
        assertTrue(svago.saving <= svago.current - svago.target + BigDecimal("0.01"))
        assertEquals(0, svago.budgetSuggestion.remainder(BigDecimal(5)).compareTo(BigDecimal.ZERO)); assertTrue(svago.budgetSuggestion >= svago.target)
        // «Ristoranti» è identico ogni mese: l'utente non ha mai mostrato di poterlo ridurre, quindi non si propone un taglio a caso
        assertTrue(r.levers.none { it.category.name == "Ristoranti" })
        assertTrue(r.residualGap.signum() > 0)                                           // il resto non si copre con le sole discrezionali: lo dice
        assertTrue(r.verdict != Verdict.HEALTHY)
    }

    @Test fun `entrate variabili - ragiono in prudenza e il fondo di emergenza chiede più mesi`() {
        val r = run(freelancer())
        assertEquals(IncomeNature.VARIABLE, r.incomeSources.single().nature); eq("0", r.incomeFixed)
        assertTrue(r.prudentIncome < r.typicalIncome)
        eq("975", r.prudentIncome)                                                       // primo quartile dei mesi: quanto incassi anche nei mesi magri
        val needs = r.indicators.first { it.id == "needs" }; assertEquals(Level.BAD, needs.level)
        val dep = r.indicators.first { it.id == "variable" }; assertTrue(dep.level != Level.GOOD)
        assertTrue(r.verdict != Verdict.HEALTHY)
        // fondo: con entrate quasi tutte variabili si chiedono 6 mesi minimi (9 consigliati), non 3
        val fund = r.indicators.first { it.id == "fund" }; assertTrue(fund.explanation.contains("almeno 6")); assertTrue(fund.explanation.contains("meglio 9"))
    }

    @Test fun `una natura non indicata si deduce dalla regolarità e il parere lo dichiara`() {
        val txs = saver() + months.map { tx("$it-12", -40, 7) } + months.mapIndexed { i, m -> tx("$m-22", -(20 + 35 * (i % 3)), 8) }
        val r = run(txs)
        val gym = r.expenseFlows.first { it.category?.name == "Palestra" }; assertEquals(ExpenseClass.FIXED, gym.cls); assertTrue(gym.inferred)   // 40 € ogni mese
        val other = r.expenseFlows.first { it.category?.name == "Altro" }; assertEquals(ExpenseClass.UNCLASSIFIED, other.cls)
        assertTrue(r.classifiedPercent < 100)
        eq("740", r.expenseFixed)                                                        // 700 + palestra inferita fissa
    }

    @Test fun `poco storico - parere provvisorio e dichiarato`() {
        val r = run(listOf("2026-01", "2026-02").flatMap { m -> listOf(tx("$m-01", 2000, 1), tx("$m-02", -700, 3), tx("$m-10", -300, 4)) })
        assertTrue(r.provisional); assertTrue(r.notes.any { it.contains("provvisorio") })
        assertEquals(Level.NA, r.indicators.first { it.id == "trend" }.level)
        assertNull(HealthEngine.compute(data(emptyList()), emptyList(), emptyList(), emptyList(), emptyMap(), today))
        assertNull(HealthEngine.compute(data(listOf(tx("2026-03-02", 2000, 1))), emptyList(), emptyList(), emptyList(), emptyMap(), today))   // nessun mese concluso
    }

    @Test fun `l'utente può correggere la natura di una entrata`() {
        // una entrata presente solo in 3 mesi su 6 viene vista come variabile; l'utente dice che è fissa
        val txs = saver() + listOf("2025-09", "2025-11", "2026-01").map { tx("$it-08", 500, 2) }
        val auto = run(txs).incomeSources.first { it.name == "Fatture" }
        assertEquals(IncomeNature.VARIABLE, auto.nature); assertFalse(auto.overridden)
        val ov = run(txs, mapOf(2L to IncomeNature.FIXED)).incomeSources.first { it.name == "Fatture" }
        assertEquals(IncomeNature.FIXED, ov.nature); assertEquals(IncomeNature.VARIABLE, ov.detected); assertTrue(ov.overridden)
    }

    @Test fun `un mese eccezionale non sposta il parere, la mediana lo ignora`() {
        val calm = saver()
        val spike = saver() + tx("2025-12-28", -5000, 8)               // una spesa una tantum enorme
        eq(run(calm).typicalSaving.toPlainString(), run(spike).typicalSaving)
        assertEquals(run(calm).verdict, run(spike).verdict)
    }

    @Test fun `il punteggio di ogni mese permette di vedere se si migliora`() {
        // i primi mesi si spende molto, poi sempre meno
        val txs = months.mapIndexed { i, m -> listOf(tx("$m-01", 2000, 1), tx("$m-02", -700, 3), tx("$m-10", -300, 4), tx("$m-15", -(900 - 150 * i), 5)) }.flatten()
        val h = HealthEngine.history(data(txs), emptyList(), emptyMap(), today, 6)
        assertTrue(h.size >= 3); assertEquals(h.sortedBy { it.first }, h)
        assertTrue("primo ${h.first().second}, ultimo ${h.last().second}", h.last().second > h.first().second)
        assertTrue(h.all { it.second in 0..100 })
    }

    @Test fun `gli obiettivi in ritardo pesano sul punteggio`() {
        val late = Goal(1, "Auto", BigDecimal(6000), BigDecimal.ZERO, deadline = d("2027-03-15"), monthly = BigDecimal(300))
        val withGoal = run(saver(), goals = listOf(late))
        assertEquals(Level.BAD, withGoal.indicators.first { it.id == "goals" }.level)
        assertTrue(withGoal.score < run(saver()).score)
    }

    @Test fun `la frase di sintesi riporta il risparmio, l'obiettivo e la mancanza`() {
        val r = run(spender())
        assertTrue(r.headline.contains("20%")); assertTrue(r.headline.contains("mancano"))
        assertTrue(run(saver()).headline.contains("coperto"))
    }
}

class IncomeNatureBackupTest {
    @Test fun `la natura delle entrate corretta dall'utente viaggia nel backup, per nome di categoria`() {
        val state = it.spese.domain.backup.LegacyState(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(),
            it.spese.domain.backup.LegacyGoal(GoalMode.MONTHLY, BigDecimal(300), 12, null), emptyMap(), emptyMap())
        val extras = it.spese.domain.backup.NativeExtras(incomeNature = listOf(it.spese.domain.backup.NativeIncomeNature("Stipendio", true), it.spese.domain.backup.NativeIncomeNature("Fatture", false)))
        val json = it.spese.domain.backup.LegacyBackupCodec.encode(it.spese.domain.backup.BackupEnvelope("2026-03-15", "t", state, extras))
        val back = it.spese.domain.backup.LegacyBackupCodec.parse(json).extras!!.incomeNature
        assertEquals(listOf("Stipendio" to true, "Fatture" to false), back.map { it.category to it.fixed })
    }
}
