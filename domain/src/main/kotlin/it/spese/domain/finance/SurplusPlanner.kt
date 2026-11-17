package it.spese.domain.finance

import it.spese.core.model.AccountType
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.math.RoundingMode

enum class FundStage { BUILD, GROW, COMPLETE }

data class AllocationStep(val label: String, val amount: BigDecimal, val why: String)

/**
 * Fondo di emergenza e destinazione di ciò che avanza.
 * Ordine consigliato: 1) fondo di emergenza (3 mesi di spese essenziali), 2) portarlo fino a 6 mesi e iniziare i primi obiettivi,
 * 3) investimenti e obiettivi a lungo termine. È un percorso educativo, non una consulenza finanziaria.
 */
data class SurplusPlan(
    val essentialMonthly: BigDecimal,
    val liquidBalance: BigDecimal,
    val monthsCovered: BigDecimal,
    val fundMin: BigDecimal,
    val fundMax: BigDecimal,
    val stage: FundStage,
    /** Ciò che è avanzato (ultimo mese chiuso o, in mancanza, la media mensile), solo se positivo. */
    val surplus: BigDecimal,
    val steps: List<AllocationStep>,
    /** Mesi per arrivare al fondo minimo versando il surplus attuale (null se non calcolabile). */
    val monthsToMinFund: Int?,
    /** Falso se nessun conto ha un saldo iniziale: il saldo è la sola somma dei movimenti importati. */
    val balanceReliable: Boolean,
) {
    val fundProgress: Float get() = if (fundMin.signum() > 0) (liquidBalance.toFloat() / fundMin.toFloat()).coerceIn(0f, 1f) else 0f
}

object SurplusPlanner {
    /** Liquidità: saldo di conti, carte e contanti (gli investimenti non contano come fondo di emergenza). */
    fun liquidBalance(data: AppData): BigDecimal {
        val liquid = data.accounts.filter { !it.archived && it.type != AccountType.INVESTIMENTO }
        val ids = liquid.map { it.id }.toSet()
        return liquid.fold(BigDecimal.ZERO) { a, c -> a + c.openingBalance } + data.transactions.filter { it.accountId in ids }.fold(BigDecimal.ZERO) { a, t -> a + t.amount }
    }

    /** Spesa mensile "essenziale": categorie fisse e necessarie (con la natura proposta quando non è stata scelta). */
    fun essentialMonthly(tx: List<Transaction>, data: AppData, suggestions: Map<Long, NatureSuggestion>): BigDecimal {
        val spese = tx.filter { it.kind == TxKind.SPESA }
        if (spese.isEmpty()) return BigDecimal.ZERO
        val byId = data.categoriesById
        val months = StatsEngine.monthsSpan(spese)
        val roll = CategoryTree.rollup(StatsEngine.compute(spese).byCategory, byId)
        val total = roll.entries.fold(BigDecimal.ZERO) { a, e ->
            val nature = byId[e.key]?.nature ?: (e.key?.let { suggestions[it]?.nature })
            if (nature == SpendNature.FIXED || nature == SpendNature.ESSENTIAL) a + e.value else a
        }
        return total.divide(months, 2, RoundingMode.HALF_UP)
    }

    fun plan(liquid: BigDecimal, essentialMonthly: BigDecimal, surplus: BigDecimal, balanceReliable: Boolean = true): SurplusPlan? {
        if (essentialMonthly.signum() <= 0) return null
        val covered = liquid.divide(essentialMonthly, 1, RoundingMode.HALF_UP)
        val min = essentialMonthly.multiply(BigDecimal(3)); val max = essentialMonthly.multiply(BigDecimal(6))
        val stage = when { covered < BigDecimal(3) -> FundStage.BUILD; covered < BigDecimal(6) -> FundStage.GROW; else -> FundStage.COMPLETE }
        val s = surplus.max(BigDecimal.ZERO)
        fun part(pct: Int) = s.multiply(BigDecimal(pct)).divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
        val steps = when (stage) {
            FundStage.BUILD -> listOf(AllocationStep("Fondo di emergenza", part(100), "Prima di tutto una rete di sicurezza: ti protegge da imprevisti senza dover fare debiti."))
            FundStage.GROW -> listOf(
                AllocationStep("Fondo di emergenza", part(50), "Completalo fino a 6 mesi di spese essenziali."),
                AllocationStep("Obiettivi e investimenti", part(50), "Ora puoi iniziare a far crescere i soldi con orizzonte lungo."),
            )
            FundStage.COMPLETE -> listOf(
                AllocationStep("Investimenti e obiettivi a lungo termine", part(80), "Il fondo è completo: il resto lavora per il tuo futuro."),
                AllocationStep("Piacere e imprevisti", part(20), "Una parte per te: risparmiare è sostenibile se non rinunci a tutto."),
            )
        }
        val missing = (min - liquid)
        val monthsToMin = if (stage == FundStage.BUILD && s.signum() > 0 && missing.signum() > 0) missing.divide(s, 0, RoundingMode.CEILING).toInt() else null
        return SurplusPlan(essentialMonthly, liquid, covered, min, max, stage, s, steps, monthsToMin, balanceReliable)
    }

    fun compute(data: AppData, tx: List<Transaction>, suggestions: Map<Long, NatureSuggestion>, surplus: BigDecimal): SurplusPlan? =
        plan(liquidBalance(data), essentialMonthly(tx, data, suggestions), surplus, data.accounts.any { it.openingBalance.signum() != 0 })
}
