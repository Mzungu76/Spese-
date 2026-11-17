package it.spese.domain

import it.spese.domain.capture.*
import org.junit.Assert.*
import org.junit.Test

class IgnoreRulesTest {
    private fun u(t: Long, text: String, src: String = "Revolut") = UnrecognizedNotification(t, src, text)

    @Test fun `la frase proposta ignora numeri e titolo`() {
        assertEquals("il tuo codice di verifica e", IgnoreFilter.suggest("Revolut — Il tuo codice di verifica è 482913. Non condividerlo"))
        assertEquals("", IgnoreFilter.suggest("Ok"))
    }

    @Test fun `la regola scarta le notifiche simili con numeri diversi`() {
        val r = IgnoreRule("revolut", "il tuo codice di verifica e", 1)
        assertTrue(IgnoreFilter.matches(listOf(r), "revolut", "Revolut", "Il tuo codice di verifica è 111222"))
        assertFalse(IgnoreFilter.matches(listOf(r), "wallet", "x", "Il tuo codice di verifica è 111222"))        // altra fonte
        assertFalse(IgnoreFilter.matches(listOf(r.copy(enabled = false)), "revolut", "Revolut", "Il tuo codice di verifica è 1"))
    }

    @Test fun `una notifica con un importo in euro non viene mai ignorata`() {
        val r = IgnoreRule("", "codice di verifica", 1)
        assertFalse(IgnoreFilter.matches(listOf(r), "revolut", "Revolut", "Codice di verifica per il pagamento di 12,50 €"))
        assertFalse(IgnoreFilter.matches(listOf(r), "revolut", "Revolut", "Codice di verifica: EUR 30"))
        assertTrue(IgnoreFilter.hasAmount("Hai pagato 3,00€"))
        assertFalse(IgnoreFilter.hasAmount("Codice 482913 valido 10 minuti"))
    }

    @Test fun `le frasi troppo corte non ignorano nulla e le non riconosciute si raggruppano`() {
        assertFalse(IgnoreFilter.matches(listOf(IgnoreRule("", "ok", 1)), "revolut", "", "ok"))
        val g = IgnoreFilter.group(listOf(u(1, "Codice 111 valido"), u(2, "Codice 222 valido"), u(3, "Offerta speciale")))
        assertEquals(2, g.size); assertEquals(2, g.first { it.count == 2 }.count)
        assertTrue(CaptureConfig(unrecognized = (1..10).map { u(it.toLong(), "x$it") }).needsReview)
        assertFalse(CaptureConfig(unrecognized = (1..9).map { u(it.toLong(), "x$it") }).needsReview)
    }
}
