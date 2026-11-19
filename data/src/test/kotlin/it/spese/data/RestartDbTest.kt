package it.spese.data

import it.spese.core.database.entity.DocumentEntity
import it.spese.core.model.TxOrigin
import it.spese.domain.usecase.*
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Ricomincia dagli estratti»: si cancellano le righe importate, si tengono i dettagli degli scontrini e la reimportazione li ritrova. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestartDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private suspend fun importRevolut(): ImportOutcome {
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        return env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
    }

    @Test fun `le righe importate si cancellano, lo scontrino resta e la reimportazione lo ritrova con foto e voci`() = runBlocking<Unit> {
        assertEquals(4, importRevolut().added)
        val amazon = env.tx.getAll().first { it.description.contains("AMAZON PRIME") }
        val cats = env.cats.getAll()
        val a = cats.first { it.name == "Supermercato e alimentari" }; val b = cats.first { it.name == "Abbonamenti digitali" }
        val doc = env.db.documents().insert(DocumentEntity(fileName = "scontrino.jpg", mimeType = "image/jpeg", sizeBytes = 10, createdAt = 0))
        env.tx.setDocument(amazon.id, doc)
        env.setSplits(amazon.id, listOf(SplitPartInput(a.id, BigDecimal("-30.00")), SplitPartInput(b.id, BigDecimal("-15.50"))))

        val restart = RestartFromStatementsUseCase(env.tx, env.backup)
        val plan = restart.preview(true)
        assertEquals(1, plan.withDetails.size); assertEquals(3, plan.toDelete.size)
        val r = restart(true)
        assertEquals(1, r.converted); assertEquals(3, r.deleted)
        val left = env.tx.getAll().single()
        assertEquals(TxOrigin.OCR, left.origin); assertEquals(left.externalId, env.tx.getDedupKey(left.id))     // «in attesa dell'estratto», mai doppione
        assertEquals(doc, left.documentId); assertEquals(2, env.tx.getSplits().count { it.txId == left.id })
        assertTrue(env.backup.hasUndo())

        // reimportazione: la riga dell'estratto sostituisce lo scontrino ereditandone foto e voci; niente doppioni
        val o = importRevolut()
        assertEquals(4, o.added); assertEquals(1, o.reconciled)
        val all = env.tx.getAll()
        assertEquals(4, all.size); assertEquals(1, all.count { it.description.contains("AMAZON PRIME") })
        val again = all.first { it.description.contains("AMAZON PRIME") }
        assertEquals(TxOrigin.IMPORT_FILE, again.origin); assertEquals(doc, again.documentId)
        assertEquals(2, env.tx.getSplits().count { it.txId == again.id })
        // reimportare ancora lo stesso estratto non aggiunge nulla
        assertEquals(0, importRevolut().added)
    }

    @Test fun `i movimenti inseriti a mano si tengono o si cancellano a scelta`() = runBlocking<Unit> {
        val acc = env.accounts.getOrCreate("Contanti")
        val save = SaveTransactionUseCase(env.tx, env.accounts, env.cats, env.rules)
        save(TransactionDraft(accountId = acc.id, bookingDate = java.time.LocalDate.parse("2026-03-02"), description = "Caffè al bar", amount = BigDecimal("-1.20")))
        val restart = RestartFromStatementsUseCase(env.tx, env.backup)
        assertEquals(1, restart.preview(true).manual.size); assertEquals(0, restart.preview(true).toDelete.size)
        assertEquals(1, restart.preview(false).toDelete.size)
        restart(false)
        assertEquals(0, env.tx.getAll().size)
    }
}
