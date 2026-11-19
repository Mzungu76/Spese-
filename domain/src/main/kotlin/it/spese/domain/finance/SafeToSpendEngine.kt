package it.spese.domain.finance

import it.spese.core.common.MonthCalendar
import it.spese.core.model.Due
import it.spese.core.model.Goal
import it.spese.domain.goals.GoalEngine
import it.spese.core.model.RecurringRule
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.recurring.RecurringEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class SafeStatus { OK, BEHIND, OVER, NOT_ENOUGH }

/** Uscita già nota che deve ancora arrivare nel periodo (ricorrenza, abbonamento/rata, scadenza). */
data class UpcomingOutflow(val name: String, val amount: BigDecimal, val date: LocalDate, val source: Source) {
    enum class Source(val label: String) { RECURRING("Ricorrenza"), SUBSCRIPTION("Abbonamento o rata"), DUE("Scadenza") }
}

/**
 * «Quanto posso spendere?»: ciò che resta del mese dopo aver tolto la spesa già fatta, le uscite note ancora da pagare e la quota
 * da mettere da parte. `available = variableBudget − variableSpent`, dove le spese di natura «fissa» già pagate stanno fuori dal confronto.
 */
data class SafeToSpendReport(
    val today: LocalDate, val periodStart: LocalDate, val periodEnd: LocalDate,
    /** Giorni che restano compreso oggi, e giorni del periodo. */
    val daysLeft: Int, val daysTotal: Int,
    val expectedIncome: BigDecimal, val incomeToDate: BigDecimal, val incomeEstimated: Boolean,
    val savingsTarget: BigDecimal,
    val spentToDate: BigDecimal, val fixedPaid: BigDecimal,
    val upcoming: List<UpcomingOutflow>,
    /** Entrate attese − risparmio − uscite fisse (pagate e da pagare): quanto si può spendere in tutto di «variabile». */
    val variableBudget: BigDecimal,
    /** Quanto resta da spendere nel periodo (negativo = già oltre). */
    val available: BigDecimal,
    /** Quanto resta ogni giorno da oggi a fine periodo (null se il periodo è finito). */
    val perDay: BigDecimal?,
    /** Positivo = in anticipo sul piano (hai speso meno del previsto fin qui), negativo = in ritardo. */
    val deltaVsPlan: BigDecimal,
    /** I dati sono fermi a una data passata (ultimo movimento vecchio): la cifra non è quella di oggi. */
    val stale: Boolean,
    val status: SafeStatus,
    /** La quota di risparmio viene dagli obiettivi (altrimenti dall'obiettivo unico del Piano). */
    val savingsFromGoals: Boolean = false,
    /** Margine di sicurezza trattenuto (non spendibile): una quota di ciò che resta dopo risparmio e uscite fisse. */
    val safetyReserve: BigDecimal = BigDecimal.ZERO,
    /** Soldi spendibili sui conti (saldo − bloccato), se il calcolo parte dai saldi; entrate ancora attese e risparmio ancora da mettere da parte. */
    val cashSpendable: BigDecimal? = null, val incomeRemaining: BigDecimal = BigDecimal.ZERO, val savingsDue: BigDecimal = BigDecimal.ZERO,
) {
    val upcomingTotal: BigDecimal get() = upcoming.fold(BigDecimal.ZERO) { a, u -> a + u.amount }
    val variableSpent: BigDecimal get() = spentToDate - fixedPaid
}

object SafeToSpendEngine {
    fun compute(
        data: AppData,
        rules: List<RecurringRule> = emptyList(),
        patterns: List<RecurringPattern> = emptyList(),
        accountId: Long? = null,
        realToday: LocalDate = LocalDate.now(),
        goals: List<Goal> = emptyList(),
        incomeOverrides: Map<Long, it.spese.domain.health.IncomeNature> = emptyMap(),
        marginPct: Int = DailyBudgetEngine.DEFAULT_MARGIN_PCT,
        valuations: List<it.spese.core.model.Valuation> = emptyList(),
        fromBalance: Boolean = false,
    ): SafeToSpendReport? = compute(
        data.transactions.filter { accountId == null || it.accountId == accountId }.filter { it.kind != TxKind.TRASFERIMENTO },
        data, rules, patterns, realToday, goals, incomeOverrides, marginPct, accountId, valuations, fromBalance,
    )

