package it.spese.domain.sharing

import it.spese.core.common.Dates
import it.spese.core.common.Money
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate

enum class GuestAlertKind { NEAR, LIMIT, WEEKLY }
data class GuestAlert(val kind: GuestAlertKind, val title: String, val text: String, val key: String)

object GuestAlerts {
    /** Soglia di preavviso: l'80% del limite. */
    private val NEAR = BigDecimal("0.8")

    /**
     * Gli avvisi non ancora mandati: all'80% e al 100% del limite del mese (una volta per mese), e il lunedì il totale della settimana scorsa.
     * Si calcolano sull'ultimo riepilogo ricevuto, quindi funzionano anche senza rete.
     */
    fun evaluate(st: GuestState, today: LocalDate): List<GuestAlert> {
        val out = ArrayList<GuestAlert>()
        val month = st.snapshots.firstOrNull { s -> s.unit == "MONTH" }
        val limit = month?.limit
        if (month != null && limit != null && limit.signum() > 0) {
            val ratio = month.spentToDate.divide(limit, 4, java.math.RoundingMode.HALF_UP)
            val nearKey = "near|${month.periodStart}"; val limitKey = "limit|${month.periodStart}"
            val left = (limit - month.spentToDate).max(BigDecimal.ZERO)
            if (st.alertLimit && ratio >= BigDecimal.ONE && limitKey !in st.notified)
                out.add(GuestAlert(GuestAlertKind.LIMIT, "Limite raggiunto", "${month.title}: hai speso ${Money.eur0(month.spentToDate)} su ${Money.eur0(limit)} fino al ${Dates.fmt(month.periodEnd).take(5)}.", limitKey))
            else if (st.alertNear && ratio >= NEAR && ratio < BigDecimal.ONE && nearKey !in st.notified)
                out.add(GuestAlert(GuestAlertKind.NEAR, "Hai usato l'80% del limite", "${month.title}: restano ${Money.eur0(left)} fino al ${Dates.fmt(month.periodEnd).take(5)}.", nearKey))
        }
        val week = st.snapshots.firstOrNull { s -> s.unit == "WEEK" }
        val prev = week?.previousTotal
        val weeklyKey = "weekly|${today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))}"
        if (st.alertWeekly && today.dayOfWeek == DayOfWeek.MONDAY && prev != null && prev.signum() > 0 && weeklyKey !in st.notified)
            out.add(GuestAlert(GuestAlertKind.WEEKLY, "La settimana scorsa", "Hai speso ${Money.eur0(prev)}.", weeklyKey))
        return out
    }
}

/** Testi brevi per il widget e le notifiche. */
object GuestSummary {
    /** «Oggi puoi spendere»: la cifra al giorno, o un trattino se non c'è (limite non calcolabile o ultimo giorno). */
    fun perDay(s: ShareSnapshot?): String = s?.dailyRemaining?.let { v -> Money.eur(v.max(BigDecimal.ZERO)) } ?: "—"
}
