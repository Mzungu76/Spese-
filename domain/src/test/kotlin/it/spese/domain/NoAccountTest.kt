package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.backup.*
import it.spese.domain.capture.CaptureConfig
import it.spese.domain.capture.PendingAmount
import it.spese.domain.capture.SourceConfig
import it.spese.domain.finance.ReviewEngine
import it.spese.domain.finance.ReviewReason
import it.spese.domain.repository.AppData
import it.spese.domain.usecase.AccountImpactCalculator
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class NoAccountTest {
    private fun d(s: String) = LocalDate.parse(s)
    private var n = 0L
    private fun tx(acc: Long?, amount: String, date: String = "2026-03-02", origin: TxOrigin = TxOrigin.MANUAL, reviewed: Boolean = false) =
        Transaction(++n, "e$n", acc, d(date), d(date), "x$n", BigDecimal(amount), TxKind.SPESA, null, origin = origin, reviewed = reviewed)

    @Test fun `un movimento senza conto sta in Verifica anche se risulta controllato`() {
        val items = ReviewEngine.items(listOf(tx(1, "-10")), emptyMap(), emptyList(), emptyList(), unassigned = listOf(tx(null, "-20", reviewed = true)))
        val noAcc = items.single { it.tx.accountId == null }
        assertEquals(ReviewReason.NO_ACCOUNT, noAcc.notes.first().reason)
        assertEquals(1, items.size)          // quello con il conto, controllato o normale, non c'è
    }

    @Test fun `Verifica con soli movimenti senza conto e dai provvisori senza conto si capisce anche la loro origine`() {
        val items = ReviewEngine.items(emptyList(), emptyMap(), emptyList(), emptyList(), unassigned = listOf(tx(null, "-12.50", origin = TxOrigin.NOTIFICATION)))
        assertEquals(listOf(ReviewReason.NO_ACCOUNT, ReviewReason.PROVISIONAL), items.single().notes.map { it.reason })
        assertTrue(ReviewEngine.items(emptyList(), emptyMap(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun `i movimenti senza conto non entrano nella contabilita`() {
        // AppData.transactions e ledger contengono solo i movimenti con conto: è il punto unico da cui partono tutti i totali
        val assigned = tx(1, "-10"); val loose = tx(null, "-99")
        val data = AppData(transactions = listOf(assigned), ledger = listOf(assigned), unassigned = listOf(loose), loaded = true)
        assertEquals(listOf(assigned), data.ledger); assertEquals(listOf(loose), data.unassigned)
        assertEquals("Senza conto", data.accountName(null))
    }

    @Test fun `il backup conserva i movimenti senza conto e non inventa un conto`() {
        val state = LegacyState(
            listOf(LegacyTx("a|1", d("2026-03-02"), d("2026-03-02"), BigDecimal("-5.00"), "Con conto", "Intesa", "", null, null, null),
                LegacyTx("|2", d("2026-03-03"), d("2026-03-03"), BigDecimal("-7.00"), "Senza conto", "", "", null, null, null)),
            emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), LegacyGoal(GoalMode.MONTHLY, BigDecimal(300), 12, null), emptyMap(), emptyMap())
        val snap = LegacyMapper.toDomain(state)
        assertEquals(listOf("Intesa"), snap.accounts.map { it.name })               // niente conto «vuoto» né «di ripiego»
        assertNotNull(snap.transactions.first { it.description == "Con conto" }.accountId)
        assertNull(snap.transactions.first { it.description == "Senza conto" }.accountId)
        val back = LegacyMapper.fromDomain(snap)
        assertEquals(setOf("Intesa", ""), back.tx.map { it.acc }.toSet())
        val json = LegacyBackupCodec.encode(BackupEnvelope("2026-03-15", "t", back))
        assertEquals(setOf("Intesa", ""), LegacyBackupCodec.parse(json).state.tx.map { it.acc }.toSet())
    }

    @Test fun `impatto dell'eliminazione di un conto - movimenti, ricorrenze, fonti e valori`() {
        val txs = listOf(tx(1, "-10"), tx(1, "-30"), tx(1, "100"), tx(2, "-5"), tx(null, "-1"))
        val rules = listOf(RecurringRule(1, "Affitto", TxKind.SPESA, BigDecimal(500), accountId = 1), RecurringRule(2, "Stipendio", TxKind.ENTRATA, BigDecimal(2000), accountId = 2))
        val capture = CaptureConfig(sources = mapOf("revolut" to SourceConfig(true, accountId = 1), "hype" to SourceConfig(true, accountId = 2)),
            pending = listOf(PendingAmount(1, "revolut", 1, -1, "x", "x")))
        val vals = listOf(Valuation(1, d("2026-01-31"), BigDecimal(500)), Valuation(2, d("2026-01-31"), BigDecimal(900)))
        val i = AccountImpactCalculator.of(1, txs, rules, capture, vals)
        assertEquals(3, i.movements); assertEquals(0, BigDecimal(100).compareTo(i.income)); assertEquals(0, BigDecimal(40).compareTo(i.expense))
        assertEquals(1, i.recurringRules); assertEquals(2, i.captureSources); assertEquals(1, i.valuations)
        assertEquals(0, AccountImpactCalculator.of(3, txs, rules, capture, vals).movements)
    }
}
