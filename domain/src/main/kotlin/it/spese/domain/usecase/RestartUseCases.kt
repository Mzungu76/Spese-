package it.spese.domain.usecase

import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.core.model.TxOrigin
import it.spese.domain.repository.BackupRepository
import it.spese.domain.repository.TransactionRepository
import javax.inject.Inject

/**
 * Cosa succede ai movimenti se si «ricomincia dagli estratti»:
 * - `withDetails`: hanno i dettagli di uno scontrino (foto) o una divisione scelta dall'utente: si tengono e tornano «in attesa dell'estratto»;
 * - `provisional`: movimenti da notifica o ricorrenza, già pensati per essere sostituiti dall'estratto: si tengono;
 * - `manual`: inseriti a mano (es. spese in contanti): l'estratto non li contiene, si tengono se richiesto;
 * - `toDelete`: tutto il resto (righe importate da estratto o da backup senza dettagli, e i manuali se non si tengono).
 */
data class RestartPlan(val withDetails: List<Transaction>, val provisional: List<Transaction>, val manual: List<Transaction>, val toDelete: List<Transaction>)

object RestartPlanner {
    private fun hasDetails(t: Transaction, splits: Map<Long, List<TransactionSplit>>) = t.documentId != null || splits[t.id].orEmpty().any { !it.auto }

    fun plan(all: List<Transaction>, splits: Map<Long, List<TransactionSplit>>, keepManual: Boolean): RestartPlan {
        val details = ArrayList<Transaction>(); val prov = ArrayList<Transaction>(); val manual = ArrayList<Transaction>(); val del = ArrayList<Transaction>()
        for (t in all) when {
            hasDetails(t, splits) -> details.add(t)
            t.origin == TxOrigin.NOTIFICATION || t.origin == TxOrigin.RECURRING -> prov.add(t)
            t.origin == TxOrigin.MANUAL && keepManual -> manual.add(t)
            else -> del.add(t)
        }
        return RestartPlan(details, prov, manual, del)
    }
}

data class RestartResult(val converted: Int, val deleted: Int, val keptProvisional: Int, val keptManual: Int)

/**
 * «Ricomincia dagli estratti»: cancella le righe importate (che si reimportano) tenendo i dettagli degli scontrini, che tornano «in attesa»:
 * alla reimportazione la riga dell'estratto con lo stesso importo e una data vicina li sostituisce ereditando foto, voci, note e categoria.
 * Conti, categorie, regole, budget e obiettivi non si toccano. Prima si salva uno snapshot per «Annulla».
 */
class RestartFromStatementsUseCase @Inject constructor(private val tx: TransactionRepository, private val backup: BackupRepository) {
    suspend fun preview(keepManual: Boolean): RestartPlan = RestartPlanner.plan(tx.getAll(), tx.getSplits().groupBy { it.txId }, keepManual)

    suspend operator fun invoke(keepManual: Boolean): RestartResult {
        val plan = preview(keepManual)
        backup.saveUndoSnapshot()
        tx.markProvisional(plan.withDetails.map { it.id })
        tx.delete(plan.toDelete.map { it.id })
        return RestartResult(plan.withDetails.size, plan.toDelete.size, plan.provisional.size, plan.manual.size)
    }
}
