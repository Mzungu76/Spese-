package it.spese.data

import it.spese.core.model.TxOrigin
import it.spese.data.settings.ValuationRepositoryImpl
import it.spese.domain.capture.*
import it.spese.domain.repository.AppDataSource
import it.spese.domain.usecase.*
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

/** Ogni movimento ha un conto, oppure sta in Verifica fuori dalla contabilità: nessun conto di ripiego, nessuna eliminazione silenziosa. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoAccountDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private fun at(date: String) = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun raw(text: String, date: String = "2026-11-14") = RawNotification("com.google.android.apps.walletnfcrel", "COOP", text, at(date))
    private val assign get() = AssignAccountUseCase(env.tx, env.accounts)
    private fun delete() = DeleteAccountUseCase(env.tx, env.accounts, assign, env.recurring, env.captureConfig, ValuationRepositoryImpl(env.context), env.backup)
    private fun source() = AppDataSource(env.tx, env.cats, env.accounts, env.rules, env.dues, env.scenarios, env.quotas, env.settings)

    @Test fun `non esiste alcun conto predefinito e un nome vuoto non crea un conto`() = runBlocking<Unit> {
        assertTrue(env.accounts.getAll().isEmpty())
        assertTrue(runCatching { env.accounts.getOrCreate("   ") }.isFailure)
        assertTrue(env.accounts.getAll().isEmpty())
    }

    @Test fun `una notifica senza conto assegnato alla fonte resta senza conto e fuori dalla contabilita`() = runBlocking<Unit> {
        env.accounts.getOrCreate("Intesa")                    // esiste un conto, ma la fonte non lo ha scelto: non si usa «il primo»
        val r = env.capture(CaptureSource.WALLET, null, raw("12,50 €"), ZoneOffset.UTC) as CaptureResult.Added
        assertNull(env.tx.get(r.txId)!!.accountId)
        val d = source().observe().first()
        assertEquals(0, d.transactions.size); assertEquals(0, d.ledger.size); assertEquals(1, d.unassigned.size)
    }

    @Test fun `assegnare il conto fa entrare il movimento nella contabilita e il provvisorio resta sostituibile dall'estratto`() = runBlocking<Unit> {
        val acc = env.accounts.getOrCreate("Revolut")
        val r = env.capture(CaptureSource.WALLET, null, raw("45,50 €"), ZoneOffset.UTC) as CaptureResult.Added
        val before = env.tx.getDedupKey(r.txId)
        assertEquals(1, assign(listOf(r.txId), acc.id))
        assertEquals(acc.id, env.tx.get(r.txId)!!.accountId)
        assertEquals(before, env.tx.getDedupKey(r.txId))                  // la chiave del provvisorio non dipende dal conto
        val d = source().observe().first()
        assertEquals(1, d.transactions.size); assertEquals(0, d.unassigned.size)
        assertEquals(0, assign(listOf(r.txId), acc.id))                   // già assegnato: nessuna modifica
        assertTrue(runCatching { assign(listOf(r.txId), 999L) }.isFailure)
    }

    @Test fun `un provvisorio senza conto viene sostituito dalla riga dell'estratto importato`() = runBlocking<Unit> {
        val r = env.capture(CaptureSource.REVOLUT, null, raw("Hai pagato 45,50 € a AMAZON PRIME", "2024-04-01"), ZoneOffset.UTC) as CaptureResult.Added
        assertNull(env.tx.get(r.txId)!!.accountId)
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        val o = env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
        assertEquals(1, o.reconciled)
        assertTrue(env.tx.getAll().none { it.origin == TxOrigin.NOTIFICATION })
    }

    @Test fun `l'importazione richiede il conto`() = runBlocking<Unit> {
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        assertTrue(runCatching { env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "  ") }.isFailure)
        assertTrue(env.accounts.getAll().isEmpty()); assertEquals(0, env.tx.getAll().size)
    }

    private suspend fun accountWithTwoMovements(): Long {
        val a = env.accounts.getOrCreate("Carta")
        env.capture(CaptureSource.WALLET, a.id, raw("10,00 €", "2026-11-10"), ZoneOffset.UTC)
        env.capture(CaptureSource.REVOLUT, a.id, raw("Hai pagato 7,00 € a BAR", "2026-11-11"), ZoneOffset.UTC)
        assertEquals(2, env.tx.getAll().count { it.accountId == a.id })
        return a.id
    }

    @Test fun `eliminare un conto spostando i movimenti su un altro`() = runBlocking<Unit> {
        val a = accountWithTwoMovements(); val b = env.accounts.getOrCreate("Conto")
        assertEquals(2, delete()(a, AccountDeletion.MOVE_TO_ACCOUNT, b.id))
        assertEquals(listOf("Conto"), env.accounts.getAll().map { it.name })
        assertEquals(2, env.tx.getAll().count { it.accountId == b.id })
        assertTrue(env.backup.hasUndo())                                   // snapshot di sicurezza salvato prima
        assertTrue(runCatching { delete()(b.id, AccountDeletion.MOVE_TO_ACCOUNT, b.id) }.isFailure)    // non si può spostare su sé stesso
    }

    @Test fun `eliminare un conto lasciando i movimenti senza conto, in Verifica`() = runBlocking<Unit> {
        val a = accountWithTwoMovements()
        assertEquals(2, delete()(a, AccountDeletion.TO_REVIEW))
        assertTrue(env.accounts.getAll().isEmpty())
        assertEquals(2, env.tx.getAll().count { it.accountId == null })
        val d = source().observe().first()
        assertEquals(0, d.transactions.size); assertEquals(2, d.unassigned.size)
    }

    @Test fun `eliminare un conto con i suoi movimenti e annullare`() = runBlocking<Unit> {
        val a = accountWithTwoMovements()
        assertEquals(2, delete()(a, AccountDeletion.DELETE_MOVEMENTS))
        assertTrue(env.accounts.getAll().isEmpty()); assertEquals(0, env.tx.getAll().size)
        assertTrue(env.backup.undoLast())                                   // «Annulla l'ultima operazione» riporta conto e movimenti
        assertEquals(listOf("Carta"), env.accounts.getAll().map { it.name })
        assertEquals(2, env.tx.getAll().size)
    }

    @Test fun `ricorrenze e fonti collegate al conto restano senza conto, non puntano a un conto che non c'e piu`() = runBlocking<Unit> {
        val a = env.accounts.getOrCreate("Carta")
        env.recurring.save(it.spese.core.model.RecurringRule(0, "Affitto", it.spese.core.model.TxKind.SPESA, BigDecimal(500), accountId = a.id))
        env.captureConfig.update { c -> c.copy(sources = c.sources + ("revolut" to SourceConfig(true, accountId = a.id))) }
        val impact = delete().impact(a.id)
        assertEquals(1, impact.recurringRules); assertEquals(1, impact.captureSources)
        delete()(a.id, AccountDeletion.TO_REVIEW)
        assertNull(env.recurring.getAll().single().accountId)
        assertNull(env.captureConfig.get().sources.getValue("revolut").accountId)
    }
}
