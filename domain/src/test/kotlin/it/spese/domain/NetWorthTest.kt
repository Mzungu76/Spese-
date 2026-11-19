package it.spese.domain

import it.spese.core.model.*
import it.spese.domain.networth.*
import it.spese.domain.repository.AppData
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class NetWorthTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals("atteso $expected, trovato $actual", 0, BigDecimal(expected).compareTo(actual))
    private val today = d("2026-03-10")
    private var n = 0L
    private fun tx(date: String, amount: Int, cat: Long, acc: Long = 1) = Transaction(++n, "e$n", acc, d(date), d(date), "x$n", BigDecimal(amount), if (amount > 0) TxKind.ENTRATA else TxKind.SPESA, cat)
    private fun cats(natures: Boolean) = listOf(
        Category(1, "Affitto", TxKind.SPESA, false, 0, nature = if (natures) SpendNature.FIXED else null),
        Category(2, "Svago", TxKind.SPESA, true, 0, nature = if (natures) SpendNature.DISCRETIONARY else null), Category(3, "Stipendio", TxKind.ENTRATA, false, 0),
    )
    private fun txs() = listOf("2025-12", "2026-01", "2026-02").flatMap { m -> listOf(tx("$m-01", 2000, 3), tx("$m-02", -800, 1), tx("$m-03", -300, 2)) } + tx("2026-03-05", -50, 2)
    private fun data(opening: Int = 2000, natures: Boolean = true, dues: List<Due> = emptyList()) = AppData(
        transactions = txs(), categories = cats(natures), dues = dues, loaded = true,
        accounts = listOf(Account(1, "Conto", AccountType.CONTO, BigDecimal(opening)), Account(2, "Fondo", AccountType.INVESTIMENTO, BigDecimal(5000))),
    )
    private val valuation = listOf(Valuation(2, d("2026-01-31"), BigDecimal(5500)))

    @Test fun `liquidità, investimenti e totale con il valore inserito dall'utente`() {
        val r = NetWorthEngine.compute(data(), emptyList(), valuation, today)!!
        eq("4650", r.liquidity); eq("5500", r.investments); eq("10150", r.total)
        assertEquals(d("2026-01-31"), r.accounts.first { it.isInvestment }.valuationDate)
    }

    @Test fun `senza valori inseriti l'investimento vale il saldo iniziale più i movimenti`() {
        eq("5000", NetWorthEngine.compute(data(), emptyList(), emptyList(), today)!!.investments)
        // movimenti dopo il valore inserito si sommano a quel valore
        val moved = data().copy(transactions = txs() + tx("2026-02-15", 400, 3, acc = 2))
        eq("5900", NetWorthEngine.compute(moved, emptyList(), valuation, today)!!.investments)
    }

    @Test fun `serie mensile dal mese del primo movimento e variazione`() {
        val r = NetWorthEngine.compute(data(), emptyList(), valuation, today)!!
        assertEquals(listOf(d("2025-12-31"), d("2026-01-31"), d("2026-02-28"), today), r.series.map { it.date })
        eq("7900", r.series.first().total)          // 2.900 + 5.000 (il valore inserito è di gennaio)
        eq("10200", r.series[2].total)              // 4.700 + 5.500
        eq("2250", r.change)
    }

    @Test fun `fondo di emergenza - mesi di spese essenziali coperti dalla liquidità`() {
        val f = NetWorthEngine.compute(data(), emptyList(), valuation, today)!!.fund
        eq("800", f.monthlyEssential); assertTrue(f.essentialKnown)
        eq("5.8", f.months); assertEquals(FundStatus.BUILDING, f.status)
        assertEquals(FundStatus.LOW, NetWorthEngine.compute(data(opening = -1500), emptyList(), valuation, today)!!.fund.status)
        assertEquals(FundStatus.OK, NetWorthEngine.compute(data(opening = 3000), emptyList(), valuation, today)!!.fund.status)
    }

    @Test fun `senza la natura delle categorie si usa la spesa totale e lo si dichiara`() {
        val f = NetWorthEngine.compute(data(natures = false), emptyList(), valuation, today)!!.fund
        assertFalse(f.essentialKnown); eq("1100", f.monthlyEssential)
    }

    @Test fun `margine investibile - oltre fondo, soldi degli obiettivi e scadenze grandi`() {
        val goals = listOf(Goal(1, "Viaggio", BigDecimal(2000), BigDecimal(500)), Goal(2, "Pausa", BigDecimal(900), BigDecimal(900), enabled = false))
        val dues = listOf(Due(1, "d1", "Bollo", BigDecimal(300), d("2026-05-01"), DueFrequency.YEARLY), Due(2, "d2", "Lontana", BigDecimal(999), d("2027-05-01"), DueFrequency.YEARLY), Due(3, "d3", "Mensile", BigDecimal(50), d("2026-03-20"), DueFrequency.MONTHLY))
        val none = NetWorthEngine.compute(data(dues = dues), goals, valuation, today)!!.margin
        eq("4800", none.emergencyTarget); eq("500", none.earmarked); eq("300", none.upcomingDues); eq("0", none.margin)       // 4.650 − 4.800 − 500 − 300 < 0
        val rich = NetWorthEngine.compute(data(opening = 10000, dues = dues), goals, valuation, today)!!.margin
        eq("12650", rich.liquidity); eq("7050", rich.margin)
    }

    @Test fun `conti archiviati esclusi, nessun conto nessun report`() {
        val d0 = data().copy(accounts = data().accounts.map { if (it.id == 2L) it.copy(archived = true) else it })
        eq("0", NetWorthEngine.compute(d0, emptyList(), valuation, today)!!.investments)
        assertNull(NetWorthEngine.compute(AppData(loaded = true), emptyList(), emptyList(), today))
    }

    @Test fun `salvataggio dei valori`() {
        val back = ValuationCodec.decode(ValuationCodec.encode(valuation + Valuation(3, d("2026-02-01"), BigDecimal("1234.56"))))
        assertEquals(2, back.size); eq("5500", back[0].value); assertEquals(d("2026-02-01"), back[1].date); eq("1234.56", back[1].value)
        assertTrue(ValuationCodec.decode("rotto").isEmpty())
    }
}

class ValuationBackupTest {
    @Test fun `i valori dei conti viaggiano nel backup, con il conto per nome`() {
        val state = it.spese.domain.backup.LegacyState(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(),
            it.spese.domain.backup.LegacyGoal(GoalMode.MONTHLY, BigDecimal(300), 12, null), emptyMap(), emptyMap())
        val vals = listOf(it.spese.domain.backup.NativeValuation("ETF Directa", LocalDate.parse("2026-02-28"), BigDecimal("12345.67")))
        val json = it.spese.domain.backup.LegacyBackupCodec.encode(it.spese.domain.backup.BackupEnvelope("2026-03-15", "t", state, it.spese.domain.backup.NativeExtras(valuations = vals)))
        val back = it.spese.domain.backup.LegacyBackupCodec.parse(json).extras!!.valuations
        assertEquals(1, back.size); assertEquals("ETF Directa", back[0].account); assertEquals(LocalDate.parse("2026-02-28"), back[0].date); assertEquals(0, BigDecimal("12345.67").compareTo(back[0].value))
    }
}
