package it.spese.domain

import it.spese.core.model.Category
import it.spese.core.model.GoalMode
import it.spese.core.model.SavingsGoal
import it.spese.core.model.TxKind
import it.spese.domain.finance.AlertEngine
import it.spese.domain.finance.AlertKind
import it.spese.domain.finance.FilterEngine
import it.spese.domain.finance.Period
import it.spese.domain.finance.PeriodResolver
import it.spese.domain.finance.RecurringDetector
import it.spese.domain.finance.StatsEngine
import it.spese.domain.finance.TxFilter
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifica che statistiche, filtri, ricorrenze, avvisi e piano coincidano con quelli calcolati dall'app HTML originale. */
class FinanceGoldenTest {
    private val snap = Fixtures.snapshotA
    private val g = Fixtures.golden
    private val cats = snap.categories.associateBy { it.id }
    private val names = cats.mapValues { it.value.name }
    private val intesaId = snap.accounts.first { it.name == "Intesa" }.id

    private fun checkStats(label: String, o: JsonObject, list: List<it.spese.core.model.Transaction>) {
        val s = StatsEngine.compute(list)
        assertMoney("$label entrate", o["inc"]!!.d(), s.income)
        assertMoney("$label spese", o["exp"]!!.d(), s.expense)
        assertMoney("$label trasferimenti", o["tr"]!!.d(), s.transfers)
        val byCat = s.byCategory.mapKeys { names[it.key] ?: "?" }
        o["byCat"]!!.jsonObject.forEach { (k, v) -> assertMoney("$label cat $k", v.d(), byCat[k] ?: BigDecimal.ZERO) }
        assertEquals("$label n. categorie", o["byCat"]!!.jsonObject.size, byCat.size)
        o["byMonth"]!!.jsonObject.forEach { (m, v) ->
            val b = s.byMonth[YearMonth.parse(m)]!!
            assertMoney("$label mese $m entrate", v.jsonObject["inc"]!!.d(), b.income)
            assertMoney("$label mese $m spese", v.jsonObject["exp"]!!.d(), b.expense)
        }
        assertEquals(o["byMonth"]!!.jsonObject.size, s.byMonth.size)
        val merch = s.byMerchant
        o["byMerch"]!!.jsonObject.forEach { (k, v) ->
            assertEquals("$label esercente $k n", v.jsonObject["n"]!!.s().toInt(), merch[k]!!.count)
            assertMoney("$label esercente $k", v.jsonObject["t"]!!.d(), merch[k]!!.total)
        }
        assertMoney("$label mesi", o["mo"]!!.d(), StatsEngine.monthsSpan(list), "0.0001")
    }

    @Test fun `statistiche su tutto il periodo`() = checkStats("tutto", g["statsAll"]!!.jsonObject, snap.transactions)

    @Test fun `statistiche per conto`() =
        checkStats("Intesa", g["statsIntesa"]!!.jsonObject, FilterEngine.apply(snap.transactions, TxFilter(accountId = intesaId), cats))

    @Test fun `periodi relativi all'ultimo movimento`() {
        val mn = snap.transactions.minOf { it.bookingDate }; val mx = snap.transactions.maxOf { it.bookingDate }
        fun r(p: Period) = PeriodResolver.resolve(p, mn, mx).let { listOf(it.from.toString(), it.to.toString()) }
        fun gold(k: String) = g[k]!!.jsonArray.map { it.s() }
        assertEquals(gold("rangeYtd"), r(Period.YearToDate))
        assertEquals(gold("range3"), r(Period.Last3Months))
        assertEquals(gold("range6"), r(Period.Last6Months))
        assertEquals(gold("rangeMonth"), r(Period.CurrentMonth))
    }

