package it.spese.domain.sharing

import it.spese.domain.repository.AppData
import it.spese.domain.repository.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

data class ShareSyncResult(val imported: Int, val published: Boolean, val problem: String? = null)

/** Crea, aggiorna e revoca la condivisione, pubblica il riepilogo e importa le spese rilevate dall'altro telefono. */
class ShareService @Inject constructor(
    private val links: ShareLinkRepository, private val api: ShareApi, private val tx: TransactionRepository, private val data: StateFlow<AppData>, private val inputs: BudgetInputsSource,
) {
    suspend fun create(serverUrl: String, createKey: String, accountId: Long, cfg: ShareConfig): ShareLink = create(serverUrl, createKey, listOf(accountId), accountId, cfg)

    /** [accountIds]: i conti condivisi; [captureAccountId]: il conto che riceve le spese rilevate dall'altro telefono (uno dei conti condivisi). */
    suspend fun create(serverUrl: String, createKey: String, accountIds: List<Long>, captureAccountId: Long, cfg: ShareConfig): ShareLink {
        val base = ShareUrl.normalize(serverUrl) ?: throw ShareApiException("Indirizzo del servizio non valido: deve iniziare con https://")
        if (createKey.isBlank()) throw ShareApiException("Inserisci la chiave di creazione impostata su Cloudflare")
        val d = data.first { it.loaded }
        val ids = checkAccounts(d, accountIds, captureAccountId)
        val created = api.create(base, createKey.trim())
        links.set(ShareLink(base, created.id, created.ownerToken, created.guestToken, ShareCrypto.keyToText(ShareCrypto.newKey()), captureAccountId, ids).withConfig(cfg))
        // se il budget non è ancora calcolabile la condivisione esiste comunque: il riepilogo partirà alla prima sincronizzazione riuscita
        runCatching { publish() }
        return links.get()!!
    }

    private fun checkAccounts(d: AppData, accountIds: List<Long>, captureAccountId: Long): List<Long> {
        val ids = accountIds.distinct()
        if (ids.isEmpty() || ids.any { id -> d.accountsById[id] == null }) throw ShareApiException("Scegli almeno un conto da condividere")
        if (captureAccountId !in ids) throw ShareApiException("Il conto che riceve le spese rilevate deve essere uno di quelli condivisi")
        return ids
    }

    /** Cambia le scelte (budget, risparmio, funzioni, nota ed eventualmente i conti) e le pubblica subito. */
    suspend fun updateConfig(cfg: ShareConfig, accountIds: List<Long>? = null, captureAccountId: Long? = null): ShareLink {
        val cur = links.get() ?: throw ShareApiException("Nessuna condivisione attiva")
        if (accountIds != null) {
            val capture = captureAccountId ?: cur.accountId.takeIf { it in accountIds } ?: accountIds.firstOrNull() ?: 0L
            val ids = checkAccounts(data.first { it.loaded }, accountIds, capture)
            links.update { l -> l.withConfig(cfg).copy(accountIds = ids, accountId = capture) }
        } else links.update { l -> l.withConfig(cfg) }
        publish()
        return links.get()!!
    }

    suspend fun publish(now: Long = System.currentTimeMillis()): ShareLink {
        val link = links.get() ?: throw ShareApiException("Nessuna condivisione attiva")
        val key = link.key ?: throw ShareApiException("La chiave della condivisione non è valida")
        val d = data.first { it.loaded }
        val snaps = ShareSnapshotBuilder.buildAll(d, link.sharedIds, link.config(), now, thread = link.thread.takeLast(THREAD), inputs = inputs.get())
        if (snaps.isEmpty()) throw ShareApiException("Servono più movimenti sui conti condivisi (o un budget fissato a mano) per calcolare il limite")
        api.publish(link.serverUrl, link.shareId, link.ownerToken, ShareCrypto.encrypt(ShareCodec.encodeBundle(snaps), key))
        links.update { l -> l.copy(lastPublishedAt = now) }
        return links.get() ?: link
    }

    /** Importa le spese arrivate dall'altro telefono (come movimenti provvisori del conto condiviso) e libera il servizio. */
    suspend fun pull(now: Long = System.currentTimeMillis()): Int {
        val link = links.get() ?: return 0
        val key = link.key ?: throw ShareApiException("La chiave della condivisione non è valida")
        val d = data.first { it.loaded }
        if (d.accountsById[link.accountId] == null) throw ShareApiException("Il conto condiviso non esiste più")
        var after = link.lastInSeq; var imported = 0
        while (true) {
            val page = api.inbox(link.serverUrl, link.shareId, link.ownerToken, after)
            if (page.items.isEmpty()) break
            // un messaggio che non si decifra (chiave diversa, manomissione) si scarta, ma non blocca gli altri
            val plain = page.items.mapNotNull { item -> ShareCrypto.decrypt(item.blob, key) }
            val messages = plain.mapNotNull { text -> ShareCodec.decodeMessage(text) }.distinctBy { m -> m.id }
            val events = plain.flatMap { text -> ShareCodec.decodeEvents(text) }
            if (messages.isNotEmpty()) links.update { l -> l.copy(thread = (l.thread + messages.filter { m -> l.thread.none { x -> x.id == m.id } }).sortedBy { m -> m.at }.takeLast(THREAD)) }
            val news = ShareEventImporter.toNewTransactions(events, link.accountId)
            val added = if (news.isEmpty()) 0 else tx.insertAll(news)
            api.ack(link.serverUrl, link.shareId, link.ownerToken, page.last)
            imported += added; after = page.last
            links.update { l -> l.copy(lastInSeq = page.last, importedCount = l.importedCount + added) }
            if (page.items.size < PAGE) break
        }
        links.update { l -> l.copy(lastPulledAt = now) }
        return imported
    }

    /** Scrive all'altra persona: il messaggio entra nella conversazione e si pubblica subito. */
    suspend fun sendMessage(text: String, now: Long = System.currentTimeMillis()): ShareLink {
        val t = text.trim().take(500)
        if (t.isBlank()) throw ShareApiException("Scrivi qualcosa")
        links.update { l -> l.copy(thread = (l.thread + ThreadMsg("h$now", true, t, now)).takeLast(THREAD)) }
        publish(now)
        return links.get()!!
    }

    /** Prima si importano le spese nuove, poi si pubblica il riepilogo aggiornato. Gli errori non bloccano l'altra metà. */
    suspend fun sync(): ShareSyncResult {
        if (links.get() == null) return ShareSyncResult(0, false, "Nessuna condivisione attiva")
        var imported = 0; var problem: String? = null; var published = false
        try { imported = pull() } catch (e: ShareApiException) { problem = e.message }
        try { publish(); published = true } catch (e: ShareApiException) { if (problem == null) problem = e.message }
        return ShareSyncResult(imported, published, problem)
    }

    /** Revoca sul servizio (se raggiungibile) e dimentica la condivisione sul telefono. */
    suspend fun revoke(): Boolean {
        val link = links.get() ?: return true
        val remote = runCatching { api.revoke(link.serverUrl, link.shareId, link.ownerToken) }.isSuccess
        links.set(null)
        return remote
    }

    private companion object { const val PAGE = 100; const val THREAD = 40 }
}
