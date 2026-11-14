package it.spese.domain.importing

import it.spese.core.common.DecimalStyle
import it.spese.core.common.NumberParser
import java.math.BigDecimal
import java.time.LocalDate

/** Esito dell'analisi di un file: ruoli proposti per ogni colonna, riga di intestazione, opzioni e avvertenze. */
data class ColumnInfo(
    val index: Int, val title: String, val role: ColumnRole,
    /** 0..1: quanto l'analisi è sicura di questo ruolo. */
    val confidence: Double, val samples: List<String>,
)

data class TableAnalysis(
    /** Indice della riga di intestazione (-1 = il file non ha intestazione). I dati iniziano nella riga successiva. */
    val headerRow: Int,
    val columns: List<ColumnInfo>,
    val options: ImportOptions,
    val warnings: List<String>,
    /** Affidabilità complessiva (minimo tra data, descrizione e importo). 0 = non riconosciuto. */
    val confidence: Double,
) {
    val roles: List<ColumnRole> get() = columns.map { it.role }
    val mapping: ColumnMapping get() = ColumnMapping.fromRoles(roles)
    val isComplete: Boolean get() = mapping.isUsable

    /** Impronta delle intestazioni: due file della stessa banca hanno la stessa firma (serve a ricordare la mappatura). */
    fun signature(rows: List<RawRow>): String = TableAnalyzer.signature(rows, headerRow, columns.size)
}

/**
 * Riconoscimento delle colonne di un estratto conto **dal contenuto**, non solo dai titoli: quali colonne contengono date,
 * importi (con segno, oppure Dare/Avere, oppure positivi + colonna segno) e testo. Funziona anche su file senza intestazione
 * o con intestazioni sconosciute, in italiano e in inglese. Tutto ciò che propone è modificabile dall'utente.
 */
object TableAnalyzer {
    private val SIGN_WORDS = setOf("D", "A", "DARE", "AVERE", "DEBIT", "CREDIT", "DEBITO", "CREDITO", "ADDEBITO", "ACCREDITO", "-", "+", "DR", "CR", "USCITA", "ENTRATA")
    private val BALANCE_KEYS = listOf("saldo", "balance", "progress", "disponibil")
    private val FEE_KEYS = listOf("fee", "commission", "tariffa", "costi", "spese di")
    private val AMOUNT_KEYS = listOf("importo", "amount", "ammontare", "valore", "lordo", "netto", "totale", "value", "euro")
    private val DEBIT_KEYS = listOf("dare", "uscite", "uscita", "addebit", "debit", "withdraw", "paid out", "spese")
    private val CREDIT_KEYS = listOf("avere", "entrate", "entrata", "accredit", "credit", "paid in", "deposit")
    private val DATE_KEYS = listOf("data", "date", "giorno")
    private val VALUE_KEYS = listOf("valuta", "value date")
    private val DESC_KEYS = listOf("descr", "causale", "operaz", "dettagli", "details", "movimento", "memo", "payee", "narrative", "beneficiar", "controparte", "merchant", "esercente", "note", "name", "nome")
    private val EXTRA_KEYS = listOf("causale", "dettagli", "details", "note", "memo", "reference", "riferimento", "beneficiar", "controparte", "ordinante", "payee")

    private fun str(v: Any?): String = when (v) { null -> ""; is LocalDate -> v.toString(); else -> v.toString().trim() }
    private fun norm(s: String) = s.lowercase().trim()
    private fun has(h: String, keys: List<String>) = keys.any { h.contains(it) }

    private fun isDateCell(v: Any?): Boolean = when (v) {
        null -> false
        is LocalDate -> true
        is BigDecimal -> false           // un numero nudo si considera importo, non data
        else -> v.toString().isNotBlank() && DateParser.parseText(v.toString()) != null
    }

    private fun numberOf(v: Any?, style: DecimalStyle = DecimalStyle.AUTO): BigDecimal? = when (v) {
        null -> null
        is LocalDate -> null
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        else -> if (isDateCell(v)) null else NumberParser.parse(v.toString(), style)
    }

