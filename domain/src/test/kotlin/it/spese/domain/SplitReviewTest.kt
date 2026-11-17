package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SplitReviewTest {
    private val cash = Category(1, "Contanti", TxKind.SPESA, true, 0)
    private val rent = Category(2, "Affitto", TxKind.SPESA, false, 0)
    private val food = Category(3, "Supermercato", TxKind.SPESA, false, 0)
    private val cats = listOf(cash, rent, food).associateBy { it.id }
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long = 1, desc: String = "Prelievo ATM", reviewed: Boolean = false, origin: TxOrigin = TxOrigin.IMPORT_FILE, manual: Boolean = false, rule: Long? = null) =
        Transaction(++n, "e$n", 1, LocalDate.parse(date), LocalDate.parse(date), desc, BigDecimal(amount), TxKind.SPESA, cat, manual, rule, origin = origin, reviewed = reviewed)
    private val quota = CashQuota(7, "Affitto", 2, BigDecimal(500))
    private fun ledgerOf(vararg t: Transaction) = t.toList()

    @Test fun `un prelievo diviso diventa parti con la stessa somma e le categorie giuste`() {
        val t = tx("2026-10-03", -800)
        val parts = mapOf(t.id to listOf(TransactionSplit(1, t.id, 2, BigDecimal(-500), 0, true, 7), TransactionSplit(2, t.id, 1, BigDecimal(-300), 1, true, 7)))
        val ex = SplitExpander.expand(listOf(t), parts, cats)
        assertEquals(listOf(2L, 1L), ex.map { it.categoryId }); assertEquals(0, BigDecimal(-800).compareTo(ex.fold(BigDecimal.ZERO) { a, x -> a + x.amount }))
        assertTrue(ex.all { it.id == t.id && it.manualCategory })
        // statistiche: 500 in Affitto, 300 in Contanti, totale spesa invariato
        val s = StatsEngine.compute(ex)
        assertEquals(0, BigDecimal(500).compareTo(s.byCategory[2L])); assertEquals(0, BigDecimal(300).compareTo(s.byCategory[1L])); assertEquals(0, BigDecimal(800).compareTo(s.expense))
        // validazione
        assertNull(SplitExpander.validate(BigDecimal(-800), listOf(BigDecimal(-500), BigDecimal(-300))))
        assertNotNull(SplitExpander.validate(BigDecimal(-800), listOf(BigDecimal(-500), BigDecimal(-200))))
        assertNotNull(SplitExpander.validate(BigDecimal(-800), listOf(BigDecimal(-800))))
        assertNotNull(SplitExpander.validate(BigDecimal(-800), listOf(BigDecimal(1300), BigDecimal(-500))))
    }

    @Test fun `la quota si stacca una volta al mese dal primo prelievo che la copre`() {
        val l = ledgerOf(tx("2026-10-02", -200), tx("2026-10-05", -800), tx("2026-10-20", -600), tx("2026-11-04", -500), tx("2026-11-09", -100))
        val plan = CashQuotaEngine.plan(l, emptyMap(), cats, listOf(quota))
        assertEquals(listOf(l[1].id, l[3].id), plan.map { it.txId })               // ottobre: 800 (non 200); novembre: 500
        assertEquals(listOf(2L, 1L), plan[0].parts.map { it.categoryId })           // quota Affitto + resto Contanti
        assertEquals(0, BigDecimal(-300).compareTo(plan[0].parts[1].amount))
        assertEquals(1, plan[1].parts.size)                                          // 500 esatti: nessun resto
        assertTrue(plan.all { p -> p.parts.all { it.auto } && p.parts[0].quotaId == 7L })     // la quota porta il suo id, il resto no
        assertNull(plan[0].parts[1].quotaId)
    }

    @Test fun `non tocca movimenti gia divisi, controllati, non prelievi o mesi gia coperti`() {
        val done = tx("2026-10-05", -800); val reviewed = tx("2026-10-06", -800, reviewed = true); val grocery = tx("2026-10-07", -900, cat = 3, desc = "Coop")
        val ledger = ledgerOf(done, reviewed, grocery)
        val already = mapOf(done.id to listOf(TransactionSplit(1, done.id, 2, BigDecimal(-500), 0, true, 7)))
        assertTrue(CashQuotaEngine.plan(ledger, already, cats, listOf(quota)).isEmpty())          // ottobre già coperto, gli altri esclusi
        assertTrue(CashQuotaEngine.plan(ledgerOf(tx("2026-10-05", -800)), emptyMap(), cats, listOf(quota.copy(enabled = false))).isEmpty())
        // un prelievo riconosciuto dalla descrizione (anche se la regola l'ha messo altrove)
        val viaRule = CashQuotaEngine.plan(ledgerOf(tx("2026-10-05", -800, cat = 3, desc = "Prelievo Bancomat")), emptyMap(), cats, listOf(quota))
        assertEquals(1, viaRule.size); assertEquals(listOf(2L, 1L), viaRule[0].parts.map { it.categoryId })   // il resto va in Contanti, non in Supermercato
    }

    @Test fun `verifica - divisi in automatico, provvisori, regole con soglia e importi insoliti`() {
        val split = tx("2026-10-05", -800)
        val prov = tx("2026-10-08", -12, cat = 3, desc = "COOP", origin = TxOrigin.NOTIFICATION)
        val ruleTx = tx("2026-10-09", -150, cat = 3, desc = "Prelievo grande", rule = 5)
        val ok = tx("2026-10-10", -20, cat = 3, desc = "Coop", rule = 6)
        val manual = tx("2026-10-11", -150, cat = 3, desc = "Prelievo", manual = true, rule = 5)
        val confirmed = tx("2026-10-12", -12, cat = 3, desc = "COOP", origin = TxOrigin.NOTIFICATION, reviewed = true)
        val hist = (1..6).map { tx("2026-09-0$it", -30, cat = 3, desc = "Coop", rule = 6) }
        val spike = tx("2026-10-13", -400, cat = 3, desc = "Elettronica", rule = 6)
        val rules = listOf(Rule(5, "prelievo", 3, min = BigDecimal(100)), Rule(6, "coop", 3))
        val splits = mapOf(split.id to listOf(TransactionSplit(1, split.id, 2, BigDecimal(-500), 0, true, 7), TransactionSplit(2, split.id, 1, BigDecimal(-300), 1, true, 7)))
        val items = ReviewEngine.items(listOf(split, prov, ruleTx, ok, manual, confirmed, spike) + hist, splits, cats.values.toList(), rules)
        val byTx = items.associate { it.tx.id to it.notes.map { n -> n.reason } }
        assertEquals(listOf(ReviewReason.AUTO_SPLIT), byTx[split.id])
        assertEquals(listOf(ReviewReason.PROVISIONAL), byTx[prov.id])
        assertTrue(ReviewReason.THRESHOLD_RULE in byTx.getValue(ruleTx.id))   // e, rispetto ai 30 € di storico, anche insolito
        assertEquals(listOf(ReviewReason.UNUSUAL), byTx[spike.id])
        listOf(ok, manual, confirmed).forEach { assertFalse("${it.description} non dovrebbe comparire", it.id in byTx) }
        assertTrue(items.first().tx.bookingDate >= items.last().tx.bookingDate)           // dal più recente
        assertTrue(items.first { it.tx.id == split.id }.notes[0].text.contains("Affitto 500 €") )
    }

    @Test fun `lo storico lontano non riempie la verifica ma le divisioni automatiche si`() {
        val old = tx("2026-01-05", -150, cat = 3, desc = "Prelievo", rule = 5); val recent = tx("2026-10-13", -10, cat = 3, desc = "Pane", rule = 6)
        val oldSplit = tx("2026-01-06", -800)
        val splits = mapOf(oldSplit.id to listOf(TransactionSplit(1, oldSplit.id, 2, BigDecimal(-500), 0, true, 7)))
        val items = ReviewEngine.items(listOf(old, recent, oldSplit), splits, cats.values.toList(), listOf(Rule(5, "prelievo", 3, min = BigDecimal(100)), Rule(6, "pane", 3)))
        assertEquals(setOf(oldSplit.id), items.map { it.tx.id }.toSet())
        assertTrue(ReviewEngine.items(emptyList(), emptyMap(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun `le analisi vedono le parti ma il libro mastro resta intero`() {
        val t = tx("2026-10-05", -800)
        val src = AppData(transactions = SplitExpander.expand(listOf(t), mapOf(t.id to listOf(TransactionSplit(1, t.id, 2, BigDecimal(-500), 0), TransactionSplit(2, t.id, 1, BigDecimal(-300), 1))), cats),
            categories = cats.values.toList(), accounts = listOf(Account(1, "C")), loaded = true, ledger = listOf(t))
        assertEquals(2, src.transactions.size); assertEquals(1, src.ledger.size)
        val m = DashboardCalculator.compute(src, TxFilter(period = Period.All), LocalDate.parse("2026-10-14"))
        assertEquals(listOf(2L, 1L), m.categories.map { it.category?.id })
    }

    // ---- quote flessibili ----
    private fun plan(l: List<Transaction>, vararg q: CashQuota, splits: Map<Long, List<TransactionSplit>> = emptyMap()) = CashQuotaEngine.plan(l, splits, cats, q.toList())

    @Test fun `ogni prelievo che la copre, e piu quote nello stesso prelievo`() {
        val a = tx("2026-10-03", -800); val b = tx("2026-10-20", -600); val small = tx("2026-10-25", -100)
        val every = plan(listOf(a, b, small), quota.copy(mode = QuotaMode.EVERY))
        assertEquals(listOf(a.id, b.id), every.map { it.txId })                       // entrambi, non il piccolo
        // due quote sullo stesso prelievo da 800: 500 affitto + 200 spese condominiali, resto 100 in Contanti
        val cond = CashQuota(8, "Condominio", 3, BigDecimal(200))
        val two = plan(listOf(a), quota, cond).single()
        assertEquals(listOf(2L, 3L, 1L), two.parts.map { it.categoryId })
        assertEquals(listOf(-500, -200, -100), two.parts.map { it.amount.toInt() }); assertEquals(0, BigDecimal(-800).compareTo(two.parts.fold(BigDecimal.ZERO) { s, p -> s + p.amount }))
        // la seconda quota non entra se il resto non basta
        assertEquals(listOf(2L, 1L), plan(listOf(tx("2026-10-03", -600)), quota, cond).single().parts.map { it.categoryId })
        // aggiungere una quota a un prelievo già diviso in automatico riduce il resto
        val first = plan(listOf(a), quota).single()
        val again = plan(listOf(a), quota, cond, splits = mapOf(a.id to first.parts.mapIndexed { i, p -> p.copy(id = i + 1L) })).single()
        assertEquals(listOf(2L, 3L, 1L), again.parts.map { it.categoryId })
    }

    @Test fun `importo esatto con tolleranza, finestra di giorni e ripetizione trimestrale`() {
        val exact = quota.copy(mode = QuotaMode.EXACT, tolerance = BigDecimal(5))
        val near = tx("2026-10-04", -497); val big = tx("2026-10-05", -800)
        val p = plan(listOf(big, near), exact).single()
        assertEquals(near.id, p.txId); assertEquals(1, p.parts.size); assertEquals(0, BigDecimal(-497).compareTo(p.parts[0].amount))   // tutto il prelievo alla quota, nessun resto
        assertTrue(plan(listOf(tx("2026-10-04", -480)), exact).isEmpty())                  // fuori tolleranza
        // finestra: l'affitto si paga entro il 10
        val early = quota.copy(dayFrom = 1, dayTo = 10)
        val l = listOf(tx("2026-10-15", -800), tx("2026-10-08", -700))
        assertEquals(l[1].id, plan(l, early).single().txId)
        // ogni 3 mesi: una sola applicazione nel trimestre gen-mar, poi di nuovo ad aprile
        val q3 = quota.copy(everyMonths = 3)
        val tri = listOf(tx("2026-01-05", -800), tx("2026-02-05", -800), tx("2026-03-05", -800), tx("2026-04-05", -800))
        assertEquals(listOf(tri[0].id, tri[3].id), plan(tri, q3).map { it.txId })
        // un'applicazione manuale con l'id della quota conta come fatta nel periodo
        val manual = tx("2026-10-02", -900, reviewed = true)
        val done = mapOf(manual.id to listOf(TransactionSplit(1, manual.id, 2, BigDecimal(-500), 0, false, 7), TransactionSplit(2, manual.id, 1, BigDecimal(-400), 1)))
        assertTrue(plan(listOf(manual, tx("2026-10-20", -800)), quota, splits = done).isEmpty())
        // le divisioni manuali non si toccano
        val manualSplit = tx("2026-11-02", -800)
        val ms = mapOf(manualSplit.id to listOf(TransactionSplit(1, manualSplit.id, 2, BigDecimal(-300), 0), TransactionSplit(2, manualSplit.id, 1, BigDecimal(-500), 1)))
        assertTrue(plan(listOf(manualSplit), quota, splits = ms).isEmpty())
    }
}
