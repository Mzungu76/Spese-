package it.spese.domain.finance

import it.spese.core.model.Category
import it.spese.core.model.SpendNature
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import java.math.BigDecimal
import java.math.RoundingMode

data class NatureSuggestion(val nature: SpendNature, val reason: String)

/**
 * Propone la natura di ogni categoria di spesa dai dati:
 * - **Fissa** se almeno il 60% della spesa mensile media è fatta di addebiti ricorrenti rilevati (stesso esercente, ogni mese, importo simile);
 * - **Necessaria** se il nome richiama le spese di base (alimentari, salute, casa, bollette, trasporti…);
 * - **Discrezionale** negli altri casi.
 * È solo un suggerimento: l'utente conferma o corregge.
 */
object NatureSuggester {
    private val ESSENTIAL = Regex("supermerc|aliment|spesa|salute|farmac|medic|casa|affitto|mutuo|condomin|tasse|imposte|bollett|utenze|famigli|persone|figli|scuola|auto|trasport|carbur|telefon|assicuraz|commission|bolli")

    fun suggest(categories: List<Category>, transactions: List<Transaction>, recurring: List<RecurringItem>): Map<Long, NatureSuggestion> {
        val spend = transactions.filter { it.kind == TxKind.SPESA }
        if (spend.isEmpty()) return categories.filter { it.kind == TxKind.SPESA }.associate { it.id to byName(it) }
        val months = StatsEngine.monthsSpan(spend)
        val avgByCat = spend.groupBy { it.categoryId }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, t -> a - t.amount }.divide(months, 10, RoundingMode.HALF_UP) }
        val recByCat = recurring.groupBy { it.categoryId }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { a, r -> a + r.mean } }
        return categories.filter { it.kind == TxKind.SPESA }.associate { c ->
            val avg = avgByCat[c.id] ?: BigDecimal.ZERO
            val rec = recByCat[c.id] ?: BigDecimal.ZERO
            c.id to if (avg.signum() > 0 && rec.signum() > 0 && rec >= avg.multiply(BigDecimal("0.6"))) NatureSuggestion(SpendNature.FIXED, "addebiti ricorrenti ogni mese")
            else byName(c)
        }
    }

    private fun byName(c: Category): NatureSuggestion =
        if (c.systemKey == null && ESSENTIAL.containsMatchIn(c.name.lowercase())) NatureSuggestion(SpendNature.ESSENTIAL, "spesa di base")
        else NatureSuggestion(SpendNature.DISCRETIONARY, "spesa di cui si può fare a meno")

    /** Spesa per natura (categorie senza natura: chiave null); le sottocategorie ereditano quella della principale. */
    fun breakdown(byCategory: Map<Long?, BigDecimal>, categories: Map<Long, Category>): Map<SpendNature?, BigDecimal> {
        val out = LinkedHashMap<SpendNature?, BigDecimal>()
        byCategory.forEach { (id, v) ->
            if (v.signum() <= 0) return@forEach
            val c = id?.let { categories[it] }
            val n = c?.nature ?: c?.parentId?.let { categories[it]?.nature }
            out.merge(n, v) { a, b -> a + b }
        }
        return out
    }
}
