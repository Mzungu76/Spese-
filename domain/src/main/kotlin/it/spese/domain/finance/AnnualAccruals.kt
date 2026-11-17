package it.spese.domain.finance

import it.spese.core.model.DueFrequency
import it.spese.core.model.Due
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Una spesa annuale (assicurazione, bollo…) con la quota da mettere da parte ogni mese. */
data class AccrualItem(
    val name: String,
    val amount: BigDecimal,
    val next: LocalDate,
    val daysLeft: Int,
    /** Quota mensile "a regime" (importo / 12). */
    val monthly: BigDecimal,
    /** Quanto dovresti aver già accantonato oggi per arrivare pronto alla scadenza. */
    val shouldHaveSaved: BigDecimal,
    /** true = scadenza inserita a mano; false = rilevata dallo storico (stesso esercente, ogni anno). */
    val manual: Boolean,
)

data class AccrualPlan(val items: List<AccrualItem>) {
    val monthlyTotal: BigDecimal get() = items.fold(BigDecimal.ZERO) { a, i -> a + i.monthly }
    val shouldHaveSavedTotal: BigDecimal get() = items.fold(BigDecimal.ZERO) { a, i -> a + i.shouldHaveSaved }

    /** Vero se un movimento di questo esercente è già "coperto" dall'accantonamento (non va contato due volte nel mese). */
    fun covers(merchant: String): Boolean {
        val m = merchant.lowercase()
        return items.any { val n = it.name.lowercase(); n.isNotBlank() && (m.contains(n) || n.contains(m)) }
    }
}

/**
 * Spese annuali: distribuite su 12 mesi, così non arrivano come una sorpresa nel mese in cui scadono.
 * Fonti: scadenze manuali "ogni anno" e addebiti rilevati dallo storico (stesso esercente, ~365 giorni tra uno e l'altro).
 */
object AnnualAccruals {
    private val MIN_AMOUNT = BigDecimal(30)
    private val DAYS_PER_MONTH = BigDecimal("30.44")

    fun detect(list: List<Transaction>, excluded: Set<Long?> = emptySet()): List<RecurringItem> {
        val out = ArrayList<RecurringItem>()
        val groups = list.filter { it.kind == TxKind.SPESA && it.amount.signum() < 0 && it.categoryId !in excluded }.groupBy { it.merchant }
        for ((m, raw) in groups) {
            if (raw.size < 2) continue
            val a = raw.sortedBy { it.bookingDate }
            val gaps = (1 until a.size).map { ChronoUnit.DAYS.between(a[it - 1].bookingDate, a[it].bookingDate) }
            if (gaps.any { it < 330 || it > 400 }) continue
            val am = a.map { it.amount.negate() }
            val mean = am.fold(BigDecimal.ZERO) { s, v -> s + v }.divide(BigDecimal(am.size), 10, RoundingMode.HALF_UP)
            if (mean < MIN_AMOUNT) continue
            val variance = am.fold(BigDecimal.ZERO) { s, v -> s + (v - mean).pow(2) }.divide(BigDecimal(am.size), 10, RoundingMode.HALF_UP)
            if (variance > mean.pow(2).multiply(BigDecimal("0.0625"))) continue
            val med = gaps.sorted()[gaps.size shr 1]
            val last = a.last()
            out.add(RecurringItem(m, a.size, mean.setScale(2, RoundingMode.HALF_UP), last.bookingDate, last.bookingDate.plusDays(med), last.categoryId))
        }
        return out
    }

    private fun item(name: String, amount: BigDecimal, next: LocalDate, today: LocalDate, manual: Boolean): AccrualItem {
        val days = ChronoUnit.DAYS.between(today, next).toInt().coerceAtLeast(0)
        val monthsLeft = BigDecimal(days).divide(DAYS_PER_MONTH, 0, RoundingMode.CEILING).min(BigDecimal(12))
        val saved = amount.multiply(BigDecimal(12) - monthsLeft).divide(BigDecimal(12), 2, RoundingMode.HALF_UP)
        return AccrualItem(name, amount, next, ChronoUnit.DAYS.between(today, next).toInt(), amount.divide(BigDecimal(12), 2, RoundingMode.HALF_UP), saved, manual)
    }

    fun plan(dues: List<Due>, tx: List<Transaction>, today: LocalDate, excluded: Set<Long?> = emptySet()): AccrualPlan {
        val manual = dues.filter { it.frequency == DueFrequency.YEARLY && it.amount.signum() > 0 }.map { item(it.name, it.amount, it.next, today, true) }
        val names = manual.map { it.name.lowercase() }
        val detected = detect(tx, excluded).filter { r ->
            val m = r.merchant.lowercase()
            names.none { n -> n.isNotBlank() && (m.contains(n) || n.contains(m)) }
        }.map { r ->
            var next = r.next
            // se l'ultima occorrenza è troppo vecchia, la prossima è la scadenza futura più vicina dello stesso ciclo
            while (ChronoUnit.DAYS.between(next, today) > 30) next = next.plusDays(365)
            item(r.merchant, r.mean, next, today, false)
        }
        return AccrualPlan((manual + detected).sortedBy { it.next })
    }
}
