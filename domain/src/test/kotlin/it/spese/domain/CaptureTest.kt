package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.capture.*
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Gli esempi di testo sono sintetici (scritti sul modello delle notifiche bancarie italiane), non copiati da notifiche reali. */
class CaptureTest {
    private fun p(s: CaptureSource, title: String, text: String) = NotificationParser.parse(s, title, text)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun `Google Wallet, titolo con l'esercente e importo nel testo`() {
        val r = p(CaptureSource.WALLET, "ESSELUNGA", "12,50 €")!!
        eq("-12.50", r.amount); assertEquals("ESSELUNGA", r.description)
        val r2 = p(CaptureSource.WALLET, "Google Wallet", "Hai pagato 8,00 € a BAR ROMA con la carta Revolut •• 1234")!!
        eq("-8", r2.amount); assertEquals("BAR ROMA", r2.description)
    }

    @Test fun `app della banca, uscite ed entrate`() {
        val a = p(CaptureSource.REVOLUT, "Revolut", "Pagamento di 23,90 € presso AMAZON IT il 14/11")!!
        eq("-23.90", a.amount); assertEquals("AMAZON IT", a.description)
        val b = p(CaptureSource.REVOLUT, "Revolut", "Hai ricevuto 150,00 € da MARIO ROSSI")!!
        eq("150", b.amount); assertEquals("MARIO ROSSI", b.description)
        val c = p(CaptureSource.HYPE, "HYPE", "Bonifico in entrata di 1.250,00 € da DAG COSTRUZIONI SRL")!!
        eq("1250", c.amount); assertEquals("DAG COSTRUZIONI SRL", c.description)
    }

    @Test fun `SMS della banca e saldo disponibile ignorato`() {
        val r = p(CaptureSource.SMS, "Postepay", "Pagamento carta Postepay *1234 di 45,00 EUR presso COOP SPA 14/11 12:30. Saldo disp: 312,20 EUR")!!
        eq("-45", r.amount); assertEquals("COOP SPA", r.description)
        assertNull(p(CaptureSource.SMS, "Postepay", "Saldo disponibile 1.234,50 €"))
    }

    @Test fun `il segno esplicito basta quando le parole non dicono la direzione`() {
        val r = p(CaptureSource.REVOLUT, "Esselunga", "−12,50 €")!!
        eq("-12.50", r.amount); assertEquals("Esselunga", r.description)
        eq("20", p(CaptureSource.HYPE, "Mario Rossi", "+20,00 €")!!.amount)
    }

    @Test fun `nel dubbio non indovina - testo ambiguo, direzione ignota, valuta estera, nessun importo`() {
        assertNull(p(CaptureSource.REVOLUT, "Revolut", "Pagamento ricevuto 10,00 €"))     // sia uscita sia entrata
        assertNull(p(CaptureSource.REVOLUT, "Revolut", "Nuova operazione di 5,00 €"))    // direzione ignota
        assertNull(p(CaptureSource.REVOLUT, "Revolut", "Hai pagato 12,00 USD a AMAZON"))  // non e euro
        assertNull(p(CaptureSource.REVOLUT, "Revolut", "Hai una nuova offerta per te"))
        assertNull(p(CaptureSource.REVOLUT, "Revolut", "Pagamento di 0,00 € presso X"))
    }

    @Test fun `importi con migliaia e decimali puntati`() {
        eq("-1234.5", p(CaptureSource.REVOLUT, "Revolut", "Pagamento di 1.234,50 € presso IKEA")!!.amount)
        eq("-12.5", p(CaptureSource.REVOLUT, "Revolut", "Pagamento di 12.50 € presso IKEA")!!.amount)
        eq("-1000", p(CaptureSource.REVOLUT, "Revolut", "Hai pagato € 1.000 a IMMOBILIARE ROSSI")!!.amount)
    }

    @Test fun `instradamento solo di fonti attive, pacchetti noti e mittenti SMS scelti`() {
        val cfg = CaptureConfig(mapOf(
            "revolut" to SourceConfig(enabled = true), "wallet" to SourceConfig(enabled = false),
            "hype" to SourceConfig(enabled = true, extraPackages = setOf("it.example.hype")),
            "sms" to SourceConfig(enabled = true, smsSenders = setOf("Postepay", "HYPE")),
        ))
        assertEquals(CaptureSource.REVOLUT, CaptureRouting.route(cfg, "com.revolut.revolut", "Revolut"))
        assertNull(CaptureRouting.route(cfg, "com.google.android.apps.walletnfcrel", "COOP"))                 // fonte spenta
        assertEquals(CaptureSource.HYPE, CaptureRouting.route(cfg, "it.example.hype", "x"))                   // pacchetto aggiunto
        assertEquals(CaptureSource.SMS, CaptureRouting.route(cfg, "com.google.android.apps.messaging", "POSTEPAY"))
        assertNull(CaptureRouting.route(cfg, "com.google.android.apps.messaging", "Mamma"))                   // altro mittente: non si legge
        assertNull(CaptureRouting.route(cfg, "com.whatsapp", "Postepay"))                                    // altra app
        assertNull(CaptureRouting.route(CaptureConfig(mapOf("sms" to SourceConfig(enabled = true))), "com.google.android.apps.messaging", "Postepay")) // nessun mittente scelto
    }

    private fun prov(id: Long, amount: Int, date: String, origin: TxOrigin = TxOrigin.NOTIFICATION) =
        Transaction(id, "e$id", 1, LocalDate.parse(date), LocalDate.parse(date), "x", BigDecimal(amount), TxKind.SPESA, null, origin = origin)

    @Test fun `abbinamento con l'estratto conto - stesso importo, data vicina, uno a uno`() {
        val provisional = listOf(prov(1, -12, "2026-11-10"), prov(2, -12, "2026-11-13"), prov(3, -50, "2026-11-01"), prov(4, -9, "2026-11-10", TxOrigin.MANUAL))
        val rows = listOf(NotificationReconciler.Row(0, BigDecimal(-12), LocalDate.parse("2026-11-11")), NotificationReconciler.Row(1, BigDecimal(-12), LocalDate.parse("2026-11-14")),
            NotificationReconciler.Row(2, BigDecimal(-50), LocalDate.parse("2026-11-20")), NotificationReconciler.Row(3, BigDecimal(-9), LocalDate.parse("2026-11-10")))
        val m = NotificationReconciler.match(provisional, rows)
        assertEquals(1L, m.getValue(0).id); assertEquals(2L, m.getValue(1).id)   // ciascuna col suo più vicino
        assertFalse(2 in m)                                                       // 50 € a 19 giorni di distanza: non è lo stesso
        assertFalse(3 in m)                                                       // un movimento inserito a mano non viene mai sostituito
    }
}
