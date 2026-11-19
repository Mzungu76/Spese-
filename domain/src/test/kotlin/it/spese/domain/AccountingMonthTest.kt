package it.spese.domain

import it.spese.core.common.MonthCalendar
import it.spese.core.model.*
import it.spese.domain.backup.*
import it.spese.domain.finance.AccountingMonth
import it.spese.domain.finance.ReviewEngine
import it.spese.domain.finance.ReviewReason
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class AccountingMonthTest {
    private fun d(s: String) = LocalDate.parse(s)
    // mese che finisce il 20: va dal 21 al 20
    private val cal = MonthCalendar(21)
    private var n = 0L
    private fun income(date: String, month: YearMonth? = null, reviewed: Boolean = false, kind: TxKind = TxKind.ENTRATA) =
        Transaction(++n, "i$n", 1L, d(date), d(date), "Stipendio", BigDecimal("1500"), kind, null, reviewed = reviewed, accountingMonth = month)

    @Test fun `senza scelta la data resta quella reale`() {
        val t = income("2026-11-16")
        assertEquals(d("2026-11-16"), AccountingMonth.effectiveDate(t, cal))
        assertSame(t, AccountingMonth.shift(listOf(t), cal).single())
    }

    @Test fun `entrata del 16 spostata nel mese successivo conta dal suo primo giorno`() {
        val cur = cal.monthOf(d("2026-11-16"))               // 21/10 - 20/11
        assertEquals(YearMonth.of(2026, 10), cur)
        val t = income("2026-11-16", month = cur.plusMonths(1))
        val shifted = AccountingMonth.shift(listOf(t), cal).single()
        assertEquals(d("2026-11-21"), shifted.bookingDate)
        assertEquals(YearMonth.of(2026, 11), cal.monthOf(shifted.bookingDate))
        assertEquals(t.amount, shifted.amount)
    }

    @Test fun `scegliere il mese della data non sposta nulla e le spese non si spostano mai`() {
        val cur = cal.monthOf(d("2026-11-16"))
        assertEquals(d("2026-11-16"), AccountingMonth.effectiveDate(income("2026-11-16", month = cur), cal))
        val spesa = income("2026-11-16", month = cur.plusMonths(1), kind = TxKind.SPESA)
        assertEquals(d("2026-11-16"), AccountingMonth.shift(listOf(spesa), cal).single().bookingDate)
    }

    @Test fun `le scelte sono il mese della data e il successivo`() {
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), AccountingMonth.choices(d("2026-11-16"), cal))
    }

    @Test fun `si chiede solo per le entrate degli ultimi giorni non ancora scelte`() {
        assertTrue(AccountingMonth.needsChoice(income("2026-11-16"), cal, 7))        // 14-20 novembre
        assertFalse(AccountingMonth.needsChoice(income("2026-11-10"), cal, 7))
        assertFalse(AccountingMonth.needsChoice(income("2026-11-16"), cal, 0))
        assertFalse(AccountingMonth.needsChoice(income("2026-11-16", month = YearMonth.of(2026, 11)), cal, 7))
        assertFalse(AccountingMonth.needsChoice(income("2026-11-16", kind = TxKind.SPESA), cal, 7))
        assertFalse(AccountingMonth.needsChoice(income("2026-11-16", reviewed = true), cal, 7))
    }

    @Test fun `la Verifica propone la scelta e la scelta la fa sparire`() {
        val t = income("2026-11-16")
        val items = ReviewEngine.items(listOf(t), emptyMap(), emptyList(), emptyList(), calendar = cal, askDays = 7)
        assertEquals(listOf(ReviewReason.INCOME_MONTH), items.single().notes.map { it.reason })
        assertTrue(ReviewEngine.items(listOf(t.copy(accountingMonth = YearMonth.of(2026, 11))), emptyMap(), emptyList(), emptyList(), calendar = cal, askDays = 7).isEmpty())
        // con la richiesta spenta non compare nulla
        assertTrue(ReviewEngine.items(listOf(t), emptyMap(), emptyList(), emptyList(), calendar = cal, askDays = 0).isEmpty())
    }

    @Test fun `il backup conserva i mesi scelti`() {
        val extras = NativeExtras(accountingMonths = listOf(NativeAccountingMonth("i1", "2026-11")))
        val state = LegacyState(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), LegacyGoal(GoalMode.MONTHLY, BigDecimal(300), 12, null), emptyMap(), emptyMap())
        val json = LegacyBackupCodec.encode(BackupEnvelope("2026-03-15", "t", state, extras))
        assertEquals(extras.accountingMonths, LegacyBackupCodec.parse(json).extras!!.accountingMonths)
    }
}
