package it.spese.domain.finance

import it.spese.core.common.Money
import it.spese.core.model.Category
import it.spese.core.model.SpendNature
import java.math.BigDecimal

/** Una categoria su cui un taglio ha senso, con quanto si risparmierebbe. */
data class Opportunity(val row: CategoryRow, val monthlySaving: BigDecimal, val yearlySaving: BigDecimal)

/** Dove si può risparmiare: le spese discrezionali (o, se la natura non è definita, quelle segnate "comprimibili") più pesanti. */
object SavingsOpportunities {
    fun isCandidate(c: Category?): Boolean = c != null && (c.nature == SpendNature.DISCRETIONARY || (c.nature == null && c.flexible && c.systemKey == null))

    fun compute(rows: List<CategoryRow>, percent: Int = 20, limit: Int = 3, minMonthly: BigDecimal = BigDecimal(10)): List<Opportunity> =
        rows.filter { isCandidate(it.category) }
            .map { r -> val m = Money.pctOf(r.monthlyAvg, percent); Opportunity(r, m, m.multiply(BigDecimal(12))) }
            .filter { it.monthlySaving >= minMonthly }
            .sortedByDescending { it.monthlySaving }
            .take(limit)

    /** Categorie con aumento significativo, le più pesanti per prime. */
    fun surging(rows: List<CategoryRow>, limit: Int = 3): List<CategoryRow> = rows.filter { it.surging }.sortedByDescending { it.delta }.take(limit)
}
