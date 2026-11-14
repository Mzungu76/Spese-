package it.spese.core.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Tutti i calcoli monetari dell'applicazione usano [BigDecimal]. Gli importi sono normalizzati a 2 decimali (HALF_UP).
 * Nel database sono memorizzati come centesimi interi (Long) per evitare qualunque errore di arrotondamento.
 */
object Money {
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)
    val HUNDRED: BigDecimal = BigDecimal(100)

    fun scale(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    fun of(s: String): BigDecimal = scale(BigDecimal(s.trim()))

    fun of(n: Long): BigDecimal = scale(BigDecimal.valueOf(n))

    fun toCents(v: BigDecimal): Long = scale(v).movePointRight(2).longValueExact()

    fun fromCents(c: Long): BigDecimal = BigDecimal.valueOf(c, 2)

    /** Divisione sicura con scala e arrotondamento definiti. */
    fun div(a: BigDecimal, b: BigDecimal, scale: Int = 10): BigDecimal =
        if (b.signum() == 0) BigDecimal.ZERO else a.divide(b, scale, RoundingMode.HALF_UP)

    /** Percentuale `part / whole * 100` con 1 decimale (0 se whole = 0). */
    fun percent(part: BigDecimal, whole: BigDecimal, decimals: Int = 1): BigDecimal =
        if (whole.signum() == 0) BigDecimal.ZERO
        else part.multiply(HUNDRED).divide(whole, decimals, RoundingMode.HALF_UP)

    /** `value * pct / 100`. */
    fun pctOf(value: BigDecimal, pct: Number): BigDecimal =
        value.multiply(BigDecimal(pct.toString())).divide(HUNDRED, 10, RoundingMode.HALF_UP)

    private fun fmt(decimals: Int): DecimalFormat {
        val sym = DecimalFormatSymbols(Locale.ITALY).apply { groupingSeparator = '.'; decimalSeparator = ',' }
        return DecimalFormat(if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0", sym).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = true
        }
    }

    /** "-1.234,56 €" (come la versione HTML: segno meno prima della cifra, simbolo finale). */
    fun eur(v: BigDecimal): String = (if (v.signum() < 0) "-" else "") + fmt(2).format(v.abs()) + " €"

    /** Importo senza decimali, es. "1.235 €". */
    fun eur0(v: BigDecimal): String {
        val r = v.setScale(0, RoundingMode.HALF_UP)
        return (if (r.signum() < 0) "-" else "") + fmt(0).format(r.abs()) + " €"
    }

    /** Importo con segno esplicito per i positivi. */
    fun eurSigned(v: BigDecimal): String = (if (v.signum() > 0) "+" else "") + eur(v)

    /** Formato CSV italiano (virgola decimale, nessun separatore delle migliaia). */
    fun csv(v: BigDecimal): String = scale(v).toPlainString().replace('.', ',')
}

/** Parsing tollerante di numeri scritti dall'utente o presenti in estratti conto. */
/** Separatore decimale: AUTO ricava il formato dal testo; COMMA = 1.234,56; DOT = 1,234.56. */
enum class DecimalStyle { AUTO, COMMA, DOT }

object NumberParser {
    /** Come [parse] ma con il formato imposto dall'utente (utile quando l'automatico è ambiguo, es. "1.234"). */
    fun parse(raw: String?, style: DecimalStyle): BigDecimal? {
        if (style == DecimalStyle.AUTO || raw == null) return parse(raw)
        val cleaned = raw.trim().replace("€", "").replace(Regex("(?i)eur(o)?"), "").replace(Regex("[\\s\u00A0']"), "")
        val normalized = if (style == DecimalStyle.COMMA) cleaned.replace(".", "") else cleaned.replace(",", "")
        return parse(if (style == DecimalStyle.COMMA) normalized.replace(',', '.') else normalized)
    }

    /**
     * Gestisce formati italiani ed europei (1.234,56), anglosassoni (1,234.56), simboli di valuta,
     * segni finali ("123,45-"), parentesi contabili "(123,45)" e il segno meno tipografico.
     * Comportamento compatibile con la versione HTML: un solo punto senza virgole è un separatore decimale ("1.5").
     * Restituisce null se non è un numero.
     */
    fun parse(raw: String?): BigDecimal? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        s = s.replace('−', '-').replace('–', '-')
            .replace(Regex("(?i)eur(o)?"), "").replace("€", "")
            .replace(Regex("[\\s\\u00A0']"), "")
        if (s.endsWith("-")) { negative = !negative; s = s.dropLast(1) }
        if (s.endsWith("+")) s = s.dropLast(1)
        if (s.startsWith("+")) s = s.drop(1)
        if (s.isEmpty() || !Regex("^-?[0-9.,]+$").matches(s)) return null
        val lastComma = s.lastIndexOf(','); val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') // 1.234,56
                else s.replace(",", "")                                      // 1,234.56
            lastComma >= 0 ->
                if (s.count { it == ',' } > 1) s.replace(",", "")           // 1,234,567
                else s.replace(',', '.')                                    // 12,5
            lastDot >= 0 && s.count { it == '.' } > 1 -> s.replace(".", "") // 1.234.567
            else -> s
        }
        val v = s.toBigDecimalOrNull() ?: return null
        return if (negative) v.negate() else v
    }

    /** Come [parse] ma restituisce l'importo normalizzato a 2 decimali. */
    fun parseMoney(raw: String?): BigDecimal? = parse(raw)?.let { Money.scale(it) }
}
