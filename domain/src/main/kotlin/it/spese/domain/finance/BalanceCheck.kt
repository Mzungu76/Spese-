package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Account
import it.spese.core.model.Transaction
import it.spese.core.model.TxOrigin
import it.spese.domain.classification.Duplicates
import it.spese.domain.networth.NetWorthEngine
import java.math.BigDecimal
import java.time.LocalDate

enum class CauseKind { PROVISIONAL, DUPLICATES, UNASSIGNED, OPENING }

/** Una possibile causa della differenza. `explainsAll` = da sola spiega tutta la differenza (al centesimo). */
data class BalanceCause(val kind: CauseKind, val text: String, val amount: BigDecimal, val count: Int, val explainsAll: Boolean)

/** Confronto tra il saldo dichiarato dalla banca e quello calcolato dall'app (saldo iniziale + movimenti fino alla data). */
data class BalanceCheck(
    val account: Account, val asOf: LocalDate, val declared: BigDecimal, val computed: BigDecimal,
    /** dichiarato − calcolato: negativo = nell'app ci sono più soldi che in banca. */
    val difference: BigDecimal, val causes: List<BalanceCause>, val suggestedOpening: BigDecimal,
) { val matches: Boolean get() = difference.abs() < BigDecimal("0.01") }

object BalanceCheckEngine {
    private val CENT = BigDecimal("0.01")
    private val PROVISIONAL = setOf(TxOrigin.NOTIFICATION, TxOrigin.RECURRING, TxOrigin.OCR)

    private fun sum(l: List<Transaction>) = l.fold(BigDecimal.ZERO) { a, t -> a + t.amount }

    /** `tx`: movimenti interi con conto (non le parti delle divisioni); `unassigned`: quelli senza conto (non contano nei saldi ma potrebbero essere di questo conto). */
    fun check(account: Account, tx: List<Transaction>, unassigned: List<Transaction>, declared: BigDecimal, asOf: LocalDate, valuations: List<it.spese.core.model.Valuation> = emptyList()): BalanceCheck {
        val computed = NetWorthEngine.balanceAt(account, tx, valuations, asOf)
        val diff = declared - computed
        val mine = tx.filter { it.accountId == account.id && !it.bookingDate.isAfter(asOf) }
        val causes = ArrayList<BalanceCause>()
        fun explains(adjust: BigDecimal) = (diff + adjust).abs() < CENT       // se la causa sparisse, la differenza diventerebbe diff + adjust

        val prov = mine.filter { it.origin in PROVISIONAL }
        if (prov.isNotEmpty()) { val s = sum(prov)
            causes.add(BalanceCause(CauseKind.PROVISIONAL, "${prov.size} movimenti provvisori (da notifica, ricorrenza o scontrino) non ancora sostituiti dalla riga dell'estratto conto: somma ${Money.eur(s)}. Se la banca li ha già addebitati, reimporta l'estratto; altrimenti sono in più.", s, prov.size, explains(s))) }
        val dupExtra = Duplicates.findProbable(mine).flatMap { it.items.drop(1) }
        if (dupExtra.isNotEmpty()) { val s = sum(dupExtra)
            causes.add(BalanceCause(CauseKind.DUPLICATES, "${dupExtra.size} possibili doppioni (stesso importo e data, descrizione uguale o simile): somma ${Money.eur(s)}. Controllali in Movimenti: due acquisti identici nello stesso giorno sono legittimi.", s, dupExtra.size, explains(s))) }
        val loose = unassigned.filter { !it.bookingDate.isAfter(asOf) }
        if (loose.isNotEmpty()) { val s = sum(loose)
            causes.add(BalanceCause(CauseKind.UNASSIGNED, "${loose.size} movimenti senza conto (somma ${Money.eur(s)}) sono fuori dai saldi: se appartengono a questo conto, assegnali da Movimenti › Verifica.", s, loose.size, (diff - s).abs() < CENT)) }
        // provvisori + doppioni insieme
        if (prov.isNotEmpty() && dupExtra.isNotEmpty() && causes.none { it.explainsAll } && explains(sum(prov) + sum(dupExtra)))
            causes.add(0, BalanceCause(CauseKind.PROVISIONAL, "I provvisori e i possibili doppioni insieme spiegano tutta la differenza.", sum(prov) + sum(dupExtra), prov.size + dupExtra.size, true))
        val suggested = account.openingBalance + diff
        if (!diff.abs().let { it < CENT })
            causes.add(BalanceCause(CauseKind.OPENING, "Se tutti i movimenti sono giusti, il saldo iniziale dovrebbe essere ${Money.eur(suggested)} (ora ${Money.eur(account.openingBalance)}).", diff, 0, causes.none { it.explainsAll }))
        return BalanceCheck(account, asOf, declared, computed, diff, causes.sortedByDescending { it.explainsAll }, suggested)
    }
}
