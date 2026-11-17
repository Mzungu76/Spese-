package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.SavingsGoal
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.domain.simulation.PlanEngine
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

enum class Tone { PRAISE, TIP, WARNING }

/** Un consiglio o un riconoscimento basato sui dati dell'utente. */
data class Insight(val id: String, val tone: Tone, val title: String, val body: String, val categoryId: Long? = null)

data class CoachInput(
    val tx: List<Transaction>,
    val categories: List<Category>,
    val goal: SavingsGoal,
    val recurring: List<RecurringItem>,
    val natureTotals: Map<SpendNature?, BigDecimal>,
    val unclassified: Int,
    val surplus: SurplusPlan?,
    val accruals: AccrualPlan,
    val staleDays: Int,
    val today: LocalDate,
)

/** Osservazioni concrete e azioni, in ordine di importanza: prima gli avvisi, poi i riconoscimenti, poi i suggerimenti. */
object Coach {
    private fun rate(income: BigDecimal, net: BigDecimal): BigDecimal? = if (income.signum() > 0) net.multiply(Money.HUNDRED).divide(income, 0, RoundingMode.HALF_UP) else null

    fun insights(i: CoachInput, limit: Int = 6): List<Insight> {
        val out = ArrayList<Insight>()
        val ym = YearMonth.from(i.today)
        val byId = i.categories.associateBy { it.id }
        val stats = StatsEngine.compute(i.tx)
        val full = stats.byMonth.keys.filter { it < ym }.sorted()

        if (i.staleDays > 7) out.add(Insight("stale", Tone.WARNING, "I dati non sono aggiornati",
            "L'ultimo movimento risale a ${i.staleDays} giorni fa: importa l'estratto conto o registra le ultime spese per avere numeri affidabili."))

        // tendenza del tasso di risparmio: ultimi 3 mesi chiusi contro i 3 precedenti
        if (full.size >= 4) {
            val recent = full.takeLast(3); val before = full.dropLast(3).takeLast(3)
            fun r(ms: List<YearMonth>) = rate(ms.fold(BigDecimal.ZERO) { a, m -> a + stats.byMonth.getValue(m).income }, ms.fold(BigDecimal.ZERO) { a, m -> a + stats.byMonth.getValue(m).net })
            val a = r(recent); val b = r(before)
            if (a != null && b != null) {
                val d = a - b
                if (d >= BigDecimal(5)) out.add(Insight("trend-up", Tone.PRAISE, "Stai risparmiando di più", "Il tasso di risparmio è passato dal ${b.toInt()}% al ${a.toInt()}% negli ultimi 3 mesi. Continua così."))
                else if (d <= BigDecimal(-5)) out.add(Insight("trend-down", Tone.WARNING, "Il risparmio sta calando", "Il tasso di risparmio è sceso dal ${b.toInt()}% al ${a.toInt()}% negli ultimi 3 mesi. Controlla dove sono cresciute le spese."))
            }
        }

        // mesi consecutivi in cui l'obiettivo è stato raggiunto
        val goalM = PlanEngine.goalMonthly(i.goal)
        if (goalM.signum() > 0) {
            var streak = 0
            for (m in full.reversed()) { if (stats.byMonth.getValue(m).net >= goalM) streak++ else break }
            if (streak >= 2) out.add(Insight("streak", Tone.PRAISE, "$streak mesi di fila sull'obiettivo", "Hai messo da parte almeno ${Money.eur0(goalM)} per $streak mesi consecutivi. La costanza è ciò che conta."))
        }

        // spese ricorrenti discrezionali (abbonamenti e simili)
        val subs = i.recurring.filter { SavingsOpportunities.isCandidate(byId[CategoryTree.rootId(it.categoryId, byId)]) }
        if (subs.size >= 2) {
            val yearly = subs.fold(BigDecimal.ZERO) { a, r -> a + r.mean }.multiply(BigDecimal(12))
            if (yearly >= BigDecimal(100)) out.add(Insight("subs", Tone.TIP, "Abbonamenti e ricorrenze: ${Money.eur0(yearly)} all'anno",
                subs.sortedByDescending { it.mean }.take(4).joinToString(", ") { "${it.merchant} (${Money.eur0(it.mean)})" } + ". Li usi tutti? Disdire quelli che non usi è il risparmio più facile."))
        }

        // peso delle spese discrezionali (riferimento 50/30/20)
        val total = i.natureTotals.values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val defined = i.natureTotals.filterKeys { it != null }.values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val disc = i.natureTotals[SpendNature.DISCRETIONARY] ?: BigDecimal.ZERO
        if (total.signum() > 0 && defined >= total.multiply(BigDecimal("0.5"))) {
            val share = Money.percent(disc, total, 0)
            if (share > BigDecimal(35)) out.add(Insight("disc", Tone.TIP, "Le spese discrezionali pesano il ${share.toInt()}%",
                "Come riferimento (50/30/20) i desideri stanno attorno al 30% delle uscite. Ridurre di poco le voci più pesanti libera margine per il risparmio."))
        }

        // fondo di emergenza
        i.surplus?.takeIf { it.balanceReliable }?.let { s ->
            when (s.stage) {
                FundStage.BUILD -> out.add(Insight("fund", Tone.TIP, "Costruisci il fondo di emergenza", "Oggi la tua liquidità copre ${s.monthsCovered.toPlainString()} mesi di spese essenziali: punta ad almeno 3 (${Money.eur0(s.fundMin)})."))
                FundStage.GROW -> out.add(Insight("fund", Tone.TIP, "Fondo di emergenza a buon punto", "Copri ${s.monthsCovered.toPlainString()} mesi di spese essenziali. Completalo fino a 6 (${Money.eur0(s.fundMax)}) e poi pensa agli investimenti."))
                FundStage.COMPLETE -> out.add(Insight("fund", Tone.PRAISE, "Fondo di emergenza completo", "Copri ${s.monthsCovered.toPlainString()} mesi di spese essenziali. Ora ciò che avanza può lavorare per obiettivi a lungo termine."))
            }
        }

        // spese annuali in arrivo
        i.accruals.items.firstOrNull { it.daysLeft in 0..90 }?.let { a ->
            out.add(Insight("annual", Tone.TIP, "Tra ${a.daysLeft} giorni: ${a.name} (${Money.eur0(a.amount)})",
                "Mettendo da parte ${Money.eur0(a.monthly)} al mese non sarà una sorpresa. A oggi dovresti averne già accantonati circa ${Money.eur0(a.shouldHaveSaved)}."))
        }

        if (i.unclassified >= 10) out.add(Insight("unclassified", Tone.TIP, "${i.unclassified} movimenti senza categoria",
            "Classificarli rende più precisi budget, consigli e avvisi. Basta una regola per esercente."))

        val order = mapOf(Tone.WARNING to 0, Tone.PRAISE to 1, Tone.TIP to 2)
        return out.sortedBy { order.getValue(it.tone) }.take(limit)
    }
}

