package it.spese.domain

import it.spese.domain.widgets.*
import org.junit.Assert.*
import org.junit.Test

class WidgetsTest {
    private val home = WidgetPage.HOME

    @Test fun `il catalogo ha identificativi unici`() {
        val ids = WidgetCatalog.specs.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `ogni preset usa solo widget del catalogo e non ripete quelli non ripetibili`() {
        LayoutPresets.all.forEach { p ->
            val items = p.layouts.getValue(home)
            assertTrue("${p.id}: vuoto", items.isNotEmpty())
            items.forEach { i -> assertNotNull("${p.id}: ${i.widgetId} sconosciuto", WidgetCatalog.spec(i.widgetId)) }
            val fixed = items.filter { WidgetCatalog.spec(it.widgetId)!!.repeatable.not() }.map { it.widgetId }
            assertEquals("${p.id}: doppioni", fixed.size, fixed.toSet().size)
        }
    }

    @Test fun `la composizione predefinita mostra ogni widget una volta e tre titoli di sezione`() {
        val d = LayoutOps.default(home)
        val fixed = WidgetCatalog.forPage(home).filter { !it.repeatable }.map { it.id }.toSet()
        assertEquals(fixed, d.filter { it.widgetId != WidgetCatalog.SECTION }.map { it.widgetId }.toSet())
        assertEquals(3, d.count { it.widgetId == WidgetCatalog.SECTION })
        assertEquals(d.size, d.map { it.uid }.toSet().size)
        // l'ordine di sempre: i widget «Oggi» prima di quelli del mese, i «Da sistemare» in fondo
        val ids = d.map { it.widgetId }
        assertTrue(ids.indexOf("health") < ids.indexOf("hero")); assertTrue(ids.indexOf("hero") < ids.indexOf("alerts")); assertTrue(ids.indexOf("period") < ids.indexOf("balances"))
    }

    @Test fun `salvataggio e lettura restituiscono la stessa composizione`() {
        val list = listOf(WidgetInstance("a", "safe"), WidgetInstance("b", "categories", false, mapOf("kind" to "ENTRATA")), WidgetInstance("c", "section", true, mapOf("title" to "Titolo \"x\"")))
        val back = LayoutCodec.decode(LayoutCodec.encode(mapOf(home to list)))
        assertEquals(list, back.getValue(home))
        assertTrue(LayoutCodec.decode("rotto").isEmpty()); assertTrue(LayoutCodec.decode(null).isEmpty())
    }

    @Test fun `normalize toglie widget sconosciuti, uid doppi e copie di widget non ripetibili`() {
        val l = listOf(WidgetInstance("a", "safe"), WidgetInstance("a", "health"), WidgetInstance("b", "safe"), WidgetInstance("c", "inesistente"),
            WidgetInstance("d", "section"), WidgetInstance("e", "section"))
        assertEquals(listOf("a", "d", "e"), LayoutOps.normalize(home, l).map { it.uid })
    }

    @Test fun `senza composizione salvata vale quella predefinita, con quella salvata vale quella`() {
        assertEquals(LayoutOps.default(home), LayoutOps.effective(emptyMap(), home))
        val own = listOf(WidgetInstance("x", "balances"))
        assertEquals(own, LayoutOps.effective(mapOf(home to own), home))
    }

    @Test fun `sposta, nascondi, rimuovi e aggiungi`() {
        var l = listOf(WidgetInstance("a", "safe"), WidgetInstance("b", "health"), WidgetInstance("c", "alerts"))
        l = LayoutOps.move(l, "c", -2); assertEquals(listOf("c", "a", "b"), l.map { it.uid })
        l = LayoutOps.move(l, "c", -5); assertEquals("c", l.first().uid)                              // non esce dai bordi
        l = LayoutOps.setEnabled(l, "a", false); assertFalse(l.first { it.uid == "a" }.enabled)
        l = LayoutOps.remove(l, "b"); assertEquals(2, l.size)
        l = LayoutOps.add(l, "health"); assertEquals("health", l.last().widgetId)
        val again = LayoutOps.add(l, "safe"); assertEquals(l.size, again.size); assertTrue(again.first { it.widgetId == "safe" }.enabled)   // già presente: si riaccende
        l = LayoutOps.add(l, "section", mapOf("title" to "A")); l = LayoutOps.add(l, "section", mapOf("title" to "B"))
        assertEquals(l.size, l.map { it.uid }.toSet().size)                                            // gli uid restano unici
        assertNull(LayoutOps.add(l, "inesistente").firstOrNull { it.widgetId == "inesistente" })
    }

    @Test fun `la galleria propone solo quello che manca, e i titoli sempre`() {
        val l = listOf(WidgetInstance("a", "safe"), WidgetInstance("b", "section"))
        val ids = LayoutOps.available(home, l).map { it.id }
        assertFalse("safe" in ids); assertTrue("section" in ids); assertTrue("health" in ids)
    }
}
