package it.spese.domain.importing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/** Parser CSV robusto: delimitatore automatico (; , tab |), campi tra virgolette, BOM, UTF-8 con ripiego su Windows-1252. */
object CsvParser {
    private val DELIMS = charArrayOf(';', ',', '\t', '|')

    /** Decodifica i byte provando UTF-8 rigoroso, poi Windows-1252/ISO-8859-1. */
    fun decode(bytes: ByteArray): String {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b = b.copyOfRange(3, b.size)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("windows-1252"))
        }
    }

    /** Sceglie il delimitatore con più occorrenze coerenti (fuori dalle virgolette) nelle prime righe. */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        var best = ';'; var bestScore = -1
        for (d in DELIMS) {
            val counts = sample.map { countOutsideQuotes(it, d) }
            if (counts.isEmpty()) continue
            val max = counts.max()
            if (max == 0) continue
            // punteggio: numero di righe con lo stesso conteggio del modale, ponderato dal conteggio
            val mode = counts.groupingBy { it }.eachCount().filterKeys { it > 0 }.maxByOrNull { it.value }
            val score = (mode?.value ?: 0) * 100 + (mode?.key ?: 0)
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var q = false; var n = 0
        for (c in line) { if (c == '"') q = !q else if (c == d && !q) n++ }
        return n
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cur = StringBuilder(); var inQ = false; var i = 0
        val t = if (text.startsWith("﻿")) text.substring(1) else text
        fun endField() { row.add(cur.toString()); cur.setLength(0) }
        fun endRow() { endField(); if (!(row.size == 1 && row[0].isEmpty())) rows.add(row); row = ArrayList() }
        while (i < t.length) {
            val c = t[i]
            if (inQ) {
                if (c == '"') { if (i + 1 < t.length && t[i + 1] == '"') { cur.append('"'); i++ } else inQ = false } else cur.append(c)
            } else when {
                c == '"' && cur.isEmpty() -> inQ = true
                c == delimiter -> endField()
                c == '\r' -> { if (i + 1 < t.length && t[i + 1] == '\n') i++; endRow() }
                c == '\n' -> endRow()
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
