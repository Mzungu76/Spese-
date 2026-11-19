package it.spese.data

import it.spese.core.model.*
import it.spese.domain.backup.LegacyBackupCodec
import it.spese.domain.importing.*
import it.spese.domain.repository.RestoreMode
import it.spese.domain.usecase.*
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseTest {
    private lateinit var env: TestEnv
    private val golden = Json.parseToJsonElement(String(TestEnv::class.java.getResourceAsStream("/golden.json")!!.readBytes())).jsonObject

    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private fun backup(name: String) = LegacyBackupCodec.parse(String(env.resource(name)))

    @Test fun `al primo avvio vengono inserite categorie e regole dell'originale`() = runBlocking<Unit> {
        assertEquals(21, env.cats.getAll().size); assertEquals(31, env.rules.getAll().size)
        assertEquals(0, env.accounts.getAll().size)           // nessun conto predefinito: i conti si creano esplicitamente
        assertNotNull(env.scenarios.observeAll().first().firstOrNull { it.isBase })
        env.seeder.ensureSeeded()      // idempotente
        assertEquals(21, env.cats.getAll().size)
        assertEquals(setOf(Category.FALLBACK_EXPENSE, Category.FALLBACK_INCOME), env.cats.getAll().mapNotNull { it.systemKey }.toSet())
    }

    @Test fun `ripristino di un backup HTML con sostituzione - dati e classificazione identici all'originale`() = runBlocking<Unit> {
        val r = env.restore(backup("backupA.json"), RestoreMode.Replace)
        assertEquals(223, r.txAdded)
        val txs = env.tx.getAll(); val cats = env.cats.getAll().associateBy { it.id }
        assertEquals(223, txs.size)
        val byExt = txs.associateBy { it.externalId }
        for (e in golden["tx"]!!.jsonArray) {
            val o = e.jsonObject; val t = byExt.getValue(o["id"]!!.jsonPrimitive.content)
            assertEquals(o["cat"]!!.jsonPrimitive.content, cats[t.categoryId]?.name)
            assertEquals(o["k"]!!.jsonPrimitive.content, t.kind.key)
            assertEquals(o["man"]!!.jsonPrimitive.content.toBoolean(), t.manualCategory)
            assertEquals(BigDecimal(o["a"]!!.jsonPrimitive.content).setScale(2, java.math.RoundingMode.HALF_UP), t.amount)
        }
        assertEquals(setOf("Intesa", "Revolut"), env.accounts.getAll().map { it.name }.toSet())
        assertEquals(2, env.dues.observeAll().first().size)
        val base = env.scenarios.observeAll().first().first { it.isBase }
        assertEquals(GoalMode.TOTAL, base.goal.mode); assertEquals(30, base.cuts.entries.first { cats[it.key]?.name == "Bar e ristoranti" }.value)
        assertEquals(BigDecimal("250.00"), env.cats.getAll().first { it.name == "Supermercato e alimentari" }.budgetMonthly)
        assertEquals(32, env.rules.getAll().size)
    }

    @Test fun `unione di due backup identica all'originale`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        val envB = backup("backupB.json")
        val plan = env.backup.planMerge(envB)
        assertEquals(golden["mergeSummary"]!!.jsonArray.map { it.jsonPrimitive.content }, plan.summary())
        val r = env.restore(envB, RestoreMode.Merge)
        assertEquals(1, r.txAdded)
        val merged = golden["merged"]!!.jsonObject
        val txs = env.tx.getAll(); val cats = env.cats.getAll(); val byId = cats.associateBy { it.id }
        assertEquals(merged["txIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), txs.map { it.externalId }.toSet())
        assertEquals(merged["cats"]!!.jsonArray.map { it.jsonObject["n"]!!.jsonPrimitive.content }.toSet(), cats.map { it.name }.toSet())
        assertEquals(BigDecimal("55.00"), cats.first { it.name == "Bar e ristoranti" }.budgetMonthly)
        assertEquals(BigDecimal("30.00"), cats.first { it.name == "Tabacchi" }.budgetMonthly)
        assertEquals(BigDecimal("650.00"), env.dues.observeAll().first().first { it.legacyId == "dtest2" }.amount)
        // classificazione dopo l'unione
        val byExt = txs.associateBy { it.externalId }
        merged["cl"]!!.jsonArray.forEach { e ->
            val a = e.jsonArray; val t = byExt.getValue(a[0].jsonPrimitive.content)
            assertEquals(a[1].jsonPrimitive.content, byId[t.categoryId]?.name); assertEquals(a[2].jsonPrimitive.content, t.kind.key)
        }
        // ordine regole: la regola "trenord" è in testa
        assertEquals("trenord", env.rules.getAll().first().keyword)
        // idempotenza: una seconda unione non cambia nulla
        assertTrue(env.backup.planMerge(envB).isEmpty)
    }

    @Test fun `annullamento dopo un ripristino riporta lo stato precedente`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        val before = env.tx.getAll().map { it.externalId }.toSet()
        env.restore(backup("backupB.json"), RestoreMode.Merge)
        assertEquals(224, env.tx.getAll().size)
        assertTrue(env.undo())
        assertEquals(before, env.tx.getAll().map { it.externalId }.toSet())
        assertEquals("", env.settings.get().lastUndo)
    }

    private suspend fun TestEnv.undo() = this.undo.invoke()

    @Test fun `esportazione JSON e rilettura senza perdite`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        val json = env.backup.buildBackupJson()
        val back = LegacyBackupCodec.parse(json)
        assertEquals(223, back.state.tx.size); assertEquals(2, back.state.ov.size); assertEquals(2, back.state.due.size)
        assertEquals(backup("backupA.json").state.tx.associate { it.id to it.a }, back.state.tx.associate { it.id to it.a })
        // un nuovo database ripristinato dall'esportazione è equivalente
        env.restore(back, RestoreMode.Replace)
        assertEquals(223, env.tx.getAll().size)
        assertEquals(2, env.tx.getAll().count { it.manualCategory })
        // il file prodotto contiene la struttura attesa dalla versione HTML
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("gestione-spese", o["app"]!!.jsonPrimitive.content)
        assertTrue(o["data"]!!.jsonObject.keys.containsAll(listOf("tx", "cats", "rules", "due", "ov", "budget", "goal", "cuts", "mt")))
    }

    @Test fun `importazione di un estratto conto XLSX con deduplica e annullamento del lotto`() = runBlocking<Unit> {
        val tables = ReadStatementUseCase().invoke(env.resource("intesa.xlsx"), "intesa.xlsx")
        val t = tables.first(); val det = t.detected!!
        val o1 = env.commit(t, det.headerRow, det.mapping, "Intesa")
        assertEquals(219, o1.added); assertEquals(0, o1.duplicates)
        val o2 = env.commit(t, det.headerRow, det.mapping, "intesa")          // stesso conto, nome in minuscolo
        assertEquals(0, o2.added); assertEquals(219, o2.duplicates)
        assertEquals(219, env.tx.getAll().size)
        // gli id coincidono con quelli dell'app HTML
        val htmlIds = golden["tx"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertTrue(env.tx.getAll().all { it.externalId in htmlIds })
        // i due caffè identici nello stesso giorno sono entrambi presenti
        assertEquals(2, env.tx.getAll().count { it.description.contains("CAFFE ROMA") })
        // classificazione automatica applicata
        assertTrue(env.tx.getAll().count { it.categoryId != null } == 219)
        // annulla l'ultimo lotto
        assertTrue(env.settings.get().lastUndo.startsWith("batch:"))
        // (il secondo import non ha creato dati: annulla il lotto corrente che è vuoto) -> si annulla il primo manualmente
        env.tx.deleteImportBatch(o1.batchId)
        assertEquals(0, env.tx.getAll().size)
    }

    @Test fun `import CSV in un secondo conto`() = runBlocking<Unit> {
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        val o = env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
        assertEquals(4, o.added)
        assertEquals(setOf("Revolut"), env.accounts.getAll().map { it.name }.toSet())       // nessun conto predefinito: esiste solo quello indicato
        val tr = env.tx.getAll().filter { it.kind == TxKind.TRASFERIMENTO }   // "REVOLUT" / "HYPE" -> Trasferimenti
        assertEquals(2, tr.size)
    }

    @Test fun `inserimento manuale, modifica, duplicazione ed eliminazione`() = runBlocking<Unit> {
        val acc = env.accounts.getOrCreate("Conto test")
        val save = SaveTransactionUseCase(env.tx, env.accounts, env.cats, env.rules)
        save(TransactionDraft(accountId = acc.id, bookingDate = LocalDate.of(2024, 5, 1), description = "Pagamento POS COOP", amount = BigDecimal("-23.45")))
        var t = env.tx.getAll().single()
        assertEquals("Supermercato e alimentari", env.cats.getAll().first { it.id == t.categoryId }.name)
        assertFalse(t.manualCategory); assertEquals(TxKind.SPESA, t.kind); assertEquals(BigDecimal("-23.45"), t.amount)
        // modifica con categoria manuale
        val bar = env.cats.getAll().first { it.name == "Bar e ristoranti" }
        save(TransactionDraft(t.id, acc.id, t.bookingDate, t.valueDate, t.description, t.amount, categoryId = bar.id, notes = "nota"))
        t = env.tx.getAll().single()
        assertTrue(t.manualCategory); assertEquals(bar.id, t.categoryId); assertEquals("nota", t.notes)
        // la riclassificazione non tocca le assegnazioni manuali
        env.reclassify(); assertEquals(bar.id, env.tx.getAll().single().categoryId)
        assertEquals(1, DuplicateTransactionsUseCase(env.tx, env.accounts)(listOf(t.id)))
        assertEquals(2, env.tx.getAll().size)
        env.tx.delete(env.tx.getAll().map { it.id }); assertEquals(0, env.tx.getAll().size)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { save(TransactionDraft(accountId = acc.id, bookingDate = t.bookingDate, description = "x", amount = BigDecimal.ZERO)) } }
    }

    @Test fun `regola creata da un movimento e priorita`() = runBlocking<Unit> {
        val acc = env.accounts.getOrCreate("Conto test")
        SaveTransactionUseCase(env.tx, env.accounts, env.cats, env.rules)(TransactionDraft(accountId = acc.id, bookingDate = LocalDate.of(2024, 5, 1), description = "NEGOZIO XYZ", amount = BigDecimal("-10")))
        val casa = env.cats.getAll().first { it.name == "Casa e giardino" }
        val t = env.tx.getAll().single()
        AssignCategoryUseCase(env.tx, env.cats)(listOf(t.id), env.cats.getAll().first { it.name == "Salute" }.id)
        assertTrue(env.tx.getAll().single().manualCategory)
        CreateRuleUseCase(env.rules, env.tx, env.reclassify)("xyz", casa.id, releaseTxId = t.id)
        val after = env.tx.getAll().single()
        assertFalse(after.manualCategory); assertEquals(casa.id, after.categoryId)
        assertEquals("xyz", env.rules.getAll().first().keyword)           // in cima alla lista
    }

    @Test fun `eliminazione categoria - regole rimosse, movimenti a Altro`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        val cats = env.cats.getAll(); val salute = cats.first { it.name == "Salute" }
        DeleteCategoryUseCase(env.cats, env.tx, env.reclassify)(salute.id)
        assertTrue(env.cats.getAll().none { it.name == "Salute" })
        val altro = env.cats.getAll().first { it.name == "Altro" }
        assertTrue(env.tx.getAll().none { it.categoryId == salute.id })
        assertTrue(env.tx.getAll().first { it.description.contains("XYZ SERVIZI") }.let { it.categoryId != salute.id })
        assertTrue(env.rules.getAll().none { it.categoryId == salute.id })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { DeleteCategoryUseCase(env.cats, env.tx, env.reclassify)(altro.id) } }
    }

    @Test fun `integrita referenziale - un conto con movimenti non si elimina e due conti si uniscono`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        val intesa = env.accounts.getAll().first { it.name == "Intesa" }; val rev = env.accounts.getAll().first { it.name == "Revolut" }
        assertFalse(env.accounts.deleteIfEmpty(intesa.id))
        env.accounts.renameOrMerge(rev.id, "intesa")      // stesso nome (case-insensitive): unione
        assertEquals(1, env.accounts.getAll().size)
        assertEquals(223, env.tx.getAll().count { it.accountId == intesa.id })
        env.accounts.renameOrMerge(intesa.id, "Conto Banca")
        assertEquals("Conto Banca", env.accounts.getAll().single().name)
    }

    @Test fun `azzeramento con possibilita di annullamento`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        env.backup.resetAll()
        assertEquals(0, env.tx.getAll().size); assertEquals(21, env.cats.getAll().size)
        assertTrue(env.undo())
        assertEquals(223, env.tx.getAll().size)
    }

    @Test fun `scenari salvati e confronto`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        val cat = env.cats.getAll().first { it.name == "Tabacchi" }
        val id = env.scenarios.save(Scenario(0, "Taglio tabacchi", SavingsGoal(), mapOf(cat.id to 50), BigDecimal("200"), setOf("rec:X")))
        val s = env.scenarios.observeAll().first().first { it.id == id }
        assertEquals(50, s.cuts[cat.id]); assertEquals(BigDecimal("200.00"), s.incomeDelta); assertEquals(setOf("rec:X"), s.removedRecurring)
        // gli scenari sopravvivono al backup nativo
        val json = env.backup.buildBackupJson()
        env.restore(LegacyBackupCodec.parse(json), RestoreMode.Replace)
        assertEquals(2, env.scenarios.observeAll().first().size)
        env.scenarios.delete(env.scenarios.observeAll().first().first { it.name == "Taglio tabacchi" }.id); assertEquals(1, env.scenarios.observeAll().first().size)
        val base = env.scenarios.observeAll().first().first { it.isBase }
        env.scenarios.delete(base.id); assertEquals(1, env.scenarios.observeAll().first().size)   // lo scenario base non si elimina
    }

    @Test fun `backup automatici con rotazione`() = runBlocking<Unit> {
        env.restore(backup("backupA.json"), RestoreMode.Replace)
        repeat(4) { i -> java.io.File(env.context.filesDir, "backups").also { it.mkdirs() }.resolve("auto-2020-01-0${i + 1}-0000.json").writeText("{}") }
        val path = env.backup.autoBackup(keep = 3)
        val list = env.backup.listAutoBackups()
        assertEquals(3, list.size); assertTrue(list.first().absolutePath == path || list.any { it.absolutePath == path })
        assertEquals(223, LegacyBackupCodec.parse(java.io.File(path).readText()).state.tx.size)
    }
}
