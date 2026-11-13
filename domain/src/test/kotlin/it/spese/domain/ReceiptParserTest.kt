package it.spese.domain

import it.spese.domain.ocr.ReceiptParser
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReceiptParserTest {
    private val today = LocalDate.of(2024, 6, 30)
    private val receipt = """
        SUPERMERCATO DEL CORSO SRL
        VIA ROMA 12 - 00100 ROMA
        P.IVA 01234567890
        SCONTRINO
        DATA 15/06/2024 ORA 18:32
        PANE INTEGRALE        2,50 A
        LATTE FRESCO 1L       1,89 A
        VINO ROSSO           12,90 B
        SUBTOTALE            17,29
        TOTALE EURO          17,29
        CONTANTE             20,00
        RESTO                 2,71
        IVA 4%     0,07
        IVA 22%    2,33
        TOTALE IVA 2,40
    """.trimIndent()

    @Test fun `scontrino tipico`() {
        val r = ReceiptParser.parse(receipt, today)
        assertEquals("Supermercato del Corso SRL".lowercase(), r.merchant!!.lowercase())
        assertEquals(LocalDate.of(2024, 6, 15), r.date)
        assertEquals(BigDecimal("17.29"), r.total)
        assertEquals(BigDecimal("2.40"), r.vat)
        assertEquals(listOf("PANE INTEGRALE", "LATTE FRESCO 1L", "VINO ROSSO"), r.items.map { it.description })
        assertEquals(BigDecimal("12.90"), r.items.last().amount)
    }

    @Test fun `fattura con totale sulla riga seguente e importi europei`() {
        val r = ReceiptParser.parse("Officina Rossi\nFattura n. 12\nData: 03-05-2024\nTotale documento\n1.234,56\n", today)
        assertEquals(BigDecimal("1234.56"), r.total); assertEquals(LocalDate.of(2024, 5, 3), r.date); assertEquals("Officina Rossi", r.merchant)
    }

    @Test fun `senza etichetta usa l'importo maggiore e ignora date impossibili`() {
        val r = ReceiptParser.parse("BAR SPORT\n01/01/1999\n2,50\n8,00\n", today)
        assertEquals(BigDecimal("8.00"), r.total); assertNull(r.date); assertNull(r.vat)
    }

    @Test fun `testo vuoto non genera errori`() {
        val r = ReceiptParser.parse("", today)
        assertNull(r.merchant); assertNull(r.total); assertTrue(r.items.isEmpty())
    }

    @Test fun `date con mese testuale`() {
        assertEquals(LocalDate.of(2024, 6, 3), ReceiptParser.parse("Trattoria\n3 giu 2024\nTotale 10,00", today).date)
    }
}
