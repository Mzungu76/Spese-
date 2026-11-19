package it.spese.domain

import it.spese.domain.sharing.*
import org.junit.Assert.*
import org.junit.Test

class SharePairingTest {
    private val key = ShareCrypto.keyToText(ShareCrypto.newKey())
    private val link = ShareLink("https://spese.example.workers.dev", "id1", "owner-segreto", "guest1", key, 3)

    @Test fun `il codice di abbinamento si decodifica e non contiene il codice della madre`() {
        val code = SharePairing.encode(link)
        assertTrue(code.startsWith("SPESE1."))
        val p = SharePairing.decode("  $code \n")!!
        assertEquals("https://spese.example.workers.dev", p.serverUrl); assertEquals("id1", p.shareId); assertEquals("guest1", p.guestToken); assertEquals(key, p.keyText)
        assertFalse(String(java.util.Base64.getUrlDecoder().decode(code.removePrefix("SPESE1."))).contains("owner-segreto"))
    }

    @Test fun `codici rotti o manomessi si rifiutano`() {
        assertNull(SharePairing.decode("")); assertNull(SharePairing.decode("SPESE1.@@@")); assertNull(SharePairing.decode("ALTRO." + SharePairing.encode(link).removePrefix("SPESE1.")))
        val http = SharePairing.encode(link.copy(serverUrl = "http://insicuro.example.com"))
        assertNull(SharePairing.decode(http))
    }

    @Test fun `solo indirizzi https, o http in locale`() {
        assertEquals("https://a.b.workers.dev", ShareUrl.normalize(" https://a.b.workers.dev/ "))
        assertNull(ShareUrl.normalize("http://a.b.com")); assertNull(ShareUrl.normalize("a.b.com")); assertNull(ShareUrl.normalize("https://a b.com")); assertNull(ShareUrl.normalize(""))
        assertEquals("http://127.0.0.1:8080", ShareUrl.normalize("http://127.0.0.1:8080"))
    }

    @Test fun `la condivisione salvata mantiene scelte e contatori`() {
        val l = link.withConfig(ShareConfig(java.math.BigDecimal("450.50"), null, ShareFeatures(captureNotifications = false, showChart = true), "ciao")).copy(lastInSeq = 9, importedCount = 4)
        val back = ShareLinkCodec.decode(ShareLinkCodec.encode(l))!!
        assertEquals(l, back); assertEquals(0, java.math.BigDecimal("450.50").compareTo(back.config().budgetMonthly)); assertFalse(back.config().features.captureNotifications)
        assertNull(ShareLinkCodec.decode("rotto")); assertNull(ShareLinkCodec.decode(null))
    }

    @Test fun `una condivisione salvata prima dei conti multipli condivide il suo conto`() {
        val old = """{"serverUrl":"https://a.b.workers.dev","shareId":"i","ownerToken":"o","guestToken":"g","keyText":"$key","accountId":3}"""
        val l = ShareLinkCodec.decode(old)!!
        assertEquals(listOf(3L), l.sharedIds); assertEquals(listOf(3L, 5L), l.copy(accountIds = listOf(3L, 5L)).sharedIds)
    }
}
