package it.spese.domain.finance

import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.SavingsGoal
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import it.spese.domain.simulation.PlanEngine
import it.spese.domain.simulation.Trends
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class MonthPoint(val month: YearMonth, val income: BigDecimal, val expense: BigDecimal, val net: BigDecimal, val savingsRate: Int?)

/** "Il tuo mese": riepilogo di un mese chiuso con confronto, obiettivo, categorie principali e andamento del risparmio. */
data class MonthlyReport(
    val month: YearMonth,
    val income: BigDecimal,
    val expense: BigDecimal,
    val net: BigDecimal,
    val savingsRate: Int?,
    val goalMonthly: BigDecimal,
    val goalReached: Boolean,
    /** Variazione % della spesa rispetto al mese prima (null se non confrontabile). */
    val expenseChange: BigDecimal?,
    val topCategories: List<Pair<String, BigDecimal>>,
    val biggestExpense: Transaction?,
    val history: List<MonthPoint>,
    val headline: String,
    val highlights: List<String>,
)

object MonthlyReportBuilder {
    /** L'ultimo mese concluso con dati (il mese di `today` è ancora in corso); in mancanza, il mese in corso. */
    fun lastCompleteMonth(tx: List<Transaction>, today: LocalDate): YearMonth? {
        if (tx.isEmpty()) return null
        val cur = YearMonth.from(today)
        return tx.map { YearMonth.from(it.bookingDate) }.filter { it < cur }.maxOrNull() ?: cur
    }

    fun history(tx: List<Transaction>, last: YearMonth, n: Int = 6): List<MonthPoint> {
        val s = StatsEngine.compute(tx)
        return (n - 1 downTo 0).map { last.minusMonths(it.toLong()) }.filter { s.byMonth.containsKey(it) }.map { m ->
            val ms = s.byMonth.getValue(m)
            MonthPoint(m, ms.income, ms.expense, ms.net, if (ms.income.signum() > 0) ms.net.multiply(Money.HUNDRED).divide(ms.income, 0, RoundingMode.HALF_UP).toInt() else null)
        }
    }

    fun build(tx: List<Transaction>, categories: List<Category>, goal: SavingsGoal, month: YearMonth): MonthlyReport? {
        val s = StatsEngine.compute(tx)
        val ms = s.byMonth[month] ?: return null
        val prev = s.byMonth[month.minusMonths(1)]
        val goalM = PlanEngine.goalMonthly(goal)
        val net = ms.net
        val rate = if (ms.income.signum() > 0) net.multiply(Money.HUNDRED).divide(ms.income, 0, RoundingMode.HALF_UP).toInt() else null
        val reached = goalM.signum() > 0 && net >= goalM
        val change = prev?.takeIf { it.expense.signum() > 0 }?.let { Trends.changePercent(it.expense, ms.expense) }
        val byId = categories.associateBy { it.id }
        val roll = CategoryTree.rollup(ms.byCategory, byId)
        val top = roll.entries.filter { it.value.signum() > 0 }.sortedByDescending { it.value }.take(3).map { (byId[it.key]?.name ?: "Senza categoria") to it.value }
        val biggest = tx.filter { it.kind == TxKind.SPESA && YearMonth.from(it.bookingDate) == month }.minByOrNull { it.amount }
        val hist = history(tx, month)
        val label = Dates.monthLabelLong(month)

        val headline = when {
            net.signum() < 0 -> "A $label hai speso ${Money.eur0(net.abs())} più di quanto hai incassato"
            reached -> "A $label hai messo da parte ${Money.eur0(net)}: obiettivo raggiunto"
            else -> "A $label hai messo da parte ${Money.eur0(net)}"
        }
        val notes = ArrayList<String>()
        if (!reached && goalM.signum() > 0 && net.signum() >= 0) notes.add("Ti sono mancati ${Money.eur0(goalM - net)} per l'obiettivo di ${Money.eur0(goalM)}.")
        if (change != null) notes.add(if (change.signum() >= 0) "Hai speso il ${change.setScale(0, RoundingMode.HALF_UP).toInt()}% in più del mese prima." else "Hai speso il ${change.abs().setScale(0, RoundingMode.HALF_UP).toInt()}% in meno del mese prima.")
        top.firstOrNull()?.let { notes.add("La voce più pesante è ${it.first}: ${Money.eur0(it.second)}.") }
        biggest?.let { notes.add("La spesa singola più alta: ${it.merchant} (${Money.eur0(it.amount.negate())}).") }
        if (rate != null && hist.size >= 2 && hist.dropLast(1).mapNotNull { it.savingsRate }.let { h -> h.isNotEmpty() && rate > h.max() }) notes.add("È il tuo miglior tasso di risparmio degli ultimi mesi.")
        return MonthlyReport(month, ms.income, ms.expense, net, rate, goalM, reached, change, top, biggest, hist, headline, notes)
    }
}

/** Testi brevi per le notifiche (riepilogo giornaliero e settimanale). */
object Digest {
    fun daily(d: DailyBudget): Pair<String, String>? = when (d.status) {
        DailyStatus.SETUP -> null
        DailyStatus.OVER -> "Budget variabile esaurito" to "Per le spese variabili di ${Dates.monthLabelLong(d.month)} hai già usato tutto: oggi evita spese extra."
        else -> "Oggi puoi spendere circa ${Money.eur0(d.perDay)}" to
            "Restano ${Money.eur0(d.available)} per le spese variabili fino a fine mese (${d.daysLeft} giorni)." +
            if (d.fixedRemaining.signum() > 0) " Uscite fisse ancora da pagare: ${Money.eur0(d.fixedRemaining)}." else ""
    }

    fun weekly(tx: List<Transaction>, today: LocalDate, d: DailyBudget?): Pair<String, String>? {
        val spese = tx.filter { it.kind == TxKind.SPESA }
        if (spese.isEmpty()) return null
        fun sum(from: LocalDate, to: LocalDate) = spese.filter { !it.bookingDate.isBefore(from) && !it.bookingDate.isAfter(to) }.fold(BigDecimal.ZERO) { a, t -> a - t.amount }
        val thisWeek = sum(today.minusDays(6), today); val before = sum(today.minusDays(13), today.minusDays(7))
        val cmp = if (before.signum() > 0) Trends.changePercent(before, thisWeek)?.let { c ->
            " (" + (if (c.signum() >= 0) "+" else "−") + c.abs().setScale(0, RoundingMode.HALF_UP).toInt() + "% rispetto alla settimana prima)"
        } ?: "" else ""
        val tail = d?.takeIf { it.status != DailyStatus.SETUP }?.let { if (it.available.signum() > 0) " Ti restano ${Money.eur0(it.available)} di budget variabile per il mese." else " Il budget variabile del mese è esaurito." } ?: ""
        return "Settimana: hai speso ${Money.eur0(thisWeek)}" to "Negli ultimi 7 giorni hai speso ${Money.eur0(thisWeek)}$cmp.$tail"
    }
}
