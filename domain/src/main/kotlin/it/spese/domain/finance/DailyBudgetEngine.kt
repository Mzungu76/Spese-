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
) { val prudent: BigDecimal get() = fixed + variablePrudent }

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
        return IncomeEstimate(HealthStats.median(fixedM), HealthStats.median(varM), HealthStats.percentile(varM, 0.25), n)
    }
}

enum class BudgetStatus { OK, OVER, NOT_ENOUGH }

/**
 * Il budget del periodo (il mese contabile in corso) e di ogni giorno che resta.
 * `limit` = entrate attese − risparmio per gli obiettivi − margine di sicurezza: è il massimo da spendere in tutto, anche per le spese fisse.
 * Da lì si tolgono quanto già speso e le uscite già note ancora da pagare (ricorrenze, abbonamenti, scadenze): il resto, diviso i giorni che restano, è la cifra al giorno.
 */
data class DailyBudget(
    val periodStart: LocalDate, val periodEnd: LocalDate, val today: LocalDate, val daysTotal: Int, val daysLeft: Int,
    /** Entrate attese: stima prudente (fisse + variabili al 25° percentile), ma mai meno di quanto già incassato. */
    val expectedIncome: BigDecimal, val incomeToDate: BigDecimal, val income: IncomeEstimate?,
    val savings: BigDecimal, val reserve: BigDecimal,
    val spent: BigDecimal, val fixedPaid: BigDecimal, val upcoming: List<UpcomingOutflow>,
    val limit: BigDecimal?, val status: BudgetStatus, val stale: Boolean,
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
     * Il margine di sicurezza è una percentuale di ciò che resta dopo risparmio e uscite fisse: copre imprevisti e entrate variabili che deludono.
     */
    fun compute(
        data: AppData, rules: List<RecurringRule> = emptyList(), ignoredPatterns: Set<String> = emptySet(), goals: List<Goal> = emptyList(),
        incomeOverrides: Map<Long, IncomeNature> = emptyMap(), marginPct: Int = DEFAULT_MARGIN_PCT, manualLimit: BigDecimal? = null, realToday: LocalDate = LocalDate.now(), savingsOverride: BigDecimal? = null, patterns: List<RecurringPattern>? = null,
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

        val est = IncomeEstimator.estimate(tx, cal, month, byId, incomeOverrides)
        // le entrate mensili attese impostate a mano nel Piano prevalgono sulla stima dallo storico
        val manualIncome = data.goal.expectedIncome?.takeIf { it.signum() > 0 }
        val expected = (manualIncome ?: est?.prudent ?: BigDecimal.ZERO).max(incomeToDate)
        val found = patterns ?: SubscriptionDetector.detect(data.transactions, SubscriptionDetector.excludedCategories(data.categories), realToday, rules).filter { it.key !in ignoredPatterns }
        val upcoming = SafeToSpendEngine.upcoming(rules, found, data.dues, today, end, start)
        val upcomingTotal = upcoming.fold(BigDecimal.ZERO) { a, u -> a + u.amount }
        val savings = (savingsOverride ?: if (goals.isNotEmpty()) GoalEngine.monthlyTotal(goals, today) else AlertEngine.goalMonthly(data.goal)).max(BigDecimal.ZERO)

        val canEstimate = est != null || incomeToDate.signum() > 0 || manualIncome != null
        val beforeMargin = (expected - savings - fixedPaid - upcomingTotal).max(BigDecimal.ZERO)
        // il margine è una quota di ciò che resta dopo risparmio e uscite fisse (pagate e da pagare)
        val reserve = if (manualLimit != null) BigDecimal.ZERO else beforeMargin.multiply(BigDecimal(marginPct.coerceIn(0, 50))).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        val limit = manualLimit?.takeIf { it.signum() > 0 } ?: if (!canEstimate) null else (expected - savings - reserve).max(BigDecimal.ZERO)
        val status = when {
            limit == null -> BudgetStatus.NOT_ENOUGH
            limit - spent - upcomingTotal < BigDecimal.ZERO -> BudgetStatus.OVER
            else -> BudgetStatus.OK
        }
        return DailyBudget(start, end, today, daysTotal, daysLeft, expected, incomeToDate, est, savings, reserve, spent, fixedPaid, upcoming, limit, status, stale)
    }
}
