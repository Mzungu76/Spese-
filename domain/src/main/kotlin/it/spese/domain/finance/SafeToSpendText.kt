package it.spese.domain.finance

import it.spese.core.common.Dates
import it.spese.core.common.Money

/** Testi italiani della cifra «disponibile», usati da Home, notifica del mattino e widget (stessa frase ovunque). */
object SafeToSpendText {
    private fun signed(v: java.math.BigDecimal) = (if (v.signum() < 0) "−" else "") + Money.eur0(v.abs())

    /** Titolo breve: la cifra al giorno. */
    fun perDay(r: SafeToSpendReport): String = when {
        r.status == SafeStatus.NOT_ENOUGH -> "Non ancora calcolabile"
        r.perDay == null -> "Periodo concluso"
        r.available.signum() < 0 -> "Margine finito"
        else -> signed(r.perDay) + " al giorno"
    }

    /** Frase di commento, una sola riga. */
    fun comment(r: SafeToSpendReport): String = when (r.status) {
        SafeStatus.NOT_ENOUGH -> "Servono le entrate di almeno un mese intero per stimare quanto puoi spendere."
        SafeStatus.OVER -> "Hai superato di ${Money.eur0(r.available.abs())} ciò che il mese ti permetteva, tenendo conto del risparmio e delle uscite in arrivo."
        SafeStatus.BEHIND -> "Stai spendendo più in fretta del previsto (${Money.eur0(r.deltaVsPlan.abs())} oltre il piano fin qui): restano ${Money.eur0(r.available)} fino al ${Dates.fmt(r.periodEnd)}."
        SafeStatus.OK -> if (r.deltaVsPlan.signum() > 0) "Sei avanti di ${Money.eur0(r.deltaVsPlan)} sul piano: restano ${Money.eur0(r.available)} fino al ${Dates.fmt(r.periodEnd)}."
            else "In linea con il piano: restano ${Money.eur0(r.available)} fino al ${Dates.fmt(r.periodEnd)}."
    } + if (r.stale) " (Dati fermi al ${Dates.fmt(r.today)}.)" else ""
}
