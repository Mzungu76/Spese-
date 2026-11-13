package it.spese.domain.importing

import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import java.math.BigDecimal
import java.time.LocalDate

/** Parsing tollerante delle date degli estratti conto. */
object DateParser {
    private val MESI = mapOf(
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6, "lug" to 7, "ago" to 8, "set" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
        "jan" to 1, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "dec" to 12,
    )

    fun parse(v: Any?): LocalDate? = when (v) {
        null -> null
        is LocalDate -> v
        is BigDecimal -> serial(v)
        is Number -> serial(BigDecimal(v.toString()))
        else -> parseText(v.toString())
    }

    /** Numero seriale Excel (sistema 1900) → data, come la versione HTML. */
    private fun serial(n: BigDecimal): LocalDate? {
        val d = n.toLong()
        return if (d in 1..2958465) LocalDate.ofEpochDay(d - 25569) else null
    }

    fun parseText(raw: String): LocalDate? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        Regex("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})").find(s)?.let { m ->
            var a = m.groupValues[1].toInt(); var b = m.groupValues[2].toInt(); var y = m.groupValues[3].toInt()
            if (m.groupValues[3].length <= 2) y += 2000
            if (b > 12 && a <= 12) { val t = a; a = b; b = t } // formato mm/dd/yyyy
            return safe(y, b, a)
        }
        Regex("^(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})").find(s)?.let { m ->
            return safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(s)?.let { return safe(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("^(\\d{1,2})\\s+([A-Za-zàù]{3})[a-z]*\\.?\\s+(\\d{2,4})").find(s)?.let { m ->
            val mo = MESI[m.groupValues[2].lowercase()] ?: return null
            var y = m.groupValues[3].toInt(); if (m.groupValues[3].length <= 2) y += 2000
            return safe(y, mo, m.groupValues[1].toInt())
        }
        return null
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: Exception) { null }
}

/** Mappatura delle colonne di un estratto conto. Gli indici sono 0-based; -1 = assente. */
data class ColumnMapping(
    val date: Int = -1,
    val valueDate: Int = -1,
    val amount: Int = -1,
    /** Colonne separate Dare (uscite) / Avere (entrate), alternativa a [amount]. */
    val debit: Int = -1,
    val credit: Int = -1,
    val description: Int = -1,
) {
    val isUsable: Boolean get() = date >= 0 && description >= 0 && (amount >= 0 || debit >= 0 || credit >= 0)
}

data class DetectedTable(val headerRow: Int, val headers: List<String>, val mapping: ColumnMapping)

object HeaderDetector {
    private fun norm(c: Any?) = (c?.toString() ?: "").lowercase().trim()

    /**
     * Cerca la riga di intestazione nelle prime 40 righe. Riconosce le intestazioni delle principali banche italiane
     * ("Data Contabile", "Data Valuta", "Importo (euro)", "Descrizione operazioni", "Causale", "Dare/Avere", ...).
     * Per la compatibilità con la versione HTML, la riga è valida se contiene "importo" e una colonna descrittiva;
     * in aggiunta sono accettate le intestazioni con colonne Dare/Avere o "Entrate/Uscite".
     */
    fun detect(rows: List<RawRow>): DetectedTable? {
        for (i in 0 until minOf(rows.size, 40)) {
            val hd = rows[i].map { norm(it) }
            if (hd.all { it.isEmpty() }) continue
            val hasDesc = hd.any { it.contains("descr") || it.contains("causale") || it.contains("operaz") || it.contains("dettagli") || it.contains("movimento") }
            val hasAmt = hd.any { it.contains("importo") || it.contains("ammontare") } ||
                (hd.any { it.contains("dare") || it.contains("uscite") || it.contains("addebit") } && hd.any { it.contains("avere") || it.contains("entrate") || it.contains("accredit") })
            if (hasDesc && hasAmt) return DetectedTable(i, rows[i].map { it?.toString()?.trim() ?: "" }, guess(hd))
        }
        return null
    }

    fun guess(headers: List<String>): ColumnMapping {
        val hd = headers.map { norm(it) }
        fun find(vararg k: String) = hd.indexOfFirst { c -> k.any { c.contains(it) } }
        val iImp = find("importo", "ammontare")
        val iDesc = find("descrizione", "causale", "operazioni", "dettagli", "movimento")
        var iD = find("data contabile", "data operazione", "data reg")
        if (iD < 0) iD = hd.indexOfFirst { it.contains("data") && !it.contains("valuta") }
        if (iD < 0) iD = find("data")
        var iV = find("data valuta", "valuta")
        if (iV == iD) iV = -1
        val iDebit = find("dare", "uscite", "addebit"); val iCredit = find("avere", "entrate", "accredit")
        return ColumnMapping(iD, iV, if (iImp >= 0) iImp else -1, if (iImp >= 0) -1 else iDebit, if (iImp >= 0) -1 else iCredit, iDesc)
    }
}

data class ParsedRow(val rowNumber: Int, val date: LocalDate, val valueDate: LocalDate, val amount: BigDecimal, val text: String)
data class SkippedRow(val rowNumber: Int, val reason: String)
data class ParseResult(val rows: List<ParsedRow>, val skipped: List<SkippedRow>)

object ImportParser {
    /**
     * Converte le righe dati (dopo l'intestazione) secondo la mappatura. Le righe senza data o importo validi vengono
     * scartate con il motivo (la versione HTML le ignorava silenziosamente). Gli importi sono arrotondati a 2 decimali.
     */
    fun parse(rows: List<RawRow>, headerRow: Int, m: ColumnMapping): ParseResult {
        val ok = ArrayList<ParsedRow>(); val skipped = ArrayList<SkippedRow>()
        fun cell(r: RawRow, i: Int): Any? = if (i in r.indices) r[i] else null
        for (i in headerRow + 1 until rows.size) {
            val r = rows[i]
            if (r.all { it == null || it.toString().isBlank() }) continue
            val d = DateParser.parse(cell(r, m.date))
            val a: BigDecimal? = if (m.amount >= 0) toNumber(cell(r, m.amount))
            else {
                val deb = toNumber(cell(r, m.debit)); val cre = toNumber(cell(r, m.credit))
                if (deb == null && cre == null) null else (cre ?: BigDecimal.ZERO) - (deb?.abs() ?: BigDecimal.ZERO)
            }
            val text = (cell(r, m.description)?.toString() ?: "").replace(Regex("\\s+"), " ").trim()
            if (d == null) { skipped.add(SkippedRow(i + 1, "data non valida")); continue }
            if (a == null) { skipped.add(SkippedRow(i + 1, "importo non valido")); continue }
            val v = if (m.valueDate >= 0) DateParser.parse(cell(r, m.valueDate)) ?: d else d
            ok.add(ParsedRow(i + 1, d, v, Money.scale(a), text))
        }
        return ParseResult(ok, skipped)
    }

    private fun toNumber(v: Any?): BigDecimal? = when (v) {
        null -> null
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        else -> NumberParser.parse(v.toString())
    }
}
