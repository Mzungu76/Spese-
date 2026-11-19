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

    @Test fun `ogni preset ha una composizione per ogni pagina, con soli widget di quella pagina e senza doppioni`() {
        LayoutPresets.all.forEach { pr ->
            WidgetPage.entries.forEach { page ->
                val items = pr.layouts[page].orEmpty()
                assertTrue("${pr.id}/${page.name}: vuoto", items.isNotEmpty())
                items.forEach { i ->
                    val spec = WidgetCatalog.spec(i.widgetId)
                    assertNotNull("${pr.id}: ${i.widgetId} sconosciuto", spec)
                    assertTrue("${pr.id}: ${i.widgetId} non è della pagina ${page.name}", page in spec!!.pages)
                }
                val fixed = items.filter { !WidgetCatalog.spec(it.widgetId)!!.repeatable }.map { it.widgetId }
                assertEquals("${pr.id}/${page.name}: doppioni", fixed.size, fixed.toSet().size)
                WidgetCatalog.forPage(page).filter { it.required }.forEach { r -> assertTrue("${pr.id}/${page.name}: manca ${r.id}", items.any { it.widgetId == r.id }) }
            }
        }
    }

    @Test fun `la composizione predefinita di ogni pagina è quella di sempre, con i widget propri della pagina`() {
        val homeOriginal = setOf("safe", "health", "advice", "balances", "hero", "cashflow", "period", "pace", "watch", "opps", "categories", "alerts", "unassigned", "transfer_pairs", "pending_amounts", "notif_review", "subs")
        assertEquals(homeOriginal, LayoutOps.default(home).filter { it.widgetId != WidgetCatalog.SECTION }.map { it.widgetId }.toSet())
        assertEquals(3, LayoutOps.default(home).count { it.widgetId == WidgetCatalog.SECTION })
        WidgetPage.entries.filter { it != home }.forEach { page ->
            val own = WidgetCatalog.forPage(page).filter { WidgetCatalog.isNative(page, it.id) && !it.repeatable }.map { it.id }.toSet()
            assertEquals(page.name, own, LayoutOps.default(page).filter { it.widgetId != WidgetCatalog.SECTION }.map { it.widgetId }.toSet())
        }
        WidgetPage.entries.forEach { page -> val d = LayoutOps.default(page); assertEquals(d.size, d.map { it.uid }.toSet().size) }
        val ids = LayoutOps.default(home).map { it.widgetId }
        assertTrue(ids.indexOf("health") < ids.indexOf("hero")); assertTrue(ids.indexOf("hero") < ids.indexOf("alerts")); assertTrue(ids.indexOf("period") < ids.indexOf("balances"))
    }

    @Test fun `ogni pagina ha almeno dieci widget tra cui scegliere`() {
        WidgetPage.entries.forEach { page -> assertTrue("${page.name}: ${WidgetCatalog.forPage(page).size}", WidgetCatalog.forPage(page).size >= 10) }
    }

    @Test fun `l'elenco dei movimenti non si può nascondere, togliere o perdere`() {
        val t = WidgetPage.TRANSACTIONS
        var l = LayoutOps.default(t)
        val uid = l.first { it.widgetId == "tx_list" }.uid
        assertEquals(l, LayoutOps.setEnabled(l, uid, false)); assertEquals(l, LayoutOps.remove(l, uid))
        l = LayoutOps.normalize(t, listOf(WidgetInstance("a", "tx_filters"), WidgetInstance("b", "tx_list", false)))
        assertTrue(l.first { it.widgetId == "tx_list" }.enabled)                              // un elenco nascosto viene riacceso
        l = LayoutOps.normalize(t, listOf(WidgetInstance("a", "tx_filters")))
        assertEquals(listOf("tx_filters", "tx_list"), l.map { it.widgetId })                  // un elenco mancante viene riaggiunto
    }

    @Test fun `un widget di una pagina non compare in un'altra`() {
        assertTrue(LayoutOps.normalize(WidgetPage.GOALS, listOf(WidgetInstance("a", "pace"), WidgetInstance("b", "goals_list"))).map { it.widgetId } == listOf("goals_list"))
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
