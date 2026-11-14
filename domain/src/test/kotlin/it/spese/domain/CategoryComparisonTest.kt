package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CategoryComparisonTest {
    private val cats = listOf(
        Category(1, "Casa", TxKind.SPESA, false, 0), Category(2, "Bollette", TxKind.SPESA, false, 0, parentId = 1), Category(3, "Svago", TxKind.SPESA, true, 0),
        Category(4, "Stipendio", TxKind.ENTRATA, false, 0),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long) = Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), "x$n", BigDecimal(amount), if (cat == 4L) TxKind.ENTRATA else TxKind.SPESA, cat)
    private val data = AppData(
        transactions = listOf(tx("2026-08-01", 2000, 4), tx("2026-09-01", 2000, 4), tx("2026-10-01", 2000, 4),
            tx("2026-08-10", -400, 1), tx("2026-09-10", -300, 2), tx("2026-10-10", -500, 1), tx("2026-10-12", -50, 2),
            tx("2026-08-20", -100, 3), tx("2026-09-20", -150, 3), tx("2026-10-20", -200, 3)),
        categories = cats, accounts = listOf(Account(1, "C")), loaded = true,
    )

    @Test fun `andamento delle categorie scelte accanto alle entrate, con le sottocategorie nella principale`() {
        val c = CategoryComparisonEngine.compute(data, TxFilter(period = Period.All), setOf(1, 3))!!
        assertEquals(3, c.labels.size); assertFalse(c.weekly)
        assertEquals(listOf(1L, 3L), c.categories.map { it.category.id })           // ordinate per importo
        assertEquals(listOf(400, 300, 550), c.categories[0].series.map { it.toInt() })   // Casa + Bollette (figlia)
        assertEquals(listOf(100, 150, 200), c.categories[1].series.map { it.toInt() })
        assertEquals(listOf(2000, 2000, 2000), c.income.map { it.toInt() })
    }

    @Test fun `incidenza sulle entrate e sulle spese, anche del totale scelto`() {
        val c = CategoryComparisonEngine.compute(data, TxFilter(period = Period.All), setOf(1, 3))!!
        val casa = c.categories.first { it.category.id == 1L }
        assertEquals(0, BigDecimal(1250).compareTo(casa.total))
        assertEquals(0, BigDecimal("20.8").compareTo(casa.percentOfIncome)); assertEquals(0, BigDecimal("73.5").compareTo(casa.percentOfExpense))
        assertEquals(0, BigDecimal(6000).compareTo(c.periodIncome)); assertEquals(0, BigDecimal(1700).compareTo(c.periodExpense))
        assertEquals(0, BigDecimal(1700).compareTo(c.selectedExpense)); assertEquals(0, BigDecimal("28.3").compareTo(c.selectedExpenseOfIncome))
    }

    @Test fun `le entrate del periodo non dipendono dalla selezione e si possono confrontare anche le entrate`() {
        val c = CategoryComparisonEngine.compute(data, TxFilter(period = Period.All), setOf(4, 3))!!
        val stip = c.categories.first { it.category.id == 4L }
        assertEquals(0, BigDecimal(6000).compareTo(stip.total)); assertEquals(0, BigDecimal(100).compareTo(stip.percentOfIncome)); assertNull(stip.percentOfExpense)
        assertEquals(0, BigDecimal(450).compareTo(c.selectedExpense))               // solo le spese entrano nel totale "speso"
    }

    @Test fun `senza selezione valida o senza movimenti non c'è confronto`() {
        assertNull(CategoryComparisonEngine.compute(data, TxFilter(), emptySet()))
        assertNull(CategoryComparisonEngine.compute(data, TxFilter(), setOf(99)))
        assertNull(CategoryComparisonEngine.compute(data.copy(transactions = emptyList()), TxFilter(), setOf(1)))
        // una sottocategoria scelta da sola si confronta come parte della principale: non compare doppia
        assertEquals(listOf(1L), CategoryComparisonEngine.compute(data, TxFilter(), setOf(1, 2))!!.categories.map { it.category.id })
    }
}
