package it.spese.domain.finance

import it.spese.core.model.Goal
import it.spese.core.model.RecurringRule
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.goals.GoalEngine
import it.spese.domain.health.HealthStats
import it.spese.domain.health.IncomeNature
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Quanto entra di solito, separando le entrate fisse (stipendio) da quelle variabili. Base: la mediana degli ultimi mesi completi. */
data class IncomeEstimate(
    /** Mediana mensile delle entrate fisse. */
    val fixed: BigDecimal,
    /** Mediana mensile delle variabili e, più prudente, il loro 25° percentile (si conta quello). */
    val variableTypical: BigDecimal, val variablePrudent: BigDecimal,
    val months: Int,
    /** Le fonti fisse (categoria principale → mediana mensile): servono a sapere quale parte è ancora attesa. */
    val fixedSources: Map<Long?, BigDecimal> = emptyMap(),
) { val prudent: BigDecimal get() = fixed + variablePrudent }

/** Entrate del periodo in corso: quelle già arrivate e quelle ancora attese (non si sottraggono mai a vicenda: uno stipendio atteso resta atteso anche dopo un extra). */
data class IncomeOutlook(val received: BigDecimal, val remaining: BigDecimal, val estimate: IncomeEstimate?)

object IncomeEstimator {
    private const val WINDOW = 6

    /**
     * Una fonte è «fissa» se compare quasi ogni mese (almeno il 70%) con importi quasi uguali (variazione ≤ 15%); le altre sono variabili.
     * Una correzione dell'utente (`overrides`, per categoria) prevale sempre. Senza almeno un mese completo non si stima nulla.
     */
    fun estimate(tx: List<Transaction>, cal: it.spese.core.common.MonthCalendar, current: YearMonth, byId: Map<Long, it.spese.core.model.Category>, overrides: Map<Long, IncomeNature> = emptyMap()): IncomeEstimate? {
        val incomes = tx.filter { it.kind == TxKind.ENTRATA }
        val all = tx.filter { cal.monthOf(it.bookingDate) < current }
        var months = all.map { cal.monthOf(it.bookingDate) }.distinct().sorted().takeLast(WINDOW)
        if (months.isEmpty()) return null
        // il primo mese in assoluto può essere parziale (conto aperto a metà mese): con abbastanza storia lo si esclude
        if (months.size >= 4 && months.first() == all.minOf { cal.monthOf(it.bookingDate) }) {
            val firstDay = all.filter { cal.monthOf(it.bookingDate) == months.first() }.minOf { it.bookingDate }
            if (firstDay.isAfter(cal.start(months.first()).plusDays(7))) months = months.drop(1)
        }
        val n = months.size
        val by = months.associateWith { m -> incomes.filter { cal.monthOf(it.bookingDate) == m }.groupBy { CategoryTree.rootId(it.categoryId, byId) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + t.amount } } }
        val ids = by.values.flatMap { it.keys }.toSet()
        fun values(id: Long?) = months.mapNotNull { m -> by.getValue(m)[id]?.takeIf { it.signum() > 0 } }
        fun natureOf(id: Long?): IncomeNature {
            id?.let { overrides[it] }?.let { return it }
            val v = values(id)
            val need = if (n >= 3) Math.ceil(0.7 * n).toInt() else n
            return if (v.size >= need && v.isNotEmpty() && HealthStats.cv(v) <= 0.15) IncomeNature.FIXED else IncomeNature.VARIABLE
        }
        val nature = ids.associateWith { natureOf(it) }
        val fixedM = months.map { m -> by.getValue(m).filterKeys { nature[it] == IncomeNature.FIXED }.values.fold(BigDecimal.ZERO) { a, v -> a + v } }
        val varM = months.map { m -> by.getValue(m).filterKeys { nature[it] != IncomeNature.FIXED }.values.fold(BigDecimal.ZERO) { a, v -> a + v } }
        val fixedSources = ids.filter { nature[it] == IncomeNature.FIXED }.associateWith { id -> HealthStats.median(values(id)) }
        return IncomeEstimate(HealthStats.median(fixedM), HealthStats.median(varM), HealthStats.percentile(varM, 0.25), n, fixedSources)
    }

    /**
     * Ancora attese = per ogni fonte fissa la mediana mensile meno quanto è già arrivato da quella fonte nel periodo (mai sotto zero),
     * più la quota prudente delle variabili meno le variabili già arrivate. Le entrate extra già incassate non riducono lo stipendio atteso.
     * Con le entrate attese scritte a mano nel Piano ([manual]) vale solo la differenza con quanto è già entrato.
     */
    fun outlook(tx: List<Transaction>, cal: it.spese.core.common.MonthCalendar, current: YearMonth, from: LocalDate, to: LocalDate, byId: Map<Long, it.spese.core.model.Category>, overrides: Map<Long, IncomeNature> = emptyMap(), manual: BigDecimal? = null): IncomeOutlook {
        val est = estimate(tx, cal, current, byId, overrides)
        val got = tx.filter { it.kind == TxKind.ENTRATA && it.bookingDate >= from && it.bookingDate <= to }
        val received = got.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        if (manual != null) return IncomeOutlook(received, (manual - received).max(BigDecimal.ZERO), est)
        if (est == null) return IncomeOutlook(received, BigDecimal.ZERO, null)
        val byRoot = got.groupBy { CategoryTree.rootId(it.categoryId, byId) }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a + t.amount } }
        val fixedLeft = est.fixedSources.entries.fold(BigDecimal.ZERO) { a, e -> a + (e.value - (byRoot[e.key] ?: BigDecimal.ZERO)).max(BigDecimal.ZERO) }
        val varGot = byRoot.filterKeys { it !in est.fixedSources.keys }.values.fold(BigDecimal.ZERO) { a, v -> a + v }
        val varLeft = (est.variablePrudent - varGot).max(BigDecimal.ZERO)
        return IncomeOutlook(received, fixedLeft + varLeft, est)
    }
}

