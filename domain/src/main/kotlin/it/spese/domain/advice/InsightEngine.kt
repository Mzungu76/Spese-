package it.spese.domain.advice

import it.spese.core.common.Dates
import it.spese.core.common.Money
import it.spese.core.model.Goal
import it.spese.core.model.TxKind
import it.spese.core.model.Valuation
import it.spese.domain.finance.AlertEngine
import it.spese.domain.finance.CategoryTree
import it.spese.domain.finance.RecurringPattern
import it.spese.domain.finance.StatsEngine
import it.spese.domain.goals.GoalEngine
import it.spese.domain.goals.GoalStatus
import it.spese.domain.networth.FundStatus
import it.spese.domain.networth.NetWorthEngine
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate

/** Dove porta il consiglio (la schermata la sceglie l'app). */
enum class InsightTarget { NONE, GOALS, NET_WORTH, SUBSCRIPTIONS, REVIEW }

/** Un consiglio breve: cosa succede, perché conta (`why`) e, se serve, una scheda della guida da leggere. level: 2 = importante, 1 = da guardare, 0 = spunto. */
data class Insight(val id: String, val level: Int, val title: String, val text: String, val why: String, val topic: LearnTopic? = null, val target: InsightTarget = InsightTarget.NONE)

/** Consigli da regole trasparenti: ognuno dice da quali numeri nasce e perché conta. */
object InsightEngine {
    /** Unico ingresso per Home e schermata Consigli: rileva da sé abbonamenti e rate (senza quelli ignorati o già coperti da una ricorrenza). */
    fun forApp(data: AppData, goals: List<Goal>, valuations: List<Valuation>, rules: List<it.spese.core.model.RecurringRule>, ignoredPatterns: Set<String>, today: LocalDate = LocalDate.now(), limit: Int = 5): List<Insight> {
        if (!data.loaded || data.transactions.isEmpty()) return emptyList()
        val patterns = it.spese.domain.finance.SubscriptionDetector.detect(data.transactions, it.spese.domain.finance.SubscriptionDetector.excludedCategories(data.categories), today, rules).filter { p -> p.key !in ignoredPatterns }
        return compute(data, goals, valuations, patterns, today, limit)
    }

