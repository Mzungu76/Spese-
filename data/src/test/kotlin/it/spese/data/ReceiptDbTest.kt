package it.spese.data

import it.spese.core.model.DocumentRef
import it.spese.core.model.TxKind
import it.spese.core.model.TxOrigin
import it.spese.data.repository.DocumentRepositoryImpl
import it.spese.domain.ocr.ReceiptItem
import it.spese.domain.usecase.*
import java.math.BigDecimal
import java.time.LocalDate
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
class ReceiptDbTest {
    private lateinit var env: TestEnv
    private lateinit var saveReceipt: SaveReceiptUseCase
    @Before fun setUp() {
        env = TestEnv(); runBlocking { env.seeder.ensureSeeded() }
        saveReceipt = SaveReceiptUseCase(SaveTransactionUseCase(env.tx, env.accounts, env.cats, env.rules), env.tx, env.setSplits, env.cats)
    }
    @After fun tearDown() = env.close()

    private suspend fun doc() = DocumentRepositoryImpl(env.db).add(DocumentRef(0, "r.jpg", "image/jpeg", 10, 0))
    private val items = listOf(ReceiptItem("Pane", BigDecimal("3.00")), ReceiptItem("Detersivo", BigDecimal("6.50")), ReceiptItem("Latte", BigDecimal("1.50")))

    @Test fun `scontrino e poi estratto conto danno un solo movimento con documento e suddivisione per voci`() = runBlocking {
        val acc = env.accounts.getOrCreate("Revolut")
        val food = env.cats.getAll().first { it.name == "Supermercato e alimentari" }
        val casa = env.cats.getAll().first { it.kind == TxKind.SPESA && it.id != food.id }
        val docId = doc()
        val parts = ReceiptSplitPlanner.plan(BigDecimal("45.50"), items, listOf(null, casa.id, null), food.id, true)
        saveReceipt(TransactionDraft(0, acc.id, LocalDate.parse("2024-04-01"), description = "AMAZON PRIME", amount = BigDecimal("-45.50"), categoryId = food.id), docId, parts)
        assertEquals(1, env.tx.getAll().size)
        assertEquals(TxOrigin.OCR, env.tx.getAll().single().origin); assertEquals(2, env.tx.getSplits().size)

        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        val o = env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
        assertEquals(1, o.reconciled)
        val all = env.tx.getAll()
        assertEquals(1, all.count { it.description.contains("AMAZON PRIME") })
        assertTrue(all.none { it.origin == TxOrigin.OCR })
        val amazon = all.first { it.description.contains("AMAZON PRIME") }
        assertEquals(docId, amazon.documentId)
        val splits = env.tx.getSplits().filter { it.txId == amazon.id }
        assertEquals(2, splits.size)
        assertEquals(0, BigDecimal("-45.50").compareTo(splits.sumOf { it.amount }))
    }

    @Test fun `estratto conto e poi scontrino si collegano senza creare un doppione`() = runBlocking {
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
        val before = env.tx.getAll().size
        val ledger = env.tx.getAll()
        val c = ReceiptMatcher.candidates(ledger, BigDecimal("-45.50"), LocalDate.parse("2024-04-01"))
        assertEquals(1, c.size)
        val acc = env.accounts.getOrCreate("Revolut")
        val docId = doc()
        val casa = env.cats.getAll().first { it.name == "Supermercato e alimentari" }
        val other = env.cats.getAll().first { it.id != casa.id && it.kind == TxKind.SPESA }
        val parts = ReceiptSplitPlanner.plan(BigDecimal("45.50"), items, listOf(other.id, null, null), casa.id, true)
        val id = saveReceipt(TransactionDraft(0, acc.id, LocalDate.parse("2024-04-01"), description = "AMAZON PRIME", amount = BigDecimal("-45.50")), docId, parts, attachToId = c[0].id)
        assertEquals(c[0].id, id)
        assertEquals(before, env.tx.getAll().size)
        assertEquals(docId, env.tx.get(id)!!.documentId)
        assertEquals(2, env.tx.getSplits().count { it.txId == id })
        // già collegato: non è più candidato per un altro scontrino
        assertTrue(ReceiptMatcher.candidates(env.tx.getAll(), BigDecimal("-45.50"), LocalDate.parse("2024-04-01")).isEmpty())
    }

    @Test fun `la divisione per voci rispetta importi, segno e limiti`() {
        val p = ReceiptSplitPlanner.plan(BigDecimal("11.00"), items, listOf(7L, 7L, null), 1L, true)
        assertEquals(listOf(1L, 7L), p.map { it.categoryId })
        assertEquals(0, BigDecimal("-1.50").compareTo(p[0].amount)); assertEquals(0, BigDecimal("-9.50").compareTo(p[1].amount))
        assertTrue(ReceiptSplitPlanner.plan(BigDecimal("11.00"), items, listOf(null, null, null), 1L, true).isEmpty())
        assertTrue(ReceiptSplitPlanner.plan(BigDecimal("11.00"), items, listOf(1L, 1L, 1L), 1L, true).isEmpty())
        // le voci assegnate superano il totale
        assertThrows(IllegalArgumentException::class.java) { ReceiptSplitPlanner.plan(BigDecimal("5.00"), items, listOf(7L, 7L, 7L), 1L, true) }
    }

    @Test fun `la memoria delle voci viaggia nel backup e si ritrova per nome di categoria`() = runBlocking {
        val mem = it.spese.data.settings.ItemMemoryRepositoryImpl(env.context)
        mem.clear()
        val food = env.cats.getAll().first { it.name == "Supermercato e alimentari" }
        mem.learn(mapOf("latte" to food.id, "pane" to food.id))
        val json = env.backup.buildBackupJson()
        assertTrue(json.contains("\"items\""))
        // ripristino su un'app vuota: la memoria si ricostruisce con gli id attuali
        mem.clear()
        assertTrue(mem.get().isEmpty())
        env.restore(it.spese.domain.backup.LegacyBackupCodec.parse(json), it.spese.domain.repository.RestoreMode.Replace)
        val idNow = env.cats.getAll().first { it.name == "Supermercato e alimentari" }.id
        assertEquals(mapOf("latte" to idNow, "pane" to idNow), mem.get())
    }
}