enum class BudgetStatus { OK, OVER, NOT_ENOUGH }

/**
 * Il budget del periodo (il mese contabile in corso) e di ogni giorno che resta.
 *
 * Con i saldi dei conti ([cash] presente): spendibile = soldi sui conti − soldi bloccati negli obiettivi; a questi si sommano le entrate ancora attese
 * fino a fine periodo e si tolgono le uscite note da pagare, la quota di risparmio non ancora messa da parte e il margine di sicurezza.
 * Senza saldi: limite = entrate del periodo − risparmio − margine, da cui si tolgono quanto già speso e le uscite note.
 * In entrambi i casi il resto, diviso i giorni che restano (oggi compreso), è la cifra al giorno.
 */
data class DailyBudget(
    val periodStart: LocalDate, val periodEnd: LocalDate, val today: LocalDate, val daysTotal: Int, val daysLeft: Int,
    /** Entrate del periodo: quelle già arrivate più quelle ancora attese (stima prudente). */
    val expectedIncome: BigDecimal, val incomeToDate: BigDecimal, val income: IncomeEstimate?,
    /** Quota mensile di risparmio degli obiettivi e margine di sicurezza trattenuto. */
    val savings: BigDecimal, val reserve: BigDecimal,
    val spent: BigDecimal, val fixedPaid: BigDecimal, val upcoming: List<UpcomingOutflow>,
    val limit: BigDecimal?, val status: BudgetStatus, val stale: Boolean,
    /** Entrate ancora attese fino a fine periodo e quota di risparmio non ancora messa da parte (ridotta dei versamenti del periodo). */
    val incomeRemaining: BigDecimal = BigDecimal.ZERO, val savingsDue: BigDecimal = BigDecimal.ZERO,
    /** Saldo dei conti e soldi bloccati, se il calcolo parte dai saldi. */
    val cash: CashPosition? = null,
) {
    val upcomingTotal: BigDecimal get() = upcoming.fold(BigDecimal.ZERO) { a, u -> a + u.amount }
    /** Quanto resta da spendere in modo libero nel periodo (negativo = già oltre). */
    val remaining: BigDecimal? get() = limit?.let { it - spent - upcomingTotal }
    /** La cifra al giorno da oggi a fine periodo (compreso oggi); mai negativa. Null se non c'è un limite o il periodo è finito. */
    val perDay: BigDecimal? get() = if (daysLeft <= 0) null else remaining?.max(BigDecimal.ZERO)?.divide(BigDecimal(daysLeft), 2, RoundingMode.HALF_UP)
}

object DailyBudgetEngine {
    const val DEFAULT_MARGIN_PCT = 10

