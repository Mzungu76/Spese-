package it.spese.domain.sharing

import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.domain.repository.NewTransaction
import java.time.Instant
import java.time.ZoneId

/**
 * Trasforma le spese rilevate sul telefono dell'altra persona in movimenti provvisori del conto condiviso. L'identificativo è «share|id»:
 * importarle due volte non crea doppioni, e quando arriva l'estratto conto la riga vera sostituisce quella provvisoria.
 */
object ShareEventImporter {
    fun toNewTransactions(events: List<ShareEvent>, accountId: Long, zone: ZoneId = ZoneId.systemDefault()): List<NewTransaction> =
        events.filter { it.amount.signum() != 0 }.distinctBy { it.id }.map { e ->
            val day = Instant.ofEpochMilli(e.atMillis).atZone(zone).toLocalDate()
            val key = "share|" + e.id
            NewTransaction(
                key, key, accountId, day, day, e.description.trim().ifBlank { "Spesa dal telefono condiviso" }.take(120), e.amount,
                if (e.amount.signum() < 0) TxKind.SPESA else TxKind.ENTRATA, null, false, null,
                notes = "Rilevato dal telefono condiviso" + if (e.source.isNotBlank()) " (${e.source})" else "", origin = TxOrigin.NOTIFICATION,
            )
        }
}
