package it.spese.domain.capture

import it.spese.core.model.Transaction
import it.spese.core.model.TxOrigin
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class SourceKind { BANK_APP, WALLET, SMS }

/**
 * Fonti da cui l'app può ricavare movimenti dalle notifiche. `rank` decide quale vince se due fonti notificano lo stesso pagamento
 * (l'app della banca è più precisa di Google Wallet). I pacchetti sono quelli noti; si possono aggiungere altri dalle impostazioni.
 */
enum class CaptureSource(val id: String, val label: String, val kind: SourceKind, val defaultPackages: Set<String>, val rank: Int) {
    REVOLUT("revolut", "Revolut", SourceKind.BANK_APP, setOf("com.revolut.revolut"), 2),
    WALLET("wallet", "Google Wallet", SourceKind.WALLET, setOf("com.google.android.apps.walletnfcrel"), 1),
    POSTE("poste", "Poste / Postepay", SourceKind.BANK_APP, setOf("posteitaliane.posteapp.apppostepay", "posteitaliane.posteapp.appbpol"), 2),
    HYPE("hype", "Hype", SourceKind.BANK_APP, emptySet(), 2),
    SMS("sms", "SMS della banca", SourceKind.SMS, setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging"), 2);

    /** Prefissi di pacchetto: le app di Poste cambiano nome tra versioni e varianti (Postepay, BancoPosta, Poste Italiane…). */
    val packagePrefixes: Set<String> get() = if (this == POSTE) setOf("posteitaliane.") else emptySet()
    fun matchesPackage(pkg: String): Boolean = pkg in defaultPackages || packagePrefixes.any { pkg.startsWith(it) }

    companion object {
        fun byId(id: String?): CaptureSource? = entries.firstOrNull { it.id == id }
    }
}

/** Notifica grezza ricevuta dal sistema (già ridotta ai soli campi di testo). */
data class RawNotification(val packageName: String, val title: String, val text: String, val postedAtMs: Long)

data class SourceConfig(
    val enabled: Boolean = false,
    /** Conto su cui registrare i movimenti di questa fonte (null = nessun conto: i movimenti di questa fonte vanno in Verifica finché non lo scegli). */
    val accountId: Long? = null,
    /** Altri pacchetti da ascoltare per questa fonte (app non nell'elenco noto). */
    val extraPackages: Set<String> = emptySet(),
    /** Solo per gli SMS: nomi dei mittenti ammessi (es. "Postepay", "HYPE"). */
    val smsSenders: Set<String> = emptySet(),
)

/** Una notifica delle fonti scelte che non è stato possibile interpretare (testo conservato solo sul telefono, per correggere il riconoscimento). */
data class UnrecognizedNotification(val atMs: Long, val source: String, val text: String)

/**
 * Notifica che segnala un movimento ma non riporta l'importo (es. Poste: «Hai un movimento in uscita su POSTEPAY»).
 * `id` è l'istante della notifica; `sign` è -1 per uscite, +1 per entrate, 0 se dalla notifica non si capisce (lo sceglie l'utente). L'utente aggiunge l'importo, oppure si risolve da sola.
 */
data class PendingAmount(val id: Long, val sourceId: String, val accountId: Long?, val sign: Int, val description: String, val text: String)

data class CaptureConfig(
    val sources: Map<String, SourceConfig> = emptyMap(), val unrecognized: List<UnrecognizedNotification> = emptyList(),
    /** Avvisa (con "Annulla") ogni volta che un movimento viene aggiunto da una notifica. */
    val notifyOnAdd: Boolean = true,
    val ignoreRules: List<IgnoreRule> = emptyList(),
    val pending: List<PendingAmount> = emptyList(),
    /** Istante (ms) dell'ultima notifica ricevuta per ogni fonte: dice se l'app sta davvero ricevendo. */
    val lastSeen: Map<String, Long> = emptyMap(),
    /** Registra come «da regolarizzare» anche le notifiche delle fonti scelte che non riportano un importo. */
    val captureNoAmount: Boolean = true,
) {
    /** Troppe notifiche non riconosciute accumulate: chiedi all'utente di controllarle. */
    val needsReview: Boolean get() = unrecognized.size >= IgnoreFilter.REVIEW_THRESHOLD
    fun of(s: CaptureSource): SourceConfig = sources[s.id] ?: SourceConfig()
    val anyEnabled: Boolean get() = CaptureSource.entries.any { of(it).enabled }
}

object CaptureRouting {
    /** A quale fonte attiva appartiene una notifica? null = va ignorata subito, senza leggerne il testo. */
    fun route(config: CaptureConfig, packageName: String, title: String): CaptureSource? {
        for (s in CaptureSource.entries) {
            val c = config.of(s)
            if (!c.enabled) continue
            if (!s.matchesPackage(packageName) && packageName !in c.extraPackages) continue
            if (s.kind == SourceKind.SMS) {
                if (c.smsSenders.isEmpty() || c.smsSenders.none { title.contains(it.trim(), ignoreCase = true) && it.isNotBlank() }) continue
            }
            return s
        }
        return null
    }
}

/** Abbina le righe di un estratto conto ai movimenti provvisori (da notifiche: data entro `maxDays` giorni; ricorrenti: entro `recurringDays`) con lo stesso importo. */
object NotificationReconciler {
    data class Row(val index: Int, val amount: BigDecimal, val date: LocalDate)

