package it.spese.domain.sharing

import it.spese.domain.repository.AppData
import it.spese.domain.repository.TransactionRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

data class ShareSyncResult(val imported: Int, val published: Boolean, val problem: String? = null)

/** Crea, aggiorna e revoca la condivisione, pubblica il riepilogo e importa le spese rilevate dall'altro telefono. */
class ShareService @Inject constructor(
    private val links: ShareLinkRepository, private val api: ShareApi, private val tx: TransactionRepository, private val data: StateFlow<AppData>,
) {
    suspend fun create(serverUrl: String, createKey: String, accountId: Long, cfg: ShareConfig): ShareLink {
        val base = ShareUrl.normalize(serverUrl) ?: throw ShareApiException("Indirizzo del servizio non valido: deve iniziare con https://")
        if (createKey.isBlank()) throw ShareApiException("Inserisci la chiave di creazione impostata su Cloudflare")
        val d = data.first { it.loaded }
        if (d.accountsById[accountId] == null) throw ShareApiException("Scegli il conto da condividere")
        val created = api.create(base, createKey.trim())
        links.set(ShareLink(base, created.id, created.ownerToken, created.guestToken, ShareCrypto.keyToText(ShareCrypto.newKey()), accountId).withConfig(cfg))
        // se il budget non è ancora calcolabile la condivisione esiste comunque: il riepilogo partirà alla prima sincronizzazione riuscita
        runCatching { publish() }
        return links.get()!!
    }

    /** Cambia le scelte (budget, risparmio, funzioni, nota) e le pubblica subito. */
    suspend fun updateConfig(cfg: ShareConfig): ShareLink {
        links.update { l -> l.withConfig(cfg) }
        publish()
        return links.get()!!
    }

    suspend fun publish(now: Long = System.currentTimeMillis()): ShareLink {
        val link = links.get() ?: throw ShareApiException("Nessuna condivisione attiva")
        val key = link.key ?: throw ShareApiException("La chiave della condivisione non è valida")
        val d = data.first { it.loaded }
        val snap = ShareSnapshotBuilder.build(d, link.accountId, link.config(), now)
            ?: throw ShareApiException("Servono più movimenti sul conto condiviso (o un budget fissato a mano) per calcolare il limite")
        api.publish(link.serverUrl, link.shareId, link.ownerToken, ShareCrypto.encrypt(ShareCodec.encodeSnapshot(snap), key))
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
            val events = page.items.flatMap { item -> ShareCrypto.decrypt(item.blob, key)?.let { text -> ShareCodec.decodeEvents(text) }.orEmpty() }
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

    private companion object { const val PAGE = 100 }
}
