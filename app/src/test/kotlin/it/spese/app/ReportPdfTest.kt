package it.spese.app

import it.spese.core.model.*
import it.spese.domain.repository.AppData
import it.spese.domain.report.*
import it.spese.feature.reports.PdfReportRenderer
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportPdfTest {
    private val cats = listOf(Category(1, "Supermercato", TxKind.SPESA, false, 0xFF2358D8), Category(2, "Stipendio", TxKind.ENTRATA, false, 0xFF0EA5C6))
    private val txs = (1..120).map { i ->
        val d = LocalDate.of(2026, 1 + i % 9, 1 + i % 27)
        if (i % 10 == 0) Transaction(i.toLong(), "e$i", 1, d, d, "Bonifico stipendio", BigDecimal(2000), TxKind.ENTRATA, 2)
        else Transaction(i.toLong(), "e$i", 1, d, d, "Pagamento POS COOP SPA numero $i", BigDecimal(-(10 + i)), TxKind.SPESA, 1)
    }
    private val data = AppData(transactions = txs, categories = cats, accounts = listOf(Account(1, "Intesa")), loaded = true)

    /** PdfDocument ha bisogno della libreria nativa di Android: sulla JVM di Robolectric non c'è, e il test si salta (l'impaginazione è provata in :domain). */
    private fun pdfAvailable() = runCatching { android.graphics.pdf.PdfDocument().also { d -> d.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(10, 10, 1).create()).also { p -> d.finishPage(p) }; d.close() } }.isSuccess

    @Test fun `ogni modello pronto diventa un PDF con piu pagine per gli elenchi lunghi`() {
        org.junit.Assume.assumeTrue("PdfDocument non disponibile su questa JVM", pdfAvailable())
        ReportPresets.BUILT_IN.forEach { p ->
            val doc = ReportBuilder.build(data, p.spec, LocalDate.of(2026, 9, 28), p.name)
            val pdf = PdfReportRenderer.render(doc)
            assertTrue("${p.id}: non è un PDF", String(pdf.bytes.copyOf(5)) == "%PDF-")
            assertTrue(pdf.pageCount >= 1); assertTrue(pdf.bytes.size > 500)
        }
        val long = ReportBuilder.build(data, ReportSpec("Elenco", period = ReportPeriod(ReportPeriodKind.ALL), sections = listOf(ReportSection.TRANSACTIONS)), LocalDate.of(2026, 9, 28))
        assertTrue(PdfReportRenderer.render(long).pageCount >= 3)
    }
}
