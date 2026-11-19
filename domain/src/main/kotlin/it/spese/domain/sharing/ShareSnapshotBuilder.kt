package it.spese.domain.sharing

import it.spese.domain.finance.PaceEngine
import it.spese.domain.finance.Period
import it.spese.domain.finance.PaceUnit
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import java.time.LocalDate

/** Costruisce ciò che si pubblica per l'altra persona: il ritmo di spesa del conto condiviso (anche se è «gestito da altri») e il suo limite. */
object ShareSnapshotBuilder {
    /** I tre periodi che l'altra persona può scegliere: settimana, mese e trimestre (quelli che non si possono calcolare mancano). */
    fun buildAll(data: AppData, accountIds: List<Long>, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now()): List<ShareSnapshot> =
        listOf(Period.CurrentWeek, Period.CurrentMonth, Period.Last3Months).mapNotNull { p -> build(data, accountIds, cfg, nowMillis, realToday, p) }

    fun build(data: AppData, accountId: Long, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now()): ShareSnapshot? = build(data, listOf(accountId), cfg, nowMillis, realToday)

    /** Con più conti il budget e il ritmo sono quelli dei loro movimenti insieme (le spese e le entrate di tutti i conti scelti). */
    fun build(data: AppData, accountIds: List<Long>, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now(), period: Period = Period.CurrentMonth): ShareSnapshot? {
        val accounts = accountIds.distinct().mapNotNull { id -> data.accountsById[id] }
        if (accounts.isEmpty()) return null
        val ids = accounts.map { a -> a.id }.toSet()
        val title = if (accounts.size <= 2) accounts.joinToString(" + ") { a -> a.name } else "${accounts.size} conti"
        // i movimenti dei conti gestiti da altri stanno fuori dalle analisi: qui servono, perché sono proprio quei conti
        val all = data.copy(transactions = data.everything.filter { t -> t.accountId in ids })
        val r = PaceEngine.compute(all, TxFilter(period = period, calendar = data.calendar), realToday, emptySet(), cfg.savingsMonthly, cfg.budgetMonthly) ?: return null
        return ShareSnapshot(
            generatedAtMillis = nowMillis, title = title, unit = r.unit.name, periodStart = r.start, periodEnd = r.end, today = r.today, days = r.days, dayIndex = r.dayIndex,
            limit = r.optimalTotal, spentToDate = r.spentToDate, optimalToDate = r.optimalToDate, dailyRemaining = r.optimalDailyRemaining, dailyAverage = r.optimalDailyAverage,
            current = r.current, average = r.average, optimal = r.optimal, features = cfg.features, note = cfg.note,
        )
    }
}
