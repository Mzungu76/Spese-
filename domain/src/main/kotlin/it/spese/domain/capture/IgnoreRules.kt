package it.spese.domain.capture

import java.text.Normalizer

/**
 * Regola "ignora notifiche come questa": le notifiche della fonte (vuota = qualunque) che contengono la frase vengono
 * scartate in silenzio. Mai se contengono un importo in euro: un pagamento vero non si perde per una regola troppo larga.
 */
data class IgnoreRule(val source: String, val phrase: String, val createdMs: Long, val enabled: Boolean = true)

/** Notifiche non riconosciute identiche (a meno di numeri e punteggiatura), raggruppate. */
data class UnrecognizedGroup(val key: String, val sample: UnrecognizedNotification, val items: List<UnrecognizedNotification>) { val count: Int get() = items.size }

object IgnoreFilter {
    const val MIN_PHRASE = 8
    /** Oltre questo numero di notifiche non riconosciute l'app chiede di controllarle. */
    const val REVIEW_THRESHOLD = 10
    private val amount = Regex("(\\d[\\d.]*[,.]\\d{1,2}|\\b\\d+)\\s*(€|eur\\b)|(€|\\beur\\b)\\s*\\d", RegexOption.IGNORE_CASE)

    /** Minuscolo, senza accenti, solo lettere separate da un solo spazio (numeri e simboli diventano spazi). */
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z]+"), " ").trim()

    fun hasAmount(s: String): Boolean = amount.containsMatchIn(s)

    /** Frase proposta: le prime parole del testo senza numeri (es. "il tuo codice di verifica e"). Vuota se il testo è troppo povero. */
    fun suggest(text: String): String {
        val body = text.substringAfter(" — ", text)
        val words = normalize(body).split(' ').filter { it.isNotEmpty() }.take(6)
        return words.joinToString(" ").takeIf { it.length >= MIN_PHRASE }.orEmpty()
    }

    fun matches(rules: List<IgnoreRule>, sourceId: String, title: String, text: String): Boolean {
        if (rules.none { it.enabled }) return false
        val raw = "$title $text"
        if (hasAmount(raw)) return false
        val hay = normalize(raw)
        return rules.any { it.enabled && it.phrase.length >= MIN_PHRASE && (it.source.isBlank() || it.source == sourceId) && hay.contains(normalize(it.phrase)) }
    }

    fun group(list: List<UnrecognizedNotification>): List<UnrecognizedGroup> =
        list.groupBy { it.source + "|" + normalize(it.text) }.map { (k, v) -> UnrecognizedGroup(k, v.maxByOrNull { it.atMs }!!, v.sortedByDescending { it.atMs }) }
            .sortedByDescending { it.sample.atMs }

    /** Id della fonte dal nome mostrato (le non riconosciute conservano l'etichetta). */
    fun sourceIdOf(label: String): String = CaptureSource.entries.firstOrNull { it.label == label }?.id ?: ""
}
