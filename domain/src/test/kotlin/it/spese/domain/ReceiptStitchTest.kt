package it.spese.domain

import it.spese.domain.ocr.ItemSuggester
import it.spese.domain.ocr.ItemSuggestion
import it.spese.domain.ocr.ProductDictionary
import it.spese.domain.ocr.ReceiptParser
import it.spese.domain.ocr.ReceiptStitcher
import it.spese.domain.usecase.ScanReceiptUseCase
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Scontrino reale lungo (supermercato, 50 righe) fotografato in due foto che si sovrappongono per ~40 righe. */
class ReceiptStitchTest {
    private val head = """
        IN'S MERCATO SPA
        VIA CASSIA, SNC
        01019 VETRALLA (VT) TEL 3755603115
        P.IVA 02896940273
        DOCUMENTO COMMERCIALE
        di vendita o prestazione
        DESCRIZIONE IVA PREZZO(€)
    """.trimIndent()
    private val common1 = """
        NUGGETS VEGETALI 10% 1.99
        COTOLETTA VEGETALE 10% 1.79
        FROLLINI ALL'UOVO 10% 1.99
        DET.CAPI SPORTIVI 22% 2.49
        CROCCHETTE GATTO 22% 2.99
        RAVIOLI RICC/SPINACI 10% 1.19
        RAVIOLI RICC/SPINACI 10% 1.19
        GNOCCHI SORRENTINA 10% 1.99
        ZUCCHERO SEMOLATO 10% 0.95
        10 DADI VEGETALI 10% 0.49
    """.trimIndent()
    // da qui le due foto si sovrappongono
    private val overlap = """
        GUANTI LATTICI TALCO 5% 1.99
        PATATINE CLAS.25GX6 10% 1.59
        PATATINE CHIPS 25GX6 10% 1.99
        10 UOVA M TERRA BOX 10% 2.45
        PANCAKE 10% 1.99
        10 CROISSANT 10% 1.79
        10 CROISSANT 10% 1.79
        ANNULLI
        10 CROISSANT 10% -1.79
        10 DADI CLASSICI 10% 0.49
        BAGNOSCHIUMA PROFUMA 22% 1.59
        SAVOIARDI 10% 1.69
        PANE BIANCO A FETTE 4% 0.85
        PANCAKE 10% 1.99
        MASCARPONE ITALIANO 4% 1.65
        PANE BIANCO A FETTE 4% 0.85
        PROSCIUTTO COTTO 10% 1.79
        PROSCIUTTO COTTO 10% 1.79
        NUTELLA 750G 10% 6.49
        FAGIOLI CANNELLINI 10% 0.49
        LENTICCHIE 10% 0.49
        PELLET EN PLUS A1 22% 7.49
        EDAM A FETTE 4% 1.79
        EDAM A FETTE 4% 1.79
        PIATTI DOSATORE 22% 0.99
        4 x 1.0 PZ x EUR 0.89 SP
        FOCACC.TRADIZIONALE 4% 3.56
        2 x 1.0 PZ x EUR 0.49 SP
        CORNETTO VUOTO 10% 0.98
        GRANA PADANO GRATT. 4% 2.89
        BEV.CAPPUCC.ALPILAND 22% 0.99
        LENTICCHIE 10% 0.49
    """.trimIndent()
    private val tail = """
        LATTE ALTA DIGERIBIL 4% 1.19
        PASSATA POMOD. BRIK 4% 0.49
        LENTICCHIE 10% 0.49
        SUGO ALLA RICOTTA 10% 1.39
        FAGIOLI CANNELLINI 10% 0.49
        PANINI PRECOTTI 6 PZ 4% 0.79
        BURRO SENZA LATTOSIO 4% 1.85
        SACCHI PATTUME GIAL. 22% 0.99
        2 x 1.0 PZ x EUR 0.69 SP
        KRAPFEN ALLA CREMA 10% 1.38
        CROSTINO ZUPPE/INSAL 4% 1.65
        SUBTOTALE 80.70
        SHOPPER BIO IN'S MAX 22% 0.15
        ARTICOLI 53
        TOTALE COMPLESSIVO € 80.85
        DI CUI IVA 7.82
        Pagamento contante 0.00
        Pagamento elettronico 80.85
        Non riscosso 0.00
        Resto 0.00
        Importo pagato 80.85
        15.11.26 10:26 DOC.N 2886-0072
        SERVER RT: 53SNS303685
        DETTAGLIO PAGAMENTI L. 21.06.2017, n.96
        TOTALE COMPLESSIVO EUR
        C. CREDITO EUR 80.85
        RESTO RICALCOLATO EUR 0.00
    """.trimIndent()
    // la prima foto finisce tagliata in fondo, con i prezzi sfalsati di una riga; la seconda inizia con la prima riga tagliata
    private val page1 = head + "\n" + common1 + "\n" + overlap + "\nLATTE ALTA DIGERIBIL 4% 0.49\nPASSATA POMOD. BRIK 4% 0.49\nLENTICCHIE 10% 1.39\nSUGO ALLA RICOTTA 10% 0.49\nFAGIOLI CANNELLINI 10% 0.79"
    private val page2 = "QUANTI LATTICI TALCO 5%\n" + overlap.lines().drop(1).joinToString("\n") + "\n" + tail

