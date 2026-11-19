package it.spese.domain.goals

import it.spese.core.model.Goal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class GoalStatus { COMPLETED, ON_TRACK, LATE, NO_PLAN, PAUSED }

/** Stato di un obiettivo oggi: quanto manca, quanto va messo da parte ogni mese, quando si arriva e se si è in tempo. */
data class GoalProgress(
    val goal: Goal, val remaining: BigDecimal, val percent: Int,
    /** Quota mensile da accantonare (0 se completato, in pausa o senza piano). */
    val monthlyQuota: BigDecimal,
    /** Quota che servirebbe per arrivare alla scadenza (null senza scadenza). */
    val neededForDeadline: BigDecimal?,
    /** Data stimata di arrivo con la quota attuale (null senza quota). */
    val eta: LocalDate?,
    val status: GoalStatus,
    /** Se in ritardo: quanto manca al mese per farcela in tempo. */
    val shortBy: BigDecimal?,
)

object GoalEngine {
    fun remaining(g: Goal): BigDecimal = (g.target - g.saved).max(BigDecimal.ZERO)

    /** Mesi interi (almeno 1) da oggi alla scadenza, contando il mese in corso. */
    fun monthsLeft(today: LocalDate, deadline: LocalDate): Int =
        ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(deadline)).toInt().coerceAtLeast(1)

    fun progress(g: Goal, today: LocalDate = LocalDate.now()): GoalProgress {
        val rem = remaining(g)
        val pct = if (g.target.signum() <= 0) 100 else g.saved.multiply(BigDecimal(100)).divide(g.target, 0, RoundingMode.DOWN).toInt().coerceIn(0, 100)
        val needed = g.deadline?.let { rem.divide(BigDecimal(monthsLeft(today, it)), 2, RoundingMode.CEILING) }
        if (rem.signum() == 0) return GoalProgress(g, rem, 100, BigDecimal.ZERO, needed, null, GoalStatus.COMPLETED, null)
        if (!g.enabled) return GoalProgress(g, rem, pct, BigDecimal.ZERO, needed, null, GoalStatus.PAUSED, null)
        val quota = (g.monthly?.takeIf { it.signum() > 0 } ?: needed ?: BigDecimal.ZERO)
        if (quota.signum() == 0) return GoalProgress(g, rem, pct, BigDecimal.ZERO, needed, null, GoalStatus.NO_PLAN, null)
        val eta = today.plusMonths(rem.divide(quota, 0, RoundingMode.CEILING).toLong())
        val late = g.monthly != null && needed != null && needed > quota
        return GoalProgress(g, rem, pct, quota, needed, eta, if (late) GoalStatus.LATE else GoalStatus.ON_TRACK, if (late) needed!! - quota else null)
    }

    fun all(goals: List<Goal>, today: LocalDate = LocalDate.now()): List<GoalProgress> =
        goals.sortedWith(compareBy({ it.priority }, { it.id })).map { progress(it, today) }

    /** Quanto va messo da parte ogni mese per tutti gli obiettivi attivi: è la quota di risparmio che «Disponibile oggi» riserva. */
    fun monthlyTotal(goals: List<Goal>, today: LocalDate = LocalDate.now()): BigDecimal =
        goals.fold(BigDecimal.ZERO) { a, g -> a + progress(g, today).monthlyQuota }

    /** Aggiunge (o toglie, se negativo) un versamento senza scendere sotto zero. */
    fun deposit(g: Goal, amount: BigDecimal): Goal = g.copy(saved = (g.saved + amount).max(BigDecimal.ZERO))

    fun describe(p: GoalProgress): String = when (p.status) {
        GoalStatus.COMPLETED -> "Obiettivo raggiunto"
        GoalStatus.PAUSED -> "In pausa"
        GoalStatus.NO_PLAN -> "Scegli una quota mensile o una scadenza per avere un piano"
        GoalStatus.ON_TRACK -> "Arrivi a ${it.spese.core.common.Dates.monthLabelLong(YearMonth.from(p.eta!!))}"
        GoalStatus.LATE -> "In ritardo: arrivi a ${it.spese.core.common.Dates.monthLabelLong(YearMonth.from(p.eta!!))}, mancano ${it.spese.core.common.Money.eur0(p.shortBy!!)} al mese per la scadenza"
    }
}
