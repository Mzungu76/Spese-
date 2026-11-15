package it.spese.domain.usecase

import it.spese.core.model.TxOrigin
import it.spese.domain.capture.PendingMatcher
import it.spese.domain.repository.AccountRepository
import it.spese.domain.repository.CaptureConfigRepository
import it.spese.domain.repository.TransactionRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** L'utente inserisce l'importo di un movimento segnalato senza importo: nasce come provvisorio da notifica e sparisce dall'elenco. */
class CompletePendingAmountUseCase @Inject constructor(
    private val saveTx: SaveTransactionUseCase, private val accounts: AccountRepository, private val config: CaptureConfigRepository,
) {
    suspend operator fun invoke(pendingId: Long, amount: BigDecimal, description: String, categoryId: Long?, accountId: Long?, zone: ZoneId = ZoneId.systemDefault()) {
        val p = config.get().pending.firstOrNull { it.id == pendingId } ?: error("Promemoria non trovato")
        require(amount.signum() > 0) { "Inserisci l'importo." }
        val acc = accountId ?: p.accountId ?: accounts.getAll().firstOrNull { !it.archived }?.id ?: accounts.getOrCreate("Conto principale").id
        val date = Instant.ofEpochMilli(p.id).atZone(zone).toLocalDate()
        val label = it.spese.domain.capture.CaptureSource.byId(p.sourceId)?.label ?: p.sourceId
        saveTx(TransactionDraft(0, acc, date, date, description.ifBlank { p.description }, if (p.sign < 0) amount.negate() else amount, categoryId, null,
            "Da notifica · $label · importo inserito a mano", null, TxOrigin.NOTIFICATION))
        config.update { c -> c.copy(pending = c.pending.filterNot { it.id == pendingId }) }
    }
}

/** Dopo un'importazione: i promemoria che trovano una sola riga compatibile (stesso giorno e direzione) si chiudono da soli. Restituisce quanti. */
class ResolvePendingAmountsUseCase @Inject constructor(private val tx: TransactionRepository, private val config: CaptureConfigRepository) {
    suspend operator fun invoke(): Int {
        val pending = config.get().pending
        if (pending.isEmpty()) return 0
        val done = PendingMatcher.resolveByStatement(pending, tx.getAll())
        if (done.isNotEmpty()) config.update { c -> c.copy(pending = c.pending.filterNot { it.id in done }) }
        return done.size
    }
}
