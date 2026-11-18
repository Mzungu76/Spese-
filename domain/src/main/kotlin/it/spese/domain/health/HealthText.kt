package it.spese.domain.health

import it.spese.core.common.Money

/** La frase di sintesi del parere: dice il risparmio rispetto all'obiettivo del 20% e il punto più debole, con i numeri. */
object HealthText {
    fun headline(r: HealthReport): String {
        val parts = ArrayList<String>()
        if (r.provisional) parts.add("Parere provvisorio (${r.monthCount} ${if (r.monthCount == 1) "mese" else "mesi"} di storico).")
        if (r.gap.signum() <= 0) parts.add("In prudenza metti da parte ${Money.eur0(r.prudentSaving)} al mese: l'obiettivo del 20% (${Money.eur0(r.targetSaving)}) è coperto.")
        else parts.add("Metti da parte il ${r.savingRatePercent ?: 0}% delle entrate tipiche; in prudenza ${Money.eur0(r.prudentSaving)} al mese contro i ${Money.eur0(r.targetSaving)} dell'obiettivo del 20%: mancano ${Money.eur0(r.gap)}.")
        val weak = r.indicators.filter { it.score != null && it.weight >= 10 }.minByOrNull { it.score!! }
        if (weak != null && weak.level != Level.GOOD && weak.id != "saving") parts.add(weak.explanation)
        return parts.joinToString(" ")
    }
}