    /** index della riga → movimento provvisorio da sostituire. Ogni provvisorio è usato una sola volta, scegliendo il più vicino nel tempo. */
    fun match(provisional: List<Transaction>, rows: List<Row>, maxDays: Int = 3, recurringDays: Int = 5, receiptDays: Int = 5): Map<Int, Transaction> {
        val free = provisional.filter { it.origin == TxOrigin.NOTIFICATION || it.origin == TxOrigin.RECURRING || it.origin == TxOrigin.OCR }.toMutableList()
        val out = LinkedHashMap<Int, Transaction>()
        for (r in rows.sortedBy { it.date }) {
            val best = free.filter { it.amount.compareTo(r.amount) == 0 }
                .map { it to abs(ChronoUnit.DAYS.between(it.bookingDate, r.date)).toInt() }
                .filter { it.second <= when (it.first.origin) { TxOrigin.RECURRING -> recurringDays; TxOrigin.OCR -> receiptDays; else -> maxDays } }.minByOrNull { it.second } ?: continue
            out[r.index] = best.first; free.remove(best.first)
        }
        return out
    }
}


/** Abbina i movimenti "senza importo" a quelli che poi arrivano con l'importo, senza mai indovinare tra più candidati. */
object PendingMatcher {
    const val WINDOW_MS = 10 * 60_000L

    /** Un pagamento con importo notificato (da un'altra fonte, es. Google Wallet) nello stesso momento chiude il promemoria. */
    fun coveredByNotification(p: PendingAmount, sign: Int, postedAtMs: Long): Boolean = (p.sign == 0 || p.sign == sign) && kotlin.math.abs(p.id - postedAtMs) <= WINDOW_MS

    /** Id dei promemoria risolti dalle righe importate: stessa direzione, stesso giorno (±1), un solo candidato e non conteso da un altro promemoria. */
    fun resolveByStatement(pending: List<PendingAmount>, rows: List<it.spese.core.model.Transaction>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Set<Long> {
        val imported = rows.filter { it.origin == TxOrigin.IMPORT_FILE }
        fun candidates(p: PendingAmount): List<it.spese.core.model.Transaction> {
            val day = java.time.Instant.ofEpochMilli(p.id).atZone(zone).toLocalDate()
            return imported.filter { (p.sign == 0 || it.amount.signum() == p.sign) && kotlin.math.abs(ChronoUnit.DAYS.between(it.bookingDate, day)) <= 1 && (p.accountId == null || it.accountId == p.accountId) }
        }
        val byPending = pending.associateWith { candidates(it) }
        return byPending.filter { (_, c) -> c.size == 1 && byPending.count { (_, o) -> o.any { it.id == c[0].id } } == 1 }.keys.map { it.id }.toSet()
    }
}
