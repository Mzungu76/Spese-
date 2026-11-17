package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.classification.TransferDetector
import it.spese.domain.classification.TransferSuggestions
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TransferSuggestionsTest {
    private fun d(s: String) = LocalDate.parse(s)
    private var n = 0L
    private fun tx(acc: Long, date: String, amount: String, kind: TxKind? = null, manual: Boolean = false, id: Long = ++n): Transaction {
        val a = BigDecimal(amount)
        return Transaction(id, "e$id", acc, d(date), d(date), "x$id", a, kind ?: if (a.signum() > 0) TxKind.ENTRATA else TxKind.SPESA, null, manualCategory = manual)
    }
    private fun data(vararg t: Transaction, splits: Map<Long, List<TransactionSplit>> = emptyMap()) = AppData(transactions = t.toList(), ledger = t.toList(), splits = splits, loaded = true)

    @Test fun `importi identici su conti diversi entro 3 giorni - coppia senza commissione`() {
        val p = TransferSuggestions.compute(data(tx(1, "2026-03-01", "-200.00"), tx(2, "2026-03-02", "200.00")), emptySet()).single()
        assertEquals(1, p.daysApart); assertEquals(0, p.fee.signum())
    }

    @Test fun `piccola commissione - esce 10,00 ed entra 9,90`() {
        val p = TransferSuggestions.compute(data(tx(1, "2026-03-01", "-10.00"), tx(2, "2026-03-01", "9.90")), emptySet()).single()
        assertEquals(0, BigDecimal("0.10").compareTo(p.fee))
        // oltre la commissione ammessa (0,50 € su 10 €) non è un giroconto
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-10.00"), tx(2, "2026-03-01", "9.00")), emptySet()).isEmpty())
        // su importi grandi la commissione ammessa cresce ma si ferma a 2 €
        assertEquals(1, TransferSuggestions.compute(data(tx(1, "2026-03-01", "-500.00"), tx(2, "2026-03-01", "498.50")), emptySet()).size)
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-500.00"), tx(2, "2026-03-01", "497.00")), emptySet()).isEmpty())
        // l'entrata non può superare l'uscita
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-10.00"), tx(2, "2026-03-01", "10.10")), emptySet()).isEmpty())
    }

    @Test fun `stesso conto, troppo lontani o già trasferimenti non sono giroconti`() {
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-50.00"), tx(1, "2026-03-01", "50.00")), emptySet()).isEmpty())
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-50.00"), tx(2, "2026-03-05", "50.00")), emptySet()).isEmpty())
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-50.00", TxKind.TRASFERIMENTO), tx(2, "2026-03-01", "50.00", TxKind.TRASFERIMENTO)), emptySet()).isEmpty())
    }

    @Test fun `si preferisce l'importo identico e poi la data più vicina, e ogni movimento si abbina una volta sola`() {
        val out = tx(1, "2026-03-10", "-100.00"); val near = tx(2, "2026-03-11", "99.50"); val exact = tx(3, "2026-03-13", "100.00")
        val p = TransferSuggestions.compute(data(out, near, exact), emptySet()).single()
        assertEquals(exact.id, p.incoming.id)
        // due uscite uguali e una sola entrata: una coppia
        assertEquals(1, TransferSuggestions.compute(data(tx(1, "2026-03-10", "-30.00"), tx(1, "2026-03-10", "-30.00"), tx(2, "2026-03-10", "30.00")), emptySet()).size)
    }

    @Test fun `movimenti divisi o classificati a mano restano fuori, e le coppie scartate non tornano`() {
        val a = tx(1, "2026-03-01", "-80.00"); val b = tx(2, "2026-03-01", "80.00")
        val split = mapOf(a.id to listOf(TransactionSplit(1, a.id, null, BigDecimal(-80))))
        assertTrue(TransferSuggestions.compute(data(a, b, splits = split), emptySet()).isEmpty())
        assertTrue(TransferSuggestions.compute(data(tx(1, "2026-03-01", "-80.00", manual = true), tx(2, "2026-03-01", "80.00")), emptySet()).isEmpty())
        val pair = TransferSuggestions.compute(data(a, b), emptySet()).single()
        assertTrue(TransferSuggestions.compute(data(a, b), setOf(pair.key)).isEmpty())
        assertEquals("e${a.id}>e${b.id}", pair.key)
    }

    @Test fun `la categoria dei trasferimenti`() {
        val cats = listOf(Category(1, "Altro", TxKind.SPESA, false, 0), Category(2, "Giri", TxKind.TRASFERIMENTO, false, 0), Category(3, "Trasferimenti", TxKind.TRASFERIMENTO, false, 0), Category(4, "Vecchia", TxKind.TRASFERIMENTO, false, 0, active = false))
        assertEquals(3L, TransferSuggestions.transferCategory(cats)!!.id)
        assertEquals(2L, TransferSuggestions.transferCategory(cats.filter { it.id != 3L })!!.id)
        assertNull(TransferSuggestions.transferCategory(cats.filter { it.kind != TxKind.TRASFERIMENTO }))
    }

    @Test fun `i giroconti scartati viaggiano nel backup`() {
        val state = it.spese.domain.backup.LegacyState(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(),
            it.spese.domain.backup.LegacyGoal(GoalMode.MONTHLY, BigDecimal(300), 12, null), emptyMap(), emptyMap())
        val json = it.spese.domain.backup.LegacyBackupCodec.encode(it.spese.domain.backup.BackupEnvelope("2026-03-15", "t", state, it.spese.domain.backup.NativeExtras(transferIgnored = listOf("e1>e2", "e3>e4"))))
        assertEquals(listOf("e1>e2", "e3>e4"), it.spese.domain.backup.LegacyBackupCodec.parse(json).extras!!.transferIgnored)
    }
}
