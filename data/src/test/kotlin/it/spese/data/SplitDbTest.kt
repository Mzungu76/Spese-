package it.spese.data

import it.spese.core.model.*
import it.spese.domain.capture.CaptureSource
import it.spese.domain.capture.RawNotification
import it.spese.domain.repository.AppDataSource
import it.spese.domain.repository.NewTransaction
import it.spese.domain.usecase.CaptureResult
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SplitDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private suspend fun cat(name: String) = env.cats.getAll().first { it.name == name }
    private suspend fun withdrawal(date: String, amount: Int, id: String): Long {
        val acc = env.accounts.getAll().first(); val c = cat("Contanti")
        env.tx.insertAll(listOf(NewTransaction(id, id, acc.id, LocalDate.parse(date), LocalDate.parse(date), "Prelievo ATM", BigDecimal(amount), TxKind.SPESA, c.id, false, null)))
        return env.tx.getAll().first { it.externalId == id }.id
    }

    @Test fun `la quota fissa divide il primo prelievo del mese, una volta sola, e le analisi la vedono`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0xFF2358D8, null, true, 99))
        env.quotas.save(CashQuota(0, "Affitto", rent, BigDecimal(500)))
        val small = withdrawal("2026-10-02", -200, "a"); val big = withdrawal("2026-10-05", -800, "b"); val later = withdrawal("2026-10-20", -600, "c")
        assertEquals(1, env.applyQuotas())
        assertEquals(0, env.applyQuotas())                                    // idempotente
        val parts = env.tx.getSplits().filter { it.txId == big }
        assertEquals(listOf(rent, cat("Contanti").id), parts.map { it.categoryId }); assertTrue(parts.all { it.auto })
        assertTrue(env.tx.getSplits().none { it.txId == small || it.txId == later })
        // AppData: analisi sulle parti, elenco intero
        val d = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas).observe().first()
        assertEquals(1, d.ledger.count { it.id == big }); assertEquals(2, d.transactions.count { it.id == big })
        assertEquals(0, BigDecimal(-1600).compareTo(d.transactions.fold(BigDecimal.ZERO) { a, t -> a + t.amount }))   // il totale non cambia
        // eliminando il movimento le parti spariscono
        env.tx.delete(listOf(big)); assertTrue(env.tx.getSplits().isEmpty())
    }

    @Test fun `divisione manuale con controlli, controllato e ricomposizione`() = runBlocking {
        val id = withdrawal("2026-10-05", -800, "m")
        val food = cat("Supermercato e alimentari").id; val cash = cat("Contanti").id
        assertTrue(runCatching { env.setSplits(id, listOf(food to BigDecimal(-500), cash to BigDecimal(-200))) }.isFailure)
        env.setSplits(id, listOf(food to BigDecimal(-500), cash to BigDecimal(-300)))
        assertEquals(2, env.tx.getSplits().size); assertTrue(env.tx.get(id)!!.reviewed)
        env.setSplits(id, emptyList())
        assertTrue(env.tx.getSplits().isEmpty()); assertTrue(env.tx.get(id)!!.reviewed)
        // controllato: la quota non lo divide più
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0, null, true, 99))
        env.quotas.save(CashQuota(0, "Affitto", rent, BigDecimal(500)))
        assertEquals(0, env.applyQuotas())
    }

    @Test fun `un prelievo notificato viene diviso subito dalla quota e finisce in verifica`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0, null, true, 99))
        env.quotas.save(CashQuota(0, "Affitto", rent, BigDecimal(500)))
        val t = LocalDate.parse("2026-10-05").atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val r = env.capture(CaptureSource.REVOLUT, null, RawNotification("com.revolut.revolut", "Revolut", "Prelievo di 800,00 € da ATM ROMA", t), ZoneOffset.UTC) as CaptureResult.Added
        assertEquals(2, env.tx.getSplits().count { it.txId == r.txId })
        val d = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas).observe().first()
        val review = it.spese.domain.finance.ReviewEngine.items(d.ledger, d.splits, d.categories, d.rules).first { it.tx.id == r.txId }
        assertEquals(setOf(it.spese.domain.finance.ReviewReason.AUTO_SPLIT, it.spese.domain.finance.ReviewReason.PROVISIONAL), review.notes.map { it.reason }.toSet())
    }

    @Test fun `le quote si salvano, si modificano e spariscono con la categoria`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0, null, true, 99))
        val id = env.quotas.save(CashQuota(0, " Affitto ", rent, BigDecimal(500)))
        env.quotas.save(CashQuota(id, "Affitto casa", rent, BigDecimal("550.50"), false))
        val q = env.quotas.getAll().single(); assertEquals("Affitto casa", q.name); assertEquals(0, BigDecimal("550.5").compareTo(q.amount)); assertFalse(q.enabled)
        env.cats.delete(rent); assertTrue(env.quotas.getAll().isEmpty())
    }
}
