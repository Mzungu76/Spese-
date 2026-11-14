package it.spese.domain.finance

import it.spese.core.common.Dates
import it.spese.core.common.MonthCalendar
import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.Due
import it.spese.core.model.SavingsGoal
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class AlertKind { BUDGET_EXCEEDED, BUDGET_NEAR, BUDGET_PACE, SAVINGS_BELOW_GOAL, RECURRING_EXPECTED, DUE_LATE, DUE_SOON, UNEXPECTED_EXPENSE }

/** level: 2 = critico, 1 = attenzione, 0 = informativo. */
data class AlertItem(val level: Int, val kind: AlertKind, val message: String, val key: String)

/** Quote di spesa del mese per categoria rispetto al budget. */
data class BudgetUsage(val category: Category, val spent: BigDecimal, val budget: BigDecimal) {
    val percent: BigDecimal get() = Money.percent(spent, budget, 4)
    val exceeded: Boolean get() = spent >= budget
}

object AlertEngine {
    /**
     * "Oggi" di riferimento: la data reale, tranne quando l'ultimo movimento è più vecchio di 45 giorni
     * (dati storici importati): in tal caso si usa l'ultimo movimento, come nella versione HTML.
     */
    fun referenceToday(realToday: LocalDate, tx: List<Transaction>): LocalDate {
        val mx = tx.maxOfOrNull { it.bookingDate } ?: realToday
        return if (ChronoUnit.DAYS.between(mx, realToday) > 45) mx else realToday
    }

    fun monthlySpending(list: List<Transaction>, month: YearMonth, cal: MonthCalendar = MonthCalendar.SOLAR): Map<Long?, BigDecimal> {
        val by = HashMap<Long?, BigDecimal>()
        list.filter { cal.monthOf(it.bookingDate) == month && it.kind == TxKind.SPESA }
            .forEach { by.merge(it.categoryId, it.amount.negate()) { a, b -> a + b } }
        return by
    }

    /** Speso nel mese da una categoria, sottocategorie comprese. */
    fun spentWithChildren(by: Map<Long?, BigDecimal>, c: Category, categories: List<Category>): BigDecimal =
        CategoryTree.withChildren(c.id, categories).fold(BigDecimal.ZERO) { a, id -> a + (by[id] ?: BigDecimal.ZERO) }

    fun budgetUsage(list: List<Transaction>, categories: List<Category>, month: YearMonth, cal: MonthCalendar = MonthCalendar.SOLAR): List<BudgetUsage> {
        val by = monthlySpending(list, month, cal)
        return categories.filter { (it.budgetMonthly ?: BigDecimal.ZERO).signum() > 0 }
            .map { BudgetUsage(it, spentWithChildren(by, it, categories), it.budgetMonthly!!) }
    }

    fun goalMonthly(goal: SavingsGoal): BigDecimal =
        if (goal.mode == it.spese.core.model.GoalMode.MONTHLY) goal.value
        else goal.value.divide(BigDecimal(maxOf(1, goal.months)), 10, RoundingMode.HALF_UP)

