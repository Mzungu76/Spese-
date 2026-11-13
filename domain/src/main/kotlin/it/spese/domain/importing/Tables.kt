package it.spese.domain.importing

import java.io.InputStream

/** Cella grezza: String, BigDecimal (numero), LocalDate (data) oppure null. */
typealias RawRow = List<Any?>

data class RawSheet(val name: String, val rows: List<RawRow>)

class UnsupportedFormatException(message: String) : Exception(message)

/** Tipi di file riconosciuti dal contenuto (non dall'estensione). */
enum class FileFormat { XLSX, XLS, CSV, JSON, UNKNOWN }

object FormatSniffer {
    fun sniff(bytes: ByteArray, fileName: String): FileFormat {
        fun starts(vararg sig: Int) = bytes.size >= sig.size && sig.indices.all { (bytes[it].toInt() and 0xFF) == sig[it] }
        if (starts(0x50, 0x4B, 0x03, 0x04)) return FileFormat.XLSX
        if (starts(0xD0, 0xCF, 0x11, 0xE0)) return FileFormat.XLS
        val n = fileName.lowercase()
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t', 'ï', '»', '¿')
        if (n.endsWith(".json") || head.startsWith("{") || head.startsWith("[")) return FileFormat.JSON
        if (n.endsWith(".csv") || n.endsWith(".txt") || n.endsWith(".tsv")) return FileFormat.CSV
        return if (bytes.isNotEmpty()) FileFormat.CSV else FileFormat.UNKNOWN
    }
}

/** Legge qualunque file tabellare supportato in fogli di righe grezze. */
object TableReader {
    fun read(bytes: ByteArray, fileName: String): List<RawSheet> = when (FormatSniffer.sniff(bytes, fileName)) {
        FileFormat.XLSX -> XlsxReader.read(bytes.inputStream())
        FileFormat.XLS -> throw UnsupportedFormatException(
            "Il formato Excel 97-2003 (.xls) non è supportato: apri il file in Excel/LibreOffice e salvalo come .xlsx oppure .csv.")
        FileFormat.CSV -> listOf(RawSheet(fileName, CsvParser.parse(CsvParser.decode(bytes))))
        FileFormat.JSON -> throw UnsupportedFormatException("Questo è un file JSON: usa \"Importa backup\".")
        FileFormat.UNKNOWN -> throw UnsupportedFormatException("File vuoto o formato non riconosciuto.")
    }

    @Suppress("unused")
    fun read(stream: InputStream, fileName: String): List<RawSheet> = read(stream.readBytes(), fileName)
}
