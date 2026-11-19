package it.spese.domain.sharing

import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.Serializable

/** Cosa l'app madre permette all'app dell'altra persona. */
data class ShareFeatures(
    /** L'app dell'altra persona rileva le notifiche di spesa e le manda all'app madre. */
    val captureNotifications: Boolean = true,
    /** Mostra anche il grafico del ritmo (altrimenti solo la cifra giornaliera). */
    val showChart: Boolean = true,
)

/** Impostazioni di una condivisione, scelte nell'app madre. */
data class ShareConfig(
    /** Budget mensile fissato a mano; se manca si calcola dalle entrate recenti del conto. */
    val budgetMonthly: BigDecimal? = null,
    /** Quanto mettere da parte ogni mese prima di calcolare il limite (0 se non serve). */
    val savingsMonthly: BigDecimal? = null,
    val features: ShareFeatures = ShareFeatures(),
    /** Un messaggio facoltativo per l'altra persona. */
    val note: String = "",
)

/** Quello che l'app madre pubblica: il budget del periodo e i dati del grafico del ritmo (una cifra per giorno). */
data class ShareSnapshot(
    val generatedAtMillis: Long, val title: String, val unit: String,
    val periodStart: LocalDate, val periodEnd: LocalDate, val today: LocalDate, val days: Int, val dayIndex: Int,
    /** Il limite di spesa del periodo (null se non calcolabile). */
    val limit: BigDecimal?, val spentToDate: BigDecimal,
    /** Quanto sarebbe ottimale aver speso a oggi, quanto si può spendere al giorno da qui a fine periodo, e la media giornaliera ideale. */
    val optimalToDate: BigDecimal?, val dailyRemaining: BigDecimal?, val dailyAverage: BigDecimal?,
    val current: List<BigDecimal>, val average: List<BigDecimal>, val optimal: List<BigDecimal>,
    val features: ShareFeatures = ShareFeatures(), val note: String = "", val version: Int = 1,
    /** Ultime spese dei conti condivisi, dove vanno i soldi nel periodo, quanto si è speso nel periodo precedente e la conversazione tra le due persone. */
    val recent: List<ShareRecent> = emptyList(), val categories: List<ShareCategory> = emptyList(), val previousTotal: BigDecimal? = null,
    val thread: List<ThreadMsg> = emptyList(),
    /** Uscite note ancora da pagare nel mese (ricorrenze, abbonamenti, scadenze) e margine di sicurezza trattenuto. */
    val upcomingTotal: BigDecimal? = null, val reserve: BigDecimal? = null,
)

data class ShareRecent(val day: LocalDate, val description: String, val amount: BigDecimal, val account: String = "")
data class ShareCategory(val name: String, val percent: Int)

/** Un messaggio tra le due persone. `id` è stabile: lo stesso messaggio non compare due volte. */
@Serializable data class ThreadMsg(val id: String, val fromHost: Boolean, val text: String, val at: Long)

/** Una spesa (o entrata) rilevata sul telefono dell'altra persona. `amount` ha il segno: negativo = spesa. */
data class ShareEvent(val id: String, val amount: BigDecimal, val description: String, val atMillis: Long, val source: String = "")