    @Test fun `due foto sovrapposte si uniscono senza righe doppie`() {
        val r = ReceiptStitcher.merge(listOf(page1, page2))
        assertEquals(1, r.overlaps.size); assertTrue("sovrapposizione trovata: ${r.overlaps}", r.overlaps[0] >= 25); assertEquals(0, r.gaps)
        val d = ReceiptParser.parse(r.text, LocalDate.parse("2026-11-16"))
        assertEquals(0, BigDecimal("80.85").compareTo(d.total)); assertEquals(0, BigDecimal("80.70").compareTo(d.subtotal))
        assertEquals(0, BigDecimal("80.85").compareTo(d.itemsSum)); assertEquals(0, BigDecimal.ZERO.compareTo(d.itemsGap))
        assertEquals(LocalDate.parse("2026-11-15"), d.date); assertEquals(0, BigDecimal("7.82").compareTo(d.vat))
        assertTrue(d.merchant!!.contains("Mercato"))
    }

    @Test fun `il parser legge IVA, quantita, annulli e torna il numero di articoli`() {
        val d = ReceiptParser.parse(ReceiptStitcher.merge(listOf(page1, page2)).text, LocalDate.parse("2026-11-16"))
        assertEquals(48, d.items.size)                                   // 50 righe meno la coppia annullata
        assertEquals(53, d.items.sumOf { it.quantity })                  // «ARTICOLI 53» stampato sullo scontrino
        assertEquals(4, d.items.first { it.description.startsWith("FOCACC") }.quantity)
        assertEquals("10 DADI VEGETALI", d.items.first { it.description.startsWith("10 DADI V") }.description)
        assertEquals(1, d.items.count { it.description == "10 CROISSANT" })       // due battute e un annullo: ne resta una
        assertTrue(d.items.any { it.description == "10 UOVA M TERRA BOX" }); assertTrue(d.items.any { it.description == "SHOPPER BIO IN'S MAX" })
        assertEquals(2, d.items.first { it.description.startsWith("KRAPFEN") }.quantity)
        assertTrue(d.items.none { it.amount.signum() < 0 })
    }

    @Test fun `senza sovrapposizione le foto si accodano e la giunzione resta segnalata`() {
        val r = ReceiptStitcher.merge(listOf("PANE 4% 0.85\nLATTE 4% 1.19\nUOVA 10% 2.45", "BURRO 4% 1.85\nPASTA 10% 0.99\nSUGO 10% 1.39"))
        assertEquals(listOf(0), r.overlaps); assertEquals(1, r.gaps)
        assertEquals(6, r.text.lines().size)
    }

    @Test fun `una sola foto resta invariata e tre foto si uniscono in catena`() {
        assertEquals(0, ReceiptStitcher.merge(listOf(page1)).overlaps.size)
        fun row(i: Int) = "VOCE " + java.lang.Long.toString(i * 2654435761L, 36).uppercase() + " 10% ${i}.50"
        val a = (1..30).joinToString("\n") { row(it) }
        val b = (20..50).joinToString("\n") { row(it) }
        val c = (40..70).joinToString("\n") { row(it) }
        val r = ReceiptStitcher.merge(listOf(a, b, c))
        assertEquals((1..70).map { row(it) }, r.text.lines()); assertEquals(0, r.gaps)
    }
}

class ScanPagesUseCaseTest {
    private class Store : it.spese.domain.repository.DocumentStore {
        val files = mutableSetOf<String>(); var n = 0
        override suspend fun saveImage(source: String): it.spese.core.model.DocumentRef { val name = "p${++n}.jpg"; files += name; return it.spese.core.model.DocumentRef(0, name, "image/jpeg", 1, 0) }
        override suspend fun stitch(pages: List<it.spese.core.model.DocumentRef>): it.spese.core.model.DocumentRef { val name = "long${++n}.jpg"; files += name; return it.spese.core.model.DocumentRef(0, name, "image/jpeg", 1, 0) }
        override suspend fun deleteFile(fileName: String) { files -= fileName }
        override suspend fun totalBytes() = 0L
        override fun pathOf(fileName: String) = fileName
    }
    private class Docs : it.spese.domain.repository.DocumentRepository {
        val rows = mutableListOf<it.spese.core.model.DocumentRef>()
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(rows.toList())
        override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun add(ref: it.spese.core.model.DocumentRef): Long { val id = rows.size + 1L; rows += ref.copy(id = id); return id }
        override suspend fun update(ref: it.spese.core.model.DocumentRef) {}
        override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
    }

