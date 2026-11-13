package it.spese.domain.importing

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Lettore XLSX minimale e senza dipendenze pesanti (solo ZIP + SAX, disponibili sia su JVM sia su Android):
 * fogli, stringhe condivise/inline, numeri, booleani e date (riconosciute dal formato numerico della cella).
 */
object XlsxReader {
    private const val MAX_ENTRY_BYTES = 80L * 1024 * 1024
    private const val MAX_ROWS = 300_000

    fun read(input: InputStream): List<RawSheet> {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name.removePrefix("/")
                if (!(n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" || n == "xl/sharedStrings.xml" || n == "xl/styles.xml" || n.startsWith("xl/worksheets/"))) continue
                val bos = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
                while (true) {
                    val r = zip.read(buf); if (r < 0) break
                    bos.write(buf, 0, r); total += r
                    if (total > MAX_ENTRY_BYTES) throw UnsupportedFormatException("File Excel troppo grande o non valido.")
                }
                files[n] = bos.toByteArray()
            }
        }
        val wbBytes = files["xl/workbook.xml"] ?: throw UnsupportedFormatException("File Excel non valido: manca workbook.xml.")
        val wb = parseWorkbook(wbBytes)
        val rels = files["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val shared = files["xl/sharedStrings.xml"]?.let { parseShared(it) } ?: emptyList()
        val dateXf = files["xl/styles.xml"]?.let { parseStyles(it) } ?: emptySet()
        val out = ArrayList<RawSheet>()
        for ((i, s) in wb.sheets.withIndex()) {
            val target = rels[s.rid]?.let { t -> if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./") }
                ?: "xl/worksheets/sheet${i + 1}.xml"
            val bytes = files[target] ?: continue
            out.add(RawSheet(s.name, parseSheet(bytes, shared, dateXf, wb.date1904)))
        }
        return out
    }

    private fun sax(bytes: ByteArray, h: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        for ((feat, v) in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false)) {
            try { f.setFeature(feat, v) } catch (_: Exception) { }
        }
        val p = f.newSAXParser()
        p.parse(InputSource(ByteArrayInputStream(bytes)), h)
    }

    private fun Attributes.get(local: String): String? {
        for (i in 0 until length) { val q = getQName(i); if (q == local || q.endsWith(":$local")) return getValue(i) }
        return null
    }

    private class SheetRef(val name: String, val rid: String?)
    private class Wb(val sheets: List<SheetRef>, val date1904: Boolean)

    private fun parseWorkbook(b: ByteArray): Wb {
        val sheets = ArrayList<SheetRef>(); var d1904 = false
        sax(b, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                if (q == "sheet" || q.endsWith(":sheet")) sheets.add(SheetRef(a.get("name") ?: "Foglio", a.get("id")))
                if (q == "workbookPr" || q.endsWith(":workbookPr")) d1904 = a.get("date1904") == "1" || a.get("date1904") == "true"
            }
        })
        return Wb(sheets, d1904)
    }

    private fun parseRels(b: ByteArray): Map<String, String> {
        val m = HashMap<String, String>()
        sax(b, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                if (q.endsWith("Relationship")) { val id = a.get("Id"); val t = a.get("Target"); if (id != null && t != null) m[id] = t }
            }
        })
        return m
    }

    private fun parseShared(b: ByteArray): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        var inSi = false; var inT = false; var inPh = false
        sax(b, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (q) { "si" -> { inSi = true; sb.setLength(0) }; "t" -> inT = true; "rPh" -> inPh = true }
            }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q) { "si" -> { out.add(sb.toString()); inSi = false }; "t" -> inT = false; "rPh" -> inPh = false }
            }
            override fun characters(ch: CharArray, s: Int, len: Int) { if (inSi && inT && !inPh) sb.append(ch, s, len) }
        })
        return out
    }

    private val BUILTIN_DATE = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58)

    private fun isDateFormat(code: String): Boolean {
        val c = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").replace(Regex("\\\\."), "").lowercase()
        return c.any { it == 'd' || it == 'y' || it == 'h' || it == 's' } || Regex("(^|[^a-z])m+([^a-z]|$)").containsMatchIn(c) && !c.contains("0")
    }

    /** Indici degli stili (cellXfs) che rappresentano date. */
    private fun parseStyles(b: ByteArray): Set<Int> {
        val custom = HashMap<Int, String>(); val xfs = ArrayList<Int>(); var inXfs = false
        sax(b, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (q) {
                    "numFmt" -> { val id = a.get("numFmtId")?.toIntOrNull(); val code = a.get("formatCode"); if (id != null && code != null) custom[id] = code }
                    "cellXfs" -> inXfs = true
                    "xf" -> if (inXfs) xfs.add(a.get("numFmtId")?.toIntOrNull() ?: 0)
                }
            }
            override fun endElement(u: String?, l: String?, q: String) { if (q == "cellXfs") inXfs = false }
        })
        return xfs.withIndex().filter { (_, id) -> id in BUILTIN_DATE || custom[id]?.let { isDateFormat(it) } == true }.map { it.index }.toSet()
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (c in ref) { if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1) else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1) else break }
        return n - 1
    }

    private fun parseSheet(b: ByteArray, shared: List<String>, dateXf: Set<Int>, d1904: Boolean): List<RawRow> {
        val rows = ArrayList<RawRow>()
        var cells = ArrayList<Any?>(); var rowNo = 0
        var cellType = ""; var cellStyle = -1; var cellCol = 0
        val text = StringBuilder(); var inV = false; var inT = false; var inIs = false; var inRow = false
        val base = if (d1904) 24107L else 25569L
        sax(b, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
                when (q) {
                    "row" -> { inRow = true; cells = ArrayList(); rowNo = a.get("r")?.toIntOrNull() ?: (rows.size + 1) }
                    "c" -> { cellType = a.get("t") ?: "n"; cellStyle = a.get("s")?.toIntOrNull() ?: -1; text.setLength(0)
                        cellCol = a.get("r")?.let { colIndex(it) } ?: cells.size }
                    "v" -> { inV = true; text.setLength(0) }
                    "is" -> { inIs = true; text.setLength(0) }
                    "t" -> if (inIs) inT = true
                }
            }
            override fun characters(ch: CharArray, s: Int, len: Int) { if (inV || (inIs && inT)) text.append(ch, s, len) }
            override fun endElement(u: String?, l: String?, q: String) {
                when (q) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = text.toString()
                        val v: Any? = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }
                            "str", "inlineStr" -> raw
                            "b" -> raw == "1"
                            "e" -> null
                            "d" -> try { LocalDate.parse(raw.take(10)) } catch (e: Exception) { raw }
                            else -> raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()?.let { n ->
                                if (cellStyle in dateXf) LocalDate.ofEpochDay(n.toLong() - base) else n
                            }
                        }
                        while (cells.size < cellCol) cells.add(null)
                        if (cells.size == cellCol) cells.add(v) else if (cellCol < cells.size) cells[cellCol] = v
                    }
                    "row" -> {
                        if (rows.size < MAX_ROWS) {
                            while (rows.size < rowNo - 1) rows.add(emptyList())
                            rows.add(cells)
                        }
                        inRow = false
                    }
                }
            }
        })
        return rows
    }
}
