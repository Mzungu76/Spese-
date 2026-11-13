package it.spese.domain.importing

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Scrittore XLSX minimale: stringhe inline, numeri, date (con formato gg/mm/aaaa) e importi (con formato 2 decimali). */
object XlsxWriter {
    /** Importo monetario: cella numerica con formato `#,##0.00`. */
    data class Amount(val value: BigDecimal)

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;"); c == '"' -> sb.append("&quot;")
            c.code < 0x20 && c != '\n' && c != '\t' && c != '\r' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun colName(i: Int): String { var n = i; val sb = StringBuilder(); do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0); return sb.toString() }

    fun write(sheets: List<RawSheet>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, content: String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.withIndex().joinToString("") { """<sheet name="${esc(it.value.name.take(31))}" sheetId="${it.index + 1}" r:id="rId${it.index + 1}"/>""" } + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/><numFmt numFmtId="165" formatCode="#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { si, sh ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                sh.rows.forEachIndexed { r, row ->
                    sb.append("<row r=\"${r + 1}\">")
                    row.forEachIndexed { c, v ->
                        val ref = colName(c) + (r + 1)
                        when (v) {
                            null -> {}
                            is Amount -> sb.append("<c r=\"$ref\" s=\"2\"><v>${v.value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}</v></c>")
                            is BigDecimal -> sb.append("<c r=\"$ref\"><v>${v.toPlainString()}</v></c>")
                            is Number -> sb.append("<c r=\"$ref\"><v>$v</v></c>")
                            is LocalDate -> sb.append("<c r=\"$ref\" s=\"1\"><v>${v.toEpochDay() + 25569}</v></c>")
                            else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"${if (r == 0) " s=\"3\"" else ""}><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${si + 1}.xml", sb.toString())
            }
        }
    }

    fun toBytes(sheets: List<RawSheet>): ByteArray = ByteArrayOutputStream().also { write(sheets, it) }.toByteArray()
}
