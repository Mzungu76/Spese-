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

    companion object {
        fun byId(id: String?): CaptureSource? = entries.firstOrNull { it.id == id }
    }
}

/** Notifica grezza ricevuta dal sistema (già ridotta ai soli campi di testo). */
data class RawNotification(val packageName: String, val title: String, val text: String, val postedAtMs: Long)

data class SourceConfig(
    val enabled: Boolean = false,
    /** Conto su cui registrare i movimenti di questa fonte (null = primo conto). */
    val accountId: Long? = null,
    /** Altri pacchetti da ascoltare per questa fonte (app non nell'elenco noto). */
    val extraPackages: Set<String> = emptySet(),
    /** Solo per gli SMS: nomi dei mittenti ammessi (es. "Postepay", "HYPE"). */
    val smsSenders: Set<String> = emptySet(),
)

/** Una notifica delle fonti scelte che non è stato possibile interpretare (testo conservato solo sul telefono, per correggere il riconoscimento). */
data class UnrecognizedNotification(val atMs: Long, val source: String, val text: String)

data class CaptureConfig(
    val sources: Map<String, SourceConfig> = emptyMap(), val unrecognized: List<UnrecognizedNotification> = emptyList(),
    /** Avvisa (con "Annulla") ogni volta che un movimento viene aggiunto da una notifica. */
    val notifyOnAdd: Boolean = true,
) {
    fun of(s: CaptureSource): SourceConfig = sources[s.id] ?: SourceConfig()
    val anyEnabled: Boolean get() = CaptureSource.entries.any { of(it).enabled }
}

object CaptureRouting {
    /** A quale fonte attiva appartiene una notifica? null = va ignorata subito, senza leggerne il testo. */
    fun route(config: CaptureConfig, packageName: String, title: String): CaptureSource? {
        for (s in CaptureSource.entries) {
            val c = config.of(s)
            if (!c.enabled) continue
            if (packageName !in s.defaultPackages && packageName !in c.extraPackages) continue
            if (s.kind == SourceKind.SMS) {
                if (c.smsSenders.isEmpty() || c.smsSenders.none { title.contains(it.trim(), ignoreCase = true) && it.isNotBlank() }) continue
            }
            return s
        }
        return null
    }
}

/** Abbina le righe di un estratto conto ai movimenti provvisori nati da notifiche (stesso importo, data entro `maxDays` giorni). */
object NotificationReconciler {
    data class Row(val index: Int, val amount: BigDecimal, val date: LocalDate)

    /** index della riga → movimento provvisorio da sostituire. Ogni provvisorio è usato una sola volta, scegliendo il più vicino nel tempo. */
    fun match(provisional: List<Transaction>, rows: List<Row>, maxDays: Int = 3): Map<Int, Transaction> {
        val free = provisional.filter { it.origin == TxOrigin.NOTIFICATION }.toMutableList()
        val out = LinkedHashMap<Int, Transaction>()
        for (r in rows.sortedBy { it.date }) {
            val best = free.filter { it.amount.compareTo(r.amount) == 0 }
                .map { it to abs(ChronoUnit.DAYS.between(it.bookingDate, r.date)).toInt() }
                .filter { it.second <= maxDays }.minByOrNull { it.second } ?: continue
            out[r.index] = best.first; free.remove(best.first)
        }
        return out
    }
}
