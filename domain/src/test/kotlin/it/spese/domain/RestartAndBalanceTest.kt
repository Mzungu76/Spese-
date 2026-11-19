package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import it.spese.domain.usecase.RestartPlanner
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RestartAndBalanceTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    private var n = 0L
    private fun tx(amount: String, date: String = "2026-03-02", origin: TxOrigin = TxOrigin.IMPORT_FILE, doc: Long? = null, desc: String? = null, acc: Long? = 1) =
        Transaction(++n, "e$n", acc, d(date), d(date), desc ?: "x$n", BigDecimal(amount), TxKind.SPESA, null, origin = origin, documentId = doc)

    // --- ricominciare dagli estratti
    @Test fun `si tengono scontrini, divisioni scelte, provvisori e (se richiesto) i manuali, il resto si cancella`() {
        val receipt = tx("-23.40", origin = TxOrigin.OCR, doc = 5)
        val statementWithReceipt = tx("-12.00", doc = 6)                             // riga d'estratto a cui è stato allegato uno scontrino
        val split = tx("-800.00")
        val autoSplit = tx("-500.00")
        val notif = tx("-4.00", origin = TxOrigin.NOTIFICATION); val rec = tx("-500", origin = TxOrigin.RECURRING)
        val manual = tx("-10.00", origin = TxOrigin.MANUAL)
        val imported = tx("-3.00"); val fromBackup = tx("-7.00", origin = TxOrigin.BACKUP)
        val all = listOf(receipt, statementWithReceipt, split, autoSplit, notif, rec, manual, imported, fromBackup)
        val splits = mapOf(split.id to listOf(TransactionSplit(1, split.id, null, BigDecimal(-500), 0, auto = false)), autoSplit.id to listOf(TransactionSplit(2, autoSplit.id, null, BigDecimal(-500), 0, auto = true)))
        val p = RestartPlanner.plan(all, splits, keepManual = true)
        assertEquals(setOf(receipt.id, statementWithReceipt.id, split.id), p.withDetails.map { it.id }.toSet())
        assertEquals(setOf(notif.id, rec.id), p.provisional.map { it.id }.toSet())
        assertEquals(listOf(manual.id), p.manual.map { it.id })
        assertEquals(setOf(autoSplit.id, imported.id, fromBackup.id), p.toDelete.map { it.id }.toSet())      // la divisione automatica di una quota non è un dettaglio da tenere
        val noManual = RestartPlanner.plan(all, splits, keepManual = false)
        assertTrue(manual.id in noManual.toDelete.map { it.id }); assertTrue(noManual.manual.isEmpty())
    }

    // --- controllo del saldo
    private val acc = Account(1, "Intesa", AccountType.CONTO, BigDecimal("1000"))
    private val asOf = d("2026-03-31")

    @Test fun `saldo uguale alla banca`() {
        val r = BalanceCheckEngine.check(acc, listOf(tx("-100"), tx("500", desc = "Stipendio")), emptyList(), BigDecimal("1400"), asOf)
        assertTrue(r.matches); eq("1400", r.computed); assertTrue(r.causes.isEmpty())
    }

    @Test fun `un provvisorio non sostituito spiega tutta la differenza`() {
        val prov = tx("-30", origin = TxOrigin.NOTIFICATION)
        val r = BalanceCheckEngine.check(acc, listOf(tx("-100", desc = "Affitto"), prov), emptyList(), BigDecimal("900"), asOf)      // app 870, banca 900
        eq("870", r.computed); eq("30", r.difference)
        val top = r.causes.first(); assertEquals(CauseKind.PROVISIONAL, top.kind); assertTrue(top.explainsAll); assertEquals(1, top.count)
    }

    @Test fun `un doppione spiega la differenza, e il saldo iniziale e sempre una via d'uscita`() {
        val a = tx("-20", desc = "PAGAMENTO POS BAR ROMA"); val b = tx("-20", desc = "PAGAMENTO POS BAR ROMA")
        val r = BalanceCheckEngine.check(acc, listOf(a, b), emptyList(), BigDecimal("980"), asOf)      // app 960, banca 980: il secondo caffè non c'è in banca
        val dup = r.causes.first { it.kind == CauseKind.DUPLICATES }; assertTrue(dup.explainsAll); eq("-20", dup.amount)
        val opening = r.causes.first { it.kind == CauseKind.OPENING }; eq("1020", r.suggestedOpening)
    }

    @Test fun `movimenti senza conto e movimenti dopo la data`() {
        val loose = tx("-50", acc = null)
        val r = BalanceCheckEngine.check(acc, listOf(tx("-10", date = "2026-03-10"), tx("-999", date = "2026-04-05")), listOf(loose), BigDecimal("940"), asOf)    // app 990 (il -999 è dopo la data), banca 940
        eq("990", r.computed)
        val c = r.causes.first { it.kind == CauseKind.UNASSIGNED }; assertTrue(c.explainsAll); assertEquals(1, c.count)
    }
}