    fun signature(rows: List<RawRow>, headerRow: Int, columns: Int): String {
        val h = rows.getOrNull(headerRow)?.map { norm(str(it)) }?.filter { it.isNotEmpty() }
        return if (h.isNullOrEmpty()) "noheader|$columns" else h.joinToString("|")
    }

    fun analyze(rows: List<RawRow>): TableAnalysis {
        val head = rows.take(300)
        val width = head.maxOfOrNull { it.size } ?: 0
        fun cell(r: Int, c: Int): Any? = head.getOrNull(r)?.getOrNull(c)
        fun isDataRow(r: Int): Boolean {
            val row = head.getOrNull(r) ?: return false
            return row.any { isDateCell(it) } && row.any { numberOf(it) != null && !isDateCell(it) }
        }
        // 1) prima riga di dati: una riga "data + numero" seguita da altre simili
        var firstData = -1
        for (i in head.indices) {
            if (!isDataRow(i)) continue
            val next = (i + 1..minOf(i + 5, head.lastIndex)).count { isDataRow(it) }
            val needed = minOf(3, head.lastIndex - i)
            if (next >= needed) { firstData = i; break }
        }
        if (firstData < 0 || width == 0) {
            val det = HeaderDetector.detect(rows)
            val titles = rows.getOrNull(det?.headerRow ?: 0)?.map { str(it) } ?: emptyList()
            val roles = det?.mapping?.toRoles(width) ?: List(width) { ColumnRole.IGNORE }
            return TableAnalysis(det?.headerRow ?: 0, (0 until width).map { ColumnInfo(it, titles.getOrElse(it) { "" }, roles[it], 0.3, emptyList()) }, ImportOptions(),
                listOf("Non riesco a riconoscere date e importi in questo file: scegli tu le colonne."), 0.0)
        }
        // 2) intestazione: la riga di testo più vicina sopra i dati
        var headerRow = -1
        for (r in firstData - 1 downTo maxOf(0, firstData - 4)) {
            val row = head[r]
            val texts = row.count { it != null && str(it).isNotEmpty() && numberOf(it) == null && !isDateCell(it) }
            if (texts >= 2 && row.none { isDateCell(it) }) { headerRow = r; break }
        }
        val titles = (0 until width).map { c -> if (headerRow >= 0) str(cell(headerRow, c)) else "" }
        val dataRows = (firstData until head.size).filter { isDataRow(it) }
        val n = dataRows.size.coerceAtLeast(1)

        // 3) statistiche per colonna
        class Stat(val c: Int) {
            val h = norm(titles[c])
            val vals = dataRows.map { cell(it, c) }
            val nonBlank = vals.count { it != null && str(it).isNotEmpty() }
            val blankShare = 1.0 - nonBlank.toDouble() / n
            val dateRate = if (nonBlank == 0) 0.0 else vals.count { isDateCell(it) }.toDouble() / nonBlank
            val nums = vals.mapNotNull { numberOf(it) }
            val numRate = if (nonBlank == 0) 0.0 else nums.size.toDouble() / nonBlank
            val negShare = if (nums.isEmpty()) 0.0 else nums.count { it.signum() < 0 }.toDouble() / nums.size
            val texts = vals.mapNotNull { str(it).takeIf { s -> s.isNotEmpty() } }
            val avgLen = if (texts.isEmpty()) 0.0 else texts.sumOf { it.length }.toDouble() / texts.size
            val uniq = if (texts.isEmpty()) 0.0 else texts.toSet().size.toDouble() / texts.size
            val signRate = if (texts.isEmpty()) 0.0 else texts.count { it.uppercase() in SIGN_WORDS }.toDouble() / texts.size
            val isDate get() = dateRate >= 0.8
            val isNumber get() = !isDate && numRate >= 0.8
        }
        val stats = (0 until width).map { Stat(it) }
        val role = Array(width) { ColumnRole.IGNORE }
        val conf = DoubleArray(width)

        // date
        val dateCols = stats.filter { it.isDate }
        val valueFirst = dateCols.filter { has(it.h, VALUE_KEYS) }
        val bookingCols = dateCols.filter { !has(it.h, VALUE_KEYS) }
        bookingCols.firstOrNull()?.let { role[it.c] = ColumnRole.DATE; conf[it.c] = it.dateRate }
        (valueFirst.firstOrNull() ?: bookingCols.drop(1).firstOrNull())?.let { role[it.c] = ColumnRole.VALUE_DATE; conf[it.c] = it.dateRate * 0.9 }
        if (role.none { it == ColumnRole.DATE } && dateCols.isNotEmpty()) { role[dateCols.first().c] = ColumnRole.DATE; conf[dateCols.first().c] = dateCols.first().dateRate }

        // colonna segno D/A
        stats.firstOrNull { it.c !in dateCols.map { d -> d.c } && it.signRate >= 0.9 && it.texts.toSet().size <= 4 }?.let { role[it.c] = ColumnRole.SIGN; conf[it.c] = it.signRate }

        // importi
        val numeric = stats.filter { (it.isNumber || (it.numRate >= 0.8 && it.dateRate < 0.5)) && role[it.c] == ColumnRole.IGNORE }
        val balance = numeric.filter { has(it.h, BALANCE_KEYS) }
        val fees = numeric.filter { has(it.h, FEE_KEYS) && !has(it.h, AMOUNT_KEYS) }
        val candidates = numeric - balance.toSet() - fees.toSet()
        // Dare/Avere: due colonne numeriche complementari (ciascuna con molte celle vuote) o con titoli espliciti
        val sparse = stats.filter { it.numRate >= 0.8 && it.blankShare in 0.25..0.9 && role[it.c] == ColumnRole.IGNORE && !has(it.h, BALANCE_KEYS) }
        val debitCol = (sparse + candidates).firstOrNull { has(it.h, DEBIT_KEYS) } ?: sparse.firstOrNull { has(it.h, DEBIT_KEYS).not() && sparse.size == 2 && it === sparse[0] }
        val creditCol = (sparse + candidates).firstOrNull { has(it.h, CREDIT_KEYS) } ?: sparse.firstOrNull { sparse.size == 2 && it === sparse[1] }
        if (debitCol != null && creditCol != null && debitCol.c != creditCol.c && role.none { it == ColumnRole.AMOUNT }) {
            role[debitCol.c] = ColumnRole.DEBIT; conf[debitCol.c] = 0.85; role[creditCol.c] = ColumnRole.CREDIT; conf[creditCol.c] = 0.85
        } else {
            val pick = candidates.filter { role[it.c] == ColumnRole.IGNORE }.let { c ->
                c.firstOrNull { has(it.h, AMOUNT_KEYS) } ?: c.firstOrNull { it.negShare > 0 } ?: c.firstOrNull()
            }
            pick?.let { role[it.c] = ColumnRole.AMOUNT; conf[it.c] = 0.55 + 0.25 * (if (has(it.h, AMOUNT_KEYS)) 1 else 0) + 0.2 * (if (it.negShare > 0) 1 else 0) }
        }

        // descrizione ed extra
        val textCols = stats.filter { role[it.c] == ColumnRole.IGNORE && it.numRate < 0.5 && it.dateRate < 0.5 && it.texts.isNotEmpty() }
        fun descScore(s: Stat) = (if (s.h.contains("descr")) 70.0 else if (has(s.h, DESC_KEYS)) 40.0 else 0.0) + s.avgLen.coerceAtMost(60.0) + s.uniq * 20
        textCols.maxByOrNull { descScore(it) }?.let { role[it.c] = ColumnRole.DESCRIPTION; conf[it.c] = (0.5 + 0.3 * (if (has(it.h, DESC_KEYS)) 1 else 0) + 0.2 * it.uniq).coerceAtMost(1.0) }
        textCols.filter { role[it.c] == ColumnRole.IGNORE && it.avgLen >= 6 && it.uniq > 0.3 && (has(it.h, EXTRA_KEYS) || has(it.h, DESC_KEYS)) }.forEach { role[it.c] = ColumnRole.EXTRA; conf[it.c] = 0.6 }

        // 4) opzioni suggerite e avvertenze
        val warnings = ArrayList<String>()
        val dateStrings = stats.filter { role[it.c] == ColumnRole.DATE || role[it.c] == ColumnRole.VALUE_DATE }.flatMap { s -> s.vals.mapNotNull { it as? String } }
        var order = DateOrder.AUTO
        val slash = Regex("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})")
        val parts = dateStrings.mapNotNull { slash.find(it.trim())?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }
        if (parts.any { it.first > 12 }) order = DateOrder.DMY
        else if (parts.any { it.second > 12 }) order = DateOrder.MDY
        else if (parts.isNotEmpty()) warnings.add("Le date sono ambigue (giorno e mese entrambi ≤ 12): le leggo come GG/MM/AAAA. Se non è così, cambia il formato nelle opzioni.")

        val amountStat = stats.firstOrNull { role[it.c] == ColumnRole.AMOUNT }
        var decimal = DecimalStyle.AUTO
        val amountStrings = amountStat?.vals?.mapNotNull { it as? String }.orEmpty()
        if (amountStrings.any { Regex(",\\d{1,2}\\s*$").containsMatchIn(it) }) decimal = DecimalStyle.COMMA
        else if (amountStrings.any { Regex("^-?\\d{1,3}\\.\\d{3}$").matches(it.trim()) } && amountStrings.none { it.contains(',') })
            warnings.add("Alcuni importi hanno un punto seguito da 3 cifre (es. 1.234): li leggo come 1,234 euro. Se sono migliaia, imposta il separatore decimale su \"virgola\".")
        if (amountStat != null && amountStat.negShare == 0.0 && role.none { it == ColumnRole.SIGN || it == ColumnRole.DEBIT })
            warnings.add("Tutti gli importi sono positivi: se sono spese (tipico delle carte) attiva \"Inverti il segno\" nelle opzioni.")
        if (role.none { it == ColumnRole.DATE }) warnings.add("Non trovo la colonna della data.")
        if (role.none { it == ColumnRole.DESCRIPTION }) warnings.add("Non trovo la colonna della descrizione.")
        if (role.none { it == ColumnRole.AMOUNT || it == ColumnRole.DEBIT || it == ColumnRole.CREDIT }) warnings.add("Non trovo la colonna dell'importo.")
        if (balance.isNotEmpty()) warnings.add("Ho ignorato la colonna del saldo (\"${balance.first().h}\"): non serve all'importazione.")
        if (fees.isNotEmpty()) warnings.add("Ho ignorato la colonna delle commissioni (\"${fees.first().h}\"): gli importi sono già al lordo?")

        val keyRoles = listOf(ColumnRole.DATE, ColumnRole.DESCRIPTION, ColumnRole.AMOUNT)
        val overall = if (role.any { it == ColumnRole.DEBIT }) minOf(conf[role.indexOf(ColumnRole.DATE).coerceAtLeast(0)], conf[role.indexOf(ColumnRole.DESCRIPTION).coerceAtLeast(0)])
        else keyRoles.minOf { r -> role.indexOf(r).let { if (it < 0) 0.0 else conf[it] } }
        val cols = (0 until width).map { c ->
            ColumnInfo(c, titles[c], role[c], conf[c], dataRows.take(6).mapNotNull { r -> str(cell(r, c)).takeIf { it.isNotEmpty() } })
        }
        return TableAnalysis(headerRow, cols, ImportOptions(order, decimal), warnings, overall)
    }
}
