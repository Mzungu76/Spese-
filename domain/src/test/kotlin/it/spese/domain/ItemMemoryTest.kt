package it.spese.domain

import it.spese.domain.ocr.ItemMemory
import org.junit.Assert.*
import org.junit.Test

class ItemMemoryTest {
    @Test fun `la chiave ignora maiuscole, accenti, quantita e punteggiatura`() {
        assertEquals("latte p s", ItemMemory.key("LATTE P.S. 1L"))
        assertEquals("caffe macinato", ItemMemory.key("Caffè macinato 250 g"))
        assertEquals("", ItemMemory.key("12,50"))
    }

    @Test fun `propone la categoria ricordata, anche con descrizioni leggermente diverse`() {
        val mem = mapOf("latte" to 7L, "detersivo piatti" to 9L, "pane" to 3L)
        val out = ItemMemory.suggest(listOf("LATTE 1L", "Detersivo piatti limone", "Cioccolato", "Pane"), mem, setOf(7L, 9L))
        assertEquals(listOf(7L, 9L, null, null), out)   // "pane" ricordato ma categoria non più valida
    }

    @Test fun `le scelte nuove sostituiscono le vecchie e la memoria ha un limite`() {
        val m = ItemMemory.merge(mapOf("latte" to 1L), mapOf("latte" to 2L, "pane" to 3L))
        assertEquals(2L, m["latte"]); assertEquals(listOf("latte", "pane"), m.keys.toList())
        val big = ItemMemory.merge(emptyMap(), (1..2000).associate { "voce$it" to 1L })
        assertEquals(1500, big.size); assertTrue("voce2000" in big)
    }
}
