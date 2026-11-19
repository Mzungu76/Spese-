package it.spese.data

import it.spese.core.model.*
import it.spese.domain.capture.CaptureSource
import it.spese.domain.capture.RawNotification
import it.spese.domain.repository.AppDataSource
import it.spese.domain.repository.NewTransaction
import it.spese.domain.usecase.CaptureResult
import it.spese.domain.usecase.SplitPartInput
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
        val acc = env.accounts.getOrCreate("Conto test"); val c = cat("Contanti")
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
        assertTrue(runCatching { env.setSplits(id, listOf(SplitPartInput(food, BigDecimal(-500)), SplitPartInput(cash, BigDecimal(-200)))) }.isFailure)
        env.setSplits(id, listOf(SplitPartInput(food, BigDecimal(-500)), SplitPartInput(cash, BigDecimal(-300))))
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
        val acc = env.accounts.getOrCreate("Revolut")        // con un conto assegnato alla fonte il movimento è nella contabilità (senza conto starebbe in Verifica come «senza conto»)
        val r = env.capture(CaptureSource.REVOLUT, acc.id, RawNotification("com.revolut.revolut", "Revolut", "Prelievo di 800,00 € da ATM ROMA", t), ZoneOffset.UTC) as CaptureResult.Added
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

    @Test fun `divisioni, quote e movimenti controllati sopravvivono al backup e al ripristino`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0xFF2358D8, null, true, 99))
        env.quotas.save(CashQuota(0, "Affitto", rent, BigDecimal(500)))
        val big = withdrawal("2026-10-05", -800, "b"); val other = withdrawal("2026-11-02", -50, "o")
        env.applyQuotas(); env.tx.setReviewed(listOf(other), true)
        val json = env.backup.buildBackupJson()
        assertTrue(json.contains("\"splits\"") && json.contains("\"quotas\""))
        env.restore(it.spese.domain.backup.LegacyBackupCodec.parse(json), it.spese.domain.repository.RestoreMode.Replace)
        val txs = env.tx.getAll(); val newBig = txs.first { it.externalId == "b" }.id
        assertEquals(2, env.tx.getSplits().count { it.txId == newBig })
        assertEquals("Affitto", env.quotas.getAll().single().name)
        assertTrue(txs.first { it.externalId == "o" }.reviewed); assertFalse(txs.first { it.externalId == "b" }.reviewed)
        assertEquals(0, env.applyQuotas())    // dopo il ripristino non divide una seconda volta
    }

    @Test fun `le opzioni della quota si salvano e sopravvivono al backup`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0, null, true, 99))
        env.quotas.save(CashQuota(0, "Affitto", rent, BigDecimal(500), true, QuotaMode.EXACT, BigDecimal(5), 3, 1, 10))
        val q = env.quotas.getAll().single()
        assertEquals(QuotaMode.EXACT, q.mode); assertEquals(0, BigDecimal(5).compareTo(q.tolerance)); assertEquals(3, q.everyMonths); assertEquals(1, q.dayFrom); assertEquals(10, q.dayTo)
        val json = env.backup.buildBackupJson()
        env.restore(it.spese.domain.backup.LegacyBackupCodec.parse(json), it.spese.domain.repository.RestoreMode.Replace)
        val back = env.quotas.getAll().single()
        assertEquals(q.copy(id = back.id, categoryId = back.categoryId), back)
    }

    @Test fun `quota applicata a mano da un prelievo e il motore non la ripete su un altro dello stesso mese`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto", TxKind.SPESA, false, 0, null, true, 99))
        val qid = env.quotas.save(CashQuota(0, "Affitto", rent, BigDecimal(500)))
        val first = withdrawal("2026-10-05", -800, "p1"); withdrawal("2026-10-20", -600, "p2")
        env.setSplits(first, listOf(SplitPartInput(rent, BigDecimal(-500), qid), SplitPartInput(cat("Contanti").id, BigDecimal(-300))))
        assertEquals(0, env.applyQuotas())
        // due quote sullo stesso prelievo, in automatico
        val cond = env.cats.insert(Category(0, "Condominio", TxKind.SPESA, false, 0, null, true, 98))
        env.quotas.save(CashQuota(0, "Condominio", cond, BigDecimal(100)))
        val n = env.applyQuotas()
        assertTrue(n >= 1)
        assertTrue(env.tx.getSplits().none { it.txId == first && it.auto })       // la divisione manuale non si tocca
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountingMonthDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    @Test fun `il giorno iniziale del mese contabile si salva e arriva ai dati`() = runBlocking {
        assertEquals(1, env.settings.get().monthStartDay)
        val src = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings)
        assertTrue(src.observe().first().calendar.isSolar)
        env.settings.update { it.copy(monthStartDay = 20) }
        assertEquals(20, env.settings.get().monthStartDay)
        assertEquals(20, src.observe().first().calendar.startDay)
        env.settings.update { it.copy(monthStartDay = 99) }                         // valori fuori misura vengono riportati a 28
        assertEquals(28, env.settings.get().monthStartDay)
        env.settings.update { it.copy(monthStartDay = 1) }
    }
}
