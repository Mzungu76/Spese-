package it.spese.domain.capture

import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.core.model.Transaction
import it.spese.core.model.TxOrigin
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

/** Un pagamento o un accredito letto da una notifica: `amount` è negativo per le uscite. */
data class ParsedPayment(val amount: BigDecimal, val merchant: String?, val description: String)

/**
 * Interpreta il testo delle notifiche di banche e wallet ("Pagamento di 12,50 € presso ESSELUNGA").
 * Funziona solo sul dispositivo. Prudente per scelta: senza un importo in euro e una parola che indichi un pagamento o un accredito
 * non produce nulla (le notifiche promozionali o i saldi non devono diventare movimenti).
 */
object BankNotificationParser {
    private val AMOUNT = Regex("""(?:€\s*(\d{1,3}(?:\.\d{3})*(?:,\d{1,2})?|\d+(?:,\d{1,2})?))|(?:(\d{1,3}(?:\.\d{3})*,\d{2}|\d+,\d{2}|\d+(?:\.\d{2})?)\s*(?:€|eur\b|euro\b))""", RegexOption.IGNORE_CASE)
    private val EXPENSE = Regex("""pagament|acquist|addebit|prelievo|hai speso|spesa di|pagato|transazione|bonifico (?:in uscita|disposto|inviato)|ricarica""", RegexOption.IGNORE_CASE)
    private val INCOME = Regex("""accredit|hai ricevuto|ricevuto|stipendio|bonifico (?:in entrata|ricevuto)|versamento|rimborso""", RegexOption.IGNORE_CASE)
    private val BALANCE_BEFORE = Regex("""(saldo|disponibil|residuo|plafond|limite)\W*[^\d€]{0,12}$""", RegexOption.IGNORE_CASE)
    private val MERCHANT = Regex("""(?:presso|da|a favore di|a|@)\s+([A-Za-z0-9À-ÿ][^.;,\n]*?)(?=\s+(?:con|il|alle|del|della|carta|tramite|in data|per|\*{2,}|su)\b|[.;,\n]|$)""", RegexOption.IGNORE_CASE)

    fun parse(title: String?, text: String?): ParsedPayment? {
        val full = listOfNotNull(title?.trim(), text?.trim()).filter { it.isNotEmpty() }.joinToString(" — ")
        if (full.isBlank()) return null
        val amount = amountOf(full) ?: return null
        val isIncome = INCOME.containsMatchIn(full) && !EXPENSE.containsMatchIn(full)
        val isExpense = EXPENSE.containsMatchIn(full)
        if (!isIncome && !isExpense) return null
        if (amount.signum() <= 0) return null
        val merchant = MERCHANT.find(full)?.groupValues?.get(1)?.trim()?.takeIf { it.length in 2..60 && !it.contains(Regex("""\d{1,3}(?:\.\d{3})*,\d{2}""")) }
        val value = Money.scale(if (isIncome) amount else amount.negate())
        return ParsedPayment(value, merchant, merchant ?: full.take(80))
    }

    private fun amountOf(s: String): BigDecimal? {
        for (m in AMOUNT.findAll(s)) {
            val before = s.substring(maxOf(0, m.range.first - 24), m.range.first)
            if (BALANCE_BEFORE.containsMatchIn(before)) continue
            val raw = m.groups[1]?.value ?: m.groups[2]?.value ?: continue
            val v = NumberParser.parseMoney(raw) ?: continue
            return v
        }
        return null
    }
}

/**
 * Quando arriva l'estratto conto, i movimenti letti dalle notifiche vengono sostituiti da quelli della banca
 * (stesso importo, date vicine), così nulla è contato due volte. Un abbinamento per volta.
 */
object NotificationReconciler {
    /** Restituisce, per ogni riga importata (indice), la notifica corrispondente da sostituire. */
    fun match(pending: List<Transaction>, fresh: List<Pair<java.time.LocalDate, BigDecimal>>, toleranceDays: Long = 4): Map<Int, Transaction> {
        val free = pending.filter { it.origin == TxOrigin.NOTIFICATION }.sortedBy { it.bookingDate }.toMutableList()
        val out = LinkedHashMap<Int, Transaction>()
        fresh.forEachIndexed { idx, (date, amount) ->
            val hit = free.filter { it.amount.compareTo(amount) == 0 && kotlin.math.abs(ChronoUnit.DAYS.between(it.bookingDate, date)) <= toleranceDays }
                .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(it.bookingDate, date)) }
            if (hit != null) { out[idx] = hit; free.remove(hit) }
        }
        return out
    }
}
