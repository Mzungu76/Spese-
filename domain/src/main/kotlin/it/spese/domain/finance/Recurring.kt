package it.spese.domain.finance

import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class RecurringItem(
    val merchant: String,
    val count: Int,
    val mean: BigDecimal,
    val last: LocalDate,
    val next: LocalDate,
    val categoryId: Long?,
) {
    val key: String get() = "rec:$merchant"
}

/**
 * Rilevamento automatico delle ricorrenze mensili (porta fedele della funzione `recurring` della versione HTML):
 * stesso esercente, almeno 3 addebiti in 3 mesi diversi, intervallo mediano tra 24 e 40 giorni,
 * importi simili (coefficiente di variazione <= 25%).
 */
object RecurringDetector {
    /** Categorie escluse dal rilevamento (come nella versione HTML): prelievi e commissioni non sono ricorrenze. */
    private val EXCLUDED_NAMES = setOf("Contanti", "Commissioni e bolli")

    fun defaultExclusions(categories: List<it.spese.core.model.Category>): Set<Long?> =
        categories.filter { it.name in EXCLUDED_NAMES }.map { it.id }.toSet()

    fun detect(list: List<Transaction>, excludedCategoryIds: Set<Long?> = emptySet()): List<RecurringItem> {
        val groups = list.filter { it.kind == TxKind.SPESA && it.amount.signum() < 0 && it.categoryId !in excludedCategoryIds }
            .groupBy { it.merchant }
        val out = ArrayList<RecurringItem>()
        for ((m, raw) in groups) {
            if (raw.size < 3) continue
            val a = raw.sortedBy { it.bookingDate }
            if (a.map { YearMonth.from(it.bookingDate) }.toSet().size < 3) continue
            val gaps = (1 until a.size).map { ChronoUnit.DAYS.between(a[it - 1].bookingDate, a[it].bookingDate) }.sorted()
            val med = gaps[gaps.size shr 1]
            if (med < 24 || med > 40) continue
            val am = a.map { it.amount.negate() }
            val mean = am.fold(BigDecimal.ZERO) { s, v -> s + v }.divide(BigDecimal(am.size), 10, RoundingMode.HALF_UP)
            val variance = am.fold(BigDecimal.ZERO) { s, v -> s + (v - mean).pow(2) }.divide(BigDecimal(am.size), 10, RoundingMode.HALF_UP)
            // sd/mean > 0.25  <=>  var > 0.0625 * mean^2
            if (variance > mean.pow(2).multiply(BigDecimal("0.0625"))) continue
            val last = a.last()
            out.add(RecurringItem(m, a.size, mean.setScale(2, RoundingMode.HALF_UP), last.bookingDate, last.bookingDate.plusDays(med), last.categoryId))
        }
        return out.sortedBy { it.next }
    }
}
