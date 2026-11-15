package it.spese.domain.ocr

import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import it.spese.domain.importing.DateParser
import java.math.BigDecimal
import java.time.LocalDate

/** Voce dello scontrino: `quantity` > 1 quando una riga «4 x 1.0 PZ x EUR 0.89» precede la voce. */
data class ReceiptItem(val description: String, val amount: BigDecimal, val quantity: Int = 1)

/** Dati estratti da uno scontrino/fattura: ogni campo è facoltativo e va confermato dall'utente. */
data class ReceiptData(
    val merchant: String?,
    val date: LocalDate?,
    val total: BigDecimal?,
    val vat: BigDecimal?,
    val items: List<ReceiptItem>,
    val description: String,
    val rawText: String,
    /** «SUBTOTALE» stampato prima di eventuali voci finali (es. shopper). */
    val subtotal: BigDecimal? = null,
) {
    /** Somma delle voci riconosciute. */
    val itemsSum: BigDecimal get() = items.fold(BigDecimal.ZERO) { a, b -> a + b.amount }
    /** Totale stampato meno somma delle voci: 0 = tutto torna; diverso da 0 = righe mancanti, doppie o lette male. null se non si può confrontare. */
    val itemsGap: BigDecimal? get() = if (total == null || items.isEmpty()) null else total - itemsSum
}

/**
 * Interpretazione euristica del testo OCR di scontrini, ricevute e fatture italiane.
 * Puro Kotlin: testabile senza Android. Il motore OCR è intercambiabile (vedi `TextRecognizer` nell'app),
 * così in futuro si potranno aggiungere estratti conto fotografati o documenti multipagina riusando lo stesso contratto.
 */
object ReceiptParser {
    private val AMOUNT = Regex("(?<![\\d.,])-?\\d{1,3}(?:[.]\\d{3})*[,]\\d{2}(?![\\d])|(?<![\\d.,])-?\\d+[,.]\\d{2}(?![\\d])")
    private val TOTAL_KEYS = listOf("totale complessivo", "totale euro", "totale eur", "importo pagato", "pagamento elettronico", "totale da pagare", "da pagare", "importo da pagare", "totale documento", "totale fattura", "totale", "total")
    private val NOT_TOTAL = listOf("subtotale", "sub totale", "sub-totale", "totale iva", "imponibile", "resto", "sconto", "articoli", "pezzi", "iva")
    private val SKIP_MERCHANT = Regex("(?i)^(scontrino|ricevuta|fattura|documento|cassa|cassiere|data|ora|tel|p\\.?\\s?iva|partita|c\\.?f\\.?|www|via |viale |piazza |corso |n\\.|nr|pos|copia|arrivederci|grazie)")
    private val SKIP_ITEM = Regex("(?i)\\b(totale|subtotale|sub\\s?totale|resto|contant\\w*|carta di|c\\.?\\s?credito|credito|bancomat|pagamento|iva|imponibile|sconto|arrotond\\w*|importo|pos|ticket|buono|punti|saldo|annull\\w*|articoli|riscosso|ricalcolato)\\b")
    private val QTY_LINE = Regex("^(\\d{1,3})\\s*[xX×]\\s*[\\d.,]+\\s*[A-Za-z]{0,3}\\s*[xX×]")
    private val VAT_SUFFIX = Regex("\\s+\\d{1,2}\\s?%$")

    fun amountsIn(line: String): List<BigDecimal> =
        AMOUNT.findAll(line).mapNotNull { NumberParser.parseMoney(it.value) }.toList()

    fun parse(text: String, today: LocalDate = LocalDate.now()): ReceiptData {
        val lines = joinSplitRows(text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() })
        val merchant = findMerchant(lines)
        val date = findDate(lines, today)
        val items = findItems(lines)
        val total = findTotal(lines, items.fold(BigDecimal.ZERO) { a, b -> a + b.amount }.takeIf { items.isNotEmpty() })
        val vat = findVat(lines)
        val desc = buildString {
            append(merchant ?: "Spesa")
            if (items.isNotEmpty() && items.size <= 3) append(": ").append(items.joinToString(", ") { it.description.take(24) })
        }
        return ReceiptData(merchant, date, total, vat, items, desc, text, findSubtotal(lines))
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

    private val PRICE_ONLY = Regex("^(?:\\d{1,2}\\s?%\\s*)?-?\\d{1,3}(?:[.]\\d{3})*[,.]\\d{2}$")

