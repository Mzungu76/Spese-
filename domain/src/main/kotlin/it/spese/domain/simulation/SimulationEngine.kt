package it.spese.domain.simulation

import it.spese.core.common.Money
import it.spese.core.model.DueFrequency
import it.spese.core.model.GoalMode
import it.spese.core.model.SavingsGoal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

/** Media mensile di spesa di una categoria (valore positivo). */
data class CategoryBaseline(val categoryId: Long?, val name: String, val monthlyAvg: BigDecimal, val flexible: Boolean = false)

/** Spesa ricorrente rilevata o dichiarata: può essere eliminata dallo scenario ("cosa succede se la tolgo?"). */
data class RecurringCommitment(val key: String, val name: String, val monthlyAmount: BigDecimal, val categoryId: Long?)

/** Pagamento futuro pianificato (scadenza manuale). */
data class ScheduledOutflow(val key: String, val name: String, val amount: BigDecimal, val first: YearMonth, val frequency: DueFrequency)

data class SimulationInput(
    val monthlyIncome: BigDecimal,
    val categories: List<CategoryBaseline>,
    val goal: SavingsGoal = SavingsGoal(),
    /** Taglio (+) o aumento (-) percentuale per categoria. */
    val cuts: Map<Long?, Int> = emptyMap(),
    /** Variazione mensile delle entrate (es. +200 €). */
    val incomeDelta: BigDecimal = BigDecimal.ZERO,
    val recurring: List<RecurringCommitment> = emptyList(),
    val removedRecurring: Set<String> = emptySet(),
    val scheduled: List<ScheduledOutflow> = emptyList(),
    val startingBalance: BigDecimal = BigDecimal.ZERO,
    val startMonth: YearMonth = YearMonth.now(),
    val horizonMonths: Int = 12,
)

data class MonthProjection(
    val index: Int,
    val month: YearMonth,
    val income: BigDecimal,
    val expense: BigDecimal,
    val scheduledOutflows: BigDecimal,
    val saving: BigDecimal,
    val cumulativeSaving: BigDecimal,
    val balance: BigDecimal,
)

data class SimulationResult(
    val baselineExpense: BigDecimal,
    val baselineIncome: BigDecimal,
    val baselineMonthlySaving: BigDecimal,
    val cutSavings: BigDecimal,
    val recurringRemovedSavings: BigDecimal,
    val incomeDelta: BigDecimal,
    val simulatedIncome: BigDecimal,
    val simulatedExpense: BigDecimal,
    val simulatedMonthlySaving: BigDecimal,
    val goalMonthly: BigDecimal,
    /** Differenza tra risparmio simulato e obiettivo (negativa = sotto l'obiettivo). */
    val vsGoal: BigDecimal,
    val savingsRatePercent: Int?,
    /** Mesi necessari per raggiungere l'obiettivo di importo totale (modalità "totale"); null se irraggiungibile o non applicabile. */
    val monthsToGoal: Int?,
    val projection: List<MonthProjection>,
    /** Proiezione "senza modifiche" (stesse ipotesi di base, nessun taglio/variazione), per il confronto. */
    val baselineProjection: List<MonthProjection>,
) {
    val goalReached: Boolean get() = vsGoal.signum() >= 0
    fun balanceAt(months: Int): BigDecimal? = projection.getOrNull(months)?.balance
    fun yearlySaving(): BigDecimal = simulatedMonthlySaving.multiply(BigDecimal(12))
}

/**
 * Motore di simulazione finanziaria puro (nessuna dipendenza da Android/UI), con tutti i calcoli in BigDecimal.
 *
 * Modello: il risparmio mensile è `entrate − spese`. Le spese di ogni categoria sono la media storica, ridotta delle
 * ricorrenze eliminate e poi soggetta al taglio percentuale. Le scadenze pianificate (se non rimosse) sono uscite
 * aggiuntive nei mesi in cui cadono. Il saldo parte da `startingBalance`.
 */
