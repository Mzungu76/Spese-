package it.spese.domain.finance

import it.spese.core.model.CashQuota
import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.core.model.TxKind
import java.time.YearMonth

/**
 * Quote fisse in contanti: una volta al mese, il primo prelievo che copre l'importo della quota viene diviso
 * (quota → categoria della quota, resto → categoria del prelievo). Mai su movimenti già divisi o già controllati dall'utente.
 */
object CashQuotaEngine {
    data class Application(val txId: Long, val quota: CashQuota, val parts: List<TransactionSplit>)

    private val WITHDRAWAL = Regex("""prelievo|prelev\b|prelev\.|\batm\b|bancomat""", RegexOption.IGNORE_CASE)

    fun isWithdrawal(t: Transaction, categories: Map<Long, Category>): Boolean =
        t.kind == TxKind.SPESA && t.amount.signum() < 0 &&
            (WITHDRAWAL.containsMatchIn(t.description) || categories[t.categoryId]?.name.equals("Contanti", ignoreCase = true))

    fun plan(ledger: List<Transaction>, splits: Map<Long, List<TransactionSplit>>, categories: Map<Long, Category>, quotas: List<CashQuota>): List<Application> {
        val taken = HashSet<Long>()                     // movimenti già divisi (da prima o in questo giro)
        val applied = HashSet<Pair<Long, YearMonth>>()  // (quota, mese) già applicati
        splits.forEach { (txId, parts) ->
            taken.add(txId)
            val month = ledger.firstOrNull { it.id == txId }?.let { YearMonth.from(it.bookingDate) }
            parts.mapNotNull { it.quotaId }.forEach { q -> if (month != null) applied.add(q to month) }
        }
        val candidates = ledger.filter { !it.reviewed && it.id !in taken && isWithdrawal(it, categories) }.sortedWith(compareBy({ it.bookingDate }, { it.id }))
        val out = ArrayList<Application>()
        for (q in quotas.filter { it.enabled && it.amount.signum() > 0 && categories.containsKey(it.categoryId) }.sortedBy { it.id }) {
            for (month in candidates.map { YearMonth.from(it.bookingDate) }.distinct()) {
                if ((q.id to month) in applied) continue
                val t = candidates.firstOrNull { it.id !in taken && YearMonth.from(it.bookingDate) == month && it.amount.abs() >= q.amount } ?: continue
                val rest = t.amount + q.amount   // negativo o zero
                val parts = buildList {
                    add(TransactionSplit(0, t.id, q.categoryId, q.amount.negate(), 0, auto = true, quotaId = q.id))
                    if (rest.signum() != 0) add(TransactionSplit(0, t.id, t.categoryId, rest, 1, auto = true, quotaId = q.id))
                }
                out.add(Application(t.id, q, parts)); taken.add(t.id); applied.add(q.id to month)
            }
        }
        return out
    }
}
