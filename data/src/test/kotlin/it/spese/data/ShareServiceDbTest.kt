package it.spese.data

import it.spese.core.model.TxKind
import it.spese.domain.repository.AppDataSource
import it.spese.domain.repository.NewTransaction
import it.spese.domain.sharing.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeShareApi : ShareApi {
    var out: String? = null; val inbox = ArrayList<InboxItem>(); var revoked = false
    override suspend fun create(baseUrl: String, createKey: String): CreatedShare =
        if (createKey != "chiave") throw ShareApiException("non autorizzato", 401) else CreatedShare("id1", "owner1", "guest1")
    override suspend fun publish(baseUrl: String, id: String, token: String, blob: String): Long { out = blob; return 1L }
    override suspend fun inbox(baseUrl: String, id: String, token: String, after: Long): InboxPage {
        val items = inbox.filter { i -> i.seq > after }.take(100)
        return InboxPage(items, items.lastOrNull()?.seq ?: after)
    }
    override suspend fun ack(baseUrl: String, id: String, token: String, upTo: Long) { inbox.removeAll { i -> i.seq <= upTo } }
    override suspend fun revoke(baseUrl: String, id: String, token: String) { revoked = true }
}

private class FakeLinks : ShareLinkRepository {
    val state = MutableStateFlow<ShareLink?>(null)
    override fun observe() = state
    override suspend fun get() = state.value
    override suspend fun set(link: ShareLink?) { state.value = link }
    override suspend fun update(transform: (ShareLink) -> ShareLink) { state.value = state.value?.let(transform) }
}

