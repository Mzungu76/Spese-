package it.spese.domain.ocr

import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.domain.importing.DateParser
import java.math.BigDecimal
import java.time.LocalDate

data class ReceiptItem(val description: String, val amount: BigDecimal)

/** Dati estratti da uno scontrino/fattura: ogni campo è facoltativo e va confermato dall'utente. */
data class ReceiptData(
    val merchant: String?,
    val date: LocalDate?,
    val total: BigDecimal?,
    val vat: BigDecimal?,
    val items: List<ReceiptItem>,
    val description: String,
    val rawText: String,
)

/**
 * Interpretazione euristica del testo OCR di scontrini, ricevute e fatture italiane.
 * Puro Kotlin: testabile senza Android. Il motore OCR è intercambiabile (vedi `TextRecognizer` nell'app),
 * così in futuro si potranno aggiungere estratti conto fotografati o documenti multipagina riusando lo stesso contratto.
 */
object ReceiptParser {
    private val AMOUNT = Regex("(?<![\\d.,])-?\\d{1,3}(?:[.]\\d{3})*[,]\\d{2}(?![\\d])|(?<![\\d.,])-?\\d+[,.]\\d{2}(?![\\d])")
    private val TOTAL_KEYS = listOf("totale complessivo", "totale euro", "totale eur", "importo pagato", "totale da pagare", "da pagare", "importo da pagare", "totale documento", "totale fattura", "totale", "total")
    private val NOT_TOTAL = listOf("subtotale", "sub totale", "sub-totale", "totale iva", "imponibile", "resto", "sconto", "articoli", "pezzi", "iva")
    private val SKIP_MERCHANT = Regex("(?i)^(scontrino|ricevuta|fattura|documento|cassa|cassiere|data|ora|tel|p\\.?\\s?iva|partita|c\\.?f\\.?|www|via |viale |piazza |corso |n\\.|nr|pos|copia|arrivederci|grazie)")
    private val SKIP_ITEM = Regex("(?i)(totale|subtotale|resto|contant|carta|bancomat|pagamento|iva|imponibile|sconto|arrotond|importo|pos|ticket|buono|punti|saldo|credito)")

    fun amountsIn(line: String): List<BigDecimal> =
        AMOUNT.findAll(line).mapNotNull { NumberParser.parseMoney(it.value) }.toList()

    fun parse(text: String, today: LocalDate = LocalDate.now()): ReceiptData {
        val lines = text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val merchant = findMerchant(lines)
        val date = findDate(lines, today)
        val total = findTotal(lines)
        val vat = findVat(lines)
        val items = findItems(lines)
        val desc = buildString {
            append(merchant ?: "Spesa")
            if (items.isNotEmpty() && items.size <= 3) append(": ").append(items.joinToString(", ") { it.description.take(24) })
        }
        return ReceiptData(merchant, date, total, vat, items, desc, text)
    }

    private fun findMerchant(lines: List<String>): String? {
        for (l in lines.take(8)) {
            val letters = l.count { it.isLetter() }
            if (letters < 3 || letters < l.length / 2) continue
            if (SKIP_MERCHANT.containsMatchIn(l) || AMOUNT.containsMatchIn(l) || DateParser.parseText(l) != null) continue
            return l.take(45).split(" ").joinToString(" ") { w -> if (w.length > 3 && w == w.uppercase()) w.lowercase().replaceFirstChar { it.uppercase() } else w }
        }
        return null
    }

    private val DATE_RX = listOf(
        Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})"),
        Regex("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2})(?!\\d)"),
        Regex("(\\d{4})-(\\d{2})-(\\d{2})"),
        Regex("(\\d{1,2})\\s+(gen|feb|mar|apr|mag|giu|lug|ago|set|ott|nov|dic)[a-z]*\\.?\\s+(\\d{2,4})", RegexOption.IGNORE_CASE),
    )

    private fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        val found = ArrayList<Pair<LocalDate, Boolean>>()
        for (l in lines) for (rx in DATE_RX) for (m in rx.findAll(l)) {
            val d = DateParser.parseText(m.value) ?: continue
            if (d.isAfter(today.plusDays(2)) || d.isBefore(today.minusYears(10))) continue
            found.add(d to l.lowercase().contains("data"))
        }
        return found.firstOrNull { it.second }?.first ?: found.firstOrNull()?.first
    }

    private fun findTotal(lines: List<String>): BigDecimal? {
        for (key in TOTAL_KEYS) {
            for ((i, l) in lines.withIndex()) {
                val low = l.lowercase()
                if (!low.contains(key)) continue
                if (key == "totale" && NOT_TOTAL.any { low.contains(it) }) continue
                val a = amountsIn(l).lastOrNull() ?: lines.getOrNull(i + 1)?.let { amountsIn(it).firstOrNull() }
                if (a != null && a.signum() > 0) return a
            }
        }
        // ripiego: l'importo più alto che compare nel documento
        return lines.flatMap { amountsIn(it) }.filter { it.signum() > 0 }.maxOrNull()
    }

    private fun findVat(lines: List<String>): BigDecimal? {
        val rows = lines.filter { l -> val low = l.lowercase(); (low.contains("iva") || low.contains("i.v.a")) && !low.contains("p.iva") && !low.contains("partita") && !low.contains("p. iva") }
        rows.firstOrNull { val l = it.lowercase(); l.contains("totale iva") || l.contains("di cui iva") || l.contains("iva inclusa") }
            ?.let { amountsIn(it).lastOrNull() }?.let { return it }
        val perRate = rows.filter { Regex("\\d{1,2}\\s?%").containsMatchIn(it) }.mapNotNull { amountsIn(it).lastOrNull() }
        if (perRate.isNotEmpty()) return perRate.fold(BigDecimal.ZERO) { a, b -> a + b }
        return rows.firstNotNullOfOrNull { amountsIn(it).lastOrNull() }
    }

    private fun findItems(lines: List<String>): List<ReceiptItem> {
        val out = ArrayList<ReceiptItem>()
        for (l in lines) {
            if (SKIP_ITEM.containsMatchIn(l)) continue
            val m = Regex("^(.*?[A-Za-zÀ-ú].*?)\\s+(-?\\d{1,3}(?:[.]\\d{3})*[,.]\\d{2})\\s*[A-Za-z]?$").find(l) ?: continue
            val a = NumberParser.parseMoney(m.groupValues[2]) ?: continue
            val d = m.groupValues[1].trim().trimEnd('x', 'X', '*', ' ').trim()
            if (d.length < 2 || DateParser.parseText(d) != null) continue
            out.add(ReceiptItem(d, a))
        }
        return out
    }
}
