package it.spese.domain.finance

import it.spese.core.model.TxKind
import it.spese.domain.repository.NewTransaction
import java.math.BigDecimal

/** «Riallinea saldo»: un solo movimento per la differenza tra il saldo reale e quello dell'app, così il saldo torna e il buco resta visibile. */
object BalanceReconciler {
    const val DESCRIPTION = "Riallineamento saldo (spese non tracciate)"

    /** `null` se il saldo già torna. L'importo ha il segno della differenza: negativo = nell'app c'era di più, quindi è una spesa non registrata. */
    fun adjustment(check: BalanceCheck, nowMillis: Long): NewTransaction? {
        if (check.matches) return null
        val amount = check.difference
        val key = "reconcile|${check.account.id}|${check.asOf}|${amount.toPlainString()}|$nowMillis"
        return NewTransaction(
            key, key, check.account.id, check.asOf, check.asOf, DESCRIPTION, amount, if (amount.signum() < 0) TxKind.SPESA else TxKind.ENTRATA, null, false, null,
            notes = "Saldo dichiarato ${check.declared.toPlainString()} contro ${check.computed.toPlainString()} calcolato dall'app al ${check.asOf}",
        )
    }
}