object SimulationEngine {
    fun run(input: SimulationInput): SimulationResult {
        val baseExpense = input.categories.fold(BigDecimal.ZERO) { a, c -> a + c.monthlyAvg }
        val baseSaving = input.monthlyIncome - baseExpense

        // 1) eliminazione delle ricorrenze (per categoria, senza scendere sotto zero)
        val removedByCat = HashMap<Long?, BigDecimal>()
        var removedTotal = BigDecimal.ZERO
        input.recurring.filter { it.key in input.removedRecurring }.forEach { r ->
            val avg = input.categories.firstOrNull { it.categoryId == r.categoryId }?.monthlyAvg ?: BigDecimal.ZERO
            val already = removedByCat[r.categoryId] ?: BigDecimal.ZERO
            val take = r.monthlyAmount.min((avg - already).max(BigDecimal.ZERO))
            removedByCat[r.categoryId] = already + take
            removedTotal += take
        }
        // 2) tagli percentuali sul residuo
        var cutTotal = BigDecimal.ZERO
        var simExpense = BigDecimal.ZERO
        input.categories.forEach { c ->
            val remaining = c.monthlyAvg - (removedByCat[c.categoryId] ?: BigDecimal.ZERO)
            val pct = input.cuts[c.categoryId] ?: 0
            val cut = Money.pctOf(remaining, pct)
            cutTotal += cut
            simExpense += remaining - cut
        }
        val simIncome = input.monthlyIncome + input.incomeDelta
        val simSaving = simIncome - simExpense
        val goalM = PlanEngine.goalMonthly(input.goal)

        fun project(income: BigDecimal, expense: BigDecimal, withScheduled: Boolean): List<MonthProjection> {
            val out = ArrayList<MonthProjection>()
            var balance = input.startingBalance; var cum = BigDecimal.ZERO
            out.add(MonthProjection(0, input.startMonth, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, balance))
            for (i in 1..input.horizonMonths) {
                val m = input.startMonth.plusMonths(i.toLong())
                val sched = if (withScheduled) input.scheduled.filter { it.key !in input.removedRecurring && hits(it, m) }.fold(BigDecimal.ZERO) { a, s -> a + s.amount } else BigDecimal.ZERO
                val saving = income - expense - sched
                cum += saving; balance += saving
                out.add(MonthProjection(i, m, income, expense + sched, sched, saving, cum, balance))
            }
            return out
        }

        val proj = project(simIncome, simExpense, true)
        val baseProj = project(input.monthlyIncome, baseExpense, true)
        val monthsToGoal = if (input.goal.mode == GoalMode.TOTAL) monthsToReach(input.goal.value, simSaving) else null
        return SimulationResult(
            baselineExpense = baseExpense, baselineIncome = input.monthlyIncome, baselineMonthlySaving = baseSaving,
            cutSavings = cutTotal, recurringRemovedSavings = removedTotal, incomeDelta = input.incomeDelta,
            simulatedIncome = simIncome, simulatedExpense = simExpense, simulatedMonthlySaving = simSaving,
            goalMonthly = goalM, vsGoal = simSaving - goalM,
            savingsRatePercent = if (simIncome.signum() > 0) simSaving.multiply(Money.HUNDRED).divide(simIncome, 0, RoundingMode.HALF_UP).toInt() else null,
            monthsToGoal = monthsToGoal, projection = proj, baselineProjection = baseProj,
        )
    }

    /** Mesi (arrotondati per eccesso) per accumulare `target` risparmiando `monthlySaving`; null se non raggiungibile. */
    fun monthsToReach(target: BigDecimal, monthlySaving: BigDecimal, alreadySaved: BigDecimal = BigDecimal.ZERO): Int? {
        val missing = target - alreadySaved
        if (missing.signum() <= 0) return 0
        if (monthlySaving.signum() <= 0) return null
        return missing.divide(monthlySaving, 0, RoundingMode.CEILING).toInt()
    }

    private fun hits(s: ScheduledOutflow, m: YearMonth): Boolean = when (s.frequency) {
        DueFrequency.ONCE -> m == s.first
        DueFrequency.MONTHLY -> !m.isBefore(s.first)
        DueFrequency.YEARLY -> !m.isBefore(s.first) && m.monthValue == s.first.monthValue
    }
}

/** Confronto tra scenari. */
data class ScenarioOutcome(val name: String, val result: SimulationResult)

data class ComparisonRow(
    val name: String,
    val monthlySaving: BigDecimal,
    val deltaVsFirst: BigDecimal,
    val balance6: BigDecimal?,
    val balance12: BigDecimal?,
    val yearlySaving: BigDecimal,
    val monthsToGoal: Int?,
    val goalReached: Boolean,
)

object ScenarioComparison {
    fun compare(outcomes: List<ScenarioOutcome>): List<ComparisonRow> {
        val base = outcomes.firstOrNull()?.result?.simulatedMonthlySaving ?: BigDecimal.ZERO
        return outcomes.map {
            val r = it.result
            ComparisonRow(it.name, r.simulatedMonthlySaving, r.simulatedMonthlySaving - base, r.balanceAt(6), r.balanceAt(12), r.yearlySaving(), r.monthsToGoal, r.goalReached)
        }
    }
}
