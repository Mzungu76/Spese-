package it.spese.domain.finance

import it.spese.core.model.Category
import it.spese.core.model.RecurringRule
import it.spese.core.model.Transaction
import it.spese.core.model.TxKind
import it.spese.domain.classification.merchant
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Ogni quanto si ripete una spesa. */
enum class Cadence(val months: Int, val label: String, val days: Int) {
    MONTHLY(1, "ogni mese", 30), BIMONTHLY(2, "ogni 2 mesi", 61), QUARTERLY(3, "ogni 3 mesi", 91), SEMIANNUAL(6, "ogni 6 mesi", 183), ANNUAL(12, "ogni anno", 365);
    /** Tolleranza (giorni) sulla data prevista: "circa lo stesso giorno". */
    val tolerance: Int get() = when (this) { MONTHLY -> 7; BIMONTHLY -> 9; QUARTERLY -> 10; SEMIANNUAL -> 12; ANNUAL -> 15 }
}

/** SUBSCRIPTION = importo costante senza fine nota; INSTALLMENT = rata (importo costante, con "rata n/m" o fine nota); VARIABLE = importo che cambia (bollette). */
enum class PatternKind(val label: String) { SUBSCRIPTION("Abbonamento"), INSTALLMENT("Rata"), VARIABLE("Spesa periodica") }

data class RecurringPattern(
    val key: String,
    val merchant: String,
    val cadence: Cadence,
    val kind: PatternKind,
    val count: Int,
    val first: LocalDate,
    val last: LocalDate,
    val next: LocalDate,
    /** Ultimo importo pagato (positivo) e media. */
    val lastAmount: BigDecimal,
    val meanAmount: BigDecimal,
    val categoryId: Long?,
    val accountId: Long?,
    /** Importo precedente se l'ultimo è cambiato in modo significativo (aumento o calo di prezzo). */
    val previousAmount: BigDecimal? = null,
    /** Rate già pagate / totali, se la descrizione le riporta ("rata 3/12"). */
    val installmentNumber: Int? = null,
    val installmentTotal: Int? = null,
    /** Mesi di ritardo rispetto alla data attesa (0 = in regola): l'addebito atteso non è arrivato. */
    val missedCycles: Int = 0,
    /** Esiste già una ricorrenza manuale equivalente tra quelle dell'app. */
    val tracked: Boolean = false,
) {
    val perMonth: BigDecimal get() = meanAmount.divide(BigDecimal(cadence.months), 2, RoundingMode.HALF_UP)
    val perYear: BigDecimal get() = meanAmount.multiply(BigDecimal(12)).divide(BigDecimal(cadence.months), 2, RoundingMode.HALF_UP)
    val remainingInstallments: Int? get() = if (installmentNumber != null && installmentTotal != null) (installmentTotal - installmentNumber).coerceAtLeast(0) else null
    val finished: Boolean get() = remainingInstallments == 0
    /** Data stimata dell'ultima rata. */
    val endDate: LocalDate? get() = remainingInstallments?.let { last.plusMonths((it * cadence.months).toLong()) }
    val priceDelta: BigDecimal? get() = previousAmount?.let { lastAmount - it }
    val priceChanged: Boolean get() = previousAmount != null
    val missing: Boolean get() = missedCycles > 0 && !finished
    /** Da 3 cicli senza addebito: probabilmente disdetto, non conta più nei totali. */
    val stale: Boolean get() = missedCycles >= 3 && !finished
    val hasNews: Boolean get() = priceChanged || missing
}

data class SubscriptionSummary(val monthly: BigDecimal, val annual: BigDecimal, val count: Int, val news: Int)

/**
 * Riconosce abbonamenti, rate e spese periodiche: stesso esercente, ripetuto con cadenza regolare (mensile, bimestrale, trimestrale,
 * semestrale, annuale) e circa lo stesso giorno. Segnala aumenti di prezzo e addebiti attesi che non sono arrivati.
 * Le rate si riconoscono dal numero in descrizione ("rata 3/12"). È una stima: l'utente può ignorare o confermare.
 */
