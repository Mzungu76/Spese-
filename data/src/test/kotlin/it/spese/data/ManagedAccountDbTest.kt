package it.spese.data

import it.spese.domain.repository.AppDataSource
import it.spese.domain.repository.NewTransaction
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un conto «gestito da altri» resta nel libro mastro e nel patrimonio, ma i suoi movimenti non entrano nelle analisi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManagedAccountDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private fun nt(key: String, acc: Long, amount: String) = NewTransaction(key, key, acc, LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), key, BigDecimal(amount),
        if (amount.startsWith("-")) it.spese.core.model.TxKind.SPESA else it.spese.core.model.TxKind.ENTRATA, null, false, null)

    @Test fun `i movimenti del conto gestito stanno fuori dalle analisi ma dentro il libro mastro`() = runBlocking {
        val mine = env.accounts.getOrCreate("Mio"); val family = env.accounts.getOrCreate("Famiglia")
        env.tx.insertAll(listOf(nt("a", mine.id, "-10"), nt("b", family.id, "-70"), nt("c", family.id, "1000")))
        val plain = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings).observe().first()
        assertEquals(3, plain.transactions.size)
        val d = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings, flowOf(setOf(family.id))).observe().first()
        assertEquals(listOf(mine.id), d.transactions.map { it.accountId }.distinct())
        assertEquals(3, d.ledger.size); assertEquals(3, d.everything.size); assertEquals(setOf(family.id), d.managedAccountIds)
    }
}
