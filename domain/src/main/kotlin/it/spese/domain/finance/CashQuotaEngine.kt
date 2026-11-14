package it.spese.domain.finance

import it.spese.core.common.MonthCalendar
import it.spese.core.model.CashQuota
import it.spese.core.model.Category
import it.spese.core.model.QuotaMode
import it.spese.core.model.Transaction
import it.spese.core.model.TransactionSplit
import it.spese.core.model.TxKind
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Quote fisse in contanti. Per ogni quota attiva si cerca, in ogni periodo (mese, trimestre, anno…), il prelievo adatto:
 * - [QuotaMode.COVERING]: il primo che la contiene; - [QuotaMode.EVERY]: ognuno che la contiene; - [QuotaMode.EXACT]: il primo di importo pari alla quota (± tolleranza).
 * La quota va alla sua categoria, il resto del prelievo resta "Contanti". Più quote possono stare nello stesso prelievo (finché il resto le copre).
 * Mai su movimenti già divisi a mano o già controllati. Parti automatiche: la quota porta il suo `quotaId`, il resto no.
 */
object CashQuotaEngine {
    /** Nuove parti complete del movimento (sostituiscono quelle automatiche esistenti). */
    data class Application(val txId: Long, val parts: List<TransactionSplit>)

    private val WITHDRAWAL = Regex("""prelievo|prelev\b|prelev\.|\batm\b|bancomat""", RegexOption.IGNORE_CASE)

    fun isWithdrawal(t: Transaction, categories: Map<Long, Category>): Boolean =
        t.kind == TxKind.SPESA && t.amount.signum() < 0 &&
            (WITHDRAWAL.containsMatchIn(t.description) || categories[t.categoryId]?.name.equals("Contanti", ignoreCase = true))

    /** Periodo di una quota: mese, trimestre o anno in cui cade una data. */
    fun bucket(q: CashQuota, month: YearMonth): Int = Math.floorDiv(month.year * 12 + month.monthValue - 1, q.everyMonths.coerceIn(1, 12))

    private class State(val tx: Transaction, val quotaParts: MutableList<TransactionSplit>, var remainder: BigDecimal, val remainderCategory: Long?) { var changed = false }

    fun plan(ledger: List<Transaction>, splits: Map<Long, List<TransactionSplit>>, categories: Map<Long, Category>, quotas: List<CashQuota>, cal: MonthCalendar = MonthCalendar.SOLAR): List<Application> {
        val monthOf = ledger.associate { it.id to cal.monthOf(it.bookingDate) }
        val applied = HashSet<Pair<Long, Int>>()   // (quota, periodo) già coperti, anche da divisioni manuali
        val usable = HashMap<Long, State>()
        val cashId = categories.values.firstOrNull { it.name.equals("Contanti", ignoreCase = true) }?.id
        for (t in ledger.sortedWith(compareBy({ it.bookingDate }, { it.id }))) {
            val parts = splits[t.id].orEmpty()
            parts.mapNotNull { it.quotaId }.forEach { qid -> monthOf[t.id]?.let { m -> quotas.firstOrNull { it.id == qid }?.let { q -> applied.add(qid to bucket(q, m)) } } }
            if (t.reviewed || !isWithdrawal(t, categories)) continue
            if (parts.any { !it.auto }) continue          // divisione manuale: non si tocca
            val rest = parts.firstOrNull { it.quotaId == null }
            usable[t.id] = State(t, parts.filter { it.quotaId != null }.toMutableList(), rest?.amount ?: if (parts.isEmpty()) t.amount else BigDecimal.ZERO, rest?.categoryId ?: cashId ?: t.categoryId)
        }
        val order = usable.values.sortedWith(compareBy({ it.tx.bookingDate }, { it.tx.id }))

        fun inWindow(q: CashQuota, s: State): Boolean {
            if (q.dayFrom <= 0 && q.dayTo <= 0) return true
            val d = s.tx.bookingDate.dayOfMonth
            return d >= (if (q.dayFrom > 0) q.dayFrom else 1) && d <= (if (q.dayTo > 0) q.dayTo else 31)
        }
        fun fits(q: CashQuota, s: State): Boolean {
            if (s.quotaParts.any { it.quotaId == q.id }) return false
            val left = s.remainder.abs()
            return when (q.mode) {
                QuotaMode.EXACT -> (left - q.amount).abs() <= q.tolerance && left.signum() > 0
                else -> left >= q.amount
            } && inWindow(q, s)
        }
        fun apply(q: CashQuota, s: State) {
            val amount = if (q.mode == QuotaMode.EXACT) s.remainder.abs() else q.amount
            s.quotaParts.add(TransactionSplit(0, s.tx.id, q.categoryId, amount.negate(), 0, auto = true, quotaId = q.id))
            s.remainder += amount; s.changed = true
        }

        for (q in quotas.filter { it.enabled && it.amount.signum() > 0 && categories.containsKey(it.categoryId) }.sortedBy { it.id }) {
            if (q.mode == QuotaMode.EVERY) { order.filter { fits(q, it) }.forEach { apply(q, it) }; continue }
            for (b in order.map { bucket(q, cal.monthOf(it.tx.bookingDate)) }.distinct()) {
                if ((q.id to b) in applied) continue
                val s = order.firstOrNull { bucket(q, cal.monthOf(it.tx.bookingDate)) == b && fits(q, it) } ?: continue
                apply(q, s); applied.add(q.id to b)
            }
        }
        return order.filter { it.changed }.map { s ->
            val parts = s.quotaParts.mapIndexed { i, p -> p.copy(position = i) } +
                (if (s.remainder.signum() != 0) listOf(TransactionSplit(0, s.tx.id, s.remainderCategory, s.remainder, s.quotaParts.size, auto = true, quotaId = null)) else emptyList())
            Application(s.tx.id, parts)
        }
    }
}