    @Test fun `statistiche e piano sugli ultimi 3 mesi`() {
        val list = FilterEngine.apply(snap.transactions, TxFilter(period = Period.Last3Months), cats)
        checkStats("3 mesi", g["stats3"]!!.jsonObject, list)
        val p = PlanEngine.base(list, snap.categories, SavingsGoal())
        val o = g["plan3"]!!.jsonObject
        assertMoney("entrate medie", o["inc"]!!.d(), p.income); assertMoney("spese medie", o["exp"]!!.d(), p.expense)
        o["cats"]!!.jsonArray.forEachIndexed { i, c ->
            assertEquals(c.jsonObject["n"]!!.s(), p.categories[i].name)
            assertMoney("media ${p.categories[i].name}", c.jsonObject["avg"]!!.d(), p.categories[i].monthlyAvg)
        }
    }

    @Test fun `piano su tutto il periodo con entrate attese`() {
        val p = PlanEngine.base(snap.transactions, snap.categories, SavingsGoal())
        val o = g["plan"]!!.jsonObject
        assertMoney("auto", o["incAuto"]!!.d(), p.incomeAuto)
        val withIncome = PlanEngine.base(snap.transactions, snap.categories, SavingsGoal(expectedIncome = BigDecimal("2000")))
        assertMoney("attese", BigDecimal(2000), withIncome.income)
        assertMoney("obiettivo totale/mesi", g["goalMonthly"]!!.d(), PlanEngine.goalMonthly(snap.goal))
    }

    @Test fun `filtri di ricerca, tipo, importo e categoria`() {
        fun n(f: TxFilter) = FilterEngine.apply(snap.transactions, f, cats).size
        assertEquals(g["qCoop"]!!.s().toInt(), n(TxFilter(query = "coop")))
        assertEquals(g["kindTr"]!!.s().toInt(), n(TxFilter(kind = TxKind.TRASFERIMENTO)))
        assertEquals(g["min100"]!!.s().toInt(), n(TxFilter(minAmount = BigDecimal(100))))
        assertEquals(g["catContanti"]!!.s().toInt(), n(TxFilter(categoryId = snap.categories.first { it.name == "Contanti" }.id)))
    }

    @Test fun `ricorrenze rilevate come nella versione HTML`() {
        val expected = g["recurring"]!!.jsonArray
        val found = RecurringDetector.detect(snap.transactions, RecurringDetector.defaultExclusions(snap.categories))
        assertEquals(expected.size, found.size)
        expected.forEachIndexed { i, e ->
            val o = e.jsonObject
            assertEquals(o["m"]!!.s(), found[i].merchant); assertEquals(o["n"]!!.s().toInt(), found[i].count)
            assertEquals(o["next"]!!.s(), found[i].next.toString()); assertEquals(o["last"]!!.s(), found[i].last.toString())
            assertMoney("media ${found[i].merchant}", o["mean"]!!.d(), found[i].mean)
        }
    }

    private fun alerts(accountId: Long?, expectedKey: String) {
        val list = snap.transactions.filter { accountId == null || it.accountId == accountId }
        val today = AlertEngine.referenceToday(LocalDate.of(2026, 11, 13), snap.transactions)
        assertEquals(g["today"]!!.s(), today.toString())
        val actual = AlertEngine.compute(list, snap.categories, snap.goal, snap.dues, today, RecurringDetector.defaultExclusions(snap.categories))
            .filter { it.kind != AlertKind.UNEXPECTED_EXPENSE }
        val expected = g[expectedKey]!!.jsonArray
        assertEquals(expected.map { it.jsonObject["l"]!!.s().toInt() to it.jsonObject["m"]!!.s() }, actual.map { it.level to it.message })
    }

    @Test fun `avvisi identici alla versione HTML (tutti i conti)`() = alerts(null, "alerts")
    @Test fun `avvisi identici alla versione HTML (singolo conto)`() = alerts(intesaId, "alertsIntesa")

    @Test fun `risparmio medio sotto l'obiettivo genera un avviso`() {
        val goal = SavingsGoal(GoalMode.MONTHLY, BigDecimal(5000))
        val a = AlertEngine.compute(snap.transactions, snap.categories, goal, emptyList(), LocalDate.of(2024, 10, 27))
        assertTrue(a.any { it.kind == AlertKind.SAVINGS_BELOW_GOAL })
    }
}
