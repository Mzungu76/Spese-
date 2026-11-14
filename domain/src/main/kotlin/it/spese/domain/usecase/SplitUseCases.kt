package it.spese.domain.usecase

import it.spese.core.model.TransactionSplit
import it.spese.domain.finance.CashQuotaEngine
import it.spese.domain.finance.SplitExpander
import it.spese.domain.repository.*
import java.math.BigDecimal
import javax.inject.Inject

/** Applica le quote fisse in contanti ai prelievi (una volta al mese, sul primo che le copre). Restituisce quanti movimenti ha diviso. */
class ApplyCashQuotasUseCase @Inject constructor(
    private val tx: TransactionRepository, private val quotas: CashQuotaRepository, private val categories: CategoryRepository,
) {
    suspend operator fun invoke(): Int {
        val q = quotas.getAll().filter { it.enabled }
        if (q.isEmpty()) return 0
        val ledger = tx.getAll()
        val splits = tx.getSplits().groupBy { it.txId }
        val plan = CashQuotaEngine.plan(ledger, splits, categories.getAll().associateBy { it.id }, q)
        plan.forEach { tx.replaceSplits(it.txId, it.parts) }
        return plan.size
    }
}

/** Divisione manuale di un movimento su più categorie (o ricomposizione, con lista vuota). Il movimento risulta controllato. */
class SetSplitsUseCase @Inject constructor(private val tx: TransactionRepository) {
    suspend operator fun invoke(txId: Long, parts: List<Pair<Long?, BigDecimal>>) {
        val t = tx.get(txId) ?: error("Movimento non trovato")
        if (parts.isNotEmpty()) SplitExpander.validate(t.amount, parts.map { it.second })?.let { error(it) }
        tx.replaceSplits(txId, parts.mapIndexed { i, p -> TransactionSplit(0, txId, p.first, p.second, i) })
        tx.setReviewed(listOf(txId), true)
    }
}

/** "Conferma" nella sezione Verifica: i movimenti escono dall'elenco. */
class ConfirmReviewUseCase @Inject constructor(private val tx: TransactionRepository) {
    suspend operator fun invoke(ids: Collection<Long>, reviewed: Boolean = true) = tx.setReviewed(ids, reviewed)
}
