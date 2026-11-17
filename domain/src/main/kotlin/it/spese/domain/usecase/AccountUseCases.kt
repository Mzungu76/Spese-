package it.spese.domain.usecase

import it.spese.domain.capture.CaptureConfig
import it.spese.domain.repository.AccountRepository
import it.spese.domain.repository.BackupRepository
import it.spese.domain.repository.CaptureConfigRepository
import it.spese.domain.repository.RecurringRepository
import it.spese.domain.repository.TransactionRepository
import it.spese.domain.repository.ValuationRepository
import java.math.BigDecimal
import javax.inject.Inject

/** Che ne è dei movimenti di un conto che si elimina. Non esiste un posto dove «parcheggiarli»: o cambiano conto, o restano senza conto in Verifica, o si cancellano. */
enum class AccountDeletion { MOVE_TO_ACCOUNT, TO_REVIEW, DELETE_MOVEMENTS }

/** Cosa tocca l'eliminazione di un conto: da mostrare nella conferma. */
data class AccountImpact(val movements: Int, val income: BigDecimal, val expense: BigDecimal, val recurringRules: Int, val captureSources: Int, val valuations: Int)

object AccountImpactCalculator {
    fun of(accountId: Long, tx: List<it.spese.core.model.Transaction>, rules: List<it.spese.core.model.RecurringRule>, capture: CaptureConfig, valuations: List<it.spese.core.model.Valuation>): AccountImpact {
        val mine = tx.filter { it.accountId == accountId }
        return AccountImpact(
            mine.size, mine.filter { it.amount.signum() > 0 }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }, mine.filter { it.amount.signum() < 0 }.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() },
            rules.count { it.accountId == accountId }, capture.sources.values.count { it.accountId == accountId } + capture.pending.count { it.accountId == accountId }, valuations.count { it.accountId == accountId },
        )
    }
}

/**
 * Elimina un conto senza mai perdere dati in silenzio: se ha movimenti si sceglie esplicitamente cosa farne, e prima si salva uno snapshot
 * per «Annulla». Ricorrenze, fonti di notifica e valori collegati al conto restano senza conto (i nuovi movimenti andranno in Verifica).
 */
class DeleteAccountUseCase @Inject constructor(
    private val tx: TransactionRepository, private val accounts: AccountRepository, private val assign: AssignAccountUseCase,
    private val recurring: RecurringRepository, private val capture: CaptureConfigRepository, private val valuations: ValuationRepository, private val backup: BackupRepository,
) {
    suspend fun impact(accountId: Long): AccountImpact = AccountImpactCalculator.of(accountId, tx.getAll(), recurring.getAll(), capture.get(), valuations.getAll())

    /** Restituisce quanti movimenti sono stati spostati, mandati in Verifica o cancellati. */
    suspend operator fun invoke(accountId: Long, mode: AccountDeletion, targetAccountId: Long? = null): Int {
        accounts.getAll().firstOrNull { it.id == accountId } ?: error("Conto inesistente")
        val mine = tx.getAll().filter { it.accountId == accountId }
        if (mode == AccountDeletion.MOVE_TO_ACCOUNT) {
            require(targetAccountId != null && targetAccountId != accountId) { "Scegli il conto su cui spostare i movimenti." }
            accounts.getAll().firstOrNull { it.id == targetAccountId } ?: error("Il conto di destinazione non esiste.")
        }
        // snapshot di sicurezza PRIMA di toccare qualunque dato (l'eliminazione si può annullare)
        backup.saveUndoSnapshot()
        when (mode) {
            AccountDeletion.MOVE_TO_ACCOUNT -> assign(mine.map { it.id }, targetAccountId)
            AccountDeletion.TO_REVIEW -> assign(mine.map { it.id }, null)
            AccountDeletion.DELETE_MOVEMENTS -> if (mine.isNotEmpty()) tx.delete(mine.map { it.id })
        }
        // riferimenti al conto: restano senza conto invece di puntare a un conto che non c'è più
        recurring.getAll().filter { it.accountId == accountId }.forEach { recurring.save(it.copy(accountId = null)) }
        capture.update { c -> c.copy(
            sources = c.sources.mapValues { (_, s) -> if (s.accountId == accountId) s.copy(accountId = null) else s },
            pending = c.pending.map { p -> if (p.accountId == accountId) p.copy(accountId = null) else p },
        ) }
        valuations.getAll().filter { it.accountId == accountId }.forEach { valuations.delete(it.accountId, it.date) }
        check(accounts.deleteIfEmpty(accountId)) { "Il conto ha ancora movimenti: non è stato eliminato." }
        return mine.size
    }
}
