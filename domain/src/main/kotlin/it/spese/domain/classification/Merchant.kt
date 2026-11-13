package it.spese.domain.classification

import it.spese.core.model.Transaction

/**
 * Estrazione dell'esercente dalla descrizione bancaria. Porta fedele della funzione `merchant()` della versione HTML.
 */
object Merchant {
    private val RX_BONIFICO = Regex("^BONIFICO", RegexOption.IGNORE_CASE)
    private val RX_BONIFICO_DA = Regex("\\bDa\\s+(.+?)\\s+(?:TRN|PER)\\b", RegexOption.IGNORE_CASE)
    private val RX_PREFIX = Regex(
        "^(Pagamento\\s+(POS|Google Pay|E-Commerce)|Pedaggio autostradale|Commissioni pagamento|Commissioni Prelievo|Prelievo)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val RX_CUT = Regex("\\s\\d{2}/\\d{2}/\\d{4}|\\sOp\\.|\\s-\\s\\d{2}/|\\sATM\\b")
    private val RX_STARS = Regex("[*]+\\s*[\\w-]*$")
    private val RX_WS = Regex("\\s+")
    private val RX_PRELIEVO = Regex("^Prelievo|^Commissioni", RegexOption.IGNORE_CASE)
    private val RX_DATE = Regex("\\s\\d{2}/\\d{2}/\\d{4}")

    fun extract(text: String): String {
        var m = text.trim()
        if (RX_BONIFICO.containsMatchIn(m)) {
            val x = RX_BONIFICO_DA.find(m)?.groupValues?.get(1)?.trim()
            return "Bonifico " + if (!x.isNullOrEmpty()) "da " + x.take(40) else ""
        }
        m = m.replace(RX_PREFIX, "")
        m = m.split(RX_CUT)[0]
        m = m.replace(RX_STARS, "").replace(RX_WS, " ").trim()
        if (RX_PRELIEVO.containsMatchIn(text)) m = text.split(RX_DATE)[0].replace(RX_WS, " ")
        return (if (m.isEmpty()) text else m).take(45)
    }

    /** Parola chiave suggerita per creare una regola a partire da un esercente (come `cleanKw` nella versione HTML). */
    fun suggestKeyword(merchant: String): String =
        merchant.lowercase().replace(Regex("[^\\wÀ-ú' .&-]"), "").trim().take(24)
}

/** Esercente effettivo di un movimento: valore impostato dall'utente oppure estratto dalla descrizione. */
val Transaction.merchant: String
    get() = merchantOverride?.takeIf { it.isNotBlank() } ?: Merchant.extract(description)
