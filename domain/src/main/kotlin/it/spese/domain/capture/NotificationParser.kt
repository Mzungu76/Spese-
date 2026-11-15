package it.spese.domain.capture

import java.math.BigDecimal

data class ParsedMovement(val amount: BigDecimal, val description: String)

/**
 * Interpreta il testo di una notifica o di un SMS bancario italiano. È volutamente prudente: senza un importo in euro e una direzione
 * certa (uscita o entrata) non inventa nulla e la notifica finisce tra le "non riconosciute", così si può migliorare il riconoscimento.
 */
object NotificationParser {
    private const val NUM = """(?:\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?|\d+(?:[.,]\d{1,2})?)"""
    private val AMOUNT = Regex("""(?<![\d.,])($NUM)\s*(?:€|eur\b|euro\b)|(?:€|eur\b)\s*($NUM)(?![\d])""", RegexOption.IGNORE_CASE)
    private val NOT_A_MOVEMENT = Regex("""saldo|disponibil|residu|limite|plafond|budget|massimale""", RegexOption.IGNORE_CASE)
    private val OUT = Regex("""pagament|pagato|addebit|acquist|prelievo|prelevat|speso|spesa|hai inviato|inviato a|bonifico (?:inviato|disposto|eseguito|in uscita)|pagopa|bollettino|commission|movimento in uscita|in uscita|\\buscita\\b|ordine di pagamento|domiciliazione""", RegexOption.IGNORE_CASE)
    private val IN = Regex("""accredit|hai ricevuto|ricevut|bonifico (?:in entrata|a tuo favore)|ti ha inviato|stipendio|rimborso|incasso|versamento|movimento in entrata|in entrata|\\bentrata\\b""", RegexOption.IGNORE_CASE)
    private val GENERIC_TITLE = Regex("""google wallet|wallet|revolut|hype|poste|postepay|pagamento|addebito|accredito|transazione|notifica|avviso|carta|movimento|operazione|nuovo|hai ricevuto|hai pagato""", RegexOption.IGNORE_CASE)
    private val MERCHANT = Regex("""(?i:presso|da|a favore di|a|@)\s+((?-i:[A-ZÀ-Ý0-9][^,;.\n]{1,40}?))(?=\s+(?:(?i:il|con|del|della|alle|in data|carta|per|tramite|su)\b)|\s+\d{1,2}/\d{1,2}|[,;.\n]|$)""")

    private val NO_AMOUNT_MOVEMENT = Regex("""movimento in (uscita|entrata)""", RegexOption.IGNORE_CASE)

    /** Direzione di una notifica che segnala un movimento ma non ha importo (-1 uscita, +1 entrata); null se non è di questo tipo. */
    fun parseWithoutAmount(source: CaptureSource, title: String, text: String): Int? {
        if (source.kind == SourceKind.WALLET) return null
        val full = "$title $text"
        if (AMOUNT.containsMatchIn(full)) return null
        val m = NO_AMOUNT_MOVEMENT.find(full) ?: return null
        return if (m.groupValues[1].equals("uscita", ignoreCase = true)) -1 else 1
    }

    fun parse(source: CaptureSource, title: String, text: String): ParsedMovement? {
        val full = "$title $text".trim()
        // importo: il primo che non sia un saldo o un limite
        val m = AMOUNT.findAll(full).firstOrNull { x -> !NOT_A_MOVEMENT.containsMatchIn(full.substring(maxOf(0, x.range.first - 28), x.range.first)) } ?: return null
        val raw = m.groups[1]?.value ?: m.groups[2]?.value ?: return null
        val value = toNumber(raw) ?: return null
        if (value.signum() == 0) return null
        val before = full.getOrNull(m.range.first - 1)
        val signHint = when (before) { '-', '−', '–' -> -1; '+' -> 1; else -> 0 }

        val out = OUT.containsMatchIn(full); val inn = IN.containsMatchIn(full)
        val sign = when {
            out && !inn -> -1
            inn && !out -> 1
            out && inn -> if (signHint != 0) signHint else return null   // testo ambiguo: meglio non indovinare
            signHint != 0 -> signHint
            source.kind == SourceKind.WALLET -> -1                        // Google Wallet notifica solo i pagamenti
            else -> return null
        }
        val amount = if (sign < 0) value.negate() else value
        return ParsedMovement(amount, describe(source, title, text, m.range))
    }

    private fun toNumber(s: String): BigDecimal? = runCatching {
        when {
            s.contains(',') -> BigDecimal(s.replace(".", "").replace(',', '.'))
            Regex("""\d{1,3}(\.\d{3})+""").matches(s) -> BigDecimal(s.replace(".", ""))
            else -> BigDecimal(s)
        }
    }.getOrNull()

    private fun describe(source: CaptureSource, title: String, text: String, amountRange: IntRange): String {
        val t = title.trim()
        // il titolo è spesso l'esercente (Google Wallet, app delle banche), tranne negli SMS dove è il mittente
        if (source.kind != SourceKind.SMS && t.isNotBlank() && t.length <= 48 && !AMOUNT.containsMatchIn(t) && !GENERIC_TITLE.containsMatchIn(t)) return t
        val body = text.trim()
        val inBody = AMOUNT.find(body)
        if (inBody != null) {
            MERCHANT.find(body, inBody.range.last + 1)?.groupValues?.get(1)?.trim()?.let { return it }
            MERCHANT.find(body)?.groupValues?.get(1)?.trim()?.let { return it }
        } else MERCHANT.find(body)?.groupValues?.get(1)?.trim()?.let { return it }
        return source.label
    }
}
