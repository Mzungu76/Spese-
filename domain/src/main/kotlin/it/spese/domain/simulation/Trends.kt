package it.spese.domain.simulation

import it.spese.core.common.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Analisi delle tendenze e previsioni statistiche semplici (regressione lineare ai minimi quadrati, medie mobili). */
object Trends {
    data class Linear(val slope: BigDecimal, val intercept: BigDecimal) {
        fun at(x: Int): BigDecimal = intercept + slope.multiply(BigDecimal(x))
    }

    fun linear(values: List<BigDecimal>): Linear? {
        val n = values.size
        if (n < 2) return null
        val nn = BigDecimal(n)
        var sx = BigDecimal.ZERO; var sy = BigDecimal.ZERO; var sxy = BigDecimal.ZERO; var sxx = BigDecimal.ZERO
        values.forEachIndexed { i, y ->
            val x = BigDecimal(i)
            sx += x; sy += y; sxy += x.multiply(y); sxx += x.multiply(x)
        }
        val den = nn.multiply(sxx) - sx.multiply(sx)
        if (den.signum() == 0) return null
        val slope = (nn.multiply(sxy) - sx.multiply(sy)).divide(den, 10, RoundingMode.HALF_UP)
        val intercept = (sy - slope.multiply(sx)).divide(nn, 10, RoundingMode.HALF_UP)
        return Linear(slope, intercept)
    }

    /** Previsione dei prossimi `ahead` punti; i valori negativi sono portati a zero (per serie di spesa/entrata). */
    fun forecast(values: List<BigDecimal>, ahead: Int, floorAtZero: Boolean = true): List<BigDecimal> {
        val l = linear(values) ?: return List(ahead) { values.lastOrNull() ?: BigDecimal.ZERO }
        return (1..ahead).map { k -> l.at(values.size - 1 + k).let { v -> if (floorAtZero) v.max(BigDecimal.ZERO) else v }.setScale(2, RoundingMode.HALF_UP) }
    }

    fun movingAverage(values: List<BigDecimal>, window: Int = 3): List<BigDecimal> =
        values.indices.map { i ->
            val from = maxOf(0, i - window + 1)
            val slice = values.subList(from, i + 1)
            slice.fold(BigDecimal.ZERO) { a, b -> a + b }.divide(BigDecimal(slice.size), 2, RoundingMode.HALF_UP)
        }

    /** Variazione percentuale tra primo e secondo periodo (null se il primo è 0). */
    fun changePercent(before: BigDecimal, after: BigDecimal): BigDecimal? =
        if (before.signum() == 0) null else (after - before).multiply(Money.HUNDRED).divide(before.abs(), 1, RoundingMode.HALF_UP)

    data class CategoryDelta(val categoryId: Long?, val before: BigDecimal, val after: BigDecimal) {
        val delta: BigDecimal get() = after - before
        val percent: BigDecimal? get() = changePercent(before, after)
    }

    /** Analisi delle variazioni di spesa per categoria tra due periodi. */
    fun categoryVariation(before: Map<Long?, BigDecimal>, after: Map<Long?, BigDecimal>): List<CategoryDelta> =
        (before.keys + after.keys).map { CategoryDelta(it, before[it] ?: BigDecimal.ZERO, after[it] ?: BigDecimal.ZERO) }
            .sortedByDescending { it.delta.abs() }
}