object SubscriptionDetector {
    private val RX_INSTALLMENT = Regex("(?:rata|rate|finanz\\w*|prestito)[^\\d]{0,12}(\\d{1,3})\\s*(?:/|di|su)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val EXCLUDED_NAMES = setOf("Contanti", "Commissioni e bolli")

    fun excludedCategories(categories: List<Category>): Set<Long?> = categories.filter { it.name in EXCLUDED_NAMES }.map { it.id }.toSet()

    /** Chiave stabile dell'esercente: solo lettere, minuscolo ("NETFLIX.COM 123" → "netflix com"). */
    fun keyOf(merchant: String): String =
        Normalizer.normalize(merchant.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z]+"), " ").trim().take(30).trim()

    private fun cadenceOf(medianDays: Long): Cadence? = when (medianDays) {
        in 24..40 -> Cadence.MONTHLY; in 52..70 -> Cadence.BIMONTHLY; in 80..102 -> Cadence.QUARTERLY
        in 165..200 -> Cadence.SEMIANNUAL; in 345..385 -> Cadence.ANNUAL; else -> null
    }

    private fun cv(values: List<BigDecimal>): Double {
        val m = values.fold(BigDecimal.ZERO) { a, b -> a + b }.toDouble() / values.size
        if (m == 0.0) return 0.0
        return Math.sqrt(values.sumOf { (it.toDouble() - m) * (it.toDouble() - m) } / values.size) / m
    }

    fun detect(
        list: List<Transaction>, excludedCategoryIds: Set<Long?> = emptySet(), today: LocalDate = LocalDate.now(), manual: List<RecurringRule> = emptyList(),
    ): List<RecurringPattern> {
        val groups = list.filter { it.kind == TxKind.SPESA && it.amount.signum() < 0 && it.categoryId !in excludedCategoryIds }.groupBy { keyOf(it.merchant) }
        val out = ArrayList<RecurringPattern>()
        for ((key, raw) in groups) {
            if (key.isBlank() || raw.size < 2) continue
            val a = raw.sortedBy { it.bookingDate }
            val gaps = (1 until a.size).map { ChronoUnit.DAYS.between(a[it - 1].bookingDate, a[it].bookingDate) }
            val med = gaps.sorted()[gaps.size shr 1]
            val cadence = cadenceOf(med) ?: continue
            // addebiti nello stesso periodo (es. doppio addebito) non contano come cicli
            val regular = gaps.count { abs(it - cadence.days) <= cadence.tolerance }
            if (regular < gaps.size * 0.8) continue
            // servono più cicli per le cadenze brevi; annuale/semestrale accettano due addebiti se l'importo è quasi uguale
            val amounts = a.map { it.amount.negate() }
            val coeff = cv(amounts)
            val minCount = if (cadence.months >= 6) 2 else 3
            if (a.size < minCount) continue
            if (cadence.months >= 6 && coeff > 0.10) continue
            if (cadence.months < 6 && coeff > 0.35) continue
            val last = a.last()
            val mean = amounts.fold(BigDecimal.ZERO) { s, v -> s + v }.divide(BigDecimal(amounts.size), 2, RoundingMode.HALF_UP)
            val inst = RX_INSTALLMENT.find(last.description)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }?.takeIf { (n, t) -> t in 2..360 && n in 1..t }
            val kind = when {
                inst != null && coeff <= 0.05 -> PatternKind.INSTALLMENT
                coeff <= 0.05 -> PatternKind.SUBSCRIPTION
                else -> PatternKind.VARIABLE
            }
            val lastAmount = amounts.last()
            // aumento/calo di prezzo: l'ultimo importo differisce dai precedenti (che erano costanti)
            val before = amounts.dropLast(1)
            var previous: BigDecimal? = null
            if (kind != PatternKind.VARIABLE || before.size >= 2) {
                val base = before.sorted()[before.size shr 1]
                val diff = (lastAmount - base).abs()
                if (before.size >= 2 && cv(before) <= 0.03 && diff >= BigDecimal("0.50") && diff.toDouble() >= base.toDouble() * 0.03) previous = base
            }
            val next = last.bookingDate.plusMonths(cadence.months.toLong())
            val overdueDays = ChronoUnit.DAYS.between(next, today) - cadence.tolerance
            val missed = if (overdueDays > 0) (1 + (overdueDays / cadence.days).toInt()) else 0
            val name = last.merchant
            val tracked = manual.any { r -> r.enabled && (sameName(keyOf(r.name), key) || (r.amount.compareTo(mean) == 0 && r.categoryId != null && r.categoryId == last.categoryId && r.everyMonths == cadence.months)) }
            out.add(RecurringPattern(key, name, cadence, kind, a.size, a.first().bookingDate, last.bookingDate, next, lastAmount, mean, last.categoryId, last.accountId,
                previous, inst?.first, inst?.second, missed, tracked))
        }
        return out.sortedByDescending { it.perMonth }
    }

    /** Stesso esercente anche se uno dei due nomi è più lungo (netflix ~ netflix com). */
    private fun sameName(a: String, b: String) = a.length >= 4 && b.length >= 4 && (a == b || a.startsWith(b) || b.startsWith(a))

    fun summary(patterns: List<RecurringPattern>): SubscriptionSummary {
        val live = patterns.filter { !it.finished && !it.stale }
        return SubscriptionSummary(live.fold(BigDecimal.ZERO) { a, p -> a + p.perMonth }, live.fold(BigDecimal.ZERO) { a, p -> a + p.perYear }, live.size, live.count { it.hasNews })
    }
}

/** Esercenti che l'utente ha scelto di non considerare abbonamenti o rate (solo sul telefono). */
interface SubscriptionReviewRepository {
    fun observeIgnored(): kotlinx.coroutines.flow.Flow<Set<String>>
    suspend fun getIgnored(): Set<String>
    suspend fun ignore(key: String)
    suspend fun restore(key: String)
    suspend fun setIgnored(keys: Set<String>)
}