    /**
     * [data] contiene i movimenti da considerare (per un conto condiviso, i suoi). [manualLimit] = tetto mensile fissato a mano (sostituisce quello calcolato).
     * [cashSource] dà i saldi dei conti alla data di riferimento (null = calcolo dalle sole entrate).
     * Il margine di sicurezza è una percentuale di ciò che si può spendere dopo risparmio e uscite note: copre imprevisti e entrate che deludono.
     */
    fun compute(
        data: AppData, rules: List<RecurringRule> = emptyList(), ignoredPatterns: Set<String> = emptySet(), goals: List<Goal> = emptyList(),
        incomeOverrides: Map<Long, IncomeNature> = emptyMap(), marginPct: Int = DEFAULT_MARGIN_PCT, manualLimit: BigDecimal? = null, realToday: LocalDate = LocalDate.now(), savingsOverride: BigDecimal? = null, patterns: List<RecurringPattern>? = null,
        cashSource: ((LocalDate) -> CashPosition?)? = null,
    ): DailyBudget? {
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        if (tx.isEmpty()) return null
        val cal = data.calendar
        val today = AlertEngine.referenceToday(realToday, tx)
        val stale = today != realToday
        val month = cal.monthOf(today)
        val start = cal.start(month); val end = cal.end(month)
        val daysTotal = cal.length(month)
        val daysLeft = (ChronoUnit.DAYS.between(today, end) + 1).toInt().coerceIn(0, daysTotal)

        val inPeriod = tx.filter { it.bookingDate >= start && it.bookingDate <= today }
        val spent = inPeriod.filter { it.kind == TxKind.SPESA }.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() }
        val incomeToDate = inPeriod.filter { it.kind == TxKind.ENTRATA }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        val byId = data.categoriesById
        val fixedPaid = inPeriod.filter { it.kind == TxKind.SPESA && CategoryTree.rootNature(it.categoryId, byId) == SpendNature.FIXED }.fold(BigDecimal.ZERO) { a, t -> a + t.amount.negate() }

        // le entrate mensili attese impostate a mano nel Piano prevalgono sulla stima dallo storico
        val manualIncome = data.goal.expectedIncome?.takeIf { it.signum() > 0 }
        val outlook = IncomeEstimator.outlook(tx, cal, month, start, today, byId, incomeOverrides, manualIncome)
        val est = outlook.estimate
        val expected = outlook.received + outlook.remaining
        val found = patterns ?: SubscriptionDetector.detect(data.transactions, SubscriptionDetector.excludedCategories(data.categories), realToday, rules).filter { it.key !in ignoredPatterns }
        val upcoming = SafeToSpendEngine.upcoming(rules, found, data.dues, today, end, start)
        val upcomingTotal = upcoming.fold(BigDecimal.ZERO) { a, u -> a + u.amount }
        val quotas = if (goals.isNotEmpty()) goals.map { g -> g to GoalEngine.progress(g, today).monthlyQuota } else emptyList()
        val savings = (savingsOverride ?: if (goals.isNotEmpty()) quotas.fold(BigDecimal.ZERO) { a, q -> a + q.second } else AlertEngine.goalMonthly(data.goal)).max(BigDecimal.ZERO)
        val pct = BigDecimal(marginPct.coerceIn(0, 50))
        val cash = if (manualLimit == null) cashSource?.invoke(today) else null

        if (cash != null) {
            // quota non ancora messa da parte in questo periodo: la quota mensile meno i versamenti registrati da inizio periodo
            val savingsDue = (savingsOverride ?: if (goals.isNotEmpty()) quotas.fold(BigDecimal.ZERO) { a, (g, q) ->
                a + (q - g.deposits.filter { d -> d.day >= start && d.day <= today }.fold(BigDecimal.ZERO) { x, d -> x + d.amount }).max(BigDecimal.ZERO)
            } else AlertEngine.goalMonthly(data.goal)).max(BigDecimal.ZERO)
            val pool = cash.spendable + outlook.remaining - upcomingTotal - savingsDue
            val reserve = pool.max(BigDecimal.ZERO).multiply(pct).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            val available = pool - reserve
            val limit = spent + upcomingTotal + available
            val status = if (available.signum() < 0) BudgetStatus.OVER else BudgetStatus.OK
            return DailyBudget(start, end, today, daysTotal, daysLeft, expected, incomeToDate, est, savings, reserve, spent, fixedPaid, upcoming, limit, status, stale, outlook.remaining, savingsDue, cash)
        }

        val canEstimate = est != null || incomeToDate.signum() > 0 || manualIncome != null
        val beforeMargin = (expected - savings - fixedPaid - upcomingTotal).max(BigDecimal.ZERO)
        // il margine è una quota di ciò che resta dopo risparmio e uscite fisse (pagate e da pagare)
        val reserve = if (manualLimit != null) BigDecimal.ZERO else beforeMargin.multiply(pct).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        val limit = manualLimit?.takeIf { it.signum() > 0 } ?: if (!canEstimate) null else (expected - savings - reserve).max(BigDecimal.ZERO)
        val status = when {
            limit == null -> BudgetStatus.NOT_ENOUGH
            limit - spent - upcomingTotal < BigDecimal.ZERO -> BudgetStatus.OVER
            else -> BudgetStatus.OK
        }
        return DailyBudget(start, end, today, daysTotal, daysLeft, expected, incomeToDate, est, savings, reserve, spent, fixedPaid, upcoming, limit, status, stale, outlook.remaining, savings, null)
    }
}
