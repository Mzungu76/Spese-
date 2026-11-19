package it.spese.domain.sharing

import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ShareDefaults { const val SERVER_URL = "https://spese.mansedario.workers.dev" }

/** La condivisione attiva vista dall'app madre: servizio, codici, chiave di cifratura, conto condiviso e scelte. Resta solo sul telefono. */
@Serializable
data class ShareLink(
    val serverUrl: String, val shareId: String, val ownerToken: String, val guestToken: String, val keyText: String,
    /** Conto su cui finiscono le spese rilevate dall'altro telefono. */
    val accountId: Long,
    /** Tutti i conti condivisi (budget e ritmo sono la somma dei loro movimenti); vuoto = solo [accountId] (condivisioni create prima dei conti multipli). */
    val accountIds: List<Long> = emptyList(),
    val budgetMonthly: String? = null, val savingsMonthly: String? = null,
    val captureNotifications: Boolean = true, val showChart: Boolean = true, val note: String = "", val safetyPct: Int = 10,
    /** Ultima spesa dell'altro telefono già importata, ultimo riepilogo pubblicato e ultimo controllo. */
    val lastInSeq: Long = 0, val lastPublishedAt: Long = 0, val lastPulledAt: Long = 0, val importedCount: Int = 0,
    /** La conversazione con l'altra persona (ultimi messaggi). */
    val thread: List<ThreadMsg> = emptyList(),
) {
    val key: ByteArray? get() = ShareCrypto.keyFromText(keyText)
    val sharedIds: List<Long> get() = accountIds.ifEmpty { listOf(accountId) }
    fun config() = ShareConfig(budgetMonthly?.toBigDecimalOrNull(), savingsMonthly?.toBigDecimalOrNull(), ShareFeatures(captureNotifications, showChart), note, safetyPct)
    fun withConfig(c: ShareConfig) = copy(budgetMonthly = c.budgetMonthly?.toPlainString(), savingsMonthly = c.savingsMonthly?.toPlainString(),
        captureNotifications = c.features.captureNotifications, showChart = c.features.showChart, note = c.note, safetyPct = c.safetyPct)
}

object ShareLinkCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(l: ShareLink): String = json.encodeToString(l)
    fun decode(raw: String?): ShareLink? = if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<ShareLink>(raw) }.getOrNull()
}

interface ShareLinkRepository {
    fun observe(): Flow<ShareLink?>
    suspend fun get(): ShareLink?
    /** Sostituisce la condivisione (null = nessuna). */
    suspend fun set(link: ShareLink?)
    /** Modifica atomica della condivisione attiva (non fa nulla se non c'è). */
    suspend fun update(transform: (ShareLink) -> ShareLink)
}

/** Indirizzo del servizio: solo https (i codici viaggiano nelle richieste); http ammesso solo per prove in locale. */
object ShareUrl {
    fun normalize(text: String): String? {
        val t = text.trim().trimEnd('/')
        if (t.isBlank() || t.any { it.isWhitespace() }) return null
        val local = Regex("^http://(localhost|127\\.0\\.0\\.1)(:\\d+)?$").matches(t)
        return if (local || Regex("^https://[A-Za-z0-9.-]+(:\\d+)?$").matches(t)) t else null
    }
}

/** Il «codice di abbinamento» che l'altra persona incolla nella sua app: indirizzo, condivisione, codice dell'ospite e chiave. */
object SharePairing {
    private const val PREFIX = "SPESE1."
    data class Pairing(val serverUrl: String, val shareId: String, val guestToken: String, val keyText: String)

    @Serializable private data class Dto(val u: String, val i: String, val t: String, val k: String)

    fun encode(l: ShareLink): String =
        PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(Dto(l.serverUrl, l.shareId, l.guestToken, l.keyText)).toByteArray(Charsets.UTF_8))

    fun decode(code: String): Pairing? = runCatching {
        val t = code.trim()
        require(t.startsWith(PREFIX))
        val d = Json { ignoreUnknownKeys = true }.decodeFromString<Dto>(String(java.util.Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)), Charsets.UTF_8))
        require(ShareUrl.normalize(d.u) != null && d.i.isNotBlank() && d.t.isNotBlank() && ShareCrypto.keyFromText(d.k) != null)
        Pairing(d.u, d.i, d.t, d.k)
    }.getOrNull()
}

class ShareApiException(message: String, val status: Int = 0) : Exception(message)
data class CreatedShare(val id: String, val ownerToken: String, val guestToken: String)
data class InboxItem(val seq: Long, val blob: String)
data class FetchedOut(val blob: String?, val updated: Long, val unchanged: Boolean)
data class InboxPage(val items: List<InboxItem>, val last: Long)

/** Parla con il servizio Cloudflare (implementazione nel modulo dati). */
interface ShareApi {
    suspend fun create(baseUrl: String, createKey: String): CreatedShare
    /** Pubblica il riepilogo cifrato; restituisce l'istante di aggiornamento del servizio. */
    suspend fun publish(baseUrl: String, id: String, token: String, blob: String): Long
    suspend fun inbox(baseUrl: String, id: String, token: String, after: Long): InboxPage
    suspend fun ack(baseUrl: String, id: String, token: String, upTo: Long)
    suspend fun revoke(baseUrl: String, id: String, token: String)
    /** L'altra persona legge il riepilogo cifrato; `null` se la madre non ne ha ancora pubblicato uno. Con [since] uguale all'ultimo aggiornamento risponde «invariato». */
    suspend fun fetch(baseUrl: String, id: String, token: String, since: Long): FetchedOut?
    /** L'altra persona manda le spese rilevate (ogni voce è già cifrata). */
    suspend fun send(baseUrl: String, id: String, token: String, items: List<String>)
}
