package it.spese.data

import it.spese.core.model.*
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
class RecurringDbTest {
    private lateinit var env: TestEnv
    private lateinit var generate: GenerateRecurringUseCase
    private lateinit var save: SaveRecurringUseCase
    @Before fun setUp() {
        env = TestEnv(); runBlocking { env.seeder.ensureSeeded() }
        generate = GenerateRecurringUseCase(env.tx, env.accounts, env.cats, env.rules, env.recurring)
        save = SaveRecurringUseCase(env.recurring, generate)
    }
    @After fun tearDown() = env.close()

    private fun salary(start: String = "2026-07-27", auto: Boolean = false, cat: Long? = null, acc: Long? = null) =
        RecurringRule(0, "Stipendio ACME", TxKind.ENTRATA, BigDecimal(2000), acc, cat, 1, 27, LocalDate.parse(start), null, true, auto)

    @Test fun `crea le occorrenze dovute come provvisori, una volta sola, e non ricrea quelle eliminate`() = runBlocking {
        env.recurring.save(salary()); generate(LocalDate.parse("2026-10-14"))
        assertEquals(3, env.tx.getAll().count { t -> t.origin == TxOrigin.RECURRING })
        // nessuna data nel futuro, tutte provvisorie e non controllate
        val all = env.tx.getAll().filter { it.origin == TxOrigin.RECURRING }
        assertTrue(all.all { !it.reviewed && it.kind == TxKind.ENTRATA && it.amount.compareTo(BigDecimal(2000)) == 0 && it.notes.startsWith("Ricorrente") })
        assertEquals(0, generate(LocalDate.parse("2026-10-14")))                                  // idempotente
        env.tx.delete(listOf(all.first { it.bookingDate == LocalDate.parse("2026-08-27") }.id))
        assertEquals(0, generate(LocalDate.parse("2026-10-14")))                                  // l'agosto eliminato non torna
        assertEquals(1, generate(LocalDate.parse("2026-10-30")))                                  // ma ottobre sì, a tempo debito
    }

    private suspend fun SaveRecurringUseCase.at(r: RecurringRule, today: String): Int { env.recurring.save(r); return generate(LocalDate.parse(today)) }

    @Test fun `categoria fissa, registrazione senza verifica e spese ricorrenti col segno giusto`() = runBlocking {
        val rent = env.cats.insert(Category(0, "Affitto incassato", TxKind.ENTRATA, false, 0, null, true, 99))
        save.at(salary("2026-09-27", auto = true, cat = rent), "2026-10-14")
        val t = env.tx.getAll().single()
        assertEquals(rent, t.categoryId); assertTrue(t.manualCategory); assertTrue(t.reviewed)
        env.recurring.save(RecurringRule(0, "Palestra", TxKind.SPESA, BigDecimal(30), null, null, 1, 5, LocalDate.parse("2026-10-05"), null, true, false))
        generate(LocalDate.parse("2026-10-14"))
        val gym = env.tx.getAll().first { it.description == "Palestra" }
        assertEquals(0, BigDecimal(-30).compareTo(gym.amount)); assertEquals(TxKind.SPESA, gym.kind)
    }

    @Test fun `modificando la data di inizio si riparte, e regole spente o non valide`() = runBlocking {
        val id = env.recurring.save(salary("2026-09-27")); generate(LocalDate.parse("2026-10-14"))
        assertEquals(1, env.tx.getAll().size)
        assertTrue(runCatching { save(salary().copy(name = " ")) }.isFailure); assertTrue(runCatching { save(salary().copy(amount = BigDecimal.ZERO)) }.isFailure)
        assertTrue(runCatching { save(salary().copy(endDate = LocalDate.parse("2026-01-01"))) }.isFailure)
        env.recurring.save(env.recurring.getAll().single().copy(id = id, enabled = false)); assertEquals(0, generate(LocalDate.parse("2026-12-30")))
    }

    @Test fun `l'estratto conto sostituisce l'entrata ricorrente (5 giorni) e ne conserva la categoria`() = runBlocking {
        val acc = env.accounts.getOrCreate("Revolut")
        val cat = env.cats.getAll().first { it.name == "Altre entrate" }.id
        env.recurring.save(RecurringRule(0, "Ricarica famiglia", TxKind.ENTRATA, BigDecimal(150), acc.id, cat, 1, 12, LocalDate.parse("2024-05-01"), null, true, false))
        generate(LocalDate.parse("2024-05-20"))
        assertEquals(1, env.tx.getAll().count { it.origin == TxOrigin.RECURRING })
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        val o = env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")      // nel file: +150 il 14/05/2024
        assertEquals(1, o.reconciled); assertTrue(env.tx.getAll().none { it.origin == TxOrigin.RECURRING })
        assertEquals(cat, env.tx.getAll().first { it.description.contains("HYPE") }.categoryId)
    }

    @Test fun `le regole ricorrenti sopravvivono al backup`() = runBlocking {
        val cat = env.cats.getAll().first { it.name == "Stipendio" }.id
        env.recurring.save(salary("2026-07-27", auto = true, cat = cat).copy(everyMonths = 2, endDate = LocalDate.parse("2027-01-01")))
        generate(LocalDate.parse("2026-10-14"))
        val json = env.backup.buildBackupJson()
        env.restore(it.spese.domain.backup.LegacyBackupCodec.parse(json), it.spese.domain.repository.RestoreMode.Replace)
        val r = env.recurring.getAll().single()
        assertEquals("Stipendio ACME", r.name); assertEquals(2, r.everyMonths); assertTrue(r.autoConfirm); assertEquals(LocalDate.parse("2027-01-01"), r.endDate)
        assertEquals(env.cats.getAll().first { it.name == "Stipendio" }.id, r.categoryId); assertNotNull(r.lastGenerated)
        assertEquals(0, generate(LocalDate.parse("2026-10-14")))                                  // dopo il ripristino non duplica
    }
}
