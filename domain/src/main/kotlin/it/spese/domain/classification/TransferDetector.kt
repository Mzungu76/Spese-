package it.spese.domain.classification

import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import kotlin.math.abs

/**
 * Riconoscimento dei giroconti tra conti: due movimenti su conti diversi, con importi opposti e date vicine.
 * Il risultato è un suggerimento: l'utente conferma prima che i movimenti vengano marcati come trasferimenti.
 */
object TransferDetector {
    data class Pair(val outgoing: Transaction, val incoming: Transaction, val daysApart: Int)

    fun detect(list: List<Transaction>, maxDays: Int = 3): List<Pair> {
        val candidates = list.filter { it.kind != TxKind.TRASFERIMENTO && it.amount.signum() != 0 }
        val outs = candidates.filter { it.amount.signum() < 0 }
        val ins = candidates.filter { it.amount.signum() > 0 }.groupBy { it.amount.setScale(2) }
        val used = HashSet<Long>()
        val result = ArrayList<Pair>()
        for (o in outs.sortedBy { it.bookingDate }) {
            val matches = ins[o.amount.negate().setScale(2)].orEmpty()
                .filter { it.id !in used && it.accountId != o.accountId }
                .map { it to abs(java.time.temporal.ChronoUnit.DAYS.between(o.bookingDate, it.bookingDate)).toInt() }
                .filter { it.second <= maxDays }
                .minByOrNull { it.second } ?: continue
            used.add(matches.first.id); used.add(o.id)
            result.add(Pair(o, matches.first, matches.second))
        }
        return result
    }
}
