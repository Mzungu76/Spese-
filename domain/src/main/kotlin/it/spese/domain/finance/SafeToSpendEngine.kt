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
) {
    val upcomingTotal: BigDecimal get() = upcoming.fold(BigDecimal.ZERO) { a, u -> a + u.amount }
    val variableSpent: BigDecimal get() = spentToDate - fixedPaid
}

object SafeToSpendEngine {
    /** Entrate attese: media degli ultimi mesi completi, ma mai meno di quanto già incassato nel periodo. */
    private const val HISTORY_MONTHS = 3
    private const val STALE_DAYS = 45L

    fun compute(
        data: AppData,
        rules: List<RecurringRule> = emptyList(),
        patterns: List<RecurringPattern> = emptyList(),
        accountId: Long? = null,
        realToday: LocalDate = LocalDate.now(),
        goals: List<Goal> = emptyList(),
    ): SafeToSpendReport? = compute(
        data.transactions.filter { accountId == null || it.accountId == accountId }.filter { it.kind != TxKind.TRASFERIMENTO },
        data, rules, patterns, realToday, goals,
    )

    /** Come [compute], rilevando da sé abbonamenti e rate dai movimenti (senza quelli ignorati dall'utente o già coperti da una ricorrenza). Unico ingresso per Home, notifica e widget. */
    fun forApp(data: AppData, rules: List<RecurringRule>, ignoredPatterns: Set<String> = emptySet(), accountId: Long? = null, realToday: LocalDate = LocalDate.now(), goals: List<Goal> = emptyList()): SafeToSpendReport? {
        if (!data.loaded) return null
        val patterns = SubscriptionDetector.detect(data.transactions, SubscriptionDetector.excludedCategories(data.categories), realToday, rules).filter { it.key !in ignoredPatterns }
        return compute(data, rules, patterns, accountId, realToday, goals)
    }

    private fun compute(tx: List<Transaction>, data: AppData, rules: List<RecurringRule>, patterns: List<RecurringPattern>, realToday: LocalDate, goals: List<Goal>): SafeToSpendReport? {
        if (tx.isEmpty()) return null
        val cal: MonthCalendar = data.calendar
        val today = AlertEngine.referenceToday(realToday, tx)
        val stale = today != realToday
        val month = cal.monthOf(today)
        val start = cal.start(month); val end = cal.end(month)
        val daysTotal = cal.length(month)
        val elapsed = (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceIn(1, daysTotal)
        val daysLeft = (daysTotal - elapsed + 1).coerceAtLeast(0)

        val inPeriod = tx.filter { it.bookingDate >= start && it.bookingDate <= today }
        val spent = inPeriod.filter { it.kind == TxKind.SPESA }.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() }
        val incomeToDate = inPeriod.filter { it.kind == TxKind.ENTRATA }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        val byId = data.categoriesById
        val fixedPaid = inPeriod.filter { it.kind == TxKind.SPESA && CategoryTree.rootNature(it.categoryId, byId) == SpendNature.FIXED }
            .fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() }

        // entrate attese
        val past = StatsEngine.compute(tx.filter { cal.monthOf(it.bookingDate) < month }, cal).byMonth
        val months = past.keys.sorted().takeLast(HISTORY_MONTHS)
        val avgIncome = if (months.isEmpty()) null else months.fold(BigDecimal.ZERO) { a, m -> a + past.getValue(m).income }.divide(BigDecimal(months.size), 2, RoundingMode.HALF_UP)
        val expectedIncome = (avgIncome ?: BigDecimal.ZERO).max(incomeToDate)

        val upcoming = upcoming(rules, patterns, data.dues, today, end, start)
        val upcomingTotal = upcoming.fold(BigDecimal.ZERO) { a, u -> a + u.amount }
        val savings = (if (goals.isNotEmpty()) GoalEngine.monthlyTotal(goals, today) else AlertEngine.goalMonthly(data.goal)).max(BigDecimal.ZERO)

        val variableBudget = expectedIncome - savings - fixedPaid - upcomingTotal
        val available = expectedIncome - savings - spent - upcomingTotal
        val perDay = if (daysLeft <= 0) null else available.divide(BigDecimal(daysLeft), 2, RoundingMode.HALF_UP)
        val idealToDate = variableBudget.max(BigDecimal.ZERO).multiply(BigDecimal(elapsed)).divide(BigDecimal(daysTotal), 2, RoundingMode.HALF_UP)
        val delta = idealToDate - (spent - fixedPaid)
        val status = when {
            avgIncome == null && incomeToDate.signum() == 0 -> SafeStatus.NOT_ENOUGH
            available.signum() < 0 -> SafeStatus.OVER
            delta.signum() < 0 -> SafeStatus.BEHIND
            else -> SafeStatus.OK
        }
        return SafeToSpendReport(today, start, end, daysLeft, daysTotal, expectedIncome, incomeToDate, avgIncome == null || avgIncome < incomeToDate, savings,
            spent, fixedPaid, upcoming, variableBudget, available, perDay, delta, stale, status, goals.isNotEmpty())
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
