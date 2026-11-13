package it.spese.domain.classification

import it.spese.core.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Identificazione dei duplicati.
 *
 * 1. Importazione: stessa logica della versione HTML (insieme con molteplicità). La chiave è
 *    `conto|data|valuta|importo|descrizione`. Se un file contiene due volte la stessa riga (es. due caffè identici
 *    nello stesso giorno) vengono importate tutte e due, ma una successiva re-importazione dello stesso file non
 *    aggiunge nulla.
 * 2. Revisione: raggruppa movimenti "probabilmente doppi" (stesso conto, importo e data con descrizioni simili).
 */
object Duplicates {
    fun key(accountKey: String, d: LocalDate, v: LocalDate, amount: BigDecimal, text: String): String =
        accountKey + "|" + d + "|" + v + "|" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "|" + text

    data class Incoming<T>(val key: String, val item: T)
    data class Planned<T>(val item: T, val key: String, val occurrence: Int, val duplicate: Boolean)

    /** Applica la regola dell'insieme con molteplicità: una riga è nuova solo se supera le occorrenze già presenti. */
    fun <T> plan(existingKeys: Collection<String>, incoming: List<Incoming<T>>): List<Planned<T>> {
        val have = HashMap<String, Int>()
        existingKeys.forEach { have[it] = (have[it] ?: 0) + 1 }
        val seen = HashMap<String, Int>()
        return incoming.map { inc ->
            val n = (seen[inc.key] ?: 0) + 1
            seen[inc.key] = n
            Planned(inc.item, inc.key, n, duplicate = n <= (have[inc.key] ?: 0))
        }
    }

    enum class Reason(val label: String) { IDENTICAL("Identici"), SIMILAR("Descrizione simile") }
    data class Group(val reason: Reason, val items: List<Transaction>)

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 }.toSet()

    private fun similarity(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() && tb.isEmpty()) return 1.0
        val inter = ta.intersect(tb).size.toDouble()
        val uni = ta.union(tb).size.toDouble()
        return if (uni == 0.0) 0.0 else inter / uni
    }

    /** Gruppi di movimenti potenzialmente doppi, per la revisione manuale. Nessuna cancellazione automatica. */
    fun findProbable(list: List<Transaction>, minSimilarity: Double = 0.6): List<Group> {
        val groups = ArrayList<Group>()
        list.groupBy { Triple(it.accountId, it.amount.setScale(2), it.bookingDate) }.values.filter { it.size > 1 }.forEach { g ->
            val identical = g.groupBy { it.description.trim().lowercase() }.values.filter { it.size > 1 }
            identical.forEach { groups.add(Group(Reason.IDENTICAL, it)) }
            val rest = g.filter { t -> identical.none { grp -> grp.any { it.id == t.id } } }
            val used = HashSet<Long>()
            for (i in rest.indices) {
                if (rest[i].id in used) continue
                val sim = rest.filter { it.id != rest[i].id && it.id !in used && similarity(it.description, rest[i].description) >= minSimilarity }
                if (sim.isNotEmpty()) {
                    val grp = listOf(rest[i]) + sim
                    grp.forEach { used.add(it.id) }
                    groups.add(Group(Reason.SIMILAR, grp))
                }
            }
        }
        return groups
    }
}
