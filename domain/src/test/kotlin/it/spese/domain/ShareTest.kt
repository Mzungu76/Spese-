package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.repository.AppData
import it.spese.domain.sharing.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ShareTest {
    private fun d(s: String) = LocalDate.parse(s)
    private var n = 0L
    private fun tx(date: String, amount: Int) = Transaction(++n, "e$n", 1, d(date), d(date), "x", BigDecimal(amount), if (amount < 0) TxKind.SPESA else TxKind.ENTRATA, null)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    /** Conto «gestito da altri»: i suoi movimenti non sono in `transactions`, ma in `everything`. */
    private fun managed(l: List<Transaction>) = AppData(transactions = emptyList(), accounts = listOf(Account(1, "Carta di famiglia")), loaded = true, managedAccountIds = setOf(1L), allTransactions = l)
    private fun history(): MutableList<Transaction> {
        val l = ArrayList<Transaction>()
        for (m in 7..10) { listOf(1, 5, 10, 15, 20, 25).forEach { day -> l.add(tx("2026-%02d-%02d".format(m, day), if (day == 1) -500 else -100)) }; l.add(tx("2026-%02d-27".format(m), 2000)) }
        return l
    }

    @Test fun `il budget del conto condiviso viene dai suoi movimenti anche se e gestito da altri`() {
        val l = history(); l.add(tx("2026-11-01", -500)); l.add(tx("2026-11-05", -300)); l.add(tx("2026-11-10", -200))
        val s = ShareSnapshotBuilder.build(managed(l), 1, ShareConfig(savingsMonthly = BigDecimal(300)), 1L, d("2026-11-10"))!!
        assertEquals("Carta di famiglia", s.title); assertEquals("MONTH", s.unit); assertEquals(30, s.days); assertEquals(9, s.dayIndex)
        eq("1700", s.limit); eq("1000", s.spentToDate); eq("1190.00", s.optimalToDate); eq("35.00", s.dailyRemaining); eq("56.67", s.dailyAverage)
        assertEquals(30, s.optimal.size); assertEquals(10, s.current.size)
    }

    @Test fun `un budget fissato a mano vale anche senza storico`() {
        val s = ShareSnapshotBuilder.build(managed(listOf(tx("2026-11-05", -300))), 1, ShareConfig(budgetMonthly = BigDecimal(900)), 1L, d("2026-11-10"))!!
        eq("900", s.limit); eq("300.00", s.optimalToDate); eq("30.00", s.dailyRemaining); eq("30.00", s.dailyAverage); assertEquals(30, s.optimal.size)
    }

    @Test fun `si pubblicano settimana, mese e trimestre, e il budget mensile si scala per periodo`() {
        val l = history(); l.add(tx("2026-11-05", -300))
        val all = ShareSnapshotBuilder.buildAll(managed(l), listOf(1L), ShareConfig(budgetMonthly = BigDecimal(900)), 1L, d("2026-11-10"))
        assertEquals(listOf("WEEK", "MONTH", "QUARTER"), all.map { it.unit })
        eq("900", all.first { it.unit == "MONTH" }.limit); eq("2700", all.first { it.unit == "QUARTER" }.limit)
        eq("207.69", all.first { it.unit == "WEEK" }.limit)                              // 900 × 12 / 52
        assertEquals(7, all.first { it.unit == "WEEK" }.days)
        // il messaggio con tutti i periodi si legge uguale; un riepilogo singolo di prima si legge come un periodo solo
        assertEquals(all, ShareCodec.decodeBundle(ShareCodec.encodeBundle(all)))
        assertEquals(listOf(all[1]), ShareCodec.decodeBundle(ShareCodec.encodeSnapshot(all[1])))
        assertTrue(ShareCodec.decodeBundle("rotto").isEmpty())
    }

    @Test fun `senza storico e senza budget il limite non e calcolabile, e un conto inesistente non da nulla`() {
        val s = ShareSnapshotBuilder.build(managed(listOf(tx("2026-11-05", -300))), 1, ShareConfig(), 1L, d("2026-11-10"))!!
        assertNull(s.limit); assertNull(s.dailyRemaining); assertTrue(s.optimal.isEmpty())
        assertNull(ShareSnapshotBuilder.build(managed(emptyList()), 99, ShareConfig(), 1L, d("2026-11-10")))
    }

    @Test fun `le spese rilevate diventano movimenti provvisori senza doppioni`() {
        val at = java.time.LocalDateTime.of(2026, 11, 10, 12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val ev = listOf(ShareEvent("a1", BigDecimal("-12.50"), "COOP", at, "Google Wallet"), ShareEvent("a1", BigDecimal("-12.50"), "COOP", at), ShareEvent("b2", BigDecimal("30"), "  ", at), ShareEvent("c3", BigDecimal.ZERO, "x", at))
        val out = ShareEventImporter.toNewTransactions(ev, 7, ZoneId.of("UTC"))
        assertEquals(2, out.size)                                                               // il doppione e la cifra zero spariscono
        val spesa = out.first { it.externalId == "share|a1" }
        assertEquals(7L, spesa.accountId); assertEquals(TxKind.SPESA, spesa.kind); assertEquals(d("2026-11-10"), spesa.bookingDate); assertEquals(spesa.externalId, spesa.dedupKey); assertEquals(TxOrigin.NOTIFICATION, spesa.origin)
        val entrata = out.first { it.externalId == "share|b2" }
        assertEquals(TxKind.ENTRATA, entrata.kind); assertEquals("Spesa dal telefono condiviso", entrata.description)
    }

    @Test fun `la cifratura funziona solo con la chiave giusta e riconosce le manomissioni`() {
        val key = ShareCrypto.newKey(); val other = ShareCrypto.newKey()
        val t1 = ShareCrypto.encrypt("budget 35,00 € al giorno", key); val t2 = ShareCrypto.encrypt("budget 35,00 € al giorno", key)
        assertNotEquals(t1, t2)                                                                // ogni cifratura è diversa
        assertEquals("budget 35,00 € al giorno", ShareCrypto.decrypt(t1, key))
        assertNull(ShareCrypto.decrypt(t1, other))
        val tampered = t1.dropLast(2) + (if (t1.last() == 'A') "BB" else "AA")
        assertNull(ShareCrypto.decrypt(tampered, key)); assertNull(ShareCrypto.decrypt("non cifrato", key))
        assertArrayEquals(key, ShareCrypto.keyFromText(ShareCrypto.keyToText(key))); assertNull(ShareCrypto.keyFromText("troppo corta"))
    }

    @Test fun `snapshot ed eventi si salvano e si rileggono uguali`() {
        val s = ShareSnapshot(5L, "Carta", "MONTH", d("2026-11-01"), d("2026-11-30"), d("2026-11-10"), 30, 9, BigDecimal("1700.00"), BigDecimal("1000.00"), BigDecimal("1190.00"), BigDecimal("35.00"), BigDecimal("56.67"),
            listOf(BigDecimal("10.00"), BigDecimal("20.50")), listOf(BigDecimal("5.00")), listOf(BigDecimal("1.00"), BigDecimal("2.00")), ShareFeatures(captureNotifications = false, showChart = true), "ciao \"a\"")
        assertEquals(s, ShareCodec.decodeSnapshot(ShareCodec.encodeSnapshot(s)))
        val noLimit = s.copy(limit = null, optimalToDate = null, dailyRemaining = null, dailyAverage = null, optimal = emptyList())
        assertEquals(noLimit, ShareCodec.decodeSnapshot(ShareCodec.encodeSnapshot(noLimit)))
        val ev = listOf(ShareEvent("a", BigDecimal("-12.50"), "COOP \"x\"", 7L, "Wallet"), ShareEvent("b", BigDecimal("3"), "", 8L))
        assertEquals(ev, ShareCodec.decodeEvents(ShareCodec.encodeEvents(ev)))
        assertNull(ShareCodec.decodeSnapshot("rotto")); assertTrue(ShareCodec.decodeEvents("rotto").isEmpty())
    }
}
