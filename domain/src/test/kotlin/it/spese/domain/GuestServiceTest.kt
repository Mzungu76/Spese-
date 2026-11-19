package it.spese.domain

import it.spese.domain.capture.CaptureRouting
import it.spese.domain.capture.CaptureSource
import it.spese.domain.sharing.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuestServiceTest {
    private class Api : ShareApi {
        var out: String? = null; var outUpdated = 1L; val sent = ArrayList<String>(); var failWith: ShareApiException? = null
        override suspend fun create(baseUrl: String, createKey: String) = throw UnsupportedOperationException()
        override suspend fun publish(baseUrl: String, id: String, token: String, blob: String) = 0L
        override suspend fun inbox(baseUrl: String, id: String, token: String, after: Long) = InboxPage(emptyList(), after)
        override suspend fun ack(baseUrl: String, id: String, token: String, upTo: Long) {}
        override suspend fun revoke(baseUrl: String, id: String, token: String) {}
        override suspend fun fetch(baseUrl: String, id: String, token: String, since: Long): FetchedOut? {
            failWith?.let { throw it }
            val b = out ?: return null
            return if (since == outUpdated) FetchedOut(null, outUpdated, true) else FetchedOut(b, outUpdated, false)
        }
        override suspend fun send(baseUrl: String, id: String, token: String, items: List<String>) { failWith?.let { throw it }; sent.addAll(items) }
    }
    private class Repo : GuestStateRepository {
        val st = MutableStateFlow<GuestState?>(null)
        override fun observe() = st
        override suspend fun get() = st.value
        override suspend fun set(state: GuestState?) { st.value = state }
        override suspend fun update(transform: (GuestState) -> GuestState) { st.value = st.value?.let(transform) }
    }

    private val keyText = ShareCrypto.keyToText(ShareCrypto.newKey())
    private val link = ShareLink("https://spese.example.workers.dev", "id1", "owner", "guest1", keyText, 1)
    private val snapshot = ShareSnapshot(1000, "Carta famiglia", "MONTH", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 19), 30, 18,
        BigDecimal(600), BigDecimal(250), BigDecimal(330), BigDecimal(25), BigDecimal(20), listOf(BigDecimal(10), BigDecimal(250)), listOf(BigDecimal(8), BigDecimal(9)), listOf(BigDecimal(9), BigDecimal(10)))

    private fun blob(s: ShareSnapshot = snapshot) = ShareCrypto.encrypt(ShareCodec.encodeSnapshot(s), link.key!!)

    @Test fun `si abbina con il codice e legge il riepilogo, poi lo trova invariato`() = runBlocking {
        val api = Api().apply { out = blob() }; val repo = Repo(); val svc = GuestService(repo, api)
        val st = svc.pair(SharePairing.encode(link), now = 5000)
        assertEquals("guest1", st.guestToken)
        assertEquals("Carta famiglia", st.snapshot!!.title); assertEquals(0, BigDecimal(25).compareTo(st.snapshot!!.dailyRemaining))
        assertEquals(1L, st.snapshotUpdated); assertEquals("", st.problem)
        api.out = null                                       // «invariato»: non tocca il riepilogo già salvato
        assertEquals("Carta famiglia", svc.refresh(9000).snapshot!!.title); assertEquals(9000L, repo.get()!!.lastRefreshAt)
    }

    @Test fun `con piu periodi si sceglie settimana, mese o trimestre`() = runBlocking {
        val week = snapshot.copy(unit = "WEEK", days = 7, limit = BigDecimal(200)); val quarter = snapshot.copy(unit = "QUARTER", limit = BigDecimal(1800))
        val api = Api().apply { out = ShareCrypto.encrypt(ShareCodec.encodeBundle(listOf(week, snapshot, quarter)), link.key!!) }; val repo = Repo(); val svc = GuestService(repo, api)
        val st = svc.pair(SharePairing.encode(link))
        assertEquals(3, st.snapshots.size); assertEquals("MONTH", st.snapshot!!.unit)             // di partenza il mese
        svc.setUnit("WEEK"); assertEquals(0, BigDecimal(200).compareTo(repo.get()!!.snapshot!!.limit))
        svc.setUnit("QUARTER"); assertEquals(0, BigDecimal(1800).compareTo(repo.get()!!.snapshot!!.limit))
        svc.setUnit("YEAR"); assertEquals("MONTH", repo.get()!!.snapshot!!.unit)                  // periodo non disponibile: ripiega sul mese
    }

    @Test fun `un codice sbagliato o un riepilogo non decifrabile danno un errore chiaro`() = runBlocking {
        val svc = GuestService(Repo(), Api())
        assertTrue(runCatching { svc.pair("boh") }.exceptionOrNull() is ShareApiException)
        val api = Api().apply { out = ShareCrypto.encrypt("{}", ShareCrypto.newKey()) }; val repo = Repo()
        val s2 = GuestService(repo, api)
        s2.pair(SharePairing.encode(link))
        assertTrue(repo.get()!!.problem.isNotBlank()); assertEquals("", repo.get()!!.snapshotJson)
    }

    @Test fun `condivisione revocata - il problema resta finche un aggiornamento non riesce`() = runBlocking {
        val api = Api().apply { out = blob() }; val repo = Repo(); val svc = GuestService(repo, api)
        svc.pair(SharePairing.encode(link))
        api.failWith = ShareApiException("non autorizzato", 401)
        assertTrue(runCatching { svc.refresh() }.exceptionOrNull() is ShareApiException)
        assertTrue(repo.get()!!.problem.contains("revocata"))
        assertNotNull(repo.get()!!.snapshot)                                // l'ultimo riepilogo resta visibile
    }

    @Test fun `le spese rilevate si mettono in coda senza doppioni e si consegnano cifrate`() = runBlocking {
        val api = Api().apply { out = blob() }; val repo = Repo(); val svc = GuestService(repo, api)
        svc.pair(SharePairing.encode(link))
        val e1 = QueuedEvent("a1", "-12.50", "Bar Centrale", 111, "Revolut"); val e2 = QueuedEvent("a2", "-8", "Edicola", 222)
        assertEquals(2, svc.queue(listOf(e1, e2))); assertEquals(0, svc.queue(listOf(e1)))
        assertEquals(2, svc.flush())
        assertTrue(repo.get()!!.outbox.isEmpty()); assertEquals(2, repo.get()!!.sentCount)
        val events = ShareCodec.decodeEvents(ShareCrypto.decrypt(api.sent.single(), link.key!!)!!)
        assertEquals(listOf("a1", "a2"), events.map { it.id }); assertEquals(0, BigDecimal("-12.50").compareTo(events[0].amount))
        assertFalse(api.sent.single().contains("Bar Centrale"))              // sul servizio solo testo cifrato
    }

    @Test fun `senza rete le spese restano in coda per il prossimo tentativo`() = runBlocking {
        val api = Api().apply { out = blob() }; val repo = Repo(); val svc = GuestService(repo, api)
        svc.pair(SharePairing.encode(link))
        svc.queue(listOf(QueuedEvent("b1", "-5", "Caffè", 1)))
        api.failWith = ShareApiException("Servizio non raggiungibile")
        assertTrue(runCatching { svc.flush() }.exceptionOrNull() is ShareApiException)
        assertEquals(1, repo.get()!!.outbox.size)
        api.failWith = null
        assertEquals(1, svc.flush()); assertTrue(repo.get()!!.outbox.isEmpty())
    }

    @Test fun `se la madre spegne la cattura le spese non vengono messe in coda`() = runBlocking {
        val off = snapshot.copy(features = ShareFeatures(captureNotifications = false, showChart = true))
        val api = Api().apply { out = blob(off) }; val repo = Repo(); val svc = GuestService(repo, api)
        svc.pair(SharePairing.encode(link))
        assertEquals(0, svc.queue(listOf(QueuedEvent("c1", "-5", "x", 1))))
    }

    @Test fun `dalla notifica di una banca alla spesa da mandare`() {
        val src = CaptureRouting.route(GuestCapture.config(listOf(CaptureSource.REVOLUT)), "com.revolut.revolut", "Revolut")
        assertEquals(CaptureSource.REVOLUT, src)
        assertNull(CaptureRouting.route(GuestCapture.config(listOf(CaptureSource.REVOLUT)), "com.altra.app", "Boh"))
        val ev = GuestCapture.eventFrom(CaptureSource.REVOLUT, "com.revolut.revolut", "Revolut", "Pagamento di 12,50 € presso Bar Centrale con la carta", 1700000000000)!!
        assertEquals(0, BigDecimal("-12.50").compareTo(BigDecimal(ev.amount))); assertEquals("Revolut", ev.source)
        assertEquals(ev.id, GuestCapture.eventFrom(CaptureSource.REVOLUT, "com.revolut.revolut", "Revolut", "Pagamento di 12,50 € presso Bar Centrale con la carta", 1700000000000)!!.id)   // stessa notifica, stesso id
        assertNull(GuestCapture.eventFrom(CaptureSource.REVOLUT, "com.revolut.revolut", "Revolut", "Il tuo saldo è 120,00 €", 1))
    }

    @Test fun `lo stato salvato mantiene coda e fonti`() {
        val s = GuestState("https://a.b.workers.dev", "i", "g", keyText, outbox = listOf(QueuedEvent("x", "-1", "d", 5)), sourceIds = listOf("revolut"))
        val back = GuestStateCodec.decode(GuestStateCodec.encode(s))!!
        assertEquals(s, back); assertEquals(listOf(CaptureSource.REVOLUT), back.activeSources)
        assertTrue(GuestState("u", "i", "g", keyText).activeSources.none { it == CaptureSource.SMS })
        assertNull(GuestStateCodec.decode("rotto"))
    }
}
