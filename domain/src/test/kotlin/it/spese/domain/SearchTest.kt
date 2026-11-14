package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    private val cats = listOf(Category(1, "Bar e ristoranti", TxKind.SPESA, true, 0), Category(2, "Casa", TxKind.SPESA, false, 0), Category(3, "Bollette", TxKind.SPESA, false, 0, parentId = 2)).associateBy { it.id }
    private var n = 0L
    private fun tx(date: String, amount: String, desc: String, cat: Long, notes: String = "") =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount), TxKind.SPESA, cat, notes = notes)
    private val all = listOf(
        tx("2026-10-14", "-3.50", "Pagamento POS CAFFÈ ROMA 14/10/2026", 1), tx("2026-03-02", "-45.50", "Pagamento POS ENEL ENERGIA", 3),
        tx("2026-10-20", "-12.00", "Pagamento POS COOP SPA", 2, notes = "spesa per la festa"),
    )
    private fun find(q: String, period: Period = Period.All) = FilterEngine.apply(all, TxFilter(period = period, query = q), cats, mapOf(1L to "Revolut")).map { it.description }

    @Test fun `senza maiuscole e accenti, e piu parole in qualunque ordine`() {
        assertEquals(1, find("caffe").size); assertEquals(1, find("CAFFÈ roma").size); assertEquals(1, find("roma caffe").size)
        assertEquals(0, find("caffe milano").size)
        assertEquals("caffe", SearchMatcher.normalize("Caffè")); assertEquals(listOf("bar", "e"), SearchMatcher.tokens("  Bar   È "))
    }

    @Test fun `cerca anche in note, categoria (e principale), conto, importo e data`() {
        assertEquals(1, find("festa").size)                  // nota
        assertEquals(1, find("ristoranti").size)             // categoria
        assertEquals(2, find("casa").size)                   // categoria principale: la figlia "Bollette" ha come padre Casa
        assertEquals(3, find("revolut").size)                // conto
        assertEquals(1, find("45,50").size); assertEquals(1, find("45.5").size); assertEquals(1, find("3,5").size)   // importo, con virgola o punto
        assertEquals(1, find("14/10").size); assertEquals(1, find("marzo").size)     // data e mese
    }

    @Test fun `la ricerca ignora il periodo e trova anche cio che e fuori dal mese scelto`() {
        val onlyOctober = FilterEngine.apply(all, TxFilter(period = Period.Month(java.time.YearMonth.of(2026, 10))), cats)
        assertEquals(2, onlyOctober.size)
        assertEquals(listOf("Pagamento POS ENEL ENERGIA"), find("enel", Period.Month(java.time.YearMonth.of(2026, 10))))   // a marzo, ma si trova lo stesso
        assertEquals(3, find("   ").size)                    // spazi = nessuna ricerca
    }

    @Test fun `il testo di ricerca entra nel conteggio dei filtri attivi`() {
        assertEquals(1, TxFilter(query = "enel").activeCount); assertEquals(0, TxFilter(query = "  ").activeCount)
    }
}