data class Lesson(val id: String, val title: String, val body: String)

/** Piccole lezioni di educazione finanziaria, scelte in base alla situazione e ruotate ogni giorno. */
object Lessons {
    /** Capitale dopo `years` anni versando `monthly` al mese a un tasso annuo nominale `ratePct` (solo a scopo illustrativo). */
    fun futureValue(monthly: BigDecimal, years: Int, ratePct: Int): BigDecimal {
        val n = years * 12
        val r = BigDecimal(ratePct).divide(BigDecimal(1200), 12, RoundingMode.HALF_UP)
        if (r.signum() == 0) return monthly.multiply(BigDecimal(n))
        val growth = BigDecimal.ONE.add(r).pow(n, MathContext(30))
        return monthly.multiply(growth.subtract(BigDecimal.ONE)).divide(r, 2, RoundingMode.HALF_UP)
    }

    fun all(monthlySaving: BigDecimal): List<Lesson> {
        val m = monthlySaving.max(BigDecimal(50)).setScale(0, RoundingMode.HALF_UP)
        val paid = m.multiply(BigDecimal(240))
        val fv = futureValue(m, 20, 4)
        return listOf(
            Lesson("pay-yourself", "Paga prima te stesso", "Appena arriva lo stipendio, sposta subito la quota di risparmio: spendi ciò che resta, non risparmi ciò che resta."),
            Lesson("fund", "A cosa serve il fondo di emergenza", "Sono 3–6 mesi di spese essenziali tenuti da parte e facilmente disponibili. Serve per imprevisti (auto, salute, lavoro) senza vendere investimenti o indebitarti."),
            Lesson("compound", "L'interesse composto lavora per te", "Mettendo da parte ${Money.eur0(m)} al mese per 20 anni, con un rendimento medio del 4% annuo, versi ${Money.eur0(paid)} e arrivi a circa ${Money.eur0(fv)}. Il tempo conta più dell'importo."),
            Lesson("rule-503020", "La regola 50/30/20", "Circa il 50% del reddito per i bisogni, 30% per i desideri, 20% per risparmio e investimenti. Non è una legge: è un punto di partenza da adattare."),
            Lesson("subs", "Il costo nascosto dei piccoli abbonamenti", "Dieci euro al mese sono 120 euro all'anno. Una revisione trimestrale degli abbonamenti è il taglio meno doloroso."),
            Lesson("inflation", "Perché il conto corrente non basta", "Con l'inflazione i soldi fermi perdono potere d'acquisto: tieni liquido il fondo di emergenza, ma ciò che non ti serve a breve va fatto lavorare."),
            Lesson("debt", "Prima i debiti costosi", "Un debito con interessi alti (carte revolving, prestiti personali) costa spesso più di quanto renda qualsiasi investimento: estinguerlo è un rendimento sicuro."),
            Lesson("annual", "Spalma le spese annuali", "Assicurazione, bollo e tasse arrivano tutte insieme: dividerle per 12 e accantonarle ogni mese evita gli scossoni di bilancio."),
        )
    }

    fun forToday(today: LocalDate, stage: FundStage?, monthlySaving: BigDecimal): Lesson {
        val list = all(monthlySaving)
        val first = when (stage) { FundStage.BUILD -> "fund"; FundStage.GROW -> "compound"; FundStage.COMPLETE -> "inflation"; null -> null }
        // a rotazione giornaliera, con la lezione più pertinente un giorno su tre
        if (first != null && today.dayOfYear % 3 == 0) return list.first { it.id == first }
        return list[today.dayOfYear % list.size]
    }
}
