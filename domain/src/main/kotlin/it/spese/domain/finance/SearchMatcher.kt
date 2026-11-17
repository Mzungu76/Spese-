package it.spese.domain.finance

import it.spese.core.common.Dates
import it.spese.core.model.Transaction
import java.math.BigDecimal
import java.text.Normalizer
import java.time.format.DateTimeFormatter

/**
 * Ricerca testuale: senza maiuscole e accenti ("caffe" trova "Caffè"), più parole = tutte devono comparire (in qualunque ordine),
 * e si può cercare anche per importo ("45,50", "45.5") o per data ("14/11", "novembre").
 */
object SearchMatcher {
    private val MARKS = Regex("\\p{M}+")
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun normalize(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")

    /** Parole da cercare (normalizzate), vuota se la ricerca è vuota. */
    fun tokens(raw: String): List<String> = normalize(raw).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** `haystack` deve essere già normalizzato con [normalize]. */
    fun matches(tokens: List<String>, haystack: String): Boolean = tokens.all { haystack.contains(it) }

    fun matches(raw: String, vararg fields: String?): Boolean {
        val t = tokens(raw)
        if (t.isEmpty()) return true
        val h = normalize(fields.filterNotNull().joinToString(" "))
        return matches(t, h)
    }

    /** "45,50 45.50 45,5 45.5" (valore assoluto): per trovare un movimento digitandone l'importo. */
    fun amountForms(amount: BigDecimal): String {
        val a = amount.abs()
        val two = a.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
        val trimmed = a.stripTrailingZeros().toPlainString().let { if (it.contains('E')) a.toPlainString() else it }
        return listOf(two, two.replace('.', ','), trimmed, trimmed.replace('.', ',')).distinct().joinToString(" ")
    }

    fun haystack(t: Transaction, merchant: String, category: String, accountName: String): String = normalize(
        listOf(t.description, merchant, t.notes, category, accountName, t.bookingDate.format(DATE), Dates.MESI_LUNGHI[t.bookingDate.monthValue - 1], amountForms(t.amount)).joinToString(" "),
    )
}
