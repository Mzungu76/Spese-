package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.Period
import it.spese.domain.repository.AppData
import it.spese.domain.report.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class ReportTest {
    // ---- misura del testo e rendering di prova su immagine (solo per i test) ----
    private val awtOk = runCatching { java.awt.GraphicsEnvironment.isHeadless(); BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(Font("SansSerif", Font.PLAIN, 10)).stringWidth("a"); true }.getOrDefault(false)
    private val metrics = TextMetrics { t, s, b ->
        if (awtOk) BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(Font("SansSerif", if (b) Font.BOLD else Font.PLAIN, 100)).stringWidth(t) * s / 100f
        else t.length * s * (if (b) 0.58f else 0.53f)
    }

    private fun png(pages: List<PageContent>, name: String) {
        val dir = System.getenv("REPORT_PNG_DIR") ?: return
        if (!awtOk) return
        pages.forEachIndexed { i, p ->
            val scale = 1.6f
            val img = BufferedImage((p.width * scale).toInt(), (p.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height); g.scale(scale.toDouble(), scale.toDouble())
            p.ops.forEach { op ->
                when (op) {
                    is RectOp -> { g.color = Color(op.color, true); if (op.radius > 0) g.fillRoundRect(op.x.toInt(), op.y.toInt(), op.w.toInt().coerceAtLeast(1), op.h.toInt().coerceAtLeast(1), (op.radius * 2).toInt(), (op.radius * 2).toInt()) else g.fillRect(op.x.toInt(), op.y.toInt(), op.w.toInt().coerceAtLeast(1), op.h.toInt().coerceAtLeast(1)) }
                    is LineOp -> { g.color = Color(op.color, true); g.stroke = java.awt.BasicStroke(op.width); g.drawLine(op.x1.toInt(), op.y1.toInt(), op.x2.toInt(), op.y2.toInt()) }
                    is TextOp -> { g.color = Color(op.color, true); g.font = Font("SansSerif", if (op.bold) Font.BOLD else Font.PLAIN, 100).deriveFont(op.size)
                        val w = g.fontMetrics.stringWidth(op.text); val x = when (op.align) { Align.LEFT -> op.x; Align.RIGHT -> op.x - w; Align.CENTER -> op.x - w / 2f }; g.drawString(op.text, x, op.y) }
                }
            }
            g.dispose(); ImageIO.write(img, "png", java.io.File(dir, "$name-${i + 1}.png"))
        }
    }

    // ---- dati ----
    private val cats = listOf(
        Category(1, "Supermercato", TxKind.SPESA, false, 0xFF2358D8, budgetMonthly = BigDecimal(300)), Category(2, "Casa", TxKind.SPESA, false, 0xFFE0742C), Category(3, "Bollette", TxKind.SPESA, false, 0xFF16A37F, parentId = 2),
        Category(4, "Bar e ristoranti", TxKind.SPESA, true, 0xFFC23B8F), Category(5, "Stipendio", TxKind.ENTRATA, false, 0xFF0EA5C6), Category(6, "Contanti", TxKind.SPESA, true, 0xFF888888), Category(7, "Affitto", TxKind.SPESA, false, 0xFF8A5CF6),
    )
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long, desc: String): Transaction {
        val kind = if (cat == 5L) TxKind.ENTRATA else TxKind.SPESA
        return Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount), kind, cat)
    }
    private val txs = (1..9).flatMap { m ->
        val d = { day: Int -> "2026-%02d-%02d".format(m, day) }
        listOf(tx(d(1), 2000, 5, "Bonifico stipendio ACME SRL"), tx(d(3), -(230 + 8 * m), 1, "Pagamento POS COOP SPA"), tx(d(9), -(90 + 12 * m), 4, "Pagamento POS BAR ROMA"),
            tx(d(12), -(110 + m), 3, "ENEL ENERGIA"), tx(d(18), -60, 4, "Pagamento POS PIZZERIA LUIGI"), tx(d(20), -(40 + 5 * m), 1, "Pagamento POS CONAD"))
    } + listOf(tx("2026-09-05", -800, 6, "Prelievo ATM ROMA"))
    private val data0 = AppData(
        transactions = txs, categories = cats, accounts = listOf(Account(1, "Intesa")), loaded = true,
        splits = mapOf(txs.last().id to listOf(TransactionSplit(1, txs.last().id, 7, BigDecimal(-500), 0, true, 7), TransactionSplit(2, txs.last().id, 6, BigDecimal(-300), 1, true))),
        quotas = listOf(CashQuota(7, "Affitto", 7, BigDecimal(500))),
    )
    private val data = data0.copy(transactions = it.spese.domain.finance.SplitExpander.expand(txs, data0.splits, cats.associateBy { it.id }), ledger = txs)
    private val today = LocalDate.parse("2026-09-12")
    private fun build(spec: ReportSpec) = ReportBuilder.build(data, spec, today)
    private fun tables(d: ReportDocument) = d.blocks.filterIsInstance<Block.Table>()

    @Test fun `riepilogo con i numeri dell'app e confronto con il mese prima`() {
        val d = build(ReportSpec("Settembre", period = ReportPeriod(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.SUMMARY)))
        assertEquals("Settembre", d.title); assertTrue(d.subtitle.contains("01/09/2026")); assertTrue(d.subtitle.contains("tutti i conti"))
        val k = d.blocks.filterIsInstance<Block.Kpis>().single().items
        assertEquals("Entrate", k[0].label); assertEquals("2.000 €", k[0].value)
        val spese = 230 + 72 + 90 + 108 + 110 + 9 + 60 + 40 + 45 + 800    // somma delle spese di settembre
        assertEquals(java.text.NumberFormat.getIntegerInstance(java.util.Locale.ITALY).format(spese) + " €", k[1].value)
        assertNotNull(tables(d).firstOrNull { it.title?.startsWith("Confronto") == true })
    }

    @Test fun `categorie con sottocategorie, quote su spese ed entrate, e filtro per categorie scelte`() {
        val all = tables(build(ReportSpec(period = ReportPeriod(ReportPeriodKind.YEAR_TO_DATE), sections = listOf(ReportSection.CATEGORIES))))
        val spese = all.first { it.columns.first().header == "Categoria" && it.columns.size == 6 }
        assertTrue(spese.rows.any { it.cells[0] == "Casa" && it.bold }); assertTrue(spese.rows.any { it.cells[0] == "Bollette" && it.indent })
        assertEquals("Totale spese", spese.footer!!.cells[0])
        assertTrue(all.any { it.title == "Entrate per categoria" })
        // solo alcune categorie: il totale cambia e le altre spariscono
        val few = tables(build(ReportSpec(period = ReportPeriod(ReportPeriodKind.YEAR_TO_DATE), categoryIds = setOf(1), sections = listOf(ReportSection.CATEGORIES)))).first { it.columns.size == 6 }
        assertEquals(listOf("Supermercato"), few.rows.map { it.cells[0] }); assertEquals("Totale scelte", few.footer!!.cells[0])
        // senza sottocategorie
        val flat = tables(build(ReportSpec(period = ReportPeriod(ReportPeriodKind.YEAR_TO_DATE), subcategories = false, sections = listOf(ReportSection.CATEGORIES)))).first { it.columns.size == 6 }
        assertTrue(flat.rows.none { it.indent })
    }

    @Test fun `elenco movimenti con raggruppamenti e subtotali, e movimento diviso indicato`() {
        val spec = ReportSpec(period = ReportPeriod(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.TRANSACTIONS), grouping = TxGrouping.CATEGORY)
        val t = tables(build(spec)).single()
        assertTrue(t.rows.count { it.group } >= 4); assertTrue(t.rows.any { it.cells.getOrNull(1)?.startsWith("Subtotale") == true })
        assertTrue(t.rows.any { it.cells.contains("Diviso (2)") })
        val plain = tables(build(spec.copy(grouping = TxGrouping.NONE))).single()
        assertEquals(plain.rows.size, plain.rows.count { !it.group }); assertTrue(plain.footer!!.cells[1].startsWith("Entrate"))
    }

    @Test fun `prelievi divisi con ripartizione e quote, budget con stato, tendenze con proiezioni`() {
        val cash = tables(build(ReportSpec(period = ReportPeriod(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.CASH))))
        assertTrue(cash[0].rows.any { r -> r.cells.any { it.contains("Affitto 500 €") } }); assertTrue(cash.any { it.title == "Quote fisse in contanti" })
        val b = tables(build(ReportSpec(period = ReportPeriod(ReportPeriodKind.CURRENT_MONTH), sections = listOf(ReportSection.BUDGETS))))
        assertTrue(b.first { it.title?.startsWith("Budget di") == true }.rows.single().cells.last() in listOf("Superato", "Vicino", "Nei limiti"))
        val tr = tables(build(ReportSpec(period = ReportPeriod(ReportPeriodKind.LAST_3), sections = listOf(ReportSection.TRENDS))))
        assertTrue(tr.any { it.title == "Bilancio mensile atteso" }); assertTrue(tr.any { it.columns.size == 9 })
    }

    @Test fun `senza movimenti nel periodo il report lo dice, e ogni modello pronto si costruisce`() {
        val e = ReportBuilder.build(data, ReportSpec(period = ReportPeriod(ReportPeriodKind.CUSTOM, from = LocalDate.parse("2020-01-01"), to = LocalDate.parse("2020-01-31")), sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES)), today)
        assertTrue(e.empty)
        ReportPresets.BUILT_IN.forEach { p -> val d = ReportBuilder.build(data, p.spec, today, p.name); assertTrue("${p.id} vuoto", d.blocks.isNotEmpty()); assertTrue(d.title.isNotBlank()) }
        assertEquals(ReportPresets.BUILT_IN.map { it.id }.toSet().size, ReportPresets.BUILT_IN.size)
    }

    @Test fun `impaginazione con margini rispettati, intestazione di tabella ripetuta e pagine numerate`() {
        val many = (1..140).map { i -> tx("2026-0${1 + i % 9}-%02d".format(1 + i % 27), -(5 + i), 4, "Pagamento POS ESERCENTE NUMERO $i con una descrizione piuttosto lunga che deve andare a capo nella colonna") }
        val d2 = data.copy(ledger = data.ledger + many, transactions = data.transactions + many)
        val doc = ReportBuilder.build(d2, ReportSpec("Elenco lungo", note = "Nota di prova per il commercialista.", period = ReportPeriod(ReportPeriodKind.YEAR_TO_DATE), sections = listOf(ReportSection.SUMMARY, ReportSection.CATEGORIES, ReportSection.TRANSACTIONS)), today)
        val pages = ReportLayout.layout(doc, metrics)
        assertTrue("servono più pagine", pages.size >= 4)
        pages.forEachIndexed { i, p ->
            p.ops.forEach { op ->
                when (op) {
                    is TextOp -> { assertTrue("testo fuori pagina: ${op.text}", op.y in 0f..p.height); val w = metrics.width(op.text, op.size, op.bold)
                        val left = when (op.align) { Align.LEFT -> op.x; Align.RIGHT -> op.x - w; Align.CENTER -> op.x - w / 2 }
                        assertTrue("testo oltre il margine a sinistra: ${op.text}", left >= 30f); assertTrue("testo oltre il margine a destra: ${op.text} (${left + w} > ${p.width - 30f})", left + w <= p.width - 30f) }
                    is RectOp -> assertTrue("riquadro fuori pagina", op.x >= 0 && op.x + op.w <= p.width + 0.5f && op.y + op.h <= p.height + 0.5f)
                    is LineOp -> assertTrue(op.y1 <= p.height)
                }
            }
            assertTrue("manca la numerazione", p.ops.any { it is TextOp && it.text == "Pagina ${i + 1} di ${pages.size}" })
        }
        // l'elenco prosegue sulle pagine successive con l'intestazione ripetuta
        val withHeader = pages.count { p -> p.ops.any { it is TextOp && it.text == "Importo" && it.bold } }
        assertTrue("intestazione ripetuta ($withHeader)", withHeader >= 3)
        png(pages, "lungo")
    }

    @Test fun `orizzontale e modelli salvati`() {
        val doc = build(ReportPresets.byId("trends")!!.spec)
        val pages = ReportLayout.layout(doc, metrics)
        assertTrue(pages.all { it.width > it.height }); png(pages, "tendenze")
        png(ReportLayout.layout(build(ReportPresets.byId("monthly")!!.spec), metrics), "mensile"); png(ReportLayout.layout(build(ReportPresets.byId("yearly")!!.spec), metrics), "annuale")
        png(ReportLayout.layout(build(ReportPresets.byId("cash")!!.spec), metrics), "contanti")
        val saved = listOf(SavedReport("a1", "Il mio", ReportSpec("T", "Nota", ReportPeriod(ReportPeriodKind.MONTH, YearMonth.of(2026, 3)), 4, setOf(1, 3), listOf(ReportSection.TRENDS, ReportSection.SUMMARY), false, false, 20, true, TxGrouping.MONTH, true)),
            SavedReport("a2", "Intervallo", ReportSpec(period = ReportPeriod(ReportPeriodKind.CUSTOM, from = LocalDate.parse("2026-01-05"), to = LocalDate.parse("2026-02-10")))))
        assertEquals(saved, ReportSpecCodec.decodeList(ReportSpecCodec.encodeList(saved)))
        assertTrue(ReportSpecCodec.decodeList("non è json").isEmpty()); assertTrue(ReportSpecCodec.decodeList(null).isEmpty())
        assertEquals(Period.Custom(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")), ReportPeriod(ReportPeriodKind.PREVIOUS_YEAR).resolve(LocalDate.parse("2026-09-12")))
        assertEquals(Period.Month(YearMonth.of(2026, 8)), ReportPeriod(ReportPeriodKind.PREVIOUS_MONTH).resolve(LocalDate.parse("2026-09-12")))
    }
}
