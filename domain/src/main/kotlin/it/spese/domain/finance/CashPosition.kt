package it.spese.domain.finance

import it.spese.core.model.AccountType
import it.spese.core.model.Goal
import it.spese.core.model.Valuation
import it.spese.domain.networth.NetWorthEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate

/** Soldi sui conti liquidi e, di questi, quelli bloccati negli obiettivi: spendibile = saldo − bloccato. */
data class CashPosition(val balance: BigDecimal, val blocked: BigDecimal) { val spendable: BigDecimal get() = balance - blocked }

object CashCalculator {
    /**
     * [accountIds] = i conti da guardare (null = tutti i liquidi non gestiti da altri). Il bloccato è quello degli obiettivi legati a quei conti,
     * mai oltre il saldo del conto; senza una selezione (o con tutti i conti liquidi selezionati) si aggiunge quello degli obiettivi senza conto, fino a ciò che resta.
     */
    fun compute(data: AppData, accountIds: Set<Long>?, goals: List<Goal>, valuations: List<Valuation>, today: LocalDate): CashPosition? {
        val accounts = data.accounts.filter { a -> !a.archived && a.type != AccountType.INVESTIMENTO && (if (accountIds == null) a.id !in data.managedAccountIds else a.id in accountIds) }
        if (accounts.isEmpty()) return null
        val tx = data.everything
        val bal = accounts.associate { a -> a.id to NetWorthEngine.balanceAt(a, tx, valuations, today) }
        val total = bal.values.fold(BigDecimal.ZERO) { s, v -> s + v }
        val active = goals.filter { g -> g.enabled && g.saved.signum() > 0 }
        val linked = accounts.fold(BigDecimal.ZERO) { s, a ->
            s + active.filter { g -> g.accountId == a.id }.fold(BigDecimal.ZERO) { x, g -> x + g.saved }.min(bal.getValue(a.id).max(BigDecimal.ZERO))
        }
        // gli obiettivi senza conto bloccano soldi se non c'è una selezione, o se la selezione comprende tutti i conti liquidi (come la Home)
        val everyLiquid = accountIds == null || data.accounts.filter { a -> !a.archived && a.type != AccountType.INVESTIMENTO && a.id !in data.managedAccountIds }.all { a -> a.id in accountIds }
        val loose = if (!everyLiquid) BigDecimal.ZERO else active.filter { g -> g.accountId == null || accounts.none { a -> a.id == g.accountId } && data.accountsById[g.accountId]?.type != AccountType.INVESTIMENTO }.fold(BigDecimal.ZERO) { s, g -> s + g.saved }
        val blocked = (linked + loose).min(total.max(BigDecimal.ZERO))
        return CashPosition(total, blocked)
    }
}
