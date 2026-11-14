package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class CategoryDetailTest {
    private val cats = listOf(
        Category(1, "Casa", TxKind.SPESA, false, 0, budgetMonthly = BigDecimal(300)),
        Category(2, "Bollette", TxKind.SPESA, false, 0, parentId = 1), Category(3, "Manutenzione", TxKind.SPESA, true, 0, parentId = 1),
        Category(4, "Bar", TxKind.SPESA, true, 0),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long, desc: String) =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount), TxKind.SPESA, cat)
    private val data = AppData(
        transactions = listOf(
            tx("2026-08-10", -100, 2, "Enel"), tx("2026-09-10", -110, 2, "Enel"), tx("2026-09-20", -50, 3, "Idraulico Rossi"),
            tx("2026-10-10", -180, 2, "Enel"), tx("2026-10-11", -60, 3, "Ferramenta Bianchi"), tx("2026-10-12", -25, 1, "Condominio"), tx("2026-10-12", -40, 4, "Bar"),
        ),
        categories = cats, accounts = listOf(Account(1, "C")), loaded = true,
    )
    private val today = LocalDate.parse("2026-10-14")
    private fun detail(id: Long, p: Period = Period.CurrentMonth) = CategoryDetailCalculator.compute(data, id, TxFilter(period = p), today)!!

    @Test fun `la categoria principale somma le sottocategorie e le mostra una per una`() {
        val d = detail(1)
        assertEquals(0, BigDecimal(265).compareTo(d.total))          // 180 + 60 + 25
        assertEquals(0, BigDecimal(110).compareTo(d.previousTotal))  // 1-12 settembre: solo Enel 110 (l'idraulico del 20 non conta)
        assertEquals(listOf("Bollette", "Manutenzione"), d.subcategories.map { it.category.name })
        assertEquals(0, BigDecimal(180).compareTo(d.subcategories[0].total))
        assertEquals(3, d.count)
    }

    @Test fun `esercenti raggruppati, con confronto e motivi della variazione`() {
        val d = detail(1)
        assertEquals("Enel", d.merchants.first().merchant)
        assertEquals(0, BigDecimal(70).compareTo(d.merchants.first().delta))   // 180 vs 110
        assertEquals("Enel", d.drivers.first().merchant)
        assertTrue(d.drivers.any { it.merchant == "Ferramenta Bianchi" })
        assertEquals("rispetto a Settembre 2026", d.previousLabel)
    }

    @Test fun `andamento mensile continuo, media e budget del mese di riferimento`() {
        val d = detail(1)
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)), d.months.map { it.first })
        assertEquals(0, BigDecimal(160).compareTo(d.months[1].second))         // 110 + 50
        assertEquals(0, BigDecimal("175.00").compareTo(d.monthlyAvg))          // (100 + 160 + 265) / 3
        assertEquals(0, BigDecimal(265).compareTo(d.budget!!.spent))
        assertFalse(d.budget!!.exceeded)
        // budget suggerito: media dei mesi completi (agosto, settembre) = 130 → 130
        assertEquals(0, BigDecimal(130).compareTo(d.suggestedBudget))
    }

    @Test fun `filtrare la categoria principale include le sottocategorie, e le eliminate non lasciano orfani`() {
        val filtered = FilterEngine.apply(data.transactions, TxFilter(categoryId = 1), data.categoriesById)
        assertEquals(6, filtered.size)
        assertEquals(1L, CategoryTree.rootId(3, data.categoriesById)); assertEquals(4L, CategoryTree.rootId(4, data.categoriesById))
        assertEquals(setOf(1L, 2L, 3L), CategoryTree.withChildren(1, cats))
        // il budget della principale tiene conto delle figlie
        val u = AlertEngine.budgetUsage(data.transactions, cats, YearMonth.of(2026, 10)).single()
        assertEquals(0, BigDecimal(265).compareTo(u.spent))
    }

    @Test fun `la classifica della Home accorpa le sottocategorie`() {
        val m = DashboardCalculator.compute(data, TxFilter(period = Period.CurrentMonth), today)
        assertEquals(listOf(1L, 4L), m.categories.map { it.category?.id })
        assertEquals(3, m.categories[0].count)
    }

    @Test fun `categoria inesistente o senza movimenti`() {
        assertNull(CategoryDetailCalculator.compute(data, 99, TxFilter(), today))
        val d = CategoryDetailCalculator.compute(data.copy(transactions = emptyList()), 4, TxFilter(), today)!!
        assertEquals(0, d.total.signum()); assertTrue(d.months.isEmpty()); assertNull(d.suggestedBudget)
    }
}
