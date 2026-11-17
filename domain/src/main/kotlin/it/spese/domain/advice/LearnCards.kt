package it.spese.domain.advice

import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.domain.networth.NetWorthReport
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class LearnTopic(val title: String, val body: String) {
    EMERGENCY_FUND("Il fondo di emergenza",
        "È una riserva di liquidità per gli imprevisti: si misura in mesi di spese essenziali (affitto, bollette, spesa, trasporti…). Il minimo è 3 mesi, 6 se il reddito è incerto. Va tenuta disponibile (conto o deposito), non investita: serve proprio quando i mercati scendono."),
    RULE_50_30_20("La regola 50/30/20",
        "Un punto di partenza semplice: circa il 50% delle entrate ai bisogni (spese fisse e necessarie), il 30% ai desideri (svago, ristoranti, shopping) e il 20% al risparmio. Non è una legge: se i bisogni pesano di più, si parte da un risparmio più piccolo e lo si fa crescere."),
    COMPOUND_INTEREST("L'interesse composto",
        "Un investimento guadagna interessi, e poi guadagna interessi anche sugli interessi. Per questo conta più il tempo dell'importo: iniziare con poco ma presto batte iniziare tardi con tanto. I rendimenti non sono garantiti: l'esempio è un'ipotesi, non una promessa."),
    FIXED_VS_DISCRETIONARY("Spese fisse e discrezionali",
        "Le spese fisse (affitto, rate, abbonamenti) si ripetono uguali e si cambiano raramente ma con effetto duraturo. Quelle discrezionali (svago, ristoranti, shopping) cambiano da un mese all'altro e sono le più facili da ridurre. Per risparmiare di più conviene guardare prima le fisse (una volta sola) poi le discrezionali (ogni mese).");

    /** Frase legata ai tuoi numeri, se ci sono. */
    fun personal(ctx: LearnContext): String? = when (this) {
        EMERGENCY_FUND -> ctx.net?.takeIf { it.fund.months != null }?.let { n ->
            "Per te: 6 mesi di spese essenziali sono ${Money.eur0(n.fund.monthlyEssential.multiply(BigDecimal(6)))}; oggi la liquidità ne copre ${n.fund.months!!.toPlainString().replace('.', ',')}."
        }
        RULE_50_30_20 -> ctx.review?.let { r -> "Per te, ${Dates.monthLabelLong(r.month)}: bisogni ${r.split.needsPercent}%, desideri ${r.split.wantsPercent}%" + (if (r.split.otherPercent > 0) ", non classificate ${r.split.otherPercent}%" else "") + ", risparmio ${r.split.savingsPercent}%." }
        COMPOUND_INTEREST -> {
            val monthly = ctx.monthlySaving.takeIf { it.signum() > 0 } ?: BigDecimal(100)
            val fv = futureValue(monthly, 4, 10)
            "Esempio: ${Money.eur0(monthly)} al mese per 10 anni, con un rendimento medio ipotetico del 4% annuo, diventano circa ${Money.eur0(fv)} (ne avresti versati ${Money.eur0(monthly.multiply(BigDecimal(120)))})."
        }
        FIXED_VS_DISCRETIONARY -> ctx.review?.takeIf { it.income.signum() > 0 }?.let { r ->
            "Per te, ${Dates.monthLabelLong(r.month)}: le spese fisse e necessarie sono il ${r.split.needsPercent}% delle entrate, quelle discrezionali il ${r.split.wantsPercent}%."
        }
    }

    companion object {
        /** Valore futuro di un versamento mensile costante, capitalizzazione mensile (illustrativo). */
        fun futureValue(monthly: BigDecimal, annualPercent: Int, years: Int): BigDecimal {
            val i = BigDecimal(annualPercent).divide(BigDecimal(1200), MathContext.DECIMAL64)
            val growth = (BigDecimal.ONE + i).pow(years * 12, MathContext.DECIMAL64)
            return monthly.multiply((growth - BigDecimal.ONE).divide(i, MathContext.DECIMAL64)).setScale(0, RoundingMode.HALF_UP)
        }
    }
}

data class LearnContext(val net: NetWorthReport? = null, val review: MonthReview? = null, val monthlySaving: BigDecimal = BigDecimal.ZERO)