    @org.junit.Test fun `piu foto diventano un solo documento lungo con il testo unito`() = kotlinx.coroutines.runBlocking {
        val store = Store(); val docs = Docs()
        val pages = mapOf("p1.jpg" to "PANE 4% 0.85\nLATTE 4% 1.19\nUOVA 10% 2.45\nBURRO 4% 1.85\nPASTA 10% 0.99",
            "p2.jpg" to "UOVA 10% 2.45\nBURRO 4% 1.85\nPASTA 10% 0.99\nSUGO 10% 1.39\nTOTALE 8.72")
        val uc = ScanReceiptUseCase(store, docs, object : it.spese.domain.repository.TextRecognizer { override suspend fun recognize(imagePath: String) = pages[imagePath] ?: "" })
        val r = uc(listOf("a", "b"))
        assertEquals(2, r.pages); assertEquals(listOf(3), r.overlaps)
        assertEquals(setOf(r.document.fileName), store.files)           // le foto singole sono state eliminate, resta il documento lungo
        assertEquals(1, docs.rows.size); assertEquals(6, r.data.items.size)
        assertEquals(0, BigDecimal("8.72").compareTo(r.data.total))
        assertEquals(0, BigDecimal.ZERO.compareTo(r.data.itemsGap))
    }

    @org.junit.Test fun `una foto sola resta com e`() = kotlinx.coroutines.runBlocking {
        val store = Store(); val docs = Docs()
        val uc = ScanReceiptUseCase(store, docs, object : it.spese.domain.repository.TextRecognizer { override suspend fun recognize(imagePath: String) = "PANE 4% 0.85\nTOTALE 0.85" })
        val r = uc("x")
        assertEquals(1, r.pages); assertEquals("p1.jpg", r.document.fileName); assertTrue(r.overlaps.isEmpty())
    }
}

class ProductDictionaryTest {
    private fun cat(id: Long, name: String, parent: Long? = null) = it.spese.core.model.Category(id, name, it.spese.core.model.TxKind.SPESA, false, 0, parentId = parent)

    @org.junit.Test fun `riconosce i prodotti dello scontrino reale`() {
        val expected = mapOf(
            "LATTE ALTA DIGERIBIL" to "Latticini e uova", "BURRO SENZA LATTOSIO" to "Latticini e uova", "10 UOVA M TERRA BOX" to "Latticini e uova", "EDAM A FETTE" to "Latticini e uova",
            "RAVIOLI RICC/SPINACI" to "Pasta e dispensa", "SUGO ALLA RICOTTA" to "Pasta e dispensa", "PASSATA POMOD. BRIK" to "Pasta e dispensa", "LENTICCHIE" to "Pasta e dispensa", "10 DADI VEGETALI" to "Pasta e dispensa",
            "FROLLINI ALL'UOVO" to "Dolci e colazione", "CORNETTO VUOTO" to "Dolci e colazione", "NUTELLA 750G" to "Dolci e colazione", "KRAPFEN ALLA CREMA" to "Dolci e colazione",
            "PANE BIANCO A FETTE" to "Pane e panificati", "FOCACC. TRADIZIONALE" to "Pane e panificati", "PANINI PRECOTTI 6 PZ" to "Pane e panificati",
            "PROSCIUTTO COTTO" to "Salumi e carne", "NUGGETS VEGETALI" to "Piatti pronti", "COTOLETTA VEGETALE" to "Piatti pronti",
            "PATATINE CLAS.25GX6" to "Snack", "BEV.CAPPUCC.ALPILAND" to "Bevande", "DET.CAPI SPORTIVI" to "Casa e pulizia", "SACCHI PATTUME GIAL." to "Casa e pulizia", "GUANTI LATTICI TALCO" to "Casa e pulizia",
            "PIATTI DOSATORE" to "Casa e pulizia", "BAGNOSCHIUMA PROFUMA" to "Igiene personale", "CROCCHETTE GATTO" to "Animali",
        )
        expected.forEach { (d, g) -> assertEquals(d, g, ProductDictionary.groupOf(d)) }
        assertNull(ProductDictionary.groupOf("SHOPPER BIO IN'S MAX")); assertNull(ProductDictionary.groupOf("PELLET EN PLUS A1"))
    }

    @org.junit.Test fun `memoria e categorie esistenti hanno la precedenza, altrimenti propone di creare`() {
        val cats = listOf(cat(1, "Supermercato"), cat(2, "Latticini", 1), cat(9, "Frutta e verdura"))
        val s = ItemSuggester.suggest(listOf("LATTE ALTA DIGERIBIL", "NUTELLA 750G", "Mela golden", "SHOPPER BIO", "PANE BIANCO"), mapOf("pane bianco" to 1L), cats, 1L)
        assertEquals(ItemSuggestion(2, null), s[0])               // esiste «Latticini» (figlia): si assegna senza creare nulla
        assertEquals(ItemSuggestion(null, "Dolci e colazione"), s[1])   // manca: da creare con il consenso
        assertEquals(ItemSuggestion(9, null), s[2])               // esiste «Frutta e verdura» come categoria a sé
        assertEquals(ItemSuggestion(null, null), s[3])            // prodotto sconosciuto: resta nella principale
        assertEquals(ItemSuggestion(1, null), s[4])               // la memoria dell'utente vince sul dizionario
        // senza una categoria principale non si propone di creare nulla
        assertEquals(ItemSuggestion(null, null), ItemSuggester.suggest(listOf("NUTELLA 750G"), emptyMap(), cats, null)[0])
    }
}