    /** L'OCR a volte separa la descrizione dal prezzo su due righe: «TOTALE COMPLESSIVO €» + «80.85» tornano una riga sola. */
    private fun joinSplitRows(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null && PRICE_ONLY.matches(next) && l.count { it.isLetter() } >= 3 && !AMOUNT.containsMatchIn(l)) { out.add("$l $next"); i += 2 }
            else { out.add(l); i++ }
        }
        return out
    }

    /**
     * Totale: cerca le righe «totale complessivo», «importo pagato»… e prende il primo importo DOPO la parola (se l'OCR ha unito due righe,
     * l'IVA che segue non si confonde con il totale). Se la somma delle voci coincide con uno dei candidati, vince quello.
     */
    private fun findTotal(lines: List<String>, itemsSum: BigDecimal?): BigDecimal? {
        val candidates = ArrayList<BigDecimal>()
        for (key in TOTAL_KEYS) {
            for ((i, l) in lines.withIndex()) {
                val low = l.lowercase()
                val at = low.indexOf(key)
                if (at < 0) continue
                if (key == "totale" && NOT_TOTAL.any { low.contains(it) }) continue
                val a = amountsIn(l.substring(at + key.length)).firstOrNull() ?: (1..2).firstNotNullOfOrNull { k -> lines.getOrNull(i + k)?.takeIf { PRICE_ONLY.matches(it) || it.count { c -> c.isLetter() } < 3 }?.let { amountsIn(it).firstOrNull() } }
                if (a != null && a.signum() > 0) candidates.add(a)
            }
        }
        if (itemsSum != null) {
            candidates.firstOrNull { (it - itemsSum).abs() <= BigDecimal("0.01") }?.let { return it }
            // nessun candidato torna con le voci: se un importo del documento coincide con la somma, è quasi certamente il totale
            lines.flatMap { amountsIn(it) }.firstOrNull { it.signum() > 0 && (it - itemsSum).abs() <= BigDecimal("0.01") && itemsSum.signum() > 0 }?.let { return it }
        }
        candidates.firstOrNull()?.let { return it }
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

    private fun findSubtotal(lines: List<String>): BigDecimal? =
        lines.lastOrNull { Regex("(?i)sub\\s?-?totale").containsMatchIn(it) }?.let { amountsIn(it).lastOrNull() }

    private fun compact(l: String) = l.lowercase().filter { it.isLetter() }
    private val END_OF_ITEMS = listOf("totalecomplessivo", "totaleeuro", "totaleeur", "importopagato", "pagamentocontante", "pagamentoelettronico", "totaledapagare")
    /** Parole di servizio che l'OCR può spezzare («Impor to pagato»): si riconoscono anche senza spazi. */
    private val SERVICE_WORDS = listOf("importopagato", "pagamento", "riscosso", "ricalcolato", "subtotale", "contante", "elettronico", "totale")

    private fun findItems(allLines: List<String>): List<ReceiptItem> {
        // le voci stanno prima dei totali: dal «totale complessivo» in poi (pagamenti, resto, firma) non ce ne sono
        val end = allLines.indexOfFirst { l -> compact(l).let { c -> END_OF_ITEMS.any { c.contains(it) } } }
        val lines = if (end >= 0) allLines.take(end) else allLines
        val out = ArrayList<ReceiptItem>()
        var qty = 1
        for (l in lines) {
            if (compact(l).let { c -> SERVICE_WORDS.any { c.contains(it) } }) continue
            val q = QTY_LINE.find(l)
            if (q != null) { qty = q.groupValues[1].toIntOrNull() ?: 1; continue }   // «4 x 1.0 PZ x EUR 0.89»: vale per la voce che segue
            if (SKIP_ITEM.containsMatchIn(l)) continue
            val m = Regex("^(.*?[A-Za-zÀ-ú].*?)\\s+(-?\\d{1,3}(?:[.]\\d{3})*[,.]\\d{2})\\s*[A-Za-z]?$").find(l) ?: continue
            val a = NumberParser.parseMoney(m.groupValues[2]) ?: continue
            val d = m.groupValues[1].trim().replace(VAT_SUFFIX, "").replace(Regex("\\s+[xX*]$"), "").trim()
            if (d.length < 2 || a.signum() == 0 || DateParser.parseText(d) != null) continue
            out.add(ReceiptItem(d, a, if (qty > 1 && a.signum() > 0) qty else 1))
            qty = 1
        }
        return cancelPairs(out)
    }

    /** «ANNULLI»: una voce negativa elimina la voce uguale che la precede (stesso testo e importo opposto). */
    private fun cancelPairs(items: List<ReceiptItem>): List<ReceiptItem> {
        val list = items.toMutableList()
        var i = 0
        while (i < list.size) {
            val n = list[i]
            if (n.amount.signum() < 0) {
                val j = (i - 1 downTo 0).firstOrNull { k -> list[k].amount.signum() > 0 && list[k].amount.compareTo(n.amount.negate()) == 0 && list[k].description.equals(n.description, ignoreCase = true) }
                if (j != null) { list.removeAt(i); list.removeAt(j); i = maxOf(0, j); continue }
            }
            i++
        }
        return list
    }
}