/** Condivisione con l'altra persona: creazione, riepilogo cifrato, import delle spese rilevate dall'altro telefono, revoca. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareServiceDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private fun nt(key: String, acc: Long, amount: String, day: LocalDate) = NewTransaction(key, key, acc, day, day, "Spesa $key", BigDecimal(amount), TxKind.SPESA, null, false, null)
    private suspend fun service(api: FakeShareApi, links: FakeLinks): Pair<ShareService, Long> {
        val acc = env.accounts.getOrCreate("Carta famiglia")
        val first = LocalDate.now().withDayOfMonth(1)
        env.tx.insertAll(listOf(nt("a1", acc.id, "-20", first), nt("a2", acc.id, "-35", first)))
        val d = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings).observe().first()
        return ShareService(links, api, env.tx, MutableStateFlow(d)) to acc.id
    }

    @Test fun `crea la condivisione, pubblica il riepilogo cifrato con il budget scelto`() = runBlocking {
        val api = FakeShareApi(); val links = FakeLinks()
        val (svc, acc) = service(api, links)
        val link = svc.create("https://spese.example.workers.dev/", "chiave", acc, ShareConfig(budgetMonthly = BigDecimal(600)))
        assertEquals("https://spese.example.workers.dev", link.serverUrl)
        assertEquals("guest1", link.guestToken)
        val blob = api.out!!
        assertFalse(blob.contains("Carta famiglia"))                                    // sul servizio c'è solo testo cifrato
        val snap = ShareCodec.decodeSnapshot(ShareCrypto.decrypt(blob, link.key!!)!!)!!
        assertEquals("Carta famiglia", snap.title); assertEquals(0, BigDecimal(600).compareTo(snap.limit)); assertEquals(0, BigDecimal(55).compareTo(snap.spentToDate))
        assertNull(ShareCrypto.decrypt(blob, ShareCrypto.newKey()))                     // un'altra chiave non apre nulla
        // il codice per l'altra persona riporta indirizzo, codice dell'ospite e chiave, ma non il codice della madre
        val pairing = SharePairing.decode(SharePairing.encode(link))!!
        assertEquals("guest1", pairing.guestToken); assertEquals(link.keyText, pairing.keyText); assertFalse(SharePairing.encode(link).contains("owner1"))
    }

    @Test fun `piu conti condivisi - il riepilogo somma i loro movimenti e le spese rilevate vanno sul conto scelto`() = runBlocking {
        val api = FakeShareApi(); val links = FakeLinks()
        val a1 = env.accounts.getOrCreate("Carta famiglia"); val a2 = env.accounts.getOrCreate("Carta figlio"); val altro = env.accounts.getOrCreate("Personale")
        val first = LocalDate.now().withDayOfMonth(1)
        env.tx.insertAll(listOf(nt("m1", a1.id, "-20", first), nt("m2", a2.id, "-35", first), nt("m3", altro.id, "-999", first)))
        val d = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings).observe().first()
        val svc = ShareService(links, api, env.tx, MutableStateFlow(d))
        val link = svc.create("https://spese.example.workers.dev", "chiave", listOf(a1.id, a2.id), a2.id, ShareConfig(budgetMonthly = BigDecimal(600)))
        assertEquals(listOf(a1.id, a2.id), link.sharedIds); assertEquals(a2.id, link.accountId)
        val snap = ShareCodec.decodeSnapshot(ShareCrypto.decrypt(api.out!!, link.key!!)!!)!!
        assertEquals("Carta famiglia + Carta figlio", snap.title)
        assertEquals(0, BigDecimal(55).compareTo(snap.spentToDate))                  // 20 + 35: il conto «Personale» resta fuori
        val ev = listOf(ShareEvent("z1", BigDecimal("-5"), "Caffe", System.currentTimeMillis()))
        api.inbox.add(InboxItem(1, ShareCrypto.encrypt(ShareCodec.encodeEvents(ev), link.key!!)))
        assertEquals(1, svc.pull())
        assertEquals(a2.id, env.tx.getAll().single { t -> t.externalId == "share|z1" }.accountId)
        // il capture deve essere tra i conti condivisi
        assertTrue(runCatching { svc.create("https://spese.example.workers.dev", "chiave", listOf(a1.id), a2.id, ShareConfig()) }.exceptionOrNull() is ShareApiException)
        // si possono cambiare i conti dopo
        svc.updateConfig(ShareConfig(budgetMonthly = BigDecimal(600)), listOf(a1.id), a1.id)
        assertEquals(listOf(a1.id), links.get()!!.sharedIds)
        assertEquals(0, BigDecimal(20).compareTo(ShareCodec.decodeSnapshot(ShareCrypto.decrypt(api.out!!, links.get()!!.key!!)!!)!!.spentToDate))
    }

    @Test fun `chiave di creazione sbagliata - errore chiaro e nessuna condivisione`() = runBlocking {
        val api = FakeShareApi(); val links = FakeLinks()
        val (svc, acc) = service(api, links)
        val e = runCatching { svc.create("https://spese.example.workers.dev", "sbagliata", acc, ShareConfig(budgetMonthly = BigDecimal(600))) }.exceptionOrNull()
        assertTrue(e is ShareApiException); assertNull(links.get())
        assertTrue(runCatching { svc.create("http://insicuro.example.com", "chiave", acc, ShareConfig()) }.exceptionOrNull() is ShareApiException)
    }

    @Test fun `le spese dell'altro telefono diventano movimenti provvisori una sola volta e il servizio si svuota`() = runBlocking {
        val api = FakeShareApi(); val links = FakeLinks()
        val (svc, acc) = service(api, links)
        val link = svc.create("https://spese.example.workers.dev", "chiave", acc, ShareConfig(budgetMonthly = BigDecimal(600)))
        val now = System.currentTimeMillis()
        val ev = listOf(ShareEvent("e1", BigDecimal("-12.50"), "Bar Centrale", now, "Revolut"), ShareEvent("e2", BigDecimal("-8"), "Edicola", now))
        api.inbox.add(InboxItem(1, ShareCrypto.encrypt(ShareCodec.encodeEvents(ev), link.key!!)))
        api.inbox.add(InboxItem(2, ShareCrypto.encrypt(ShareCodec.encodeEvents(ev), ShareCrypto.newKey())))   // non decifrabile: scartata
        api.inbox.add(InboxItem(3, ShareCrypto.encrypt(ShareCodec.encodeEvents(listOf(ev[0])), link.key!!)))  // doppione: nessun duplicato
        assertEquals(2, svc.pull())
        assertTrue(api.inbox.isEmpty())
        val imported = env.tx.getAll().filter { t -> t.externalId.startsWith("share|") }
        assertEquals(2, imported.size); assertTrue(imported.all { t -> t.accountId == acc && t.kind == TxKind.SPESA })
        assertEquals(3L, links.get()!!.lastInSeq); assertEquals(2, links.get()!!.importedCount)
        assertEquals(0, svc.pull())
    }

    @Test fun `la revoca dimentica la condivisione anche se il servizio non risponde`() = runBlocking {
        val api = FakeShareApi(); val links = FakeLinks()
        val (svc, acc) = service(api, links)
        svc.create("https://spese.example.workers.dev", "chiave", acc, ShareConfig(budgetMonthly = BigDecimal(600)))
        assertTrue(svc.revoke()); assertTrue(api.revoked); assertNull(links.get())
    }
}
