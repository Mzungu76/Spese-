package it.spese.domain.sharing

import it.spese.domain.finance.PaceEngine
import it.spese.domain.finance.Period
import it.spese.domain.finance.CategoryTree
import it.spese.domain.finance.DailyBudget
import it.spese.domain.finance.DailyBudgetEngine
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.math.RoundingMode
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import java.time.LocalDate

/** Costruisce ciò che si pubblica per l'altra persona: il ritmo di spesa del conto condiviso (anche se è «gestito da altri») e il suo limite. */
object ShareSnapshotBuilder {
    /**
     * I tre periodi che l'altra persona può scegliere: settimana, mese e trimestre (quelli che non si possono calcolare mancano).
     * Il limite viene dal budget giornaliero (entrate prudenti − risparmio degli obiettivi − margine; la cifra al giorno toglie anche le uscite note da pagare)
     * ed è lo stesso per tutti i periodi: settimana e trimestre lo scalano, la cifra al giorno è sempre quella vera.
     */
    fun buildAll(data: AppData, accountIds: List<Long>, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now(), thread: List<ThreadMsg> = emptyList(), inputs: BudgetInputs = BudgetInputs()): List<ShareSnapshot> {
        val budget = budgetFor(data, accountIds, cfg, realToday, inputs)
        if (accountIds.none { id -> data.accountsById[id] != null }) return emptyList()
        val cfg2 = if (budget?.limit != null) cfg.copy(budgetMonthly = budget.limit) else cfg
        return listOf(Period.CurrentWeek, Period.CurrentMonth, Period.Last3Months).mapNotNull { p ->
            build(data, accountIds, cfg2, nowMillis, realToday, p, thread)?.let { s ->
                if (budget == null) s else s.copy(dailyRemaining = budget.perDay, upcomingTotal = budget.upcomingTotal, reserve = budget.reserve, incomeExpected = budget.expectedIncome, savings = budget.savings, fixedPaid = budget.fixedPaid)
            }
        }
    }

    /**
     * Il budget giornaliero dei soli conti condivisi: i loro movimenti, le loro ricorrenze e i loro abbonamenti.
     * Le scadenze non sono legate a un conto e restano fuori; il risparmio degli obiettivi vale se non lo si fissa a mano (0 = nessuno).
     */
    fun budgetFor(data: AppData, accountIds: List<Long>, cfg: ShareConfig, realToday: LocalDate = LocalDate.now(), inputs: BudgetInputs = BudgetInputs()): DailyBudget? {
        val ids = accountIds.distinct().filter { id -> data.accountsById[id] != null }.toSet()
        if (ids.isEmpty()) return null
        val scoped = data.copy(transactions = data.everything.filter { t -> t.accountId in ids }, dues = emptyList())
        val rules = inputs.rules.filter { r -> r.accountId in ids }
        return DailyBudgetEngine.compute(scoped, rules, inputs.ignoredPatterns, inputs.goals, inputs.incomeOverrides, inputs.marginPct, cfg.budgetMonthly, realToday, cfg.savingsMonthly)
    }

    fun build(data: AppData, accountId: Long, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now()): ShareSnapshot? = build(data, listOf(accountId), cfg, nowMillis, realToday)

    /** Con più conti il budget e il ritmo sono quelli dei loro movimenti insieme (le spese e le entrate di tutti i conti scelti). */
    fun build(data: AppData, accountIds: List<Long>, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now(), period: Period = Period.CurrentMonth, thread: List<ThreadMsg> = emptyList()): ShareSnapshot? {
        val accounts = accountIds.distinct().mapNotNull { id -> data.accountsById[id] }
        if (accounts.isEmpty()) return null
        val ids = accounts.map { a -> a.id }.toSet()
        val title = if (accounts.size <= 2) accounts.joinToString(" + ") { a -> a.name } else "${accounts.size} conti"
        // i movimenti dei conti gestiti da altri stanno fuori dalle analisi: qui servono, perché sono proprio quei conti
        val all = data.copy(transactions = data.everything.filter { t -> t.accountId in ids })
        val r = PaceEngine.compute(all, TxFilter(period = period, calendar = data.calendar), realToday, emptySet(), cfg.savingsMonthly, cfg.budgetMonthly, budgetIsComputed = true) ?: return null
        return ShareSnapshot(
            generatedAtMillis = nowMillis, title = title, unit = r.unit.name, periodStart = r.start, periodEnd = r.end, today = r.today, days = r.days, dayIndex = r.dayIndex,
            limit = r.optimalTotal, spentToDate = r.spentToDate, optimalToDate = r.optimalToDate, dailyRemaining = r.optimalDailyRemaining, dailyAverage = r.optimalDailyAverage,
            current = r.current, average = r.average, optimal = r.optimal, features = cfg.features, note = cfg.note,
            recent = recent(data, ids), categories = categories(all, r.start, r.end), previousTotal = r.pastWindows.lastOrNull()?.total, thread = thread,
        )
    }

    /** Le ultime spese dei conti condivisi (importo intero, anche se divise), dalla più recente. */
    private fun recent(data: AppData, ids: Set<Long>): List<ShareRecent> =
        data.ledger.filter { t -> t.accountId in ids && t.kind == TxKind.SPESA }.sortedWith(compareByDescending<it.spese.core.model.Transaction> { t -> t.bookingDate }.thenByDescending { t -> t.id })
            .take(RECENT).map { t -> ShareRecent(t.bookingDate, t.merchant.take(60), t.amount, data.accountName(t.accountId)) }

    /** Le tre categorie principali del periodo e «Tutto il resto», in percentuale della spesa. */
    private fun categories(d: AppData, from: LocalDate, to: LocalDate): List<ShareCategory> {
        val spent = d.transactions.filter { t -> t.kind == TxKind.SPESA && !t.bookingDate.isBefore(from) && !t.bookingDate.isAfter(to) }
        val total = spent.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() }
        if (total.signum() <= 0) return emptyList()
        val byRoot = spent.groupBy { t -> CategoryTree.rootId(t.categoryId, d.categoriesById) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() } }
        val top = byRoot.entries.sortedByDescending { e -> e.value }.take(3)
        val out = top.map { e -> ShareCategory(d.categoryName(e.key), pct(e.value, total)) }.toMutableList()
        val rest = 100 - out.sumOf { c -> c.percent }
        if (byRoot.size > 3 && rest > 0) out.add(ShareCategory("Tutto il resto", rest))
        return out
    }

    private fun pct(v: BigDecimal, total: BigDecimal) = v.multiply(BigDecimal(100)).divide(total, 0, RoundingMode.HALF_UP).toInt()
    private const val RECENT = 15
}
