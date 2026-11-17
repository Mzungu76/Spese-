package it.spese.domain.networth

import it.spese.core.common.Money
import java.math.BigDecimal

/** Frasi italiane per il fondo di emergenza e il margine investibile. */
object NetWorthText {
    fun fund(f: EmergencyFund, liquidity: BigDecimal): String {
        val m = f.months ?: return "Servono almeno un mese di movimenti per stimare le tue spese essenziali."
        val base = if (f.essentialKnown) "spese essenziali (fisse e necessarie) di ${Money.eur0(f.monthlyEssential)} al mese" else "spese di ${Money.eur0(f.monthlyEssential)} al mese (indica la natura delle categorie per contare solo le essenziali)"
        val left = f.monthlyEssential.multiply(f.target) - liquidity
        return when (f.status) {
            FundStatus.OK -> "La liquidità copre ${m.toPlainString().replace('.', ',')} mesi di $base: il fondo di emergenza è completo."
            FundStatus.BUILDING -> "La liquidità copre ${m.toPlainString().replace('.', ',')} mesi di $base. Il minimo è ${f.minTarget.toInt()}, quello consigliato ${f.target.toInt()}: mancano ${Money.eur0(left)}."
            FundStatus.LOW -> "La liquidità copre solo ${m.toPlainString().replace('.', ',')} mesi di $base: prima di investire costruisci il fondo (minimo ${f.minTarget.toInt()} mesi, consigliato ${f.target.toInt()})."
            FundStatus.UNKNOWN -> "Servono almeno un mese di movimenti per stimare le tue spese essenziali."
        }
    }

    fun margin(m: InvestableMargin, f: EmergencyFund): String = when {
        f.status == FundStatus.UNKNOWN -> "Il margine si calcola quando l'app conosce le tue spese."
        m.margin.signum() > 0 -> "Hai ${Money.eur0(m.margin)} di liquidità oltre il fondo di emergenza, i soldi già destinati agli obiettivi e le scadenze dei prossimi 6 mesi: è il margine che potresti investire."
        else -> "Nessun margine da investire per ora: la liquidità serve a completare il fondo di emergenza, agli obiettivi e alle scadenze in arrivo."
    }
}
