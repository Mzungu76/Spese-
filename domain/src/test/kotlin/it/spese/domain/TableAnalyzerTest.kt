package it.spese.domain

import it.spese.core.common.DecimalStyle
import it.spese.domain.importing.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TableAnalyzerTest {
    private fun csv(text: String) = CsvParser.parse(text)
    private fun roles(a: TableAnalysis) = a.roles

    @Test fun `estratto xlsx con preambolo - stessa mappatura dell'HTML`() {
        val rows = TableReader.read(Fixtures.bytes("intesa.xlsx"), "intesa.xlsx")[0].rows
        val a = TableAnalyzer.analyze(rows)
        assertEquals(4, a.headerRow)
        assertEquals(listOf(ColumnRole.DATE, ColumnRole.VALUE_DATE, ColumnRole.AMOUNT, ColumnRole.DESCRIPTION), roles(a))
        assertTrue(a.isComplete); assertTrue(a.confidence > 0.6)
        assertEquals(HeaderDetector.detect(rows)!!.mapping, a.mapping)          // identico al riconoscimento per intestazioni
    }

    @Test fun `csv italiano e secondo conto`() {
        val a = TableAnalyzer.analyze(csv(String(Fixtures.bytes("revolut.csv"))))
        assertEquals(listOf(ColumnRole.DATE, ColumnRole.VALUE_DATE, ColumnRole.AMOUNT, ColumnRole.DESCRIPTION), roles(a))
        assertEquals(DecimalStyle.COMMA, a.options.decimal)
    }

    @Test fun `file senza intestazione`() {
        val a = TableAnalyzer.analyze(csv("01/02/2024;Pizzeria da Mario;-12,50\n02/02/2024;Stipendio azienda;1.500,00\n03/02/2024;Caffe bar;-1,20\n04/02/2024;Supermercato Coop;-48,90\n"))
        assertEquals(-1, a.headerRow)
        assertEquals(listOf(ColumnRole.DATE, ColumnRole.DESCRIPTION, ColumnRole.AMOUNT), roles(a))
        val res = ImportParser.parse(csv("01/02/2024;Pizzeria da Mario;-12,50\n02/02/2024;Stipendio;1.500,00\n").map { it }, a.headerRow, a.mapping, a.options)
        assertEquals(listOf(BigDecimal("-12.50"), BigDecimal("1500.00")), res.rows.map { it.amount })
    }

    @Test fun `intestazioni sconosciute ma colonne dare e avere complementari`() {
        val a = TableAnalyzer.analyze(csv("Quando;Che cosa;Colonna X;Colonna Y\n01/03/2024;Affitto;600,00;\n02/03/2024;Bonifico stipendio;;1.800,00\n03/03/2024;Bolletta luce;75,40;\n04/03/2024;Rimborso amico;;20,00\n05/03/2024;Spesa;31,10;\n"))
        assertEquals(ColumnRole.DEBIT, a.roles[2]); assertEquals(ColumnRole.CREDIT, a.roles[3])
        val res = ImportParser.parse(csv("Quando;Che cosa;X;Y\n01/03/2024;Affitto;600,00;\n02/03/2024;Stipendio;;1.800,00\n"), 0, a.mapping, a.options)
        assertEquals(listOf(BigDecimal("-600.00"), BigDecimal("1800.00")), res.rows.map { it.amount })
    }

    @Test fun `export in inglese con commissioni e saldo`() {
        val text = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n" +
            "CARD_PAYMENT,Current,2024-03-05 10:11:12,2024-03-06 08:00:00,Coffee Shop,-3.50,0.00,EUR,COMPLETED,996.50\n" +
            "TOPUP,Current,2024-03-07 09:00:00,2024-03-07 09:00:01,Top-up by card,200.00,0.00,EUR,COMPLETED,1196.50\n" +
            "CARD_PAYMENT,Current,2024-03-08 12:00:00,2024-03-09 08:00:00,Supermarket Rome,-41.20,0.00,EUR,COMPLETED,1155.30\n" +
            "CARD_PAYMENT,Current,2024-03-09 12:00:00,2024-03-10 08:00:00,Gas station,-60.00,0.00,EUR,COMPLETED,1095.30\n"
        val a = TableAnalyzer.analyze(csv(text))
        assertEquals(ColumnRole.DATE, a.roles[2]); assertEquals(ColumnRole.VALUE_DATE, a.roles[3])
        assertEquals(ColumnRole.DESCRIPTION, a.roles[4]); assertEquals(ColumnRole.AMOUNT, a.roles[5])
        assertEquals(ColumnRole.IGNORE, a.roles[6]); assertEquals(ColumnRole.IGNORE, a.roles[9])        // commissioni e saldo
        assertTrue(a.warnings.any { it.contains("saldo") })
        val res = ImportParser.parse(csv(text), a.headerRow, a.mapping, a.options)
        assertEquals(4, res.rows.size); assertEquals(BigDecimal("-3.50"), res.rows[0].amount); assertEquals(LocalDate.of(2024, 3, 5), res.rows[0].date)
    }

    @Test fun `importi sempre positivi con colonna segno D-A`() {
        val text = "Data;Causale;Importo;Segno\n01/04/2024;Pagamento POS;25,00;D\n02/04/2024;Bonifico ricevuto;100,00;A\n03/04/2024;Prelievo ATM;50,00;D\n04/04/2024;Pagamento POS bar;3,20;D\n"
        val a = TableAnalyzer.analyze(csv(text))
        assertEquals(ColumnRole.SIGN, a.roles[3]); assertEquals(ColumnRole.AMOUNT, a.roles[2])
        assertFalse(a.warnings.any { it.contains("positivi") })
        val res = ImportParser.parse(csv(text), a.headerRow, a.mapping, a.options)
        assertEquals(listOf("-25.00", "100.00", "-50.00", "-3.20"), res.rows.map { it.amount.toPlainString() })
    }

    @Test fun `carta con tutti importi positivi - avviso e inversione del segno`() {
        val text = "Data;Descrizione;Importo\n01/04/2024;Amazon;25,00\n02/04/2024;Netflix;9,99\n03/04/2024;Esselunga;50,00\n04/04/2024;Bar;3,20\n"
        val a = TableAnalyzer.analyze(csv(text))
        assertTrue(a.warnings.any { it.contains("positivi") })
        val res = ImportParser.parse(csv(text), a.headerRow, a.mapping, a.options.copy(invertSign = true))
        assertTrue(res.rows.all { it.amount.signum() < 0 })
    }

    @Test fun `date americane e ambigue`() {
        val us = TableAnalyzer.analyze(csv("Date,Description,Amount\n03/25/2024,Shop,-10.00\n03/26/2024,Cafe,-2.50\n03/27/2024,Pay,500.00\n03/28/2024,Rent,-300.00\n"))
        assertEquals(DateOrder.MDY, us.options.dateOrder)
        val res = ImportParser.parse(csv("Date,Description,Amount\n03/25/2024,Shop,-10.00\n"), 0, us.mapping, us.options)
        assertEquals(LocalDate.of(2024, 3, 25), res.rows.single().date)
        val amb = TableAnalyzer.analyze(csv("Data;Descrizione;Importo\n01/02/2024;A;-1,00\n03/04/2024;B;-2,00\n05/06/2024;C;-3,00\n07/08/2024;D;-4,00\n"))
        assertTrue(amb.warnings.any { it.contains("ambigue") })
        // l'utente può forzare MM/GG
        val forced = ImportParser.parse(csv("Data;Descrizione;Importo\n01/02/2024;A;-1,00\n"), 0, amb.mapping, amb.options.copy(dateOrder = DateOrder.MDY))
        assertEquals(LocalDate.of(2024, 1, 2), forced.rows.single().date)
    }

    @Test fun `punto come migliaia segnalato e correggibile`() {
        val text = "Data;Descrizione;Importo\n01/02/2024;Auto;1.234\n02/02/2024;Casa;2.500\n03/02/2024;Bar;3\n04/02/2024;Spesa;4.100\n"
        val a = TableAnalyzer.analyze(csv(text))
        assertTrue(a.warnings.any { it.contains("migliaia") })
        val fixed = ImportParser.parse(csv(text), 0, a.mapping, a.options.copy(decimal = DecimalStyle.COMMA))
        assertEquals(BigDecimal("1234.00"), fixed.rows.first().amount)
    }

    @Test fun `descrizione extra, righe finali ignorate`() {
        val text = "Data;Descrizione;Causale;Importo\n01/02/2024;POS COOP;Spesa settimanale;-30,00\n02/02/2024;BONIFICO;Stipendio febbraio;1.000,00\n03/02/2024;POS BAR;Caffe;-1,50\n04/02/2024;POS ENI;Carburante;-40,00\nTotale;;;2.000,00\n"
        val a = TableAnalyzer.analyze(csv(text))
        assertEquals(ColumnRole.DESCRIPTION, a.roles[1]); assertEquals(ColumnRole.EXTRA, a.roles[2])
        val res = ImportParser.parse(csv(text), a.headerRow, a.mapping, a.options)
        assertEquals("POS COOP – Spesa settimanale", res.rows[0].text)
        // la riga "Totale" ha data non valida: scartata con motivo; con skipLast non viene nemmeno considerata
        assertEquals(listOf("data non valida"), res.skipped.map { it.reason })
        assertEquals(4, ImportParser.parse(csv(text), a.headerRow, a.mapping, a.options.copy(skipLast = 1)).rows.size)
    }

    @Test fun `file non finanziario - nessun riconoscimento ma nessun errore`() {
        val a = TableAnalyzer.analyze(csv("Nome;Cognome\nMario;Rossi\nLuca;Bianchi\n"))
        assertEquals(0.0, a.confidence, 0.0); assertFalse(a.isComplete); assertTrue(a.warnings.isNotEmpty())
        assertEquals(0.0, TableAnalyzer.analyze(emptyList()).confidence, 0.0)
    }

    @Test fun `firma delle intestazioni uguale per file della stessa banca`() {
        val r1 = csv("Data;Descrizione;Importo\n01/02/2024;A;-1,00\n02/02/2024;B;-2,00\n03/02/2024;C;-3,00\n04/02/2024;D;-4,00\n")
        val r2 = csv("DATA ; DESCRIZIONE ; IMPORTO\n11/03/2024;X;-9,00\n12/03/2024;Y;-8,00\n13/03/2024;Z;-7,00\n14/03/2024;W;-6,00\n")
        assertEquals(TableAnalyzer.analyze(r1).signature(r1), TableAnalyzer.analyze(r2).signature(r2))
    }
}
