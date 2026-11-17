package it.spese.domain.recurring

import it.spese.core.model.RecurringRule
import java.time.LocalDate
import java.time.YearMonth

/** Date in cui un movimento ricorrente va registrato. */
object RecurringEngine {
    /** Il giorno scelto nel mese dato, o l'ultimo giorno se il mese è più corto (31 → 28/29/30). */
    fun dateIn(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /**
     * Occorrenze da creare fino a `to` incluso (di norma oggi): a partire dal mese di inizio, ogni `everyMonths` mesi,
     * dopo l'ultima già generata, non prima dell'inizio e non oltre la fine. Limite di sicurezza: 600 occorrenze.
     */
    fun due(rule: RecurringRule, to: LocalDate): List<LocalDate> {
        if (!rule.enabled || rule.amount.signum() <= 0) return emptyList()
        val step = rule.everyMonths.coerceIn(1, 12).toLong()
        val out = ArrayList<LocalDate>()
        var m = YearMonth.from(rule.startDate)
        while (out.size < 600) {
            val d = dateIn(m, rule.dayOfMonth)
            if (d.isAfter(to)) break
            if (rule.endDate != null && d.isAfter(rule.endDate)) break
            if (!d.isBefore(rule.startDate) && (rule.lastGenerated == null || d.isAfter(rule.lastGenerated))) out.add(d)
            m = m.plusMonths(step)
        }
        return out
    }

    /** Prossima data in programma dopo `from` (per mostrarla all'utente), null se la regola è finita o spenta. */
    fun next(rule: RecurringRule, from: LocalDate): LocalDate? {
        if (!rule.enabled) return null
        val step = rule.everyMonths.coerceIn(1, 12).toLong()
        var m = YearMonth.from(rule.startDate)
        repeat(1200) {
            val d = dateIn(m, rule.dayOfMonth)
            if (rule.endDate != null && d.isAfter(rule.endDate)) return null
            if (!d.isBefore(rule.startDate) && d.isAfter(from)) return d
            m = m.plusMonths(step)
        }
        return null
    }

    fun describe(rule: RecurringRule): String = when (rule.everyMonths) {
        1 -> "ogni mese"; 12 -> "ogni anno"; else -> "ogni ${rule.everyMonths} mesi"
    } + ", il giorno ${rule.dayOfMonth}"
}
