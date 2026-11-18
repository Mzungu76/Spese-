package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.finance.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BalanceReconcilerTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val acc = Account(1, "Carta di famiglia", AccountType.CARTA, BigDecimal(100))
    private fun tx(id: Long, date: String, amount: Int) = Transaction(id, "e$id", 1, d(date), d(date), "x", BigDecimal(amount), if (amount < 0) TxKind.SPESA else TxKind.ENTRATA, null)

    @Test fun `se in banca c'e meno, registra una spesa non tracciata che fa tornare il saldo`() {
        val ledger = listOf(tx(1, "2026-11-01", 500), tx(2, "2026-11-05", -50))          // l'app calcola 100 + 500 − 50 = 550
        val check = BalanceCheckEngine.check(acc, ledger, emptyList(), BigDecimal(420), d("2026-11-18"))
        val t = BalanceReconciler.adjustment(check, 1L)!!
        assertEquals(0, BigDecimal(-130).compareTo(t.amount)); assertEquals(TxKind.SPESA, t.kind); assertEquals(1L, t.accountId); assertEquals(d("2026-11-18"), t.bookingDate)
        val after = BalanceCheckEngine.check(acc, ledger + Transaction(3, t.externalId, 1, t.bookingDate, t.valueDate, t.description, t.amount, t.kind, null), emptyList(), BigDecimal(420), d("2026-11-18"))
        assertTrue(after.matches)
    }

    @Test fun `se in banca c'e di piu, il movimento e un'entrata, e se torna gia non si registra nulla`() {
        val ledger = listOf(tx(1, "2026-11-01", 500))
        val more = BalanceCheckEngine.check(acc, ledger, emptyList(), BigDecimal(700), d("2026-11-18"))
        assertEquals(TxKind.ENTRATA, BalanceReconciler.adjustment(more, 1L)!!.kind)
        assertNull(BalanceReconciler.adjustment(BalanceCheckEngine.check(acc, ledger, emptyList(), BigDecimal(600), d("2026-11-18")), 1L))
    }
}
