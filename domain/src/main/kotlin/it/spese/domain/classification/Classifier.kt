package it.spese.domain.classification

import it.spese.core.model.AmountSign
import it.spese.core.model.Category
import it.spese.core.model.Rule
import it.spese.core.model.TxKind
import java.math.BigDecimal
import java.util.regex.Pattern

/** Esito della classificazione di un movimento. */
data class Classification(
    val categoryId: Long?,
    val kind: TxKind,
    val manual: Boolean,
    /** Regola che ha classificato il movimento; null se manuale o se ha prevalso il fallback. */
    val ruleId: Long?,
)

/**
 * Motore delle regole testuali. Una regola vale se: la parola chiave è contenuta nella descrizione
 * (o corrisponde all'espressione regolare quando ha il prefisso `re:`), il segno e l'importo assoluto sono compatibili.
 * Vince la prima regola (per priorità crescente) che corrisponde.
 */
class RuleEngine(rules: List<Rule>) {
    private class Compiled(val rule: Rule, val test: (String) -> Boolean)

    private val compiled: List<Compiled> = rules.filter { it.active }.sortedBy { it.priority }.map { r ->
        val kw = r.keyword.trim()
        val f: (String) -> Boolean = buildMatcher(kw)
        Compiled(r, f)
    }

    private fun buildMatcher(kw: String): (String) -> Boolean {
        if (kw.length >= 3 && kw.substring(0, 3).equals("re:", ignoreCase = true)) {
            val p = try {
                Pattern.compile(kw.substring(3), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (e: Exception) {
                null
            }
            return { t -> p != null && p.matcher(t).find() }
        }
        val k = kw.lowercase()
        return { t -> k.isEmpty() || t.lowercase().contains(k) }
    }

    fun match(description: String, amount: BigDecimal): Rule? {
        val abs = amount.abs()
        for (c in compiled) {
            val r = c.rule
            if (r.min.signum() != 0 && abs < r.min) continue
            if (r.max.signum() != 0 && abs > r.max) continue
            if (r.sign == AmountSign.NEGATIVE && amount.signum() >= 0) continue
            if (r.sign == AmountSign.POSITIVE && amount.signum() <= 0) continue
            if (c.test(description)) return r
        }
        return null
    }

    companion object {
        /** Verifica la sintassi di una parola chiave (utile nella UI di modifica regole). */
        fun isValidKeyword(kw: String): Boolean {
            val k = kw.trim()
            if (k.length >= 3 && k.substring(0, 3).equals("re:", true)) {
                return try { Pattern.compile(k.substring(3)); true } catch (e: Exception) { false }
            }
            return true
        }
    }
}

/**
 * Classificatore: applica prima l'assegnazione manuale, poi le regole, infine la categoria di fallback
 * ("Altre entrate" / "Altro"). La tipologia del movimento deriva dalla categoria (o dal segno se manca).
 */
class Classifier(private val categories: List<Category>, rules: List<Rule>) {
    private val byId = categories.associateBy { it.id }
    private val engine = RuleEngine(rules)
    private val fallbackIncome = categories.firstOrNull { it.systemKey == Category.FALLBACK_INCOME }
    private val fallbackExpense = categories.firstOrNull { it.systemKey == Category.FALLBACK_EXPENSE }

    fun classify(description: String, amount: BigDecimal, manualCategoryId: Long?): Classification {
        if (manualCategoryId != null && byId.containsKey(manualCategoryId)) {
            return Classification(manualCategoryId, byId.getValue(manualCategoryId).kind, true, null)
        }
        val rule = engine.match(description, amount)
        val cat: Category? = when {
            rule != null -> byId[rule.categoryId]
            amount.signum() >= 0 -> fallbackIncome
            else -> fallbackExpense
        }
        val kind = cat?.kind ?: if (amount.signum() >= 0) TxKind.ENTRATA else TxKind.SPESA
        return Classification(cat?.id, kind, false, rule?.id)
    }
}
