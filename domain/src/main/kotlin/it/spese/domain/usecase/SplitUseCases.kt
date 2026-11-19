package it.spese.domain.usecase

import it.spese.core.model.TransactionSplit
import it.spese.domain.finance.CashQuotaEngine
import it.spese.domain.finance.SplitExpander
import it.spese.domain.repository.*
import java.math.BigDecimal
import javax.inject.Inject

/** Applica le quote fisse in contanti ai prelievi (una volta al mese, sul primo che le copre). Restituisce quanti movimenti ha diviso. */
class ApplyCashQuotasUseCase @Inject constructor(
    private val tx: TransactionRepository, private val quotas: CashQuotaRepository, private val categories: CategoryRepository, private val settings: SettingsRepository,
) {
    suspend operator fun invoke(): Int {
        val q = quotas.getAll().filter { it.enabled }
        if (q.isEmpty()) return 0
        val ledger = tx.getAll()
        val splits = tx.getSplits().groupBy { it.txId }
        val plan = CashQuotaEngine.plan(ledger, splits, categories.getAll().associateBy { it.id }, q, it.spese.core.common.MonthCalendar(settings.get().monthStartDay.coerceIn(1, 28)))
        plan.forEach { tx.replaceSplits(it.txId, it.parts) }
        return plan.size
    }
}

/** Parte di una divisione scelta dall'utente; con `quotaId` conta come applicazione di quella quota (il motore non la ripete nel periodo). */
data class SplitPartInput(val categoryId: Long?, val amount: BigDecimal, val quotaId: Long? = null)

/** Divisione manuale di un movimento su più categorie (o ricomposizione, con lista vuota). Il movimento risulta controllato. */
class SetSplitsUseCase @Inject constructor(private val tx: TransactionRepository) {
    suspend operator fun invoke(txId: Long, parts: List<SplitPartInput>) {
        val t = tx.get(txId) ?: error("Movimento non trovato")
        if (parts.isNotEmpty()) SplitExpander.validate(t.amount, parts.map { it.amount })?.let { error(it) }
        tx.replaceSplits(txId, parts.mapIndexed { i, p -> TransactionSplit(0, txId, p.categoryId, p.amount, i, auto = false, quotaId = p.quotaId) })
        tx.setReviewed(listOf(txId), true)
    }
}

/** "Conferma" nella sezione Verifica: i movimenti escono dall'elenco. */
class ConfirmReviewUseCase @Inject constructor(private val tx: TransactionRepository) {
    /** Conferma i movimenti; quelli senza conto restano in Verifica finché non si sceglie il conto. */
    suspend operator fun invoke(ids: Collection<Long>, reviewed: Boolean = true) {
        val ok = if (reviewed) ids.filter { tx.get(it)?.accountId != null } else ids.toList()
        if (ok.isNotEmpty()) tx.setReviewed(ok, reviewed)
    }
}

/** Sceglie il mese contabile di un'entrata (null = quello della data); la scelta vale come conferma. */
class SetAccountingMonthUseCase @Inject constructor(private val tx: TransactionRepository) {
    suspend operator fun invoke(ids: Collection<Long>, month: java.time.YearMonth?) {
        val ok = ids.filter { id -> tx.get(id)?.kind == it.spese.core.model.TxKind.ENTRATA }
        if (ok.isEmpty()) return
        tx.setAccountingMonth(ok, month)
        if (month != null) tx.setReviewed(ok, true)
    }
}
