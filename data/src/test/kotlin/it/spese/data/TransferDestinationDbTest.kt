package it.spese.data

import it.spese.core.model.TxKind
import it.spese.domain.repository.AppDataSource
import it.spese.domain.repository.NewTransaction
import it.spese.domain.usecase.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un giroconto può dire dove vanno i soldi: su un altro conto dell'app (metà speculare, senza doppioni) o fuori dall'app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferDestinationDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private fun uc() = SetTransferDestinationUseCase(env.tx, env.accounts, env.cats, AssignCategoryUseCase(env.tx, env.cats), ConfirmReviewUseCase(env.tx))
    private fun nt(key: String, acc: Long, amount: String) = NewTransaction(key, key, acc, LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), "Giro $key", BigDecimal(amount),
        if (amount.startsWith("-")) TxKind.SPESA else TxKind.ENTRATA, null, false, null)
    private fun data() = runBlocking { AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings).observe().first() }

    @Test fun `verso un conto dell'app crea la meta speculare una sola volta e tutto esce da entrate e spese`() = runBlocking {
        val a = env.accounts.getOrCreate("Revolut"); val b = env.accounts.getOrCreate("Tasca")
        env.tx.insertAll(listOf(nt("x1", a.id, "-300")))
        val id = env.tx.getAll().single().id
        assertEquals(1, uc()(listOf(id), TransferDestination.ToAccount(b.id)))
        uc()(listOf(id), TransferDestination.ToAccount(b.id))                       // ripetuto: nessun doppione
        val d = data()
        assertEquals(2, d.ledger.size)
        val mirror = d.ledger.single { it.accountId == b.id }
        assertEquals(0, BigDecimal(300).compareTo(mirror.amount)); assertEquals(TxKind.TRASFERIMENTO, mirror.kind)
        assertTrue(d.ledger.all { it.kind == TxKind.TRASFERIMENTO })
        assertTrue(d.review().none { it.second == b.id })                               // la metà speculare nasce già controllata
    }

    @Test fun `fuori dall'app resta un giroconto con il nome della destinazione`() = runBlocking {
        val a = env.accounts.getOrCreate("Revolut")
        env.tx.insertAll(listOf(nt("y1", a.id, "-50")))
        val id = env.tx.getAll().single().id
        uc()(listOf(id), TransferDestination.Outside("Tasca Vacanze"))
        val t = env.tx.get(id)!!
        assertEquals(TxKind.TRASFERIMENTO, t.kind); assertTrue(t.notes.contains("Destinazione: Tasca Vacanze"))
        assertEquals(1, data().ledger.size)
    }

    @Test fun `tasca dello stesso conto - il saldo del conto non cambia e l'importo esce da entrate e spese`() = runBlocking {
        val a = env.accounts.getOrCreate("Revolut")
        env.tx.insertAll(listOf(nt("p0", a.id, "1000"), nt("p1", a.id, "-300")))
        val out = env.tx.getAll().single { it.amount.signum() < 0 }.id
        uc()(listOf(out), TransferDestination.SamePocket("Vacanze"))
        uc()(listOf(out), TransferDestination.SamePocket("Vacanze"))                // ripetuto: nessun doppione
        val d = data()
        assertEquals(3, d.ledger.size)
        assertEquals(0, BigDecimal(1000).compareTo(d.ledger.fold(BigDecimal.ZERO) { s, t -> s + t.amount }))      // il saldo resta 1.000
        assertEquals(2, d.ledger.count { it.kind == TxKind.TRASFERIMENTO })
    }

    private fun it.spese.domain.repository.AppData.review() = it.spese.domain.finance.ReviewEngine.items(ledger, splits, categories, rules, unassigned).map { it.tx.id to it.tx.accountId }
}
