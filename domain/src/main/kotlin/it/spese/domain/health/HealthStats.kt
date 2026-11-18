package it.spese.domain.health

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Statistiche robuste: la mediana e i quartili non si lasciano spostare da un mese eccezionale come fa la media. */
internal object HealthStats {
    fun percentile(values: List<BigDecimal>, p: Double): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val s = values.sorted()
        val idx = p * (s.size - 1)
        val lo = floor(idx).toInt(); val hi = ceil(idx).toInt()
        if (lo == hi) return s[lo].setScale(2, RoundingMode.HALF_UP)
        return (s[lo] + (s[hi] - s[lo]).multiply(BigDecimal(idx - lo))).setScale(2, RoundingMode.HALF_UP)
    }

    fun median(values: List<BigDecimal>): BigDecimal = percentile(values, 0.5)

    /** Coefficiente di variazione (deviazione standard / media) di valori positivi; serve solo a riconoscere la regolarità, non è un importo. */
    fun cv(values: List<BigDecimal>): Double {
        val d = values.map { it.toDouble() }
        if (d.size < 2) return 0.0
        val mean = d.average()
        if (mean <= 0.0) return Double.MAX_VALUE
        return sqrt(d.sumOf { (it - mean) * (it - mean) } / d.size) / mean
    }

    /** Interpolazione lineare a tratti tra punti (x, y) con x crescenti; fuori dai punti vale il valore dell'estremo. */
    fun piecewise(x: Double, vararg pts: Pair<Double, Double>): Double {
        if (x <= pts.first().first) return pts.first().second
        if (x >= pts.last().first) return pts.last().second
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            if (x <= x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return pts.last().second
    }

    fun ceil5(v: BigDecimal): BigDecimal = v.max(BigDecimal.ZERO).divide(BigDecimal(5), 0, RoundingMode.CEILING).multiply(BigDecimal(5))
}
