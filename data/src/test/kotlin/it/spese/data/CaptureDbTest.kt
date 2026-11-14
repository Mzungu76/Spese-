package it.spese.data

import it.spese.core.model.TxOrigin
import it.spese.domain.backup.LegacyBackupCodec
import it.spese.domain.capture.*
import it.spese.domain.repository.ClassificationUpdate
import it.spese.domain.usecase.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
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
class CaptureDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded() } }
    @After fun tearDown() = env.close()

    private fun at(date: String, h: Int = 12, m: Int = 0) = LocalDate.parse(date).atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun raw(pkg: String, title: String, text: String, t: Long) = RawNotification(pkg, title, text, t)

    @Test fun `la notifica diventa un movimento provvisorio classificato dalle regole`() = runBlocking {
        val r = env.capture(CaptureSource.WALLET, null, raw("com.google.android.apps.walletnfcrel", "COOP", "12,50 €", at("2026-11-14")), ZoneOffset.UTC) as CaptureResult.Added
        val t = env.tx.get(r.txId)!!
        assertEquals(TxOrigin.NOTIFICATION, t.origin); assertEquals(0, BigDecimal("-12.5").compareTo(t.amount)); assertEquals(LocalDate.parse("2026-11-14"), t.bookingDate)
        assertEquals("Supermercato e alimentari", env.cats.getAll().first { it.id == t.categoryId }.name)   // regola "coop"
        assertTrue(t.notes.startsWith("Da notifica"))
    }

    @Test fun `la stessa notifica riproposta e un pagamento notificato da due app vengono registrati una volta sola`() = runBlocking {
        val w = raw("com.google.android.apps.walletnfcrel", "BAR ROMA", "3,00 €", at("2026-11-14", 8, 0))
        assertTrue(env.capture(CaptureSource.WALLET, null, w, ZoneOffset.UTC) is CaptureResult.Added)
        assertEquals(CaptureResult.Duplicate, env.capture(CaptureSource.WALLET, null, w, ZoneOffset.UTC))
        // un minuto dopo la banca notifica lo stesso pagamento: sostituisce Wallet (fonte più precisa)
        val b = raw("com.revolut.revolut", "Revolut", "Hai pagato 3,00 € a BAR ROMA SRL", at("2026-11-14", 8, 1))
        assertTrue(env.capture(CaptureSource.REVOLUT, null, b, ZoneOffset.UTC) is CaptureResult.Added)
        val all = env.tx.getAll().filter { it.origin == TxOrigin.NOTIFICATION }
        assertEquals(1, all.size); assertEquals(CaptureSource.REVOLUT, CaptureNotificationUseCase.sourceOf(all[0].externalId))
        // e se Wallet arriva dopo la banca, non aggiunge nulla
        assertEquals(CaptureResult.Duplicate, env.capture(CaptureSource.WALLET, null, raw("com.google.android.apps.walletnfcrel", "BAR ROMA", "3,00 €", at("2026-11-14", 8, 2)), ZoneOffset.UTC))
        // due caffè identici in momenti diversi sono due movimenti
        assertTrue(env.capture(CaptureSource.WALLET, null, raw("com.google.android.apps.walletnfcrel", "BAR ROMA", "3,00 €", at("2026-11-14", 15, 0)), ZoneOffset.UTC) is CaptureResult.Added)
        assertEquals(2, env.tx.getAll().count { it.origin == TxOrigin.NOTIFICATION })
    }

    @Test fun `notifica non interpretabile e registrata come non riconosciuta e nessun movimento nasce`() = runBlocking {
        assertEquals(CaptureResult.Unrecognized, env.capture(CaptureSource.REVOLUT, null, raw("com.revolut.revolut", "Revolut", "Nuova operazione di 5,00 €", at("2026-11-14")), ZoneOffset.UTC))
        assertEquals(0, env.tx.getAll().size)
    }

    @Test fun `l'importazione dell'estratto sostituisce il provvisorio e conserva la categoria scelta a mano`() = runBlocking {
        val acc = env.accounts.getOrCreate("Revolut")
        val r = env.capture(CaptureSource.REVOLUT, acc.id, raw("com.revolut.revolut", "Revolut", "Hai pagato 45,50 € a AMAZON PRIME", at("2024-04-01")), ZoneOffset.UTC) as CaptureResult.Added
        val shopping = env.cats.getAll().first { it.name == "Abbonamenti digitali" }
        env.tx.applyClassifications(listOf(ClassificationUpdate(r.txId, shopping.id, shopping.kind, true, null)))   // l'utente corregge la categoria
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        val o = env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
        assertEquals(1, o.reconciled)
        val all = env.tx.getAll()
        assertTrue(all.none { it.origin == TxOrigin.NOTIFICATION })
        val amazon = all.first { it.description.contains("AMAZON PRIME") }
        assertEquals(TxOrigin.IMPORT_FILE, amazon.origin); assertEquals(shopping.id, amazon.categoryId); assertTrue(amazon.manualCategory)
        assertEquals(1, all.count { it.description.contains("AMAZON PRIME") })
    }

    @Test fun `la configurazione si salva e si rilegge, con al piu 30 notifiche non riconosciute`() = runBlocking {
        env.captureConfig.update { c ->
            c.copy(sources = c.sources + ("sms" to SourceConfig(true, 7, setOf("x.y"), setOf("Postepay"))),
                unrecognized = (1..40).map { UnrecognizedNotification(it.toLong(), "revolut", "testo $it\u001Eextra") })
        }
        val c = env.captureConfig.get()
        assertEquals(SourceConfig(true, 7, setOf("x.y"), setOf("Postepay")), c.of(CaptureSource.SMS))
        assertFalse(c.of(CaptureSource.REVOLUT).enabled)
        assertEquals(30, c.unrecognized.size); assertEquals(40L, c.unrecognized.first().atMs)
        assertEquals("testo 40 extra", c.unrecognized.first().text)
    }

    @Test fun `le regole di notifiche ignorate si salvano, si rileggono e viaggiano nel backup`() = runBlocking {
        env.captureConfig.update { c -> c.copy(ignoreRules = listOf(IgnoreRule("revolut", "il tuo codice di verifica e", 5L, false))) }
        assertEquals(listOf(IgnoreRule("revolut", "il tuo codice di verifica e", 5L, false)), env.captureConfig.get().ignoreRules)
        val json = env.backup.buildBackupJson()
        assertTrue(json.contains("\"ignore\""))
        env.captureConfig.update { it.copy(ignoreRules = emptyList()) }
        env.restore(LegacyBackupCodec.parse(json), it.spese.domain.repository.RestoreMode.Replace)
        assertEquals(1, env.captureConfig.get().ignoreRules.size)
        assertFalse(env.captureConfig.get().ignoreRules[0].enabled)
    }
}
