package it.spese.domain.classification

import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Riconoscimento dei giroconti tra conti: due movimenti su conti diversi, con importi opposti (o quasi: una piccola commissione) e date vicine.
 * Il risultato è un suggerimento: l'utente conferma prima che i movimenti vengano marcati come trasferimenti.
 */
object TransferDetector {
    /** `fee` = quanto è uscito in più di quanto è entrato (commissione), zero se gli importi coincidono. */
    data class Pair(val outgoing: Transaction, val incoming: Transaction, val daysApart: Int, val fee: BigDecimal = BigDecimal.ZERO) {
        /** Chiave stabile della coppia (identificativi dei due movimenti), per ricordare «non è un giroconto». */
        val key: String get() = outgoing.externalId + ">" + incoming.externalId
    }

    private val MIN_FEE = BigDecimal("0.50")
    private val MAX_FEE = BigDecimal("2.00")

    /** Commissione ammessa: l'1% dell'importo, ma tra 0,50 € e 2,00 €. */
    fun feeAllowed(amount: BigDecimal): BigDecimal = amount.multiply(BigDecimal("0.01")).max(MIN_FEE).min(MAX_FEE)

    fun detect(list: List<Transaction>, maxDays: Int = 3, withFees: Boolean = true): List<Pair> {
        val candidates = list.filter { it.kind != TxKind.TRASFERIMENTO && it.amount.signum() != 0 }
        val outs = candidates.filter { it.amount.signum() < 0 }.sortedBy { it.bookingDate }
        val ins = candidates.filter { it.amount.signum() > 0 }.groupBy { it.bookingDate.toEpochDay() }
        val used = HashSet<Long>()
        val result = ArrayList<Pair>()
        for (o in outs) {
            val out = o.amount.negate()
            val maxFee = if (withFees) feeAllowed(out) else BigDecimal.ZERO
            var best: Pair? = null
            for (d in -maxDays..maxDays) for (i in ins[o.bookingDate.toEpochDay() + d].orEmpty()) {
                if (i.id in used || i.accountId == o.accountId) continue
                val fee = out - i.amount
                if (fee.signum() < 0 || fee > maxFee) continue
                val cand = Pair(o, i, abs(d), fee)
                val b = best
                // meglio l'importo identico, poi la data più vicina; a parità resta il primo trovato
                if (b == null || cand.fee < b.fee || (cand.fee.compareTo(b.fee) == 0 && cand.daysApart < b.daysApart)) best = cand
            }
            best?.let { used.add(it.incoming.id); used.add(o.id); result.add(it) }
        }
        return result
    }
}
