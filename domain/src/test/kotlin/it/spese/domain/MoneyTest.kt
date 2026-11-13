package it.spese.domain

import it.spese.core.common.Money
import it.spese.core.common.NumberParser
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun `nessun errore di arrotondamento binario`() {
        // 0.1 + 0.2 con i double dà 0.30000000000000004: con BigDecimal è esatto
        assertEquals(BigDecimal("0.30"), Money.of("0.10") + Money.of("0.20"))
        val sum = (1..1000).fold(BigDecimal.ZERO) { a, _ -> a + Money.of("0.10") }
        assertEquals(BigDecimal("100.00"), sum)
    }

    @Test fun `conversione in centesimi e ritorno`() {
        assertEquals(123456L, Money.toCents(Money.of("1234.56")))
        assertEquals(-5L, Money.toCents(BigDecimal("-0.05")))
        assertEquals(BigDecimal("-12.34"), Money.fromCents(-1234))
        assertEquals(1L, Money.toCents(BigDecimal("0.005")))   // HALF_UP
    }

    @Test fun `formattazione come la versione HTML`() {
        assertEquals("1.234,56 €", Money.eur(BigDecimal("1234.56")))
        assertEquals("-1.234,56 €", Money.eur(BigDecimal("-1234.56")))
        assertEquals("0,00 €", Money.eur(BigDecimal.ZERO))
        assertEquals("1.235 €", Money.eur0(BigDecimal("1234.56")))
        assertEquals("619 €", Money.eur0(BigDecimal("618.99")))
        assertEquals("+12,50 €", Money.eurSigned(BigDecimal("12.5")))
        assertEquals("-1234,50", Money.csv(BigDecimal("-1234.5")))
    }

    @Test fun `percentuali`() {
        assertEquals(BigDecimal("33.3"), Money.percent(BigDecimal.ONE, BigDecimal(3)))
        assertEquals(BigDecimal.ZERO, Money.percent(BigDecimal.ONE, BigDecimal.ZERO))
    }

    @Test fun `parsing numeri italiani ed europei`() {
        fun p(s: String) = NumberParser.parse(s)
        assertEquals(BigDecimal("1234.56"), p("1.234,56"))
        assertEquals(BigDecimal("-12.5"), p("-12,5"))
        assertEquals(BigDecimal("1234.56"), p("1,234.56"))
        assertEquals(BigDecimal("1234567"), p("1.234.567"))
        assertEquals(BigDecimal("12.34"), p("12.34"))
        assertEquals(BigDecimal("-123.45"), p("123,45-"))
        assertEquals(BigDecimal("-123.45"), p("(123,45)"))
        assertEquals(BigDecimal("-12.00"), p("€ -12.00"))
        assertEquals(BigDecimal("-12.5"), p("−12,5"))
        assertEquals(BigDecimal("1500"), p("1 500"))
        assertNull(p("abc")); assertNull(p("")); assertNull(p("12a"))
    }
}
