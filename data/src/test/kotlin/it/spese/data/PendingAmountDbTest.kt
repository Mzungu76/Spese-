package it.spese.data

import it.spese.core.model.TxOrigin
import it.spese.domain.capture.*
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
class PendingAmountDbTest {
    private lateinit var env: TestEnv
    @Before fun setUp() { env = TestEnv(); runBlocking { env.seeder.ensureSeeded(); env.captureConfig.update { it.copy(pending = emptyList()) } } }
    @After fun tearDown() = env.close()

    private fun at(date: String, h: Int = 12, m: Int = 0) = LocalDate.parse(date).atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun raw(pkg: String, title: String, text: String, t: Long) = RawNotification(pkg, title, text, t)
    private val poste = "posteitaliane.posteapp.apppostepay"

    @Test fun `una notifica Poste senza importo diventa un promemoria`() = runBlocking {
        val r = env.capture(CaptureSource.POSTE, null, raw(poste, "Poste Italiane", "Hai un movimento in uscita su POSTEPAY EVOLUTION", at("2024-04-02")), ZoneOffset.UTC)
        assertTrue(r is CaptureResult.NeedsAmount)
        val p = (r as CaptureResult.NeedsAmount).pending
        assertEquals(-1, p.sign); assertEquals("Movimento Poste / Postepay in uscita", p.description)
        assertTrue(env.tx.getAll().isEmpty())
        // si salva e si rilegge
        env.captureConfig.update { it.copy(pending = listOf(p)) }
        assertEquals(listOf(p.copy(accountId = null)), env.captureConfig.get().pending)
    }

    @Test fun `se Wallet ha gia notificato il pagamento con importo non serve chiedere nulla`() = runBlocking {
        env.capture(CaptureSource.WALLET, null, raw("com.google.android.apps.walletnfcrel", "BAR ROMA", "3,00 €", at("2024-04-02", 12, 0)), ZoneOffset.UTC)
        val r = env.capture(CaptureSource.POSTE, null, raw(poste, "Poste Italiane", "Hai un movimento in uscita su POSTEPAY", at("2024-04-02", 12, 2)), ZoneOffset.UTC)
        assertEquals(CaptureResult.Duplicate, r)
        // un'entrata non è coperta da un pagamento
        assertTrue(env.capture(CaptureSource.POSTE, null, raw(poste, "Poste Italiane", "Hai un movimento in entrata su POSTEPAY", at("2024-04-02", 12, 3)), ZoneOffset.UTC) is CaptureResult.NeedsAmount)
    }

    @Test fun `il pagamento di Wallet che arriva dopo chiude il promemoria`() {
        val p = PendingAmount(at("2024-04-02", 12, 0), "poste", null, -1, "x", "y")
        assertTrue(PendingMatcher.coveredByNotification(p, -1, at("2024-04-02", 12, 5)))
        assertFalse(PendingMatcher.coveredByNotification(p, 1, at("2024-04-02", 12, 5)))
        assertFalse(PendingMatcher.coveredByNotification(p, -1, at("2024-04-02", 13, 0)))
    }

    @Test fun `inserendo l importo nasce un movimento provvisorio e il promemoria sparisce`() = runBlocking {
        val r = env.capture(CaptureSource.POSTE, null, raw(poste, "Poste Italiane", "Hai un movimento in uscita su POSTEPAY", at("2024-04-02")), ZoneOffset.UTC) as CaptureResult.NeedsAmount
        env.captureConfig.update { it.copy(pending = listOf(r.pending)) }
        val complete = CompletePendingAmountUseCase(SaveTransactionUseCase(env.tx, env.accounts, env.cats, env.rules), env.accounts, env.captureConfig)
        complete(r.pending.id, BigDecimal("23.40"), "COOP", null, null, ZoneOffset.UTC)
        val t = env.tx.getAll().single()
        assertEquals(0, BigDecimal("-23.40").compareTo(t.amount)); assertEquals(TxOrigin.NOTIFICATION, t.origin); assertEquals(LocalDate.parse("2024-04-02"), t.bookingDate)
        assertTrue(env.captureConfig.get().pending.isEmpty())
    }

    @Test fun `l estratto conto chiude il promemoria se c e una sola riga compatibile`() = runBlocking {
        val p = PendingAmount(at("2024-04-02"), "poste", null, -1, "Movimento Poste in uscita", "")
        val other = PendingAmount(at("2024-06-10"), "poste", null, -1, "Movimento Poste in uscita", "")   // nessuna riga quel giorno
        env.captureConfig.update { it.copy(pending = listOf(p, other)) }
        val t = ReadStatementUseCase().invoke(env.resource("revolut.csv"), "revolut.csv").first()
        env.commit(t, t.detected!!.headerRow, t.detected!!.mapping, "Revolut")
        val n = ResolvePendingAmountsUseCase(env.tx, env.captureConfig).invoke()
        assertEquals(1, n)
        assertEquals(listOf(other.id), env.captureConfig.get().pending.map { it.id })
    }

    @Test fun `una notifica generica senza importo diventa un promemoria da verificare, e si completa scegliendo la direzione`() = runBlocking {
        val r = env.capture(CaptureSource.POSTE, null, raw(poste, "Poste Italiane", "Nuova comunicazione disponibile", at("2024-04-02")), ZoneOffset.UTC) as CaptureResult.NeedsAmount
        assertEquals(0, r.pending.sign); assertEquals("Notifica Poste / Postepay da verificare", r.pending.description)
        // con la registrazione estesa spenta non nasce nulla (finisce tra le non riconosciute)
        assertEquals(CaptureResult.Unrecognized, env.capture(CaptureSource.POSTE, null, raw(poste, "Poste Italiane", "Nuova comunicazione disponibile", at("2024-04-03")), ZoneOffset.UTC, noAmountToo = false))
        // si salva e si rilegge, anche con la direzione 0 e l'impostazione
        env.captureConfig.update { it.copy(pending = listOf(r.pending), captureNoAmount = false) }
        val c = env.captureConfig.get(); assertEquals(0, c.pending.single().sign); assertFalse(c.captureNoAmount)
        val complete = CompletePendingAmountUseCase(SaveTransactionUseCase(env.tx, env.accounts, env.cats, env.rules), env.accounts, env.captureConfig)
        try { complete(r.pending.id, BigDecimal("9.90"), "", null, null, ZoneOffset.UTC); fail("senza direzione non si può completare") } catch (e: IllegalStateException) { }
        complete(r.pending.id, BigDecimal("9.90"), "Canone", null, null, ZoneOffset.UTC, sign = -1)
        assertEquals(0, BigDecimal("-9.90").compareTo(env.tx.getAll().single().amount)); assertTrue(env.captureConfig.get().pending.isEmpty())
        env.captureConfig.update { it.copy(captureNoAmount = true) }
    }
}
