package it.spese.domain.ocr

import java.text.Normalizer

/** Correzioni degli errori tipici dell'OCR sugli scontrini: cifre scambiate con lettere, IVA impossibile, parole storpiate. */
object ReceiptCorrector {
    private val CONFUSED = mapOf('O' to '0', 'o' to '0', 'I' to '1', 'l' to '1', '|' to '1', 'S' to '5', 'B' to '8')
    private val PRICE_TOKEN = Regex("(?<![\\w.,])([\\dOoIl|SB]{1,5})\\s?[.,]\\s?([\\dOoIl|SB]{2})(?![\\w])")
    private val VAT_TOKEN = Regex("(?<![\\w])([\\dOoIl]{1,2})\\s?[%X](?![\\w])")
    private val VALID_VAT = setOf("4", "5", "10", "22")

    private fun digits(s: String) = s.map { CONFUSED[it] ?: it }.joinToString("")

    /** «1O.99» → «10.99», «l,49» → «1,49», «1. 99» → «1.99», «1OX» → «10%»: solo se il risultato è un numero credibile. */
    fun fixLine(line: String): String {
        var out = PRICE_TOKEN.replace(line) { m ->
            val a = m.groupValues[1]; val b = m.groupValues[2]
            val real = (a + b).count { it.isDigit() }
            if (real == 0 || real * 2 < (a + b).length) m.value else digits(a) + m.value.first { it == '.' || it == ',' } + digits(b)
        }
        out = VAT_TOKEN.replace(out) { m ->
            val v = digits(m.groupValues[1])
            if (v in VALID_VAT && (m.groupValues[1] != v || m.value.last() == 'X')) "$v%" else m.value
        }
        return out
    }

    private fun letters(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").filter { it.isLetter() }

    private fun distanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var diff = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++diff > 1) return false
            when { a.length > b.length -> i++; a.length < b.length -> j++; else -> { i++; j++ } }
        }
        return diff + (a.length - i) + (b.length - j) <= 1
    }

    /**
     * Ripara le parole della descrizione con un solo carattere sbagliato rispetto a una parola nota (dizionario dei prodotti e voci già
     * viste dall'utente). Solo per parole di almeno 5 lettere, non già note, e se la parola nota più vicina è una sola.
     */
    fun repairWords(description: String, vocabulary: Set<String>): String {
        if (vocabulary.isEmpty()) return description
        // la parola può contenere cifre scambiate con lettere («PROSCIUTT0»): 0→o, 1→i, 5→s, 8→b
        return Regex("[A-Za-zÀ-ú][A-Za-zÀ-ú0158]{4,}").replace(description) { m ->
            val fixed = m.value.map { when (it) { '0' -> 'o'; '1' -> 'i'; '5' -> 's'; '8' -> 'b'; else -> it } }.joinToString("")
            val w = letters(fixed)
            if (w.length < 5) return@replace m.value
            val styled = { v: String -> if (m.value.all { !it.isLetter() || it.isUpperCase() }) v.uppercase() else v.replaceFirstChar { it.uppercase() } }
            if (w in vocabulary) return@replace if (fixed != m.value) styled(w) else m.value
            val near = vocabulary.filter { it.length >= 5 && it[0] == w[0] && distanceAtMostOne(w, it) }
            if (near.size != 1) m.value else styled(near[0])
        }
    }
}
