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

class PosteRoutingTest {
    @org.junit.Test fun `qualunque app di Poste viene riconosciuta come fonte`() {
        val cfg = CaptureConfig(mapOf("poste" to SourceConfig(enabled = true)))
        for (pkg in listOf("posteitaliane.posteapp.apppostepay", "posteitaliane.posteapp.appbpol", "posteitaliane.posteapp.appposteid", "posteitaliane.nuova.app"))
            org.junit.Assert.assertEquals(pkg, CaptureSource.POSTE, CaptureRouting.route(cfg, pkg, "Poste Italiane"))
        org.junit.Assert.assertNull(CaptureRouting.route(cfg, "com.altra.app", "Poste Italiane"))
        org.junit.Assert.assertNull(CaptureRouting.route(CaptureConfig(), "posteitaliane.posteapp.apppostepay", "x"))   // fonte spenta
    }
}

class PosteNotificationTest {
    private fun p(title: String, text: String) = NotificationParser.parse(CaptureSource.POSTE, title, text)

    @org.junit.Test fun `movimento in uscita e in entrata di Poste vengono riconosciuti`() {
        val out = p("Poste Italiane", "Hai un movimento in uscita su POSTEPAY EVOLUTION di 12,50 euro")!!
        org.junit.Assert.assertEquals(0, java.math.BigDecimal("-12.50").compareTo(out.amount))
        val out2 = p("Hai un movimento in uscita su PostePay", "Importo: 45,00 € - Pagamento carta presso COOP SPA")!!
        org.junit.Assert.assertEquals(0, java.math.BigDecimal("-45").compareTo(out2.amount)); org.junit.Assert.assertEquals("COOP SPA", out2.description)
        val inn = p("Poste Italiane", "Hai un movimento in entrata su POSTEPAY EVOLUTION di 100,00 euro")!!
        org.junit.Assert.assertEquals(0, java.math.BigDecimal("100").compareTo(inn.amount))
    }

    @org.junit.Test fun `senza importo o con saldo non inventa nulla`() {
        org.junit.Assert.assertNull(p("Poste Italiane", "Hai un movimento in uscita su POSTEPAY EVOLUTION"))
        org.junit.Assert.assertNull(p("Poste Italiane", "Saldo disponibile 1.234,50 euro"))
    }
}

class ReceiptReconcileTest {
    private fun tx(id: Long, origin: it.spese.core.model.TxOrigin, amount: String, date: String) = it.spese.core.model.Transaction(
        id, "e$id", 1, java.time.LocalDate.parse(date), java.time.LocalDate.parse(date), "x", java.math.BigDecimal(amount), it.spese.core.model.TxKind.SPESA, null, false, null, origin = origin)

    @org.junit.Test fun `lo scontrino scansionato e sostituito dalla riga dell estratto conto entro 5 giorni`() {
        val rows = listOf(NotificationReconciler.Row(0, java.math.BigDecimal("-12.00"), java.time.LocalDate.parse("2026-11-18")))
        val m = NotificationReconciler.match(listOf(tx(1, it.spese.core.model.TxOrigin.OCR, "-12.00", "2026-11-14")), rows)
        org.junit.Assert.assertEquals(1L, m[0]?.id)
        // oltre la finestra, o con importo diverso, resta separato
        org.junit.Assert.assertTrue(NotificationReconciler.match(listOf(tx(1, it.spese.core.model.TxOrigin.OCR, "-12.00", "2026-11-10")), rows).isEmpty())
        org.junit.Assert.assertTrue(NotificationReconciler.match(listOf(tx(1, it.spese.core.model.TxOrigin.OCR, "-12.50", "2026-11-17")), rows).isEmpty())
    }

    @org.junit.Test fun `suggerimenti e etichette delle sottocategorie`() {
        org.junit.Assert.assertTrue("Frutta e verdura" in it.spese.domain.finance.SubcategoryTemplates.suggest("Supermercato e alimentari"))
        org.junit.Assert.assertTrue(it.spese.domain.finance.SubcategoryTemplates.suggest("Zzz").isEmpty())
    }
}

class PendingParseTest {
    @org.junit.Test fun `riconosce la direzione senza importo solo per le frasi esplicite`() {
        org.junit.Assert.assertEquals(-1, NotificationParser.parseWithoutAmount(CaptureSource.POSTE, "Poste Italiane", "Hai un movimento in uscita su POSTEPAY"))
        org.junit.Assert.assertEquals(1, NotificationParser.parseWithoutAmount(CaptureSource.POSTE, "Poste Italiane", "Hai un movimento in entrata su POSTEPAY"))
        org.junit.Assert.assertNull(NotificationParser.parseWithoutAmount(CaptureSource.POSTE, "Poste Italiane", "Scopri le nuove offerte"))
        org.junit.Assert.assertNull(NotificationParser.parseWithoutAmount(CaptureSource.POSTE, "Poste Italiane", "Hai un movimento in uscita di 12,50 euro"))   // ha l'importo: lo gestisce il parser normale
        org.junit.Assert.assertEquals(-1, NotificationParser.parseWithoutAmount(CaptureSource.WALLET, "x", "movimento in uscita"))
    }
}


class AmountlessBroadTest {
    @org.junit.Test fun `con la registrazione estesa ogni notifica senza importo delle fonti scelte diventa da regolarizzare`() {
        // direzione chiara dalle parole, altrimenti 0 (la sceglie l'utente)
        org.junit.Assert.assertEquals(-1, NotificationParser.parseWithoutAmount(CaptureSource.REVOLUT, "Revolut", "Pagamento effettuato con la carta presso BAR ROMA", broad = true))
        org.junit.Assert.assertEquals(1, NotificationParser.parseWithoutAmount(CaptureSource.REVOLUT, "Revolut", "Hai ricevuto un bonifico da Mario", broad = true))
        org.junit.Assert.assertEquals(0, NotificationParser.parseWithoutAmount(CaptureSource.POSTE, "Poste Italiane", "Nuova comunicazione disponibile", broad = true))
        org.junit.Assert.assertEquals(-1, NotificationParser.parseWithoutAmount(CaptureSource.WALLET, "Google Wallet", "Pagamento completato", broad = true))
        // senza la modalità estesa restano solo le frasi esplicite
        org.junit.Assert.assertNull(NotificationParser.parseWithoutAmount(CaptureSource.POSTE, "Poste Italiane", "Nuova comunicazione disponibile"))
        // con un importo la gestisce il parser normale; testo vuoto: niente
        org.junit.Assert.assertNull(NotificationParser.parseWithoutAmount(CaptureSource.REVOLUT, "Revolut", "Pagamento di 12,50 €", broad = true))
        org.junit.Assert.assertNull(NotificationParser.parseWithoutAmount(CaptureSource.REVOLUT, "", " ", broad = true))
    }

    @org.junit.Test fun `un promemoria con direzione non chiara si chiude con qualunque pagamento vicino e con una sola riga dell estratto`() {
        val p = PendingAmount(1_000_000L, "poste", null, 0, "Notifica Poste da verificare", "x")
        org.junit.Assert.assertTrue(PendingMatcher.coveredByNotification(p, -1, 1_000_000L + 60_000)); org.junit.Assert.assertTrue(PendingMatcher.coveredByNotification(p, 1, 1_000_000L + 60_000))
        org.junit.Assert.assertFalse(PendingMatcher.coveredByNotification(p, -1, 1_000_000L + 3_600_000))
    }
}
