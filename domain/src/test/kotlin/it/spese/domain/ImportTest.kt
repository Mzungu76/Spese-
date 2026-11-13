package it.spese.domain

import it.spese.core.model.Transaction
import it.spese.domain.classification.Duplicates
import it.spese.domain.importing.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ImportTest {
    @Test fun `CSV con punto e virgola, virgolette e formato italiano`() {
        val csv = "Data;Descrizione;Importo\n01/02/2024;\"Bar; \"\"Centrale\"\"\";-1.234,50\n02/02/2024;Stipendio;1.500,00\n"
        val rows = CsvParser.parse(csv)
        assertEquals(';', CsvParser.detectDelimiter(csv))
        assertEquals(3, rows.size)
        assertEquals("Bar; \"Centrale\"", rows[1][1])
    }

    @Test fun `delimitatori diversi e decodifica Windows-1252`() {
        assertEquals(',', CsvParser.detectDelimiter("a,b,c\n1,2,3\n"))
        assertEquals('\t', CsvParser.detectDelimiter("a\tb\tc\n1\t2\t3\n"))
        assertEquals('|', CsvParser.detectDelimiter("a|b|c\n1|2|3\n"))
        val cp = "Caffè €".toByteArray(charset("windows-1252"))
        assertEquals("Caffè €", CsvParser.decode(cp))
        assertEquals("Caffè", CsvParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Caffè".toByteArray()))
    }

    @Test fun `date in formati diversi`() {
        val d = LocalDate.of(2024, 3, 5)
        listOf("05/03/2024", "5-3-2024", "05.03.24", "2024-03-05", "20240305", "5 mar 2024", "05/03/2024 10:22").forEach { assertEquals(it, d, DateParser.parse(it)) }
        assertEquals(LocalDate.of(2024, 3, 25), DateParser.parse("03/25/2024")) // mm/dd/yyyy
        assertEquals(LocalDate.of(2024, 3, 5), DateParser.parse(BigDecimal("45356")))   // seriale Excel
        assertNull(DateParser.parse("31/02/2024")); assertNull(DateParser.parse("boh"))
    }

    @Test fun `XLSX di un estratto conto con preambolo`() {
        val sheets = TableReader.read(Fixtures.bytes("intesa.xlsx"), "intesa.xlsx")
        assertEquals("Movimenti", sheets[0].name)
        val det = HeaderDetector.detect(sheets[0].rows)!!
        assertEquals(4, det.headerRow)
        assertEquals(ColumnMapping(date = 0, valueDate = 1, amount = 2, description = 3), det.mapping)
        val res = ImportParser.parse(sheets[0].rows, det.headerRow, det.mapping)
        assertEquals(219, res.rows.size); assertTrue(res.skipped.isEmpty())
        val first = res.rows.first()
        assertEquals(LocalDate.of(2024, 10, 27), first.date)
        val golden = Fixtures.golden["tx"]!!.jsonArray.map { it.jsonObject }.filter { it["acc"]!!.s() == "Intesa" }
        assertEquals(golden.size, res.rows.size)
        assertEquals(golden.sumOf { it["a"]!!.d() }.setScale(2), res.rows.sumOf { it.amount })
    }

    @Test fun `CSV italiano di un secondo conto`() {
        val sheets = TableReader.read(Fixtures.bytes("revolut.csv"), "revolut.csv")
        val det = HeaderDetector.detect(sheets[0].rows)!!
        val res = ImportParser.parse(sheets[0].rows, det.headerRow, det.mapping)
        assertEquals(4, res.rows.size)
        assertEquals(BigDecimal("-1234.56"), res.rows.last().amount)
        assertEquals(BigDecimal("200.00"), res.rows.first().amount)
    }

    @Test fun `colonne Dare e Avere, righe non valide segnalate`() {
        val rows: List<RawRow> = listOf(
            listOf("Data operazione", "Causale", "Dare", "Avere"),
            listOf("01/01/2024", "Pizzeria", "12,50", null),
            listOf("02/01/2024", "Bonifico", null, "1.000,00"),
            listOf("data errata", "X", "1", null),
            listOf("03/01/2024", "Y", "abc", null),
            listOf(null, null, null, null),
        )
        val det = HeaderDetector.detect(rows)!!
        assertEquals(ColumnMapping(date = 0, valueDate = -1, amount = -1, debit = 2, credit = 3, description = 1), det.mapping)
        val res = ImportParser.parse(rows, 0, det.mapping)
        assertEquals(listOf(BigDecimal("-12.50"), BigDecimal("1000.00")), res.rows.map { it.amount })
        assertEquals(listOf("data non valida", "importo non valido"), res.skipped.map { it.reason })
    }

    @Test fun `intestazioni personalizzate richiedono mappatura manuale`() {
        val rows: List<RawRow> = listOf(listOf("Quando", "Cosa", "Quanto"), listOf("01/01/2024", "Caffè", "-1,20"))
        assertNull(HeaderDetector.detect(rows))
        val res = ImportParser.parse(rows, 0, ColumnMapping(date = 0, amount = 2, description = 1))
        assertEquals(BigDecimal("-1.20"), res.rows.single().amount)
    }

    @Test fun `XLS binario non supportato con messaggio chiaro`() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val e = assertThrows(UnsupportedFormatException::class.java) { TableReader.read(ole, "vecchio.xls") }
        assertTrue(e.message!!.contains("xlsx"))
    }

    @Test fun `scrittura e rilettura XLSX`() {
        val rows = listOf<RawRow>(listOf("Data", "Importo", "Descrizione"), listOf(LocalDate.of(2024, 2, 29), XlsxWriter.Amount(BigDecimal("-12.30")), "Caffè & <co>"))
        val back = XlsxReader.read(XlsxWriter.toBytes(listOf(RawSheet("Prova", rows))).inputStream())
        assertEquals("Prova", back[0].name)
        assertEquals(LocalDate.of(2024, 2, 29), back[0].rows[1][0])
        assertEquals(0, BigDecimal("-12.30").compareTo(back[0].rows[1][1] as BigDecimal))
        assertEquals("Caffè & <co>", back[0].rows[1][2])
    }

    @Test fun `deduplica con insieme a molteplicita come l'HTML`() {
        val a = Duplicates.key("Intesa", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1), BigDecimal("-1.30"), "caffe")
        val plan = Duplicates.plan(listOf(a), listOf("x", "y", "z").map { Duplicates.Incoming(a, it) })
        assertEquals(listOf(true, false, false), plan.map { it.duplicate })
        assertEquals(listOf(1, 2, 3), plan.map { it.occurrence })
        val none = Duplicates.plan(emptyList(), listOf(Duplicates.Incoming(a, 1), Duplicates.Incoming(a, 2)))
        assertEquals(listOf(false, false), none.map { it.duplicate }) // due caffè identici nello stesso file: entrambi importati
    }

    @Test fun `reimportare lo stesso file non aggiunge nulla`() {
        val sheets = TableReader.read(Fixtures.bytes("intesa.xlsx"), "intesa.xlsx")
        val det = HeaderDetector.detect(sheets[0].rows)!!
        val res = ImportParser.parse(sheets[0].rows, det.headerRow, det.mapping)
        val first = ImportPlanner.preview(res, "Intesa", emptyList())
        assertEquals(219, first.newCount)
        val second = ImportPlanner.preview(res, "Intesa", first.rows.map { it.key })
        assertEquals(0, second.newCount); assertEquals(219, second.duplicateCount)
        // gli id generati coincidono con quelli prodotti dalla versione HTML
        val htmlIds = Fixtures.golden["tx"]!!.jsonArray.map { it.jsonObject["id"]!!.s() }.toSet()
        assertTrue(first.rows.all { ImportPlanner.externalId(it) in htmlIds })
    }

    @Test fun `duplicati probabili e trasferimenti tra conti`() {
        fun tx(id: Long, acc: Long, d: String, a: String, t: String) = Transaction(id, "e$id", acc, LocalDate.parse(d), LocalDate.parse(d), t, BigDecimal(a), it.spese.core.model.TxKind.SPESA, null)
        val l = listOf(tx(1, 1, "2024-01-01", "-20.00", "PAGAMENTO POS RISTORANTE ROMA"), tx(2, 1, "2024-01-01", "-20.00", "Pagamento POS ristorante roma 01/01"),
            tx(3, 1, "2024-01-05", "-9.99", "Netflix"), tx(4, 1, "2024-01-05", "-9.99", "Netflix"),
            tx(5, 1, "2024-02-01", "-200.00", "Giroconto"), tx(6, 2, "2024-02-02", "200.00", "Ricarica"), tx(7, 2, "2024-02-02", "200.00", "Altro"))
        val groups = Duplicates.findProbable(l)
        assertEquals(setOf(Duplicates.Reason.IDENTICAL, Duplicates.Reason.SIMILAR), groups.map { it.reason }.toSet())
        val pairs = it.spese.domain.classification.TransferDetector.detect(l)
        assertEquals(1, pairs.size); assertEquals(5L, pairs[0].outgoing.id); assertEquals(6L, pairs[0].incoming.id)
    }
}