    fun compute(
        accountTx: List<Transaction>,
        categories: List<Category>,
        goal: SavingsGoal,
        dues: List<Due>,
        today: LocalDate,
        excludedRecurringCategories: Set<Long?> = emptySet(),
        cal: MonthCalendar = MonthCalendar.SOLAR,
    ): List<AlertItem> {
        val out = ArrayList<AlertItem>()
        if (accountTx.isEmpty()) return out
        val ym = cal.monthOf(today); val dim = cal.length(ym); val dd = cal.dayIndex(today)
        val by = monthlySpending(accountTx, ym, cal)
        val label = Dates.monthLabel(ym)
        for (c in categories) {
            val b = c.budgetMonthly ?: continue
            if (b.signum() <= 0) continue
            val sp = spentWithChildren(by, c, categories)
            val proj = if (dd < dim) sp.multiply(BigDecimal(dim)).divide(BigDecimal(dd), 10, RoundingMode.HALF_UP) else sp
            if (sp >= b) out.add(AlertItem(2, AlertKind.BUDGET_EXCEEDED, "Budget \"${c.name}\" superato ($label): ${Money.eur0(sp)} su ${Money.eur0(b)}", "bud:${c.id}"))
            else if (sp >= b.multiply(BigDecimal("0.8")))
                out.add(AlertItem(1, AlertKind.BUDGET_NEAR, "Budget \"${c.name}\" all'${Money.percent(sp, b, 0).toInt()}% ($label): ${Money.eur0(sp)} su ${Money.eur0(b)}", "bud:${c.id}"))
            else if (dd >= 7 && dd < dim && proj > b.multiply(BigDecimal("1.05")))
                out.add(AlertItem(1, AlertKind.BUDGET_PACE, "\"${c.name}\": a questo ritmo chiuderai $label a ${Money.eur0(proj)} (budget ${Money.eur0(b)})", "bud:${c.id}"))
        }
        val prev = StatsEngine.compute(accountTx.filter { cal.monthOf(it.bookingDate) < ym }, cal)
        val pm = prev.byMonth.keys.sorted().takeLast(3)
        if (pm.isNotEmpty()) {
            var i = BigDecimal.ZERO; var e = BigDecimal.ZERO
            pm.forEach { i += prev.byMonth.getValue(it).income; e += prev.byMonth.getValue(it).expense }
            val sv = (i - e).divide(BigDecimal(pm.size), 10, RoundingMode.HALF_UP)
            val g = goalMonthly(goal)
            if (g.signum() > 0 && sv < g)
                out.add(AlertItem(1, AlertKind.SAVINGS_BELOW_GOAL, "Risparmio medio degli ultimi ${pm.size} mesi (${Money.eur0(sv)}/mese) sotto l'obiettivo di ${Money.eur0(g)}/mese", "goal"))
        }
        val rec = RecurringDetector.detect(accountTx, excludedRecurringCategories)
        rec.forEach { r ->
            val d = ChronoUnit.DAYS.between(today, r.next).toInt()
            if (d in -3..7) {
                val when_ = if (d < 0) "atteso da ${-d} g" else if (d == 0) "oggi" else "tra $d g"
                out.add(AlertItem(0, AlertKind.RECURRING_EXPECTED, "Addebito ricorrente probabile: ${r.merchant} (~${Money.eur0(r.mean)}) $when_", r.key))
            }
        }
        dues.forEach { x ->
            val d = ChronoUnit.DAYS.between(today, x.next).toInt()
            if (d < 0) out.add(AlertItem(2, AlertKind.DUE_LATE, "Scadenza in ritardo: ${x.name} (${Money.eur0(x.amount)}) del ${Dates.fmt(x.next)}", "due:${x.id}"))
            else if (d <= 7) out.add(AlertItem(1, AlertKind.DUE_SOON, "Scadenza ${if (d == 0) "oggi" else "tra $d giorni"}: ${x.name} (${Money.eur0(x.amount)})", "due:${x.id}"))
        }
        out.addAll(unexpectedExpenses(accountTx, categories, ym, cal))
        return out.sortedByDescending { it.level }
    }

    /**
     * Spese non previste: nel mese corrente, spese di importo >= 50 € che superano di oltre 3 deviazioni standard
     * la spesa media storica della stessa categoria (servono almeno 5 movimenti precedenti).
     */
    fun unexpectedExpenses(list: List<Transaction>, categories: List<Category>, month: YearMonth, cal: MonthCalendar = MonthCalendar.SOLAR): List<AlertItem> {
        val names = categories.associate { it.id to it.name }
        val out = ArrayList<AlertItem>()
        val hist = list.filter { it.kind == TxKind.SPESA && cal.monthOf(it.bookingDate) < month }.groupBy { it.categoryId }
        list.filter { it.kind == TxKind.SPESA && cal.monthOf(it.bookingDate) == month && it.amount.negate() >= BigDecimal(50) }.forEach { t ->
            val h = hist[t.categoryId] ?: return@forEach
            if (h.size < 5) return@forEach
            val xs = h.map { it.amount.negate() }
            val mean = xs.fold(BigDecimal.ZERO) { s, v -> s + v }.divide(BigDecimal(xs.size), 10, RoundingMode.HALF_UP)
            val variance = xs.fold(BigDecimal.ZERO) { s, v -> s + (v - mean).pow(2) }.divide(BigDecimal(xs.size), 10, RoundingMode.HALF_UP)
            val a = t.amount.negate()
            if (a > mean && (a - mean).pow(2) > variance.multiply(BigDecimal(9)))
                out.add(AlertItem(1, AlertKind.UNEXPECTED_EXPENSE,
                    "Spesa non prevista: ${t.merchant} ${Money.eur0(a)} (${names[t.categoryId] ?: "Altro"}, media ${Money.eur0(mean)})", "tx:${t.id}"))
        }
        return out
    }
}
