package it.spese.domain.sharing

import it.spese.domain.capture.CaptureConfig
import it.spese.domain.capture.CaptureSource
import it.spese.domain.capture.NotificationParser
import it.spese.domain.capture.SourceConfig
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Una spesa rilevata e non ancora consegnata alla madre (si conserva sul telefono finché non c'è rete). */
@Serializable data class QueuedEvent(val id: String, val amount: String, val description: String, val at: Long, val source: String = "")

/** Lo stato dell'app dell'altra persona: l'abbinamento, l'ultimo riepilogo ricevuto (in chiaro, solo sul telefono) e le spese da consegnare. */
@Serializable
data class GuestState(
    val serverUrl: String, val shareId: String, val guestToken: String, val keyText: String,
    val snapshotJson: String = "", val snapshotUpdated: Long = 0, val lastRefreshAt: Long = 0,
    /** Ultimo problema (es. condivisione revocata): resta finché un aggiornamento non riesce. */
    val problem: String = "",
    val outbox: List<QueuedEvent> = emptyList(), val sentCount: Int = 0,
    /** Fonti di notifiche da ascoltare (id); null = tutte quelle note tranne gli SMS. */
    val sourceIds: List<String>? = null,
) {
    val key: ByteArray? get() = ShareCrypto.keyFromText(keyText)
    val snapshot: ShareSnapshot? get() = if (snapshotJson.isBlank()) null else ShareCodec.decodeSnapshot(snapshotJson)
    val activeSources: List<CaptureSource> get() = (sourceIds?.mapNotNull { id -> CaptureSource.byId(id) } ?: CaptureSource.entries.filter { s -> s != CaptureSource.SMS })
}

object GuestStateCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(s: GuestState): String = json.encodeToString(s)
    fun decode(raw: String?): GuestState? = if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<GuestState>(raw) }.getOrNull()
}

interface GuestStateRepository {
    fun observe(): Flow<GuestState?>
    suspend fun get(): GuestState?
    suspend fun set(state: GuestState?)
    /** Modifica atomica (non fa nulla se non c'è un abbinamento). */
    suspend fun update(transform: (GuestState) -> GuestState)
}

/** Dalle notifiche delle fonti scelte alle spese da mandare alla madre. */
object GuestCapture {
    /** Le fonti attive come configurazione di rilevamento (gli SMS servono i mittenti: qui non si usano). */
    fun config(sources: List<CaptureSource>) = CaptureConfig(sources = sources.filter { s -> s != CaptureSource.SMS }.associate { s -> s.id to SourceConfig(enabled = true) })

    /** `null` se la notifica non è un movimento riconoscibile con importo. L'id è stabile: la stessa notifica ripetuta non crea doppioni. */
    fun eventFrom(source: CaptureSource, packageName: String, title: String, text: String, postedAtMs: Long): QueuedEvent? {
        val parsed = NotificationParser.parse(source, title, text) ?: return null
        if (parsed.amount.signum() == 0) return null
        val digest = MessageDigest.getInstance("SHA-256").digest("$packageName|$postedAtMs|$title|$text".toByteArray(Charsets.UTF_8))
        val id = "g" + digest.take(8).joinToString("") { b -> "%02x".format(b) }
        return QueuedEvent(id, parsed.amount.toPlainString(), parsed.description.take(120), postedAtMs, source.label)
    }
}

/** Abbinamento, aggiornamento del riepilogo e consegna delle spese rilevate. */
class GuestService(private val repo: GuestStateRepository, private val api: ShareApi) {
    /** Collega l'app al codice ricevuto dalla madre e prova subito a leggere il riepilogo. */
    suspend fun pair(code: String, now: Long = System.currentTimeMillis()): GuestState {
        val p = SharePairing.decode(code) ?: throw ShareApiException("Il codice non è valido: copialo di nuovo per intero")
        repo.set(GuestState(p.serverUrl, p.shareId, p.guestToken, p.keyText))
        runCatching { refresh(now) }
        return repo.get()!!
    }

    suspend fun refresh(now: Long = System.currentTimeMillis()): GuestState {
        val st = repo.get() ?: throw ShareApiException("Nessuna condivisione collegata")
        val key = st.key ?: throw ShareApiException("La chiave del codice non è valida")
        try {
            val f = api.fetch(st.serverUrl, st.shareId, st.guestToken, st.snapshotUpdated)
            if (f != null && !f.unchanged && f.blob != null) {
                val plain = ShareCrypto.decrypt(f.blob, key) ?: throw ShareApiException("Non riesco a leggere il riepilogo: il codice potrebbe non essere quello giusto")
                if (ShareCodec.decodeSnapshot(plain) == null) throw ShareApiException("Il riepilogo ricevuto non è in un formato che conosco: aggiorna l'app")
                repo.update { s -> s.copy(snapshotJson = plain, snapshotUpdated = f.updated, lastRefreshAt = now, problem = "") }
            } else repo.update { s -> s.copy(lastRefreshAt = now, problem = "") }
        } catch (e: ShareApiException) {
            val revoked = e.status == 401 || e.status == 403
            repo.update { s -> s.copy(problem = if (revoked) "La condivisione è stata revocata o il codice non vale più" else e.message.orEmpty()) }
            throw e
        }
        return repo.get()!!
    }

    /** Mette in coda le spese rilevate (se la madre ha spento la cattura non fa nulla). Restituisce quante ne ha aggiunte. */
    suspend fun queue(events: List<QueuedEvent>): Int {
        val st = repo.get() ?: return 0
        if (st.snapshot?.features?.captureNotifications == false || events.isEmpty()) return 0
        var added = 0
        repo.update { s ->
            val known = s.outbox.mapTo(HashSet()) { e -> e.id }
            val fresh = events.filter { e -> known.add(e.id) }
            added = fresh.size
            s.copy(outbox = (s.outbox + fresh).takeLast(MAX_OUTBOX))
        }
        return added
    }

    /** Consegna le spese in coda; quelle non consegnate restano per il prossimo tentativo. */
    suspend fun flush(): Int {
        val st = repo.get() ?: return 0
        val key = st.key ?: return 0
        var sent = 0
        for (chunk in st.outbox.chunked(CHUNK)) {
            val blob = ShareCrypto.encrypt(ShareCodec.encodeEvents(chunk.map { e -> ShareEvent(e.id, BigDecimal(e.amount), e.description, e.at, e.source) }), key)
            try { api.send(st.serverUrl, st.shareId, st.guestToken, listOf(blob)) }
            catch (e: ShareApiException) { repo.update { s -> s.copy(problem = if (e.status == 401 || e.status == 403) "La condivisione è stata revocata o il codice non vale più" else e.message.orEmpty()) }; throw e }
            val ids = chunk.mapTo(HashSet()) { e -> e.id }
            repo.update { s -> s.copy(outbox = s.outbox.filter { e -> e.id !in ids }, sentCount = s.sentCount + chunk.size) }
            sent += chunk.size
        }
        return sent
    }

    /** Aggiorna il riepilogo e consegna le spese; un problema in una metà non blocca l'altra. */
    suspend fun sync(now: Long = System.currentTimeMillis()): String? {
        var problem: String? = null
        try { refresh(now) } catch (e: ShareApiException) { problem = e.message }
        try { flush() } catch (e: ShareApiException) { if (problem == null) problem = e.message }
        return problem
    }

    suspend fun setSources(ids: List<String>) { repo.update { s -> s.copy(sourceIds = ids) } }
    suspend fun unpair() { repo.set(null) }

    private companion object { const val CHUNK = 20; const val MAX_OUTBOX = 500 }
}
