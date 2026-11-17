package it.spese.domain.usecase

import it.spese.core.model.TxOrigin
import it.spese.domain.capture.*
import it.spese.domain.classification.Classifier
import it.spese.domain.repository.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.abs

sealed interface CaptureResult {
    data class Added(val txId: Long, val description: String, val amount: BigDecimal) : CaptureResult
    /** Stesso pagamento già registrato (notifica riproposta, o notificato da un'altra app). */
    data object Duplicate : CaptureResult
    data object Unrecognized : CaptureResult
    /** Il movimento è segnalato senza importo: va completato dall'utente (o si risolve da solo). */
    data class NeedsAmount(val pending: PendingAmount) : CaptureResult
}

/**
 * Trasforma una notifica delle fonti scelte in un movimento provvisorio, classificato con le regole esistenti.
 * Lo stesso pagamento notificato da due app (es. Google Wallet e la banca) viene registrato una volta sola, tenendo la fonte più precisa.
 */
class CaptureNotificationUseCase @Inject constructor(
    private val tx: TransactionRepository, private val accounts: AccountRepository,
    private val categories: CategoryRepository, private val rules: RuleRepository, private val applyQuotas: ApplyCashQuotasUseCase,
) {
    suspend operator fun invoke(source: CaptureSource, accountId: Long?, raw: RawNotification, zone: ZoneId = ZoneId.systemDefault(), noAmountToo: Boolean = true): CaptureResult {
        val parsed = NotificationParser.parse(source, raw.title, raw.text) ?: return noAmount(source, accountId, raw, zone, noAmountToo)
        val acc = accounts.getAll().let { all -> all.firstOrNull { it.id == accountId && !it.archived } ?: all.firstOrNull { !it.archived } } ?: accounts.getOrCreate("Conto principale")
        val externalId = externalId(source, raw, parsed.amount)
        val existing = tx.getAll()
        if (existing.any { it.externalId == externalId }) return CaptureResult.Duplicate

        // stesso pagamento notificato da un'altra fonte nei 3 minuti precedenti o successivi
        val twin = existing.firstOrNull { t ->
            t.origin == TxOrigin.NOTIFICATION && t.amount.compareTo(parsed.amount) == 0 && sourceOf(t.externalId) != source &&
                (postedAt(t.externalId)?.let { abs(it - raw.postedAtMs) <= TWIN_WINDOW_MS } ?: false)
        }
        if (twin != null) {
            if ((sourceOf(twin.externalId)?.rank ?: 0) >= source.rank) return CaptureResult.Duplicate
            tx.delete(listOf(twin.id))
        }

        val c = Classifier(categories.getAll(), rules.getAll()).classify(parsed.description, parsed.amount, null)
        val date = Instant.ofEpochMilli(raw.postedAtMs).atZone(zone).toLocalDate()
        tx.insertAll(listOf(NewTransaction(externalId, externalId, acc.id, date, date, parsed.description, parsed.amount, c.kind, c.categoryId, false, c.ruleId,
            null, "Da notifica · ${source.label}", TxOrigin.NOTIFICATION)))
        applyQuotas()
        val id = tx.getAll().firstOrNull { it.externalId == externalId }?.id ?: return CaptureResult.Duplicate
        return CaptureResult.Added(id, parsed.description, parsed.amount)
    }

    /** Notifica senza importo: se un pagamento di pari direzione è già stato registrato da un'altra notifica nello stesso momento (es. Wallet), non serve chiedere nulla. */
    private suspend fun noAmount(source: CaptureSource, accountId: Long?, raw: RawNotification, zone: ZoneId, noAmountToo: Boolean): CaptureResult {
        val sign = NotificationParser.parseWithoutAmount(source, raw.title, raw.text, noAmountToo) ?: return CaptureResult.Unrecognized
        val covered = tx.getAll().any { t -> t.origin == TxOrigin.NOTIFICATION && (sign == 0 || t.amount.signum() == sign) && sourceOf(t.externalId) != source &&
            (postedAt(t.externalId)?.let { abs(it - raw.postedAtMs) <= PendingMatcher.WINDOW_MS } ?: false) }
        if (covered) return CaptureResult.Duplicate
        val description = when { sign < 0 -> "Movimento ${source.label} in uscita"; sign > 0 -> "Movimento ${source.label} in entrata"; else -> "Notifica ${source.label} da verificare" }
        return CaptureResult.NeedsAmount(PendingAmount(raw.postedAtMs, source.id, accountId, sign, description, "${raw.title} ${raw.text}".trim().take(160)))
    }

    companion object {
        const val TWIN_WINDOW_MS = 3 * 60_000L
        fun externalId(source: CaptureSource, raw: RawNotification, amount: BigDecimal) =
            "notif|${source.id}|${raw.postedAtMs}|${amount.toPlainString()}|${(raw.title + "|" + raw.text).hashCode()}"
        fun sourceOf(externalId: String): CaptureSource? = externalId.split('|').takeIf { it.size >= 5 && it[0] == "notif" }?.let { CaptureSource.byId(it[1]) }
        fun postedAt(externalId: String): Long? = externalId.split('|').takeIf { it.size >= 5 && it[0] == "notif" }?.get(2)?.toLongOrNull()
    }
}