    /** Come [compute], rilevando da sé abbonamenti e rate dai movimenti (senza quelli ignorati dall'utente o già coperti da una ricorrenza). Unico ingresso per Home, notifica e widget. */
    fun forApp(
        data: AppData, rules: List<RecurringRule>, ignoredPatterns: Set<String> = emptySet(), accountId: Long? = null, realToday: LocalDate = LocalDate.now(), goals: List<Goal> = emptyList(),
        incomeOverrides: Map<Long, it.spese.domain.health.IncomeNature> = emptyMap(), marginPct: Int = DailyBudgetEngine.DEFAULT_MARGIN_PCT,
        valuations: List<it.spese.core.model.Valuation> = emptyList(), fromBalance: Boolean = false,
    ): SafeToSpendReport? {
        if (!data.loaded) return null
        val patterns = SubscriptionDetector.detect(data.transactions, SubscriptionDetector.excludedCategories(data.categories), realToday, rules).filter { it.key !in ignoredPatterns }
        return compute(data, rules, patterns, accountId, realToday, goals, incomeOverrides, marginPct, valuations, fromBalance)
    }

    /** La cifra «disponibile oggi» è il budget giornaliero ([DailyBudgetEngine]): entrate prudenti, obiettivi, uscite note e margine; qui solo il confronto con il piano. */
    private fun compute(tx: List<Transaction>, data: AppData, rules: List<RecurringRule>, patterns: List<RecurringPattern>, realToday: LocalDate, goals: List<Goal>, overrides: Map<Long, it.spese.domain.health.IncomeNature>, marginPct: Int, accountId: Long?, valuations: List<it.spese.core.model.Valuation>, fromBalance: Boolean): SafeToSpendReport? {
        val cashSource: ((LocalDate) -> CashPosition?)? = if (fromBalance) { d -> CashCalculator.compute(data, accountId?.let { id -> setOf(id) }, goals, valuations, d) } else null
        val b = DailyBudgetEngine.compute(data.copy(transactions = tx), rules, emptySet(), goals, overrides, marginPct, null, realToday, null, patterns, cashSource) ?: return null
        val limit = b.limit ?: (b.expectedIncome - b.savings)
        val variableBudget = limit - b.fixedPaid - b.upcomingTotal
        val available = limit - b.spent - b.upcomingTotal
        val perDay = if (b.daysLeft <= 0) null else available.divide(BigDecimal(b.daysLeft), 2, RoundingMode.HALF_UP)
        val elapsed = (b.daysTotal - b.daysLeft + 1).coerceIn(1, b.daysTotal)
        val idealToDate = variableBudget.max(BigDecimal.ZERO).multiply(BigDecimal(elapsed)).divide(BigDecimal(b.daysTotal), 2, RoundingMode.HALF_UP)
        val delta = idealToDate - (b.spent - b.fixedPaid)
        val status = when {
            b.cash == null && b.income == null && b.incomeToDate.signum() == 0 && data.goal.expectedIncome == null -> SafeStatus.NOT_ENOUGH
            available.signum() < 0 -> SafeStatus.OVER
            delta.signum() < 0 -> SafeStatus.BEHIND
            else -> SafeStatus.OK
        }
        return SafeToSpendReport(b.today, b.periodStart, b.periodEnd, b.daysLeft, b.daysTotal, b.expectedIncome, b.incomeToDate, b.income == null || b.income.prudent < b.incomeToDate, b.savings,
            b.spent, b.fixedPaid, b.upcoming, variableBudget, available, perDay, delta, b.stale, status, goals.isNotEmpty(), b.reserve, b.cash?.spendable, b.incomeRemaining, b.savingsDue)
    }

    /** Uscite note da oggi a fine periodo: ricorrenze manuali, abbonamenti/rate rilevati, scadenze. Niente doppioni tra ricorrenze e rilevati. */
    fun upcoming(rules: List<RecurringRule>, patterns: List<RecurringPattern>, dues: List<Due>, today: LocalDate, end: LocalDate, start: LocalDate = today): List<UpcomingOutflow> {
        val out = ArrayList<UpcomingOutflow>()
        rules.filter { it.enabled && it.kind == TxKind.SPESA && it.amount.signum() > 0 }.forEach { r ->
            var from = today.minusDays(1)
            repeat(40) {
                val d = RecurringEngine.next(r, from) ?: return@repeat
                if (d.isAfter(end)) return@forEach
                from = d
                if (r.lastGenerated == null || d.isAfter(r.lastGenerated)) out.add(UpcomingOutflow(r.name, r.amount, d, UpcomingOutflow.Source.RECURRING))
            }
        }
        patterns.filter { !it.tracked && !it.stale && !it.finished && it.next <= end && it.last < start }.forEach { p ->
            out.add(UpcomingOutflow(p.merchant, p.lastAmount.max(p.meanAmount), maxOf(p.next, today), UpcomingOutflow.Source.SUBSCRIPTION))
        }
        dues.filter { it.next <= end }.forEach { d -> out.add(UpcomingOutflow(d.name, d.amount.abs(), maxOf(d.next, today), UpcomingOutflow.Source.DUE)) }
        return out.sortedBy { it.date }
    }
}
