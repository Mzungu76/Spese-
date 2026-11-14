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
}

/**
 * Trasforma una notifica delle fonti scelte in un movimento provvisorio, classificato con le regole esistenti.
 * Lo stesso pagamento notificato da due app (es. Google Wallet e la banca) viene registrato una volta sola, tenendo la fonte più precisa.
 */
class CaptureNotificationUseCase @Inject constructor(
    private val tx: TransactionRepository, private val accounts: AccountRepository,
    private val categories: CategoryRepository, private val rules: RuleRepository, private val applyQuotas: ApplyCashQuotasUseCase,
) {
    suspend operator fun invoke(source: CaptureSource, accountId: Long?, raw: RawNotification, zone: ZoneId = ZoneId.systemDefault()): CaptureResult {
        val parsed = NotificationParser.parse(source, raw.title, raw.text) ?: return CaptureResult.Unrecognized
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

    companion object {
        const val TWIN_WINDOW_MS = 3 * 60_000L
        fun externalId(source: CaptureSource, raw: RawNotification, amount: BigDecimal) =
            "notif|${source.id}|${raw.postedAtMs}|${amount.toPlainString()}|${(raw.title + "|" + raw.text).hashCode()}"
        fun sourceOf(externalId: String): CaptureSource? = externalId.split('|').takeIf { it.size >= 5 && it[0] == "notif" }?.let { CaptureSource.byId(it[1]) }
        fun postedAt(externalId: String): Long? = externalId.split('|').takeIf { it.size >= 5 && it[0] == "notif" }?.get(2)?.toLongOrNull()
    }
}
