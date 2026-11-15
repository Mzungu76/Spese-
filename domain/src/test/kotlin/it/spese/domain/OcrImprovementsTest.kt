package it.spese.domain

import it.spese.domain.ocr.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OcrImprovementsTest {
    private val today = LocalDate.parse("2026-11-16")

    @Test fun `le immagini alte si dividono in fasce sovrapposte che coprono tutto`() {
        assertEquals(listOf(0 until 1200), OcrTiles.plan(1000, 1200))              // non alta: intera
        val tiles = OcrTiles.plan(1000, 4000)
        assertTrue(tiles.size >= 3); assertEquals(0, tiles.first().first); assertEquals(3999, tiles.last().last)
        tiles.zipWithNext().forEach { (a, b) -> assertTrue("sovrapposizione", b.first < a.last) }
        assertTrue(tiles.all { it.last - it.first + 1 <= 1400 })
    }

    @Test fun `le righe delle fasce si riuniscono senza doppioni nella sovrapposizione`() {
        fun l(t: String, y: Float, conf: Float = 0.9f) = OcrLine(t, 300f, y, 0f, 30f, conf)
        val a = (0 until 1300) to listOf(l("PANE 1.00", 100f), l("LATTE 1.19", 600f), l("UOVA 2.45", 1150f, 0.5f))       // ultima riga tagliata, poco sicura
        val b = (975 until 2275) to listOf(l("UOVA 2.45", 1152f, 0.95f), l("BURRO 1.85", 1500f), l("PASTA 0.99", 2000f))
        val merged = OcrTiles.mergeStrips(listOf(a, b)).sortedBy { it.cy }
        assertEquals(listOf("PANE 1.00", "LATTE 1.19", "UOVA 2.45", "BURRO 1.85", "PASTA 0.99"), merged.map { it.text })
        assertEquals(0.95f, merged[2].confidence, 0.001f)                                                                    // tiene la lettura più sicura
    }

    @Test fun `il riquadro del testo toglie lo sfondo`() {
        val lines = (1..10).map { OcrLine("RIGA NUMERO $it", 500f, 400f + it * 40f, 0f, 30f) }
        val b = OcrTiles.textBounds(lines, 1500, 2000)!!
        assertTrue(b[0] in 0..500 && b[2] in 500..1500); assertTrue(b[1] >= 100 && b[3] <= 1100)
    }

    @Test fun `il ritaglio non taglia le righe larghe`() {
        // righe lunghe (larghe 900 px) centrate a 700: il bordo sinistro reale è 250, non 700
        val lines = (1..10).map { OcrLine("RAVIOLI RICC/SPINACI $it", 700f, 400f + it * 40f, 0f, 30f, 0.9f, 900f) }
        val b = OcrTiles.textBounds(lines, 1500, 2000)!!
        assertTrue("sinistra ${b[0]}", b[0] <= 250); assertTrue("destra ${b[2]}", b[2] >= 1150)
    }

    @Test fun `nitidezza e contrasto`() {
        val w = 64; val h = 64
        val sharp = IntArray(w * h) { i -> if ((i % w / 4 + i / w / 4) % 2 == 0) 20 else 230 }                    // scacchiera netta
        val blur = IntArray(w * h) { i -> 120 + (i % w) / 8 }                                                      // sfumatura morbida
        assertFalse(ImageQuality.isBlurry(ImageQuality.sharpness(sharp, w, h))); assertTrue(ImageQuality.isBlurry(ImageQuality.sharpness(blur, w, h)))
        val faint = IntArray(1000) { 110 + (it % 30) }                                                              // carta sbiadita: poco contrasto
        val s = ImageQuality.stretch(faint)
        assertTrue(s.max() - s.min() > 200)
    }

    @Test fun `corregge cifre e IVA scambiate con lettere`() {
        assertEquals("NUGGETS 10% 1.99", ReceiptCorrector.fixLine("NUGGETS 1O% 1.99"))
        assertEquals("LATTE 4% 1.19", ReceiptCorrector.fixLine("LATTE 4X l.19"))
        assertEquals("PASTA 10% 10.49", ReceiptCorrector.fixLine("PASTA 10% 1O.49"))
        assertEquals("PANE 4% 0.85", ReceiptCorrector.fixLine("PANE 4% 0. 85"))
        assertEquals("SHOPPER BIO", ReceiptCorrector.fixLine("SHOPPER BIO"))                       // niente da correggere
        assertEquals("OLIO D.OLIVA", ReceiptCorrector.fixLine("OLIO D.OLIVA"))                    // nessuna cifra vera: non si tocca
    }

    @Test fun `ripara le parole storpiate con la parola nota piu vicina`() {
        val vocab = ProductDictionary.allKeywords
        assertEquals("LATTE ALTA DIGERIBIL", ReceiptCorrector.repairWords("LATTF ALTA DIGERIBIL", vocab))
        assertEquals("PROSCIUTTO COTTO", ReceiptCorrector.repairWords("PROSCIUTT0 COTTO", vocab))
        assertEquals("ZAFFERANO MACINATO", ReceiptCorrector.repairWords("ZAFFERANO MACINATO", vocab))     // sconosciute ma non vicine: invariate
        assertEquals("NUTELLA GRANDE", ReceiptCorrector.repairWords("NUTELLA GRANDE", vocab))
    }

    @Test fun `quantita per prezzo unitario ripara un prezzo letto male`() {
        val d = ReceiptParser.parse("COOP\nPIATTI 22% 0.99\n4 x 1.0 PZ x EUR 0.89 SP\nFOCACCIA 4% 3.58\nPANE 4% 0.85\nTOTALE COMPLESSIVO 5.40\nImporto pagato 5.40", today)
        assertEquals(1, d.repairs.size); assertEquals(0, BigDecimal("3.56").compareTo(d.items[1].amount)); assertEquals(0, BigDecimal.ZERO.compareTo(d.itemsGap))
    }

    @Test fun `una cifra scambiata si ripara solo se la voce e poco sicura, il totale e confermato e la soluzione e una sola`() {
        val text = "COOP\nPANE 4% 0.85\nLATTE 4% 1.19\nUOVA 10% 2.48\nTOTALE COMPLESSIVO 4.49\nPagamento elettronico 4.49"       // UOVA è 2.45 (5 letto come 8? no: 3↔8): 2.48 -> 2.45 non confusabile
        assertTrue(ReceiptParser.parse(text, today, weakRows = listOf("UOVA 10% 2.48")).repairs.isEmpty())
        val t2 = "COOP\nPANE 4% 0.85\nLATTE 4% 1.19\nUOVA 10% 2.83\nTOTALE COMPLESSIVO 4.87\nPagamento elettronico 4.87"       // 2.83 → 2.83? la voce vera è 2.83-… sotto: vedi test dedicato
        val d = ReceiptParser.parse("COOP\nPANE 4% 0.85\nLATTE 4% 1.19\nUOVA 10% 2.83\nTOTALE COMPLESSIVO 4.83\nPagamento elettronico 4.83", today, weakRows = listOf("UOVA 10% 2.83"))
        // 0.85+1.19+2.83 = 4.87 ≠ 4.83: serve scambiare 3→8? la cifra 8 (2.83→2.43 non confusabile); nessuna soluzione unica → nessuna riparazione
        assertTrue(d.repairs.isEmpty()); assertTrue(t2.isNotEmpty())
    }

    @Test fun `scambio di cifra confusabile su voce debole viene riparato`() {
        // vero prezzo 1.89, letto 1.39 (8↔3): la somma 0.85+1.19+1.39 = 3.43 ≠ 3.93
        val text = "COOP\nPANE 4% 0.85\nLATTE 4% 1.19\nCOLA 22% 1.39\nTOTALE COMPLESSIVO 3.93\nPagamento elettronico 3.93"
        val weak = ReceiptParser.parse(text, today, weakRows = listOf("COLA 22% 1.39"))
        assertEquals(1, weak.repairs.size); assertEquals(0, BigDecimal("1.89").compareTo(weak.items[2].amount)); assertEquals(setOf(2), weak.weakItems)
        // senza segnale di poca sicurezza non si tocca nulla
        assertTrue(ReceiptParser.parse(text, today).repairs.isEmpty())
        // totale non confermato da altre righe: non si tocca
        assertTrue(ReceiptParser.parse("COOP\nPANE 4% 0.85\nLATTE 4% 1.19\nCOLA 22% 1.39\nTOTALE COMPLESSIVO 3.93", today, weakRows = listOf("COLA 22% 1.39")).repairs.isEmpty())
    }

    @Test fun `le righe poco sicure segnano le voci da controllare`() {
        val d = ReceiptParser.parse("COOP\nPANE 4% 0.85\nLATTE 4% 1.19\nTOTALE 2.04", today, weakRows = listOf("LATTE 4% 1,19"))
        assertEquals(setOf(1), d.weakItems)
    }
}
