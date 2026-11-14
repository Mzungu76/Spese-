package it.spese.domain.finance

import it.spese.core.model.Category
import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.core.model.TxKind

/**
 * Per le analisi un movimento diviso diventa una "parte" per ogni categoria: stesso id, data e descrizione del movimento,
 * importo e categoria della parte. La somma delle parti è l'importo del movimento, quindi saldi e totali non cambiano.
 * Le parti sono sempre "categoria scelta" (non vanno tra i movimenti da classificare).
 */
object SplitExpander {
    fun expand(ledger: List<Transaction>, splits: Map<Long, List<TransactionSplit>>, categories: Map<Long, Category>): List<Transaction> {
        if (splits.isEmpty()) return ledger
        return ledger.flatMap { t ->
            val parts = splits[t.id]
            if (parts.isNullOrEmpty()) listOf(t)
            else parts.sortedBy { it.position }.map { p ->
                t.copy(amount = p.amount, categoryId = p.categoryId, kind = p.categoryId?.let { categories[it]?.kind } ?: t.kind, manualCategory = true, ruleId = null)
            }
        }
    }

    /** I movimenti divisi hanno almeno due parti e la somma uguale all'importo: `null` se tutto torna, altrimenti il motivo. */
    fun validate(amount: java.math.BigDecimal, parts: List<java.math.BigDecimal>): String? = when {
        parts.size < 2 -> "Servono almeno due parti."
        parts.any { it.signum() == 0 } -> "Ogni parte deve avere un importo."
        parts.any { it.signum() != amount.signum() } -> "Le parti devono avere lo stesso segno del movimento."
        parts.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }.compareTo(amount) != 0 -> "La somma delle parti deve essere uguale all'importo del movimento."
        else -> null
    }
}
