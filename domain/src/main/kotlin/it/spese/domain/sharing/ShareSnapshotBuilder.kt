package it.spese.domain.sharing

import it.spese.domain.finance.PaceEngine
import it.spese.domain.finance.Period
import it.spese.domain.finance.TxFilter
import it.spese.domain.repository.AppData
import java.time.LocalDate

/** Costruisce ciò che si pubblica per l'altra persona: il ritmo di spesa del conto condiviso (anche se è «gestito da altri») e il suo limite. */
object ShareSnapshotBuilder {
    fun build(data: AppData, accountId: Long, cfg: ShareConfig, nowMillis: Long, realToday: LocalDate = LocalDate.now()): ShareSnapshot? {
        val account = data.accountsById[accountId] ?: return null
        // i movimenti dei conti gestiti da altri stanno fuori dalle analisi: qui servono, perché è proprio quel conto
        val all = data.copy(transactions = data.everything)
        val r = PaceEngine.compute(all, TxFilter(period = Period.CurrentMonth, accountId = accountId, calendar = data.calendar), realToday, emptySet(), cfg.savingsMonthly, cfg.budgetMonthly) ?: return null
        return ShareSnapshot(
            generatedAtMillis = nowMillis, title = account.name, unit = r.unit.name, periodStart = r.start, periodEnd = r.end, today = r.today, days = r.days, dayIndex = r.dayIndex,
            limit = r.optimalTotal, spentToDate = r.spentToDate, optimalToDate = r.optimalToDate, dailyRemaining = r.optimalDailyRemaining, dailyAverage = r.optimalDailyAverage,
            current = r.current, average = r.average, optimal = r.optimal, features = cfg.features, note = cfg.note,
        )
    }
}
