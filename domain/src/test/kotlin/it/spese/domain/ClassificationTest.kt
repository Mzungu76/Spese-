package it.spese.domain

import it.spese.core.model.AmountSign
import it.spese.core.model.Category
import it.spese.core.model.Rule
import it.spese.core.model.TxKind
import it.spese.domain.classification.Classifier
import it.spese.domain.classification.DefaultData
import it.spese.domain.classification.Merchant
import it.spese.domain.classification.RuleEngine
import java.math.BigDecimal
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationTest {
    private val cats = listOf(
        Category(1, "Stipendio", TxKind.ENTRATA, false, 0),
        Category(2, "Altre entrate", TxKind.ENTRATA, false, 0, systemKey = Category.FALLBACK_INCOME),
        Category(3, "Supermercato", TxKind.SPESA, false, 0),
        Category(4, "Contanti", TxKind.SPESA, true, 0),
        Category(5, "Altro", TxKind.SPESA, true, 0, systemKey = Category.FALLBACK_EXPENSE),
        Category(6, "Trasferimenti", TxKind.TRASFERIMENTO, false, 0),
    )
    private fun rule(id: Long, kw: String, cat: Long, sg: AmountSign = AmountSign.ANY, min: Int = 0, max: Int = 0, prio: Int = id.toInt()) =
        Rule(id, kw, cat, sg, BigDecimal(min), BigDecimal(max), prio)

    @Test fun `testo semplice contiene, case insensitive`() {
        val c = Classifier(cats, listOf(rule(1, "coop", 3)))
        assertEquals(3L, c.classify("Pagamento POS COOP ALLEANZA", BigDecimal("-10"), null).categoryId)
    }

    @Test fun `espressioni regolari con prefisso re`() {
        val c = Classifier(cats, listOf(rule(1, "re:^bonifico.*\\bspese\\b", 1, AmountSign.POSITIVE)))
        assertEquals(1L, c.classify("BONIFICO a vostro favore per spese", BigDecimal("50"), null).categoryId)
        assertEquals(5L, c.classify("BONIFICO a vostro favore per spese", BigDecimal("-50"), null).categoryId) // segno negativo: non vale
    }

    @Test fun `regex non valida non genera corrispondenze ne eccezioni`() {
        val c = Classifier(cats, listOf(rule(1, "re:([", 3)))
        assertEquals(5L, c.classify("qualsiasi", BigDecimal("-1"), null).categoryId)
        assertFalse(RuleEngine.isValidKeyword("re:(["))
        assertTrue(RuleEngine.isValidKeyword("re:coop|conad"))
    }

    @Test fun `condizioni sull'importo in valore assoluto e priorita`() {
        val rules = listOf(rule(1, "re:prelievo", 3, min = 100), rule(2, "re:prelievo", 4))
        val c = Classifier(cats, rules)
        assertEquals(3L, c.classify("Prelievo ATM", BigDecimal("-150"), null).categoryId)
        assertEquals(4L, c.classify("Prelievo ATM", BigDecimal("-50"), null).categoryId)
        val onlyMax = Classifier(cats, listOf(rule(1, "", 5, AmountSign.NEGATIVE, max = 3)))
        assertEquals(5L, onlyMax.classify("x", BigDecimal("-2.50"), null).categoryId)
        assertNull(onlyMax.classify("x", BigDecimal("-2.50"), null).ruleId?.takeIf { it != 1L })
    }

    @Test fun `la priorita decide`() {
        val rules = listOf(rule(1, "coop", 3, prio = 5), rule(2, "coop", 4, prio = 0))
        assertEquals(4L, Classifier(cats, rules).classify("coop", BigDecimal("-1"), null).categoryId)
    }

    @Test fun `l'assegnazione manuale prevale sulle regole`() {
        val c = Classifier(cats, listOf(rule(1, "coop", 3)))
        val r = c.classify("COOP", BigDecimal("-1"), manualCategoryId = 4)
        assertEquals(4L, r.categoryId); assertTrue(r.manual); assertNull(r.ruleId)
    }

    @Test fun `fallback per segno e tipologia dalla categoria`() {
        val c = Classifier(cats, listOf(rule(1, "revolut", 6)))
        assertEquals(2L, c.classify("sconosciuto", BigDecimal("5"), null).categoryId)
        assertEquals(5L, c.classify("sconosciuto", BigDecimal("-5"), null).categoryId)
        val t = c.classify("giroconto revolut", BigDecimal("-200"), null)
        assertEquals(TxKind.TRASFERIMENTO, t.kind)
    }

    @Test fun `estrazione esercente`() {
        assertEquals("COOP ALLEANZA 3.0", Merchant.extract("Pagamento POS COOP ALLEANZA 3.0 12/03/2024 Op. 1234"))
        assertEquals("Bonifico da DAG COSTRUZIONI SRL", Merchant.extract("BONIFICO SEPA DA DAG COSTRUZIONI SRL TRN 12345 PER STIPENDIO"))
        assertEquals("Prelievo Bancomat ATM", Merchant.extract("Prelievo Bancomat ATM 20/10/2024").let { it.substringBefore(" 20/") })
        assertEquals("coop alleanza", Merchant.suggestKeyword("COOP ALLEANZA!"))
    }

    // --- confronto con l'originale HTML (golden master generato dall'app HTML su un estratto conto sintetico) ---
    @Test fun `classificazione identica alla versione HTML su 223 movimenti`() {
        val g = Fixtures.golden["tx"]!!.jsonArray
        val snap = Fixtures.snapshotA
        val catName = snap.categories.associate { it.id to it.name }
        val byExt = snap.transactions.associateBy { it.externalId }
        assertEquals(g.size, snap.transactions.size)
        var checked = 0
        for (e in g) {
            val o = e.jsonObject
            val t = byExt[o["id"]!!.s()] ?: error("movimento mancante ${o["id"]}")
            assertEquals("categoria di ${t.description}", o["cat"]!!.s(), catName[t.categoryId])
            assertEquals("tipo di ${t.description}", o["k"]!!.s(), t.kind.key)
            assertEquals("manuale ${t.description}", o["man"]!!.jsonPrimitive.content.toBoolean(), t.manualCategory)
            assertEquals("esercente", o["merchant"]!!.s(), it.spese.domain.classification.Merchant.extract(t.description))
            checked++
        }
        assertEquals(223, checked)
    }

    @Test fun `le regole predefinite sono quelle dell'originale`() {
        assertEquals(21, DefaultData.categories.size)
        assertEquals(31, DefaultData.rules.size)
        assertEquals(DefaultData.PALETTE.size, 16)
    }
}