    fun compute(data: AppData, goals: List<Goal>, valuations: List<Valuation>, patterns: List<RecurringPattern>, today: LocalDate = LocalDate.now(), limit: Int = 5): List<Insight> {
        val out = ArrayList<Insight>()
        val net = runCatching { NetWorthEngine.compute(data, goals, valuations, today) }.getOrNull()
        val review = runCatching { MonthReviewEngine.compute(data, goals, today) }.getOrNull()

        // fondo di emergenza e margine da investire
        net?.let { n ->
            val f = n.fund
            when (f.status) {
                FundStatus.LOW -> out.add(Insight("fund-low", 2, "Il fondo di emergenza è sotto i ${f.minTarget.toInt()} mesi",
                    "La liquidità copre ${f.months!!.toPlainString().replace('.', ',')} mesi di spese essenziali.", "Un imprevisto (auto, salute, lavoro) senza fondo costringe a indebitarsi o a vendere investimenti nel momento peggiore.", LearnTopic.EMERGENCY_FUND, InsightTarget.NET_WORTH))
                FundStatus.BUILDING -> out.add(Insight("fund-building", 1, "Il fondo di emergenza è a ${f.months!!.toPlainString().replace('.', ',')} mesi",
                    "Il minimo è ${f.minTarget.toInt()} mesi, quello consigliato ${f.target.toInt()}.", "Completarlo prima di investire evita di dover disinvestire in un momento sfavorevole.", LearnTopic.EMERGENCY_FUND, InsightTarget.NET_WORTH))
                FundStatus.OK -> if (n.margin.margin >= BigDecimal(100)) out.add(Insight("invest-margin", 0, "Hai ${Money.eur0(n.margin.margin)} di margine da investire",
                    "Oltre al fondo di emergenza, agli obiettivi e alle scadenze dei prossimi 6 mesi.", "Il denaro fermo sul conto perde potere d'acquisto con l'inflazione; investito con orizzonte lungo può crescere.", LearnTopic.COMPOUND_INTEREST, InsightTarget.NET_WORTH))
                FundStatus.UNKNOWN -> {}
            }
        }
        // obiettivi in ritardo
        GoalEngine.all(goals).filter { it.status == GoalStatus.LATE }.take(1).forEach { p ->
            out.add(Insight("goal-late-${p.goal.id}", 2, "«${p.goal.name}» è in ritardo", "Per la scadenza servono ${Money.eur0(p.neededForDeadline!!)} al mese: ne metti ${Money.eur0(p.monthlyQuota)}.", "Una quota più bassa del necessario sposta l'arrivo: meglio rivederla ora che alla scadenza.", null, InsightTarget.GOALS))
        }
        // risparmio dell'ultimo mese
        review?.let { r ->
            r.savingsRatePercent?.let { rate ->
                if (rate < 10) out.add(Insight("saving-low", 1, "Hai risparmiato il $rate% di ${monthName(r)}", "La regola 50/30/20 indica almeno il 20% delle entrate.", "Risparmiare presto e con regolarità conta più dell'importo: è l'abitudine che costruisce il patrimonio.", LearnTopic.RULE_50_30_20, InsightTarget.REVIEW))
            }
            if (r.actions.isNotEmpty()) out.add(Insight("review-ready", 0, "La chiusura di ${monthName(r)} ha ${r.actions.size} ${if (r.actions.size == 1) "azione" else "azioni"} da valutare", "Ogni azione si applica con un tocco.", "Un controllo mensile veloce vale più di tanti controlli distratti.", null, InsightTarget.REVIEW))
        }
        // categoria in crescita da tre mesi
        growing(data, review)?.let { out.add(it) }
        // abbonamenti e rate aumentati
        patterns.filter { it.priceChanged && (it.priceDelta ?: BigDecimal.ZERO).signum() > 0 && !it.stale }.maxByOrNull { it.priceDelta ?: BigDecimal.ZERO }?.let { p ->
            out.add(Insight("sub-up-${p.key}", 1, "${p.merchant} è aumentato di ${Money.eur(p.priceDelta!!)}", "Da ${Money.eur(p.previousAmount!!)} a ${Money.eur(p.lastAmount)} (${Money.eur0(p.perYear - p.previousAmount!!.multiply(BigDecimal(12)).divide(BigDecimal(p.cadence.months), 2, java.math.RoundingMode.HALF_UP))} in più all'anno).", "Gli aumenti piccoli passano inosservati ma si ripetono ogni mese: ogni tanto va chiesto se il servizio vale ancora il prezzo.", LearnTopic.FIXED_VS_DISCRETIONARY, InsightTarget.SUBSCRIPTIONS))
        }
        return out.sortedByDescending { it.level }.take(limit)
    }

    private fun monthName(r: MonthReview) = Dates.monthLabelLong(r.month)

    private fun growing(data: AppData, review: MonthReview?): Insight? {
        val pm = review?.month ?: return null
        val byId = data.categoriesById
        val tx = data.transactions.filter { it.kind != TxKind.TRASFERIMENTO }
        val byMonth = StatsEngine.compute(tx, data.calendar).byMonth
        fun rolled(m: java.time.YearMonth) = byMonth[m]?.let { CategoryTree.rollup(it.byCategory, byId) } ?: emptyMap()
        val m0 = rolled(pm.minusMonths(2)); val m1 = rolled(pm.minusMonths(1)); val m2 = rolled(pm)
        return m2.mapNotNull { (id, v2) ->
            val c = id?.let { byId[it] } ?: return@mapNotNull null
            if (c.systemKey != null) return@mapNotNull null
            val v0 = m0[id] ?: return@mapNotNull null; val v1 = m1[id] ?: return@mapNotNull null
            if (v0 < v1 && v1 < v2 && v2 >= v0.multiply(BigDecimal("1.2")) && v2 - v0 >= BigDecimal(30)) Triple(c, v0, v2) else null
        }.maxByOrNull { it.third - it.second }?.let { (c, v0, v2) ->
            Insight("growing-${c.id}", 1, "«${c.name}» cresce da 3 mesi", "Da ${Money.eur0(v0)} a ${Money.eur0(v2)} al mese.", "Una crescita lenta e costante è difficile da notare mese per mese, ma a fine anno pesa.", LearnTopic.FIXED_VS_DISCRETIONARY, InsightTarget.REVIEW)
        }
    }
}
